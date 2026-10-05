# Validation — AudioScope 0.5.0, 2026-10-05

## Findings and fixes

The two supplied Samsung diagnostics report repeated phone-mic failures while Sources monitoring is active and a misleading external-microphone message. Code inspection found that route validation rejected telephony device type 18, Sources reopened competing previews during recording, and local-mic cleanup could call the helper’s close operation despite never opening a helper source. These paths are corrected. The exact routed device at each failure was absent from the diagnostics; this evidence does not prove which transition caused every incident. Raw user diagnostics are not published.

Any Phone Mic starts ordinary MIC, requests the built-in device, and tries other local presets if opening fails. It follows non-Bluetooth mic changes and can reopen a dead recorder up to three times while preserving its WAV/consumer and resetting the recorder timestamp base. Regular Microphone accepts built-in and telephony routes. Wrong-device PCM is discarded during a short restoration attempt. All extra Sources previews pause throughout recording/finalization; active rows use the recording’s waveform. Manual conflicting previews receive readable messages and notifications.

## Completed checks

- Clean release/debug/instrumentation builds and lint succeeded. **25 unit tests passed**: audio pipeline 6, mic routing 2, recording behavior 6, source behavior 4, recommendations 4, system setup 3. Android lint: **0 errors, 26 warnings**, retaining prior compatibility/foreground-service warnings.
- **Three new Android 13 emulator MicRegressionTest cases passed.** Actual regular-mic PCM keeps growing after Record → Sources navigation with all sources wanted and a conflicting manual preview attempted; no extra preview/helper open or close occurs. Nonempty mic/mix WAVs are saved, the mix is the list player, and detail contains both tracks. The other cases verify Any Phone Mic is the sole automatic physical-mic preview and local preview teardown never closes a helper source. The first run exposed an Android-13-incompatible Stream.toList call in test code; it was replaced before the passing rerun. Real mic capture uses AudioRecord; a counting helper fixture detects accidental helper operations.
- **Five adjacent emulator cases passed:** regular mic bypasses the helper and verifies its route; disconnected Bluetooth presets are absent/unmonitored; app-wide default format resets every override; reorder/hide preserves the catalog; hidden/unwanted source cleanup releases the meter.
- Normalization’s unit test measures adjusted mixed samples and verifies the originals remain unchanged. It is capped peak normalization with weighted headroom, not perceptual speech loudness matching.
- Sources, Sessions, full-screen recording, Bluetooth settings, mix settings and the expanded security popup were rendered/reviewed at 1080 × 2220, density 440, Standard text. Switch explanations use full-width wrapping, long Settings content scrolls, and system-bar padding remains visible. Normalization was toggled and restored. The popup was dismissed without enabling TCP. Screenshots end in `-0.5.png`.
- The final clean build follows the passing device tests and changes only explanation/accessibility text. Its debug APK was installed for the final rendered review. No emulator runtime crash appeared in the review log. Owned old helper fixtures were stopped before mic tests; no physical phone was operated.
- Release identity: `dev.audioscope`, versionCode **5**, versionName **0.5.0**, min API **33**, target API **35**, non-debuggable. APK v2 signature verification succeeded. Same RSA-3072 certificate as prior releases: `147eeca07321e6d5e43e13869320f63087df4aabeba5a008046b5fde0eb22380`.
- APK size: **24,586,386 bytes**. SHA-256: `8682c3756198497ed4f7b8f1db896b17f474940f68d43c7a420e4312e3be41c1`.

## Remaining physical checks and limits

Samsung Android 16 mic/telephony transitions, CMF headset route holding across preview-to-record handoff, disconnect/reconnect, and multiple named Bluetooth/USB/wired inputs need physical acceptance. Android may expose only one usable headset microphone, can reject a preferred route, and Telecom controls managed phone calls. Headset holding deliberately affects media playback. Disconnects or failed route restoration report errors instead of silently substituting another mic.

Any Phone Mic accepts non-Bluetooth wired/USB input by design; it is not built-in-only. An unreported route is tolerated only when no Bluetooth input or AudioScope-owned headset route is present; confirmed Bluetooth is rejected. This does not prove undocumented OEM routing on an emulator. Valid silent PCM frames do not prove both call parties are audible.

Offline TCP setup/shutdown, USB mode changes, real pairing, Samsung Recent, screen-off endurance, reboot/update reminders and Shevery interoperability were not freshly exercised. Existing auto-call detection, playback, exports and naming remain implemented. Automatic post-boot mic capture, CallVault-style protected native handoff and per-app automatic recording rules remain unimplemented.

## History and publication

[0.4 validation](VALIDATION-0.4.0.md) and [0.3 validation](VALIDATION-0.3.0.md) retain historical checks. [The latest request audit](REQUIREMENTS-0.5.0.md) maps changes to the request. The [CallVault comparison](CALLVAULT-COMPARISON-2026-10-05.md) describes 0.4; 0.5 supersedes its mic/mix observations.

Keys, credentials, caches, raw diagnostics and recordings are excluded from Git/source archives. Delivery states whether pushing succeeded; a local version tag does not imply publication.
