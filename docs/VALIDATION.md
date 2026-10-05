# Validation — 2026-10-05

This is an experimental Android build, verified on an Android 13 x86_64 emulator. It has not been tested on the user's Galaxy S23 Ultra.

## Automated checks

- Five JUnit audio-pipeline tests: silent-buffer/clipping health, WAV header repair, delayed stereo alignment/channel separation, gain/mute mix behavior and separate named Matroska tracks.
- Android lint: zero errors. Remaining warnings concern intentionally used hidden AudioPolicy/Binder APIs, the runtime-gated shell provider, dependency versions and the paired ADB library's TLS implementation.
- Debug and privately signed release builds. Release APK verified with APK Signature Scheme v2 and an RSA-3072 key; package dev.audioscope, version 0.1.0, minimum API 33, target API 35, and no debuggable manifest flag. APK SHA-256: `82c8ca7d72233905aaf1a6afdcb54a076877743b33798a079b258ddc07c54c74`.
- A Gradle wrapper distribution checksum is pinned. A GitHub Actions build/test/lint workflow is supplied. The first remote run failed in Android SDK setup; the workflow now uses the runner’s preinstalled SDK on Ubuntu 24.04. The amended remote run is pending publication.

## Emulator checks

- Microphone foreground-service recording at 48 kHz mono; pause/resume; stop/finalize. The initial session wrote 1,370,640 PCM frames with zero dropped chunks.
- Detached shell daemon delivery and reconnection through Binder. This was started using desktop ADB, not through the app's wireless pairing dialog.
- Dark purple Capture, Sources, Lab and Settings layouts visually inspected. Teal accent switching and return to Purple exercised.
- Sources metering is based on measured PCM and writes no session files until recording starts.
- Record all attempted all 27 catalog entries; 20 initialized and produced WAVs in the final emulator run, while the remaining entries visibly failed. Earlier 17-entry testing initialized 16 routes. Background recording notification observed, and separate WAV headers/frame counts checked against the completed manifest. Communication playback policy rejection was visible and logged; an initialized telephony source with no call is not proof of call audio capture.
- Generated-tone self-test passed for WAV, a stereo pair with 100 ms offset, normalized mix, two-track PCM Matroska, AAC and Opus. External ffprobe inspection confirmed two named 48 kHz PCM MKA streams starting at 0 and 100 ms, AAC in M4A, and Opus in WebM.
- A monitoring/recording finalization race discovered during Record all testing was fixed by waiting for service finalization before reopening preview routes.

The delivered non-debuggable release APK was also installed on the emulator and passed the same generated-tone pipeline test.

## Physical-device acceptance checklist

1. Install the signed APK on the S23 Ultra. Connect Shizuku/Shevery and verify shell permissions in Lab. Independently test embedded ADB pairing/launch, then turn Wi-Fi off and verify the daemon continues recording. Test reconnection after reboot.
2. For a carrier call, test VOICE_CALL, then uplink/downlink, with the handset, speaker and Bluetooth routes. Speak distinctive phrases at both ends. Confirm which saved track contains each party; record the route, mode, signal stats and device report.
3. Repeat with Wi-Fi Calling and Teams/other VoIP apps. Arm communication loopback before joining. Check the remote playback and local microphone tracks separately; app privacy flags and Samsung policy may still prevent either.
4. Exercise Sources monitoring and Record all. Some source presets may alias one another or lose capture priority when all are opened. Verify expected normal playback with loopback policies and observe remote-submix output redirection.
5. Stop/restart a single source while others record; verify separate numbered segments and routing offsets. Test per-source PCM/AAC/Opus selections, share ZIPs, interruption repair and low-storage/session limits.
6. Lock the screen and test notification actions and longer recording duration. Compare clock drift and timestamp sidecars. Test battery optimization/process death behavior.

FLAC, virtual microphone injection, automatic phone-call interception, a robust OEM capability database and automatic file rotation remain outside this first implementation. Signal indicators report real samples; they cannot prove that a remote party's voice is present.

## Source catalog references

The catalog exposes all public [MediaRecorder.AudioSource constants](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource) and [AudioAttributes playback usages](https://developer.android.com/reference/android/media/AudioAttributes). Legacy communication-notification usages are exposed individually for diagnostics although Android 13+ treats them as notification usage. Device-private and automotive system usages are not automatically enumerated.

## Local publication status

The public repository https://github.com/ibrahim91015/AudioScope and v0.1.0 tag were published through local Git. Initial release creation failed because Windows PowerShell sent a non-ASCII JSON title using the wrong encoding. The publisher now sends explicit UTF-8 request bytes and has passed a Windows PowerShell 5.1 HTTP round-trip test. Rerun Publish-GitHub.ps1 to push these tooling corrections and complete release uploads. This Codex process still cannot read the local Windows Credential Manager store; credentials remain on the local computer.
