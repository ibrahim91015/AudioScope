# AudioScope 0.5.3-rc2

Android 17 phone-testing candidate, including the user's notification cleanup request.

## Notifications

Related status messages now replace one another: disconnected/connected helper,
paused/resumed recording, armed/disarmed automatic calls, held/released Bluetooth
microphone, and offline restart state. Successful helper connection also dismisses
obsolete pairing/setup warnings. Stopping a session removes its old recording
status; working source audio clears that source's previous error.

Playback recovery clears playback errors, a successful custom-folder write clears
the old folder warning, and automatic-call arming replaces its boot reminder.
Unrelated source and export failures remain visible. Ordinary informational
messages expire after 20 seconds; saved-recording notices last ten minutes.
Unresolved problems remain until corrected or dismissed. History stays in Settings.
Persistent recording, playback, pairing and reconnect controls are still owned
by their corresponding foreground services.

## Reconnection

Phone testing found that a process restart could start a second Embedded ADB
helper before the original delivered its Binder. A credential-checked local
rendezvous now asks the existing helper to reconnect. The authenticated ADB
connection can reach that rendezvous on ROMs that restrict direct app-to-shell
sockets. Only one helper can own the rendezvous for this app UID.

Helper API is now 6. Older helpers retire during the update; the existing pairing
identity is retained. Explicitly switching transports stops the old helper after
preview cleanup, and delayed Binder delivery cannot undo the selection.

## Phone findings so far

The SM-S918U1 is running Android 17/API 37 and already has Shevery 14.6.5.
Authorization expiry is already disabled. The app can read Wireless debugging's
enabled state on this build, so hidden-setting handling is a preventive fix rather
than a confirmed cause on this phone.

The candidate reconnected without a pairing code and retained the same public
identity after app shutdown. Helper-owned WAV growth passed with an unread
waveform pipe, including screen-off capture.

Samsung rejected cgroup migration: the helper remains in adbd's group. USB
debugging is enabled, the existing TCP listener is port 5555, and the configured
USB default is MTP. A debugging-service stop can therefore still affect the
helper. Follow-up checks passed for real-call screen-off continuity and automatic
reconnection after reboot without a pairing code. The phone's USB default was
then changed to Charging only after another adbd restart; a subsequent lock/wake
cycle kept both processes alive. Shevery-specific capture remains untested.

The OS event-notification category was blocked independently of the app-wide
permission. With the user's approval, that category and recording controls were
enabled in Samsung Settings. Cleanup then passed on the phone: resolved helper
state disappeared while an unrelated notification remained.

No private recordings, phone identifiers, raw logs or pairing keys are included
in the repository.

See [validation and remaining acceptance](VALIDATION-0.5.3-rc2.md).
