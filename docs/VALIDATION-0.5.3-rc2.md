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

## Call finalization and reboot

The test call finalized with both tracks stopped, no track errors and two
published copies. A user-approved reboot then destroyed the previous helper and
TCP listener. After unlock, the boot-started reconnect service discovered the
Wireless debugging port and authenticated automatically with the same public
identity. No pairing code or manual Connect action was used.

Samsung subsequently restarted adbd while the USB default was MTP, killing its
helper too. The phone's USB default was changed to Charging only while USB
debugging remained enabled. Both persistent and active USB configuration read
`sec_charging,adb`. A subsequent screen-off/wake cycle retained the same adbd and
helper processes. AudioScope was left connected with automatic calls armed.

Opening the app after reboot re-arms its microphone foreground service. The
boot reconnect service itself does not open microphone sources.

## Remaining acceptance

User listening/intelligibility, other calling apps and Shevery capture have not
been independently retested. The helper and pairing checks used Embedded ADB.
Wi-Fi-loss and USB-unplug scenarios were not tested in this session.

The helper could not leave adbd's cgroup on this Samsung build. A stopped adbd
can still kill the helper. Authorization expiry is disabled and Wireless
debugging is readable/enabled. No claim is made that the app can override Force
stop or OEM security policy. File transfer can still be selected manually from
the phone's USB notification.

Raw phone logs, audio, pairing keys and unique phone identifiers are excluded
from Git and public artifacts.
