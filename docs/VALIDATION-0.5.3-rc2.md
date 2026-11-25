# AudioScope 0.5.3-rc2 validation

November 25, 2026. Package `dev.audioscope`, version code 9, helper API 6.
Release APK: 24,652,090 bytes.
SHA-256: `44d23fdd3d8a74d5d3615f3ddcf8b928d15cd0cd313199c87b310e4287dab752`.
The APK retains the existing AudioScope release signing certificate.

## Local checks

All 53 unit tests passed, including six notification-policy cases. Android lint
completed with zero errors. Release and instrumentation APK assembly succeeded.
The last code change adds playback-policy prearming when a helper reconnects to
an already armed automatic-call service; release assembly and lint also passed
after that change.

## Phone checks completed

The user's SM-S918U1 is on Android 17/API 37. The update was installed in place.
Existing app data, pairing identity and recordings were preserved.

- The helper reconnected using the stored identity without a pairing code.
- Public-key fingerprints matched across app shutdown/reopening.
- Helper WAV growth continued for at least six seconds with no waveform-pipe
  reader. The same test passed with the screen off.
- An initial candidate could launch a duplicate helper during restart. This
  reproduced on the phone and led to the API 6 rendezvous fix.
- After that fix, Force stop followed by opening the app reattached to the same
  helper PID; one helper remained, and automatic calls were rearmed.
- Notification cleanup passed on the phone: a stale helper-topic notification
  was removed after confirmed connection, while an unrelated notification stayed.
  The fixture waits for Android's asynchronous notification posting.
- Global notification permission was enabled with the user's approval.
  Samsung's event and recording-control categories were separately blocked and
  were enabled through Settings. Reliability, pairing, problem and playback
  categories were already enabled.

The first notification test failed because its category was blocked; that is not
counted as a passing result. It passed after the category was enabled.

## Real-call screen-off check

An automatic Wi-Fi/app-call session used separate VoIP playback and Communication
mic tracks. During approximately 26 seconds with the display off, both WAVs
continued growing, the helper PID stayed unchanged, and neither track reported
an error. Both original tracks contained signal during that screen-off interval;
the microphone had recovered to the helper's MIC preset. Levels were measured
locally without transcribing or publishing call audio.

This confirms continuity and non-silent samples in this call. It does not replace
the user's listening check for intelligibility or prove every calling app.

## Remaining acceptance

Call finalization/listening, an actual reboot and Shevery capture are pending.
The direct helper test alone is evidence of file continuity under a blocked
reader and screen lock, not proof of both voices on a real call.

The helper could not leave adbd's cgroup on this Samsung build. A stopped adbd
can still kill the helper. The configured USB default is MTP; authorization
expiry is disabled and Wireless debugging is readable/enabled. No unsupported
claim is made that the app can override Force stop or OEM security policy.

Raw phone logs, audio, pairing keys and unique phone identifiers are excluded
from Git and public artifacts.
