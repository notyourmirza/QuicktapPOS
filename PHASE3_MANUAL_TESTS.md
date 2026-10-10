> **Superseded by 1.2.0.** The Cloud Sync add-on described in this report was
> deleted: the paid `cloudsync/` package, its screen and its Settings card are gone, and
> multi-till data now travels through `v1/sync/push|pull` against `billing-api/`.
> The report is kept as history of what was built, not as a description of this tree.

# Phase 3 — test script

Automated unit tests are not possible for this module: it is wired into
`Room`, `WorkManager` and the Android Keystore, all of which need an
instrumented JVM or a device. **Nothing below builds an APK** — steps 1–6 are
static/CLI checks you can run now; steps 7+ are manual checks on a built APK.

---

## A. Static checks (already run, repeatable)

```bash
cd quicktap-android-cn

# 1. Parse every new + modified file (no Android SDK needed for a parse-only run)
LC_ALL=C.utf8 javac -J-Dfile.encoding=UTF-8 -J-Dsun.jnu.encoding=UTF-8 \
  -encoding UTF-8 -d /tmp/out -proc:none -nowarn \
  app/src/main/java/com/quicktap/pos/cloudsync/*.java \
  app/src/main/java/com/quicktap/pos/ui/cloudsync/*.java \
  app/src/main/java/com/quicktap/pos/data/类009.java \
  app/src/main/java/com/quicktap/pos/类001.java \
  app/src/main/java/com/quicktap/pos/sync/类034.java \
  app/src/main/java/com/quicktap/pos/ui/settings/类092.java 2>&1 \
  | grep -E "expected|illegal start|reached end of file|<identifier>"
# expected output: nothing

# 2. Lint (does not produce an APK)
./gradlew :app:lintDebug

# 3. Unit tests already in the project
./gradlew :app:testDebugUnitTest
```

## B. Server-side smoke test (no app needed)

```bash
# Replace KEY with a real pos_live_… key from the portal.
BASE=https://quickxlab.com/api/v1
APPID=<REDACTED-APP-ID>
APIKEY=<REDACTED-APP-KEY-len48>

curl -s -H "X-App-Id: $APPID" -H "X-Api-Key: $APIKEY" \
     -H "Authorization: Bearer $KEY" "$BASE/auth/verify?device_uid=dev-test-1"

curl -s -H "X-App-Id: $APPID" -H "X-Api-Key: $APIKEY" \
     -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
     -X POST "$BASE/sync/push" \
     -d '{"device_uid":"dev-test-1","records":[{"id":"11111111-1111-1111-1111-111111111111","entity":"products","data":{"name":"Test","price":10,"clientUpdatedAt":'"$(date +%s000)"'},"version":1,"is_deleted":false,"updated_at":'"$(date +%s000)"'}]}'

curl -s -H "X-App-Id: $APPID" -H "X-Api-Key: $APIKEY" \
     -H "Authorization: Bearer $KEY" "$BASE/sync/pull?since=0&limit=10&device_uid=dev-test-1"
```

Expected: `200` with `"success":true` for all three. The second call run twice
must return the same `accepted` count and never create a duplicate row.

---

## C. Manual tests on the built APK

Install **over** the previous version so the existing data is preserved.

| # | Test | Expected |
|---|---|---|
| 1 | Install over the old version | All products, sales, customers still there. No crash on first launch. |
| 2 | No key entered | Settings › Cloud Sync shows "Not connected — the app is fully offline". **Zero network requests** — verify with a proxy (Charles/mitmproxy) or by watching `sync_logs` on the server. |
| 3 | Sell something with no key | Works exactly as before, receipt prints, no delay. |
| 4 | Wrong key | "Invalid key" (red). Sync stops. No repeated requests. |
| 5 | Revoke the key in the portal, then Sync now | "Invalid key", syncing stops. |
| 6 | Correct key, plan without Cloud Sync | "Not included in your plan" + upgrade hint. No data is uploaded. |
| 7 | Correct key, plan with Cloud Sync | "Connected". Plan name and `Users x/y` shown. |
| 8 | Make a sale while connected | Within ~10 s the sale reaches the server — check Customer portal › Data or `GET /v1/sync/pull`. |
| 9 | Airplane mode → make 5 sales → turn internet back on | Pending count shows 5; all 5 upload automatically. |
| 10 | Press **Sync now** twice quickly | Second press is debounced; the server receives each record **once** (`sync_records` count unchanged on the repeat). |
| 11 | Edit a product on device A, wait, then on device B press Sync now | B shows the edit. Pulled data does **not** re-enter the push queue (no ping-pong). |
| 12 | Edit the *same* product on both devices while both are offline, then bring both online | The edit with the **later** timestamp wins on both devices and on the server. |
| 13 | Second phone, same key → **Import data from cloud** | Progress counter runs; afterwards the data matches. |
| 14 | Kill the app mid-import, reopen, press Import again | It resumes; no duplicates afterwards. |
| 15 | Plan with 3 users → try to add a 4th | Clear message: "Your plan allows 3 users. Please upgrade." No crash. |
| 16 | Let the plan expire | "Plan expired". Sync stops. **No local data is deleted.** |
| 17 | **Disconnect** | Key removed, syncing stops, every local record untouched, POS keeps working. |
| 18 | Re-enter the key after disconnect | Reconnects; previously queued changes upload. |
| 19 | Force a network error (bad endpoint host) | "Error", retries with backoff. A sale made during this still completes normally. |
| 20 | Set the device clock 2 hours wrong, then sync | Warning about the clock appears. |
| 21 | **Upload existing data once** on a device with pre-existing data | All rows upload once; re-running it pushes the same data without duplicating. |
| 22 | Firebase licence flow | Unchanged — the licence banner, status screen and key-change flow behave exactly as in 1.0. |
| 23 | Release build (minified) | All of the above still work — verifies the ProGuard keep rules. |

---

## D. What the automated checks cover, and what they do not

**Covered without a build:** Java parse of every new/modified file; every
`…Dao().method()` call in the new code resolves against the real DAO sources;
every view-binding id used exists in its layout.

**Not covered (needs the Room annotation processor / a device):** Room SQL
validity and parameter binding in `CloudSyncDao`, the WorkManager schedules,
and the Keystore round trip. These are exercised by manual tests 7–14 and 23.
