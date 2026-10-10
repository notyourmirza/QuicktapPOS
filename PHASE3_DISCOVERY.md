# Phase 3 §3.0 — Discovery report

Read before writing any code. Everything below comes from the real sources on
the server (`api/` on the PHP side, `app/src/main/java/com/quicktap/pos/` on
the Android side), not from assumptions.

---

## 1. How the app stores data

| Thing | Where |
|---|---|
| Database | **Room** (SQLite), `quicktap.db`, `com.quicktap.pos.data.AppDatabase` |
| Schema version | **11** — migrations MIGRATION_1_2 … MIGRATION_10_11 |
| ⚠️ Builder option | `.fallbackToDestructiveMigration()` — **any** unmatched migration wipes the database |
| Entities (14) | `Product`, `Category`, `Bill`, `BillItem`, `Expense`, `ExpenseCategory`, `Customer`, `Supplier`, `Purchase`, `PurchaseItem`, `CashSession`, `Refund`, `LedgerCustomer`, `LedgerEntry` |
| DAOs | `ProductDao`, `CategoryDao`, `BillDao`, `SyncDao`, `ExpenseDao`, `CommercialDao`, `LedgerDao` |
| **Single write layer** | **`data/类009`** — "Single source of truth for all offline data. ViewModels talk only to this class; nothing else touches a DAO directly." |
| Threading | `util/类045` — `io()` (3-thread pool) and `main()` (Handler). Every repository write already runs on `io()`. |
| Existing sync columns | `uuid`, `updatedAt`, `dirty`, `deleted` exist **only** on `products`, `categories`, `bills` (added in MIGRATION_1_2). The other 11 tables have no uuid. |

**Consequence:** the change-capture hook has exactly one home — `类009`.

### Where each write happens in `类009`

| Method | Entities touched | Notes |
|---|---|---|
| `saveProduct` / `deleteProduct` | products | soft delete (`deleted = true`) |
| `saveCategory` / `deleteCategory` | categories | soft delete |
| `saveBillBlocking` | bills + bill_items + **product stock** | stock moves via `productDao.reduceStock/bumpSold`, bypassing `saveProduct` — so products must be re-queued explicitly |
| `markCustomerOrderPaid` | bills | |
| `deleteBill` | bills | hard delete; separate from the rolling-month purge |
| `saveExpense` / `deleteExpense` | expenses | hard delete |
| `saveCustomer` / `deleteCustomer` | customers | hard delete |
| `saveSupplier` / `deleteSupplier` | suppliers | hard delete |
| `saveLedgerCustomer` | ledger_customers | |
| `savePurchaseBlocking`, `saveRefund` | product stock | same reason as bills |
| `purgeBillsOlderThan` | bills | **not hooked on purpose** — a retention purge must not delete other devices' data |

---

## 2. Settings screens, users, Firebase key flow

| Question | Answer |
|---|---|
| Settings screen | `ui/settings/类092` (a Fragment, layout `fragment_settings.xml`). `ui/settings/类091` is the **printer** activity, not the settings screen. |
| Existing "Backup & Restore" card | holds `textSyncStatus`, `switchAutoSync`, `buttonSyncNow` — wired to the legacy JWT sync (`sync/类034`) |
| Does the app create POS users? | **No.** No user-management screen exists. `POST /v1/auth/register` belongs to the **licence / account** flow (`license/类013`, `ui/license/类078`), not to per-seat user management. ⇒ §3.6 "if the app can create users" does not apply; the plan-limit message is still surfaced verbatim. |
| Firebase key flow | `net/类018` resolves `api_base_url` from Firebase Remote Config → `Auth::requireApiClient` credentials live in `BuildConfig.APP_ID` / `API_KEY`. Licence state machine: `license/类012…类015`, `auth/类002…类004`. **Completely untouched by Phase 3.** |
| Existing sync | `sync/类034` (engine, JWT) + `sync/类035` (WorkManager, 6-hourly, off by default) |

---

## 3. The plan

### New files
One isolated package `com.quicktap.pos.cloudsync` (15 classes) + one screen
`ui/cloudsync/CloudSyncActivity` + layout + drawable. See `PHASE3_REPORT.md` §1.

### Existing files that must be touched, and why

| File | Why |
|---|---|
| `data/类009` | the only write layer → the only place change capture can live |
| `sync/类034` | 2 lines: route `/v1/sync/*` to Cloud Sync when configured |
| `类001` | schedule the worker + publish status on cold start |
| `ui/settings/类092` | the "Cloud Sync" entry |
| `fragment_settings.xml` | the card |
| `ui/类042` + `top_app_bar.xml` + `strings.xml` | the toolbar status icon (§3.7) |
| `AndroidManifest.xml` | register the activity |
| `BillDao`, `ExpenseDao`, `LedgerDao`, `CommercialDao` | +5 additive queries needed to apply pulled records |
| `net/类019` | visibility only (`static` → `public static`) |
| `build.gradle`, `proguard-rules.pro` | version bump + keep rules |

### Entities synced — confirmed against the real tables

Server `CloudController::TYPED_ENTITIES` =
`categories, products, customers, expenses, orders, bills, suppliers, ledger_customers`.

| Entity | Server name | Local source | Has uuid? |
|---|---|---|---|
| Categories | `categories` | `categories` | ✅ own column |
| Products | `products` | `products` | ✅ own column |
| Sales | `bills` | `bills` + nested `bill_items` | ✅ own column |
| Customers | `customers` | `customers` (commercial module) | ❌ → uuid map |
| Suppliers | `suppliers` | `suppliers` | ❌ → uuid map |
| Expenses | `expenses` | `expenses` | ❌ → uuid map |
| Ledger customers | `ledger_customers` | `ledger_customers` | ❌ → uuid map |
| — | `orders` | no such table in the app (bills *are* the sales) | n/a |

`purchase_items`, `cash_sessions`, `refunds`, `ledger_entries`, `expense_categories`
are **not** synced: they are device-local operational records, they are not in
the server's typed list, and syncing them would need a substantially larger
change to the local schema. Stock levels ride along inside `products`.

### Database decision (the important one)

`AppDatabase` is **not** migrated. It is on version 11 and built with
`.fallbackToDestructiveMigration()`, so one wrong migration destroys the shop's
sales history. Instead Phase 3 keeps the queue, the uuid map and the cursor in
a **separate Room database** (`quicktap_cloudsync.db`, version 1). Result:
identical behaviour, zero risk to `quicktap.db`.

---

## 4. Risks found

1. `.fallbackToDestructiveMigration()` on `quicktap.db` — the single biggest
   hazard in this codebase. Avoided entirely (see above).
2. `ProductDao.observeAll()` / `observeFiltered()` do **not** filter
   `deleted`, so the app's own soft delete leaves rows visible. Phase 3 mirrors
   the app's behaviour exactly rather than "fixing" it — that is out of scope
   and would change existing behaviour.
3. `Auth::requireApiClient()` runs **before** the router, so every call —
   keyed or not — must carry `X-App-Id` / `X-Api-Key`. A Bearer-only request
   gets `401 NO_API_KEY`.
4. `POST /v1/sync/push` hard-fails above **200** records (`413 BATCH_TOO_LARGE`).
5. `Request::input()` reads the JSON body first, then the query string, so
   `device_uid` works in a POST body but must be a **query parameter** on GETs.
6. The previous session had replaced the guard in `sync/类034` with a
   Cloud-Sync-only check, which silently disabled the legacy JWT sync for every
   shop without a key. Reverted.
7. Room does **not** write the generated id back into an inserted entity, so
   `product.id` is still `0` right after `insert()`. The uuid map therefore
   must never be keyed on the local id for entities that own their own uuid
   column — otherwise two new rows collide at id 0.
