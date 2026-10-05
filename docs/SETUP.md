# Setup and offline capture

1. Install the release APK on Android 13+. Allow microphone and notifications. Open Sources for live metering, or Capture for selected-source recording.
2. For privileged routes, choose one backend in Settings. Shizuku/Shevery must already have a running service; press Connect and authorize AudioScope.
3. Alternatively, enable Android Developer options and Wireless debugging. Keep Wi-Fi enabled for initial setup. Open Pair device with pairing code. Enter that dialog's pairing port and six-digit code in AudioScope and press Pair this phone. Return to the main Wireless debugging screen and enter its **connection** port, then Connect & start offline daemon. Pairing and connection ports differ and can change.
4. Wait for the header to show the embedded daemon connection. The detached shell process transports audio through Binder and local pipes. Recording no longer depends on Wi-Fi. Reboot ends the process; reconnect after reboot. OEM process killing or ADB configuration changes can also end it.
5. Optional off-Wi-Fi restart switches ADB to TCP port 5555. This can expose an authorized-key ADB listener on network interfaces and may interrupt Shizuku. The app explains this before enabling it. Disable TCP listener when finished. This optional mode clears after reboot.

The embedded ADB pairing path is implemented but still needs testing on the target Samsung phone. The emulator validation bootstrapped the same detached daemon through desktop ADB, so it does not establish that Samsung's pairing UI, permissions or offline process survival behave identically.

On Sources, monitoring opens all 27 listed routes concurrently without saving files. Some will fail or be silent; this is expected when the platform denies capture or routes are inactive. The app does not automatically retry failed metering continuously. Leave/re-enter Sources after changing permissions or backend. Leaving the app stops previews, while active recordings remain in the foreground service. Selecting another tab stops previews.

Press a source's gray dot to start saving that source. Its dot turns red only when a recording reader is running. Press the red dot to stop that source; stopping the last source finalizes the session. Record all attempts all listed sources. Its red state means there is an active session; press it to stop and save the session. Format changes take effect for the next recording segment. WAV originals are retained for PCM, AAC and Opus selections.

For playback policies, open Capture and Arm loopback before the target app starts playback or joins a call. Sources monitoring also opens these policies when a helper is connected. Sample-rate/channel/UID changes disarm the existing policies. Disarm all policies is available in Settings; stopping a source does not automatically unregister its loopback policy.

Keep battery optimization permissive for AudioScope and the chosen helper if the phone kills background capture. Notification permission enables visible action controls. Files and diagnostics remain local; use Sessions sharing to export a ZIP. Check actual saved audio before trusting any route for an important recording.

The Lab pipeline test generates 440/880 Hz tones with a 100 ms offset, verifies stereo length, writes a normalized mix and two-track MKA, and exercises AAC and Opus containers. Generated tones are explicitly labeled and do not test telephony capture. Probes and sweeps do use actual selected recording sources.
