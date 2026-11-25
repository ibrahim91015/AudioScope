# AudioScope 0.5.3-rc1 candidate validation

November 25, 2026. Local checks only; Android 17 phone acceptance is pending.

- Package: `dev.audioscope`; version code 8, version name `0.5.3-rc1`.
- Minimum SDK 33; target/compile SDK 35.
- Signed release APK: 24,635,706 bytes.
- SHA-256: `38fa337db9897d7492d48a2061fbb81b4df376e743f8db897d1fe0b7cd4bf581`.
- APK signature verification passed (v2), using the existing AudioScope release certificate.
- Signing certificate SHA-256: `147eeca07321e6d5e43e13869320f63087df4aabeba5a008046b5fde0eb22380`.

## Completed checks

All **47 unit tests** passed: 38 existing tests plus nine reliability cases. New coverage checks saved identity persistence, legacy-key migration, same-key certificate reconstruction, refusal to overwrite corrupt identity, WAV growth without a UI consumer, pause/resume continuity, authentication refusal classification, and guarded adbd-repair eligibility/cooldown.

Android lint completed with **0 errors and 26 warnings**. Remaining warnings include existing hidden-API use, library trust-manager behavior, UI/i18n/accessibility items, and intentionally synchronous persistence of pairing state. The optional `WRITE_SECURE_SETTINGS` manifest entry has one narrow `ProtectedPermissions` suppression: the explicit setup action requests an ADB development grant and verifies it at runtime. No general lint baseline was introduced.

Release assembly and instrumentation APK compilation completed successfully. The new `HelperReliabilityDeviceTest` is prepared to leave a real waveform pipe unread while checking that helper-owned WAV data continues to grow. It was compiled, not executed. Existing instrumentation assertions were updated for helper API 5 and independent file writing.

An initial build found an Android 13 API compatibility call; it was replaced. A later compilation caught a reused local variable in diagnostic-log export; it was corrected. Those unsuccessful build attempts are not counted as successful checks.

## Not yet verified

No physical phone or emulator tests were run for this candidate. The unit WAV test uses a direct file sink, not an Android AudioRecord, Binder connection, or real phone call.

The following require the user's phone: Android 17 Wireless debugging visibility; actual pairing persistence after reboot; discovery after a port change; Samsung screen lock, cgroup permissions and battery behavior; caller/remote-party audibility; Shevery restart/binding behavior; successful development-permission grant; and the optional stopped-adbd repair.

No claim is made that Force stop, revoked authorization, OEM security restrictions, or a killed privileged helper can be bypassed. Pairing identity persistence and an available debugging transport are separate requirements.

See [the investigation and phone acceptance plan](ANDROID17-RELIABILITY.md).
