# Validation — AudioScope 0.2.0, 2026-10-05

## Build and completed checks

- Debug, Android test APK, and privately signed release builds completed successfully. Eleven JUnit tests passed: PCM health, WAV repair, stereo alignment, mixes, Matroska tracks, readable capture errors and automatic-call state transitions. Android lint reported zero errors and 23 warnings.
- The release is `dev.audioscope`, versionCode 2 / versionName 0.2.0, minimum API 33 and target API 35. The non-debuggable APK uses the same RSA-3072, APK Signature Scheme v2 certificate as 0.1. Certificate SHA-256: `147eeca07321e6d5e43e13869320f63087df4aabeba5a008046b5fde0eb22380`.
- APK SHA-256: `eae995af67e7fa8780fe946834d16c4980ed0ad0f4b3318bf3c703ec272449ce`.
- Five Android 13 emulator regression tests passed: resetting all 27 format overrides; preserving the catalog while reordering/hiding; opening only wanted monitors and releasing collapsed ones; discarding a failed session with no PCM; and local pairing-port discovery exposing an actual RemoteInput notification while Android Settings is open. Invalid code length retained a retryable notification. This used a temporary local NSD fixture, not a successful TLS pairing with Android's real adbd.
- A real microphone session produced 48 kHz mono PCM with pause/resume and a notification bookmark. Its 49.185-second WAV and 49.173-second AAC/M4A were inspected with ffprobe. The completed M4A appeared in MediaStore with `audio/mp4`, `Recordings/AudioScope/` and `is_pending=0`.
- An additional end-to-end emulator harness exercised the actual capture notification PendingIntents, public-copy creation, M4A decoding and the media pause action. It failed to locate the Sessions waveform after navigation. The harness was removed from the release test suite; its navigation issue and the rest of playback acceptance were not completed before the user's request to stop testing and deliver the APK.
- Source density, global format changes and app text-size controls were exercised. Earlier 150% system-font checks exposed truncation; the relevant controls were widened or changed to wrap content. Final font defaults are Standard (100%), with source labels and supporting text increased slightly and a consistent sans-serif font. Final camera-cutout/device/font-scale acceptance remains a phone check.

No further tests were run after the user requested delivery. A release-over-0.1 installation test was not completed; the verified matching certificate enables the normal Android update path.

## Physical-device evidence and remaining checks

The user verified 0.1.0 recording a Wi-Fi voicemail call on the Galaxy S23 Ultra using the VoIP/Teams playback route while CallVault recorded silence. Version 0.2 preserves that communication-playback policy path. New 0.2 behavior is not claimed as physically verified.

On the S23 Ultra, remaining checks include successful inline wireless-debugging TLS pairing and automatic helper connection, Shevery authorization/binding, automatic carrier and Wi-Fi call detection, Sessions waveform seeking and media controls, Files/Recent indexing, long recordings and screen-off behavior. Protected audio routes can still be rejected or silenced by Android/OEM policy. Initialization and measured activity do not establish that both call parties are present.

Silent PCM with actual frames is retained for diagnosis. Empty selections and failed starts producing no PCM are discarded. Originals remain private WAV files; selected-format public copies are published after export. Encoded output is written to a temporary file and renamed only after its muxer closes, so the player can use WAV while saving.

## Earlier 0.1 checks

The earlier build passed generated-tone WAV, aligned stereo, normalized mix, MKA with named tracks, AAC and Opus checks. A detached desktop-ADB shell helper was delivered/reconnected through Binder. Twenty routes produced WAVs in an emulator Record all run; this was not proof of protected call capture. These older checks were not repeated after the delivery request.

## Publication

The public repository and v0.1.0 release already exist at https://github.com/ibrahim91015/AudioScope. The 0.2 publisher verifies the local GitHub account, pushes source/tag without force and uploads the signed APK and corresponding source archive. Credentials and signing keys are excluded from source archives.
