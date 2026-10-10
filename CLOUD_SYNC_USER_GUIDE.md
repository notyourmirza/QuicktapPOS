# Cloud Sync — guide for your customers

Cloud Sync keeps your products, categories, sales, expenses and customers in
step across every phone and tablet in your shop. It is an optional paid add-on:
**if you do not switch it on, nothing changes — the POS keeps working fully
offline, exactly as before.**

---

## 1. Where do I get my endpoint and API key?

1. Sign in to the customer portal (**quickxlab.com**).
2. Open **Cloud Sync** in your account menu.
3. Press **Create key**. The key is shown **once** — copy it straight away.
   It looks like `pos_live_…`
4. Copy the **Endpoint URL** shown next to it (it starts with `https://`).

---

## 2. How do I connect a device?

1. On the POS, open **Settings**.
2. Scroll to the **Cloud Sync** card and press **Open Cloud Sync**.
3. Paste the **Endpoint URL**.
4. Paste the **API key**.
5. Press **Save & Connect**.

Within a second or two you will see one of these:

| What you see | What it means | What to do |
|---|---|---|
| **Connected** | Everything is working. | Nothing — changes now sync automatically. |
| **Invalid key** | The key was not accepted, or it was revoked. | Create a new key in the portal and paste it again. |
| **Not included in your plan** | Your plan does not have the Cloud Sync add-on. | Upgrade your plan in the portal. |
| **Plan expired** | Your subscription ended. | Renew. **Nothing was deleted** — your local data is intact and syncing resumes the moment you renew. |
| **Offline – changes are queued** | No internet right now. | Nothing. Everything is stored locally and uploads when the connection returns. |
| **Error** | Something else failed. | It retries on its own. If it persists, check the endpoint URL. |

The key is stored encrypted on the device and is never shown again.

---

## 3. Setting up a second (new) device

1. Install QuickTap POS on the new device and connect it with the **same**
   endpoint and API key.
2. If your cloud account already has data, the app offers
   **"Upload this device's data?"** — answer **Not now** on the new, empty
   device.
3. Press **Import data from cloud**. A progress counter runs while the records
   download. If the connection drops, just press it again — it resumes.
4. When it finishes, the two devices stay in step automatically.

**Merging is safe:** when the same record exists in both places, the one that
was changed **most recently** wins. Local records are never deleted by an
import.

---

## 4. Uploading data that was already on the device

Records created *before* you switched Cloud Sync on are not uploaded
automatically. To send them up:

**Cloud Sync › Upload existing data once**

Use this once, right after switching the feature on, or after restoring a
backup. The app offers it automatically when you connect to a cloud account
that is still empty.

---

## 5. What the status icons mean

| Icon / label | Meaning |
|---|---|
| **Connected** | Up to date. |
| **Connecting…** | A sync round trip is in progress. |
| **Offline – N pending** | No internet. N changes are waiting and will upload automatically. |
| **N pending** (while connected) | N changes are queued and will upload within seconds. |
| **Invalid key** (red) | Sync stopped — the key needs replacing. |
| **Not included in your plan** (red) | Upgrade to switch syncing on. |
| **Plan expired** (red) | Renew to resume. |
| **Error** (red) | Retries automatically. |

Tapping the Cloud Sync entry in Settings opens the full screen with the plan
name, how many users/devices you are using, the pending count and the time of
the last successful sync.

---

## 6. Disconnecting

**Cloud Sync › Disconnect** removes the API key and stops all syncing.

> **Nothing is deleted.** Every product, sale and customer already on the
> device stays exactly where it is, and the POS carries on working offline.

---

## 7. Good to know

- **A sale is never blocked by syncing.** The sale is saved on the device
  first; uploading happens in the background.
- **Date & time matter.** If a device's clock is badly wrong, the app warns
  you — the most recent change wins, so a wrong clock can make an older edit
  overwrite a newer one.
- **Users and devices are limited by your plan.** The Cloud Sync screen shows
  e.g. `Users 2/3   Devices 1/2`. Adding a fourth user is blocked by the
  server with a clear "please upgrade" message; existing users are never
  removed.
