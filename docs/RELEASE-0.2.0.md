AudioScope 0.2.0 redesigns the recording UI while preserving the VoIP playback path verified by the user on an S23 Ultra Wi-Fi voicemail call in 0.1.

- Five main tabs, safe system/camera-cutout insets, consistent sans-serif typography, Standard text by default, and optional Small/Large text sizes.
- Editable Sources: drag reorder, hide/show, collapsed hidden routes, Detailed/Comfortable/Compact/Mini density. Hidden collapsed routes are not monitored.
- Actual waveform session player with seeking, play/pause, ten-second skips, speed and Android media controls.
- App-wide M4A/AAC, WAV, Opus/WebM or PCM default resets every source dropdown; per-source overrides remain available.
- Local port discovery and inline notification-code pairing while Android Settings stays open, followed by helper startup.
- Active capture/pairing notifications stay separate from routine updates. Human-readable source failures and repair notifications; recording, automation, saved-file and setup updates with history.
- MediaStore audio copies visible in Files/Recordings/AudioScope and Recent; readable names and session renaming.
- Loudest-signal recommendations, debounced user-armed call capture, VoIP/Wi-Fi candidates and clean removal of failed empty sessions.
- Improved policy/pipe cleanup and Shevery/Shizuku Binder reconnection.

See docs/VALIDATION.md for emulator checks and remaining physical S23 tests. This is an experimental device build; protected capture and call detection depend on Android, OEM policy and calling apps. Private WAV originals remain available if encoding or public export fails.

The APK uses the original AudioScope signing certificate and can update 0.1 without uninstalling. Corresponding GPL source is included; signing keys and recordings are excluded.
