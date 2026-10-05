# AudioScope

An offline Android recording app with a dark purple Material 3 interface, selectable accents, live source meters, and separate audio tracks. Android 13+. Package `dev.audioscope`. Version **0.2.0**.

![Settings](docs/settings.png)

Standard text (100%) is the default. Settings also offers Small (85%) and Large (115%). Source labels and status text use a consistent sans-serif hierarchy.

## Record and compare

- **Record:** source selections and presets, an optional recording name, pause/resume, bookmarks, and live signal health. Stop saves independent tracks and any configured mixes.
- **Sources:** six common routes shown initially; 21 specialist routes in a collapsed Hidden sources group. Only shown routes are monitored while this tab is visible. Pencil mode supports drag ordering, Move up, Hide and Show. Detailed, Comfortable, Compact and two-column Mini views are saved. Compact routes expose format and error details by tapping their name or waveform.
- **Record all** records shown sources, including expanded hidden sources. Hidden collapsed routes are excluded. Gray dots turn red while that source records. Concurrent routes may compete for hardware; a visible waveform reports actual PCM, not a successful API call.
- **Auto recommendation:** compares a smoothed measured level and recommends the loudest non-silent route, adding an available microphone for playback capture. This does not start ambient recording automatically.
- **Automatic calls:** user-armed foreground service detects answered phone calls or communication audio mode, debounces state changes, and attempts VoIP/Wi-Fi playback + microphone + carrier capture. It ends only an automatic session when the call ends; manual sessions remain manual. Stopping during a call suppresses restart until the next call. Arm while the app is open; re-arm after reboot or process termination.

## Listen and find files

Sessions has inline play/pause, actual waveform seeking, elapsed/duration labels, ten-second skips and playback speed. One player is shared across tracks, with Android media-session notifications and audio focus. Tap a session title to rename it. Files & details retains access to originals, timing, logs and sharing; Share session produces a ZIP.

The selected-format copies are published through MediaStore into **Files → Recordings → AudioScope**, making them discoverable in **Recent**. Raw PCM goes in Downloads/AudioScope. File names include date/time, session label and source name. Private originals remain in `Android/data/dev.audioscope/files/sessions` for recovery, waveform display and reprocessing. Android file-manager sorting/indexing can vary.

Settings → Default audio format changes **every source selector** and clears old per-source overrides. Available formats are M4A/AAC, WAV, Opus/WebM and raw PCM. Apply default to every source also resets overrides without changing the default. Encoded files are produced after capture; encoder failure preserves and publishes the WAV instead and posts a repair notification.

## Setup and notifications

Embedded ADB uses the CallVault-style workflow: local mDNS discovery, direct Wireless debugging navigation, an inline notification reply for the six-digit code while Android Settings stays open, then automatic connection-port discovery and helper startup. No account, desktop, cloud processing or internet upload is needed. After connection, the detached Binder helper continues capture without Wi-Fi. Reboot ends it.

Shevery/Shizuku setup requests missed Binder delivery, asks for app authorization, uses a versioned user service and reports connection failures. Embedded ADB remains an independent path. See [setup](docs/SETUP.md).

Recording notifications have pause/resume, bookmark and stop. Playback has play/pause and skip controls. Setup, saved recordings, automation, low storage, codec failures and source failures have separate updates. Source errors contain readable explanations, repair steps and their original technical detail. Alerts opens an in-app notification history, including events when Android notifications are disabled. Manage channels and optional routine updates in Settings.

## Tools

Five-second probes, sequential source sweeps, generated-tone pipeline tests, WAV-header repair, device/helper inspection, diagnostic export and filterable logs. Routing supports left/right stereo, normalized mono mixes, per-source gain/mute and named PCM tracks in an MKA container. Originals retain captured PCM.

The source catalog exposes 27 public Android input presets and playback usages, including legacy aliases and experimental remote submix. Hidden sources do not consume preview resources unless expanded. Closing a preview releases its unpinned playback policy. Explicitly pre-armed policies remain until disarmed. Remote submix can redirect speaker audio.

## Device behavior and validation

The user verified **0.1.0** capturing a Wi-Fi voicemail call on an S23 Ultra through VoIP/Teams playback where another recorder was silent. Version 0.2 preserves that voice-communication policy path and includes it in automatic call candidates. A listed route can still be rejected, unavailable or silenced by Android/OEM policy. Audio initialization alone does not prove both call parties are captured.

No empty source selection creates a session; failed starts producing no PCM are removed. Silent PCM with real frames is retained, so silence can be diagnosed. See [validation](docs/VALIDATION.md) for completed checks and remaining S23 tests. New physical-device behavior is not claimed from emulator results.

## Build and test

JDK 17, Android SDK platform 35, build tools 34.0.0. Set `sdk.dir` in ignored `local.properties`, or use Android Studio:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Run device regression tests on a disposable emulator, with microphone and notification permissions granted and its Wi-Fi debugging network approved:

```sh
adb shell am instrument -w -e class dev.audioscope.SettingsRegressionTest dev.audioscope.test/android.test.InstrumentationTestRunner
```

Selecting the test class avoids the legacy runner scanning unrelated compatibility classes inside dependencies. The NSD test registers a temporary local pairing service; it does not send a real pairing code or start an external device's helper.

For release signing, use ignored `signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and `./gradlew assembleRelease`. The private local key must be preserved to update existing installations; source archives exclude it. The optional Windows `AUDIOSCOPE_JAVAC` workaround does not change normal build requirements.

## Source and license

Public repository: [ibrahim91015/AudioScope](https://github.com/ibrahim91015/AudioScope). `Publish-GitHub.ps1` verifies the local GitHub account, pushes source and the version tag without force, and uploads the signed APK and corresponding source ZIP. Run in a normal PowerShell terminal when Windows Credential Manager is unavailable to the Codex process.

AudioScope is an independently modified fork / edited derivative of capture and daemon components from [CallVault](https://github.com/madkongo/CallVault) and [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder). [Shevery](https://github.com/HmnDev-Tech/shevery) is optional and is not bundled. GPLv3-or-later with upstream Section 7 terms; see [LICENSE](LICENSE) and [NOTICE](NOTICE). No analytics or cloud service. Only record where you have permission.
