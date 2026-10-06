# Validation — AudioScope 0.5.1, 2026-10-05

Communication mic now leaves the preferred device unset and accepts Android-selected communication input, including Bluetooth, wired, USB and phone routes. Null/unreported routing is tolerated for this source instead of applying the phone-only rejection. It remains an explicit preview and still cannot start extra monitoring during a recording. Regular Microphone, Any Phone Mic and named headset/external routing policies are unchanged.

Clean signed-release build, unit tests and lint passed. **26 unit cases passed**, including a new route-policy case checking Communication mic accepts external/Bluetooth routes while both phone choices still reject Bluetooth. Lint: **0 errors, 26 existing warnings**. No new emulator/hardware run was required for this narrow policy change. Actual headset switching and recorder behavior remain physical-device acceptance; Android can still close/reject a recorder independently of route validation.

Release identity: dev.audioscope, versionCode 6, versionName 0.5.1, min API 33, target API 35. Signature verified with the existing certificate: `147eeca07321e6d5e43e13869320f63087df4aabeba5a008046b5fde0eb22380`.

APK SHA-256: `b5caaf5fc3fd97791242e6d4bb9c5cc3bd918ab42a4bf2f8f722d18b2cb6af6c`.

[0.5.0 validation](VALIDATION-0.5.0.md) preserves the eight prior emulator checks and broader feature/hardware limits. Git/source archives exclude private signing keys, raw diagnostics and recordings. Local commits/tags do not imply publication; delivery states whether pushing succeeded.
