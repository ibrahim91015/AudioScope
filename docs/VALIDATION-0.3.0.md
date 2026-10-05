# Validation — AudioScope 0.3.0, 2026-10-05

## Completed checks

- Clean debug, test APK and signed release builds succeeded. Nineteen JUnit tests passed. Android lint: zero errors, 25 warnings.
- Ten Android 13 emulator regression cases passed across SettingsRegressionTest and FeatureRegressionTest: all-source format reset, source order/hiding, hidden monitor release, no-PCM session disposal, mDNS pairing discovery with real RemoteInput, headset absence/manual-preview guard, actual built-in microphone routing without the helper, capture/export/public M4A/media notification/full-screen waveform seeking, scoped ongoing-call notification naming, and a synthetic completed call-log lookup that rejects unrelated calls. Test call-log rows were cleaned up. These fixtures do not establish physical caller naming or real adbd TLS pairing.
- Actual microphone PCM was paused, resumed and bookmarked through notification PendingIntents, encoded as AAC/M4A, and published with MediaStore is_pending=0 and audio/mp4. The full-screen recording player decoded M4A; waveform accessibility seeking changed actual playback position, speed changed, and pause/resume/close notification actions worked.
- Android's real folder picker granted Documents/AudioScopeTest. The folder and grant survived force-stop/relaunch and debug APK updates. Finished M4A audio and a JSON metadata sidecar were copied into that folder without leftover temporary files; media scanner returned indexing URIs. The checked custom M4A was 48 kHz mono AAC, duration 2.112 seconds, verified with ffprobe. Private originals remained intact.
- Sessions card navigation was exercised directly in the emulator and opened the dedicated SessionActivity. The list/detail screenshots are in this repository. Standard text remains the default; layout uses system-bar/cutout insets. Physical accessibility-scale acceptance remains a phone check.
- Release identity: dev.audioscope, versionCode 3, versionName 0.3.0, min API 33, target API 35, non-debuggable. APK Signature Scheme v2 verification succeeded. Same RSA-3072 certificate as 0.1 and 0.2: 147eeca07321e6d5e43e13869320f63087df4aabeba5a008046b5fde0eb22380. A release-over-release installation retention test was not performed; matching signing identity enables the normal update path.
- APK SHA-256: `c32a8d822d2736ce97dbbc02d1a71270642f0f80d5a5fd28bdaa6a84d8b203e0`.

## Phone and provider acceptance still required

The user's earlier S23 Ultra Wi-Fi voicemail capture through VoIP/Teams is the existing physical evidence. That protected communication-playback policy path is preserved. New routing and UI behavior is not claimed as physically verified.

Remaining checks: CMF earbuds while YouTube plays with phone-only previews, explicit headset monitoring/recording and disconnect/release; Samsung Files Recent indexing; optional caller/contact/direction access with the actual phone and call apps; successful notification-reply TLS pairing, direct Samsung Wireless debugging navigation, and Shevery binding; automatic carrier/Wi-Fi/app calls, screen-off and long recordings. Android/OEM routing can reject the preferred input or silence protected audio. Activity alone does not prove both call parties are present.

Only shown sources acquire previews while Sources is visible. Recording startup releases other previews to protect active capture; recording tracks continue to show their measured waveform. Bluetooth previews are additionally explicit and never start from visibility alone. Ordinary mic presets accept PCM only after the actual built-in route is verified. Wrong/unknown input fails with repair guidance. Protected telephony/playback captures retain their separate helper/policy path.

Custom exports stage privately. Providers supporting rename publish through a temporary document; other providers receive a finished-file copy. A failed chosen-folder write posts a repair notice and falls back to the default public folder. Cloud provider Recent/rename behavior varies. AudioScope's JSON sidecar describes its multi-track session; it does not claim BCR-schema interoperability.

## Earlier results

See [0.2 validation](VALIDATION-0.2.0.md) and [requirements audit](REQUIREMENTS-0.3.0.md). Earlier generated-tone, stereo/mix/MKA, AAC/Opus and desktop-ADB helper checks remain historical evidence. The old Sessions navigation harness problem was resolved in the new regression flow.

## Publication

The public repository is https://github.com/ibrahim91015/AudioScope. The updated publisher pushes the committed source/tag without force and uploads the APK plus corresponding source archive. Signing keys, credentials, build caches and captured recordings are excluded. The delivery message states whether the current push succeeded.
