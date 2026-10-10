# Source protection notes

- English UI resources under `app/src/main/res` were intentionally preserved.
- Room entities, DAOs, and `AppDatabase` were intentionally kept stable to avoid changing
  default table names or generated metadata for existing installations.
- App-owned implementation and UI type identifiers were changed to Chinese identifiers.
- The release build uses R8 (`minifyEnabled true`, `shrinkResources true`).
- Do not treat this as cryptographic protection: a determined analyst can still reverse
  engineer an APK. Keep API keys and secrets out of the client and protect the backend.
