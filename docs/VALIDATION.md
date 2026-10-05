# Validation — AudioScope 0.4.0, 2026-10-05

## Completed checks for this release

- Clean debug, instrumentation APK, signed release, unit-test and lint builds succeeded. All 22 JUnit cases passed. Android lint: zero errors, 26 warnings; warnings include existing/newer foreground-service type constants and prior compatibility checks.
- Three Android 13 emulator SettingsUiRegressionTest cases passed: category navigation and full-width switch explanations; reliability-page access does not start microphones or arm calls, and saved offline opt-in cannot report ready across a changed boot count; helper API 3 reads actual debugging state, confirms a same-value USB setting write, rejects arbitrary shell operations, and the guard notification Stop action clears the preference and stops the service without recording.
- The privileged state test used an owned shell-helper fixture on emulator-5554. It did not change a physical phone's USB mode, disable debugging, open a TCP listener, or test real pairing. Foreground-service notification behavior was exercised with Android notifications allowed.
- Rendered Settings home, audio-quality, appearance and reliability pages were reviewed at 1080 × 2220 / density 440, Standard text. Titles, switches, full-width explanations and system-bar padding are visible. Scrolled content remains scrollable. Screenshots: settings-home-0.4.png, settings-quality-0.4.png, settings-appearance-0.4.png, settings-reliability-0.4.png.
- Release identity: dev.audioscope, versionCode 4, versionName 0.4.0, min API 33, target API 35, non-debuggable. APK Signature Scheme v2 verification succeeded. Same RSA-3072 signing certificate as prior releases: 147eeca07321e6d5e43e13869320f63087df4aabeba5a008046b5fde0eb22380.
- APK SHA-256: `7064d089f6d41eb0765f5be7bd821c98b1523fa2934b51e9d055b4394ce331b0`.

## Limits and physical checks

Actual Wi-Fi-free restart/TCP shutdown, USB-default changes and lock/unlock churn, Samsung Wireless debugging intents/TLS pairing, boot/update notification delivery, and screen-off endurance need S23 Ultra acceptance. Debugging changes can restart the helper. The optional guard restores a setting only while its foreground service runs, Wi-Fi is active and Embedded ADB privilege remains reachable. It cannot revive a dead helper. Post-boot microphone capture, native protected-recording handoff and per-app automation are not implemented.

The user's prior S23 Ultra Wi-Fi voicemail result remains the existing physical capture evidence. The v0.3.0 phone-only routing, explicit Bluetooth previews, storage, naming and playback implementations remain present, but fresh CMF-earbud and Samsung Recent checks were not performed in this release. A listed source or initialized recorder does not prove both call parties are audible.

## Historical checks and comparison

[0.3.0 validation](VALIDATION-0.3.0.md) records the earlier ten device regression cases, actual PCM/notification controls, folder grants/exports and playback checks. Those are historical evidence, not a repeated 0.4.0 device run. See [the comparison report](CALLVAULT-COMPARISON-2026-10-05.md) for code-level findings, feature gaps and prioritized work.

## Publication

Source, screenshots, the comparison, setup and release notes are versioned. Private signing keys, credentials, build caches and captured recordings are excluded. The delivery message states whether the new Git push succeeded; local commits do not imply that GitHub has them.
