# Source protection notes

- English UI resources under `app/src/main/res` were intentionally preserved.
- Room entities, DAOs, and `AppDatabase` were intentionally kept stable to avoid changing
  default table names or generated metadata for existing installations.
- App-owned implementation and UI type identifiers were changed to Chinese identifiers.
- The release build uses R8 (`minifyEnabled true`, `shrinkResources true`).
- Do not treat this as cryptographic protection: a determined analyst can still reverse
  engineer an APK. Keep API keys and secrets out of the client and protect the backend.
- Renaming to `类NNN` is only correct when the **file name matches the class it declares**
  (`license/类012.java` declaring `public class 类012`). The tree originally shipped
  `#U7c7bNNN.java` names, which `javac` rejects on every platform; all 91 were renamed.
  `tools/check_sources.py` fails if a name, a layout id or a cross-package reference ever
  drifts again, which is also the only guard available where no JDK exists.
- The server's real authority is the API, not the class names: see `billing-api/` and
  `LICENSE_API.md`. Obfuscation is a speed bump for a casual reader, nothing more.
