# AudioScope 0.5.3-rc1

Android 17 connection and screen-off recording candidate. Install over the existing app; do not uninstall or clear app data.

- Retains and atomically stores the existing ADB identity.
- Discovers ports even when Android hides Wireless debugging status.
- Automatically reconnects the selected helper, with backoff and a persistent stop control.
- Keeps healthy helpers intact during reconnects and Shevery rebinding.
- Saves protected recordings directly in the helper, independently of the waveform pipe.
- Adds best-effort shell process protection and more useful restart diagnostics.
- Explains optional authorization expiry prevention and stopped-debugging-service repair in Background & offline recording.

Helper API changes from 4 to 5. The old helper must stop once and reconnect after the update; this should reuse the pairing identity.

The app does not promise to survive Force stop or revoked authorization. Reboot may still require Wi-Fi/Wireless debugging to reconnect. Automatic microphone recording resumes when the app is opened, rather than launching a microphone service directly from boot.

See [the investigation and phone acceptance plan](ANDROID17-RELIABILITY.md).

[Local validation and APK identity](VALIDATION-0.5.3-rc1.md).
