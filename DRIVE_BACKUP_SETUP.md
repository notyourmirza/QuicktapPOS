# Google Drive backup — what you have to create

The app talks to Google Drive itself (no server of ours in between): it signs the
shopkeeper in with their own Google account and writes **one encrypted copy** of the
Room database into the app's private `appDataFolder`. No Google API key is needed for
that folder, and nobody else — not even the shopkeeper's own file list — can see it.

This file is the whole setup: ~10 minutes in the Google Cloud console, one JSON file
into the repo, then it works forever.

---

## 1. What the app actually does

| Action | Behaviour |
|---|---|
| **Weekly automatic backup** | WorkManager job (`backup/类005`) runs when a backup is more than 7 days old, and only if the Drive account is still connected. Sealed with **this device's key**. Silent; failures land in Settings, not in a toast. |
| **Backup Now** (`Settings` → *Google Drive* card) | Asks for an optional passphrase, then uploads `Latest Backup <date-time>.qtpbackup`. |
| **Restore from Backup** | Downloads the newest copy, decrypts, verifies, and replaces the local database. A safety copy of the current data is written first. |
| Retention | Exactly **one** copy in Drive. The previous file is deleted only after the new upload has been verified by size, so a failed run can never lose the only backup. |
| Local file backup | *Download Backup File* / *Restore Backup File* in the same screen use the same format, so a `.qtpbackup` file can be moved by hand between phone and Drive. |

Encryption (`backup/BackupCrypto.java`):

```
QTPB2 │ kdf byte │ [passphrase only: hash id, iterations, 16-byte salt] │ iv │ AES-256-GCM ciphertext
```

* **no passphrase** → the key lives in the device's `AndroidKeyStore`, so the copy can only be
  opened on that phone. This is what the weekly job uses, and it needs nothing from you.
* **passphrase** → `PBKDF2-HMAC-SHA256`, 210 000 iterations, random salt per backup, then the
  same AES-256-GCM. The copy opens on *any* phone that knows the phrase — this is the path for
  a replaced or broken till.
* The passphrase is never stored anywhere. Forget it and the backup cannot be opened; there is
  no recovery, by design.
* Older `QTPB1` files (sealed with the device key, no passphrase) still restore.

---

## 2. Credentials you must create

All in <https://console.cloud.google.com>. Create/select a project first (any name,
e.g. `quicktap-pos`).

### a) OAuth consent screen

* **Google Auth Platform → Branding / Public**: app name `QuickTap POS`, your support
  e-mail, and add the scope `https://www.googleapis.com/auth/drive.appdata`.
* While the app is *In testing*, add each shopkeeper's Google address under
  **Test users**, or Drive calls come back with a consent error. Publish the app
  (`In production`) when you stop adding test users. `drive.appdata` is not a
  "sensitive" scope, so no verification review is required for it.

### b) OAuth client — Android

* **Clients → Create client → Android**.
* Package name: `com.quicktap.pos`
* SHA-1: **one entry per signing certificate**, otherwise the sign-in dialog appears
  and then fails with `DEVELOPER_ERROR`:

  ```bash
  # debug builds (what you test with)
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
          -storepass android -keypass android | grep SHA1

  # your release keystore
  keytool -list -v -keystore path/to/quicktap-release.jks | grep SHA1
  ```

  In Android Studio the debug fingerprint is also printed by
  `Gradle › :app › signingReport`.

### c) OAuth client — Web application

Required even though the app never opens a browser: the Gradle `google-services`
plugin builds the `default_web_client_id` resource from this client, and the app asks
Google for a server auth code against it.
* **Clients → Create client → Web application** → *no* authorised origins; add
  `https://localhost/` under "Authorised redirect URIs" (the Android flow never uses it,
  but the console refuses an empty list).

### d) Enable the APIs

* **APIs & Services → Library** → enable **Google Drive API** and
  **Google Sign-In API**.

### e) `app/google-services.json`

* **Clients → Android client → Download google-services.json**, put it at
  `app/google-services.json`, replacing the placeholder that is committed.
* It must contain **both** clients: `client_type 1` (Android) *and* `client_type 3`
  (Web). The plugin reads them into `default_web_client_id`; `类006.isOAuthConfigured()`
  reports that resource back, which is why Settings can tell you the build is not set up
  instead of failing at upload time.
* Nothing secret is in this file (client ids are public identifiers), so it is fine to
  keep it in the repository — the *secrets* are the SHA-1 + package combination on
  Google's side.

### f) Already present, nothing to do

* `app/build.gradle` already declares the Drive/Play-services dependencies and applies
  `com.google.gms.google-services`.
* `android:usesCleartextTraffic`/permissions: Drive upload runs over HTTPS, no extra
  permission is needed beyond the network one.

---

## 3. No `google-services.json`? The app still works

With the placeholder file (`"oauth_client": []`) the build succeeds and everything
except Drive keeps working: `webClientId()` returns null, so sign-in falls back to
`DEFAULT_SIGN_IN` and the token comes from `GoogleAuthUtil`. Settings says

> Google Drive needs setup: this build has no OAuth client id (see DRIVE_BACKUP_SETUP.md)

instead of pretending a backup happened. **Local** file backup/restore works in that
state, and so does the licence/billing flow — those never touch Google.

---

## 4. Handover checklist for a new till

1. On the old till: *Backup Now* → type a passphrase (≥ 8 characters), confirm it.
2. On the new till: install the app, sign in with the same shop account, open the same
   card, **Connect Google Drive** with the same Google account.
3. *Restore from Backup* → the app knows the copy is passphrase-sealed and asks for it
   before it downloads anything.
4. The app replaces its database and restarts the screens; the previous local data was
   copied aside first, so a wrong choice can be undone from Settings › Restore Backup File.

If the shopkeeper never sets a passphrase, step 1 must be done **on the phone that will
keep the data** — a device-key-sealed copy cannot be opened elsewhere. That is the only
rule to explain to a customer.

---

## 5. When Drive refuses

The last failure is always written into the status line under the Drive card
(`Settings` → *Google Drive*), together with the next scheduled backup. Common causes:

| Status text | Meaning / fix |
|---|---|
| `Google is waiting for the Drive permission.` | Consent was revoked or the scope was added after the account was connected. Tap *Backup Now* again and approve the prompt that opens. |
| `… this build has no OAuth client id` | `app/google-services.json` is still the placeholder, or it has no `client_type 3` entry. |
| `Drive rejected the upload…` | Usually quota or consent. The previous backup is untouched by design. |
| `Wrong passphrase…` | The copy was sealed with a different phrase. There is no way around it. |
| `sealed on another device` | That copy was made without a passphrase on another phone. Ask for a passphrase-sealed copy. |
| `This backup is from a newer app version` | Restore was attempted with an older APK; update the app first. (`schema_version` is checked, so a mismatch can never half-import.) |
