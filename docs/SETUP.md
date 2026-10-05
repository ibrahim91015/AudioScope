# AudioScope 0.5 setup

Install the 0.5 APK over an earlier release to preserve data. All releases use the same signing key. Finish a current recording before updating. Reconnect the helper after an update; an old helper is detected and stopped so capture uses matching code.

## Embedded ADB pairing

1. Enable Android Developer options if necessary (tap Build number seven times in About phone / Software information).
2. Grant AudioScope notification and microphone permissions. Settings → Connect capture helper → Pair in Wireless debugging.
3. AudioScope starts its pairing foreground service and opens Android's Wireless debugging page, with a highlighted Developer-options fallback if the direct activity is unavailable.
4. Enable Wireless debugging and approve Android's Wi-Fi network prompt.
5. Tap **Pair device with pairing code** in Android Settings. Keep this dialog open.
6. Pull down notifications. In AudioScope's pairing notification, tap **Enter code**, enter the six digits and send. This does not switch away from Android Settings. The pairing port is discovered automatically.
7. After Android accepts the code, AudioScope discovers the connection port and starts its offline helper. Wait for **Offline helper connected** and return to AudioScope.

If the phone is already paired, use Connect paired phone to rediscover the changing connection port. Setup has a Cancel action and a five-minute timeout. If discovery fails on a particular ROM/network, Advanced capture & diagnostics offers manual ports and connection. Never confuse the pairing port with the connection port; Android uses different sockets.

## Shevery / Shizuku

Start the installed manager's service. In AudioScope Settings → Connect capture helper tap Connect, then authorize AudioScope when prompted. AudioScope explicitly requests missed Binder delivery and binds a user service versioned to the installed APK. Open helper manager launches Shevery first if installed. Connection or authorization problems produce repair messages. If a manager remains incompatible, Embedded ADB provides a separate capture path.

## Recording a call

For Wi-Fi/Teams/app calls, compare VoIP / Wi-Fi call playback and Any Phone Mic on Sources. Advanced capture & diagnostics can arm communication playback before joining a call. Automatic calls also pre-arms this playback policy when a helper is connected and starts VoIP playback + microphone + carrier capture when a call is detected.

For carrier calls, probe both-sides or separate local/remote sources. Audio Policy rejected means that privilege or OEM policy refused that route; it does not mean all sources fail. AudioRecord could not open can reflect an unavailable route, sample format or too many concurrent inputs. Try one source, 48 kHz Mono, and inspect actual signal.

Automatic call mode must be armed while AudioScope is visible. It watches phone state (optional permission) and communication audio mode. Some calling apps/OEMs do not expose a reliable mode; test your actual app. It does not arm automatically after reboot. The persistent notification gives clear status and Disarm controls.

## Offline and files

The app processes audio locally. A connected detached helper uses Binder and continues without Wi-Fi; pairing/reconnection still needs Android's Wireless debugging transport. Optional Wi-Fi-free helper restart is in Settings → Background & offline recording. It uses a saved randomized high TCP port and is separate from ordinary capture.

Default format is in Settings → Audio quality & formats. Changing it resets every source selector. Individual overrides can be set afterward. Encoded copies are produced on stop. Public copies appear under Recordings/AudioScope (PCM under Downloads/AudioScope) through MediaStore and should be listed by file managers' Recent view. Private WAV originals, timing, bookmarks and logs are accessible through Sessions → Files & details / Share session.

Notifications are separated into recording controls, playback, setup, problems and routine updates. Enable Android notifications for inline pairing. Alerts stores notification details inside the app as well.

## Custom folder and metadata

Settings → Save folder & metadata → Choose save folder opens Android’s folder picker. Pick a local folder such as Documents/Calls or a writable SD-card folder, then approve access. Android disallows granting some storage roots; choose a subfolder. The grant survives restarts and normal APK updates. Older folder grants are retained so saved sessions remain renameable. Copy session metadata writes a JSON sidecar in custom folders with call identity, tracks, formats, timing, bookmarks and error details. Originals are staged privately; a failed custom save falls back to the default public folder with a repair notification.

## Bluetooth / CMF earbuds

Allow Nearby devices in Settings → Bluetooth & microphones. Pair the CMF earbuds in Android and enable their Calls profile; media-only A2DP does not expose a microphone. Connected headset presets appear separately on Sources. They remain off until Start monitoring or Record. Any Phone Mic is the default idle preview and starts ordinary MIC with a built-in preference, following non-Bluetooth changes without treating telephony input as an external mic. Other mic presets require an explicit preview tap, which stops any competing phone preview. During recording, all extra previews pause; the Sources waveforms use the recording itself. Protected call/playback tracks retain their independent helper path.

If YouTube/media changes when using a headset source, stop that source or tap its preview waveform to stop monitoring. Hold Bluetooth mic until stopped is on by default: AudioScope keeps the requested headset route even if media is affected, and releases its own communication route only when the last headset preview/recording ends. Starting recording from a headset preview retains route ownership during the handoff. Record Bluetooth mic now starts directly without a preview. Settings offers preferred-headset selection, speech sample rates, disabling headset route preparation when a call app already owns it, disabling phone previews, and releasing an idle route. Android Telecom retains control during a managed phone call. Turning off Prepare headset call audio or Hold Bluetooth mic until stopped lets a call app manage the route; pinning can still fail if Android does not supply the requested input. Show each connected microphone adds Android-provided Bluetooth/USB/wired device names. Any Bluetooth Mic picks the preferred or first headset and holds that device; Android may only expose one active mic. The CMF/Samsung combination still needs physical acceptance; Android may reject the preferred input.

## Automatic call naming

Enable Name recordings from call details. Allow phone caller names & direction requests optional call-log, contacts and phone-state access. Set up VoIP call naming opens Android notification access; grant AudioScope access if you want caller names exposed in ongoing call notifications. Samsung may require Allow restricted settings on an APK-installed app before enabling notification access. Permissions can be revoked in Android Settings. No call details are uploaded.

The filename template supports {date}, {app}, {direction}, {contact}, {number}, {label}, {source}. Missing values disappear; the date and track index keep exported names distinct. Incoming/outgoing is included only when the call log or Android call notification identifies it. A call app’s hidden details cannot be recovered by inventing a name. A phone call already in progress when monitoring starts can have unknown direction until a matching completed call-log entry appears.

## USB debugging, offline restart and background reliability

Open Settings → Background & offline recording. USB/Wireless state is read through the current helper; without a reachable helper the app shows Not verified rather than trusting a saved switch. USB debugging needs no connected cable. Keep Developer options enabled; switching debugging off or changing USB functions may stop a privileged helper.

**Restart capture helper without Wi-Fi** is opt-in. Recording and a running helper already work offline without this option. Connect Embedded ADB over Wireless debugging, enable USB debugging, then choose Enable helper restart without Wi-Fi…. The confirmation explains that a randomized port is not a security boundary, traditional TCP ADB differs from Wireless debugging’s TLS transport, authorized keys can grant powerful shell access, and listeners can be reachable through network interfaces. Use trusted networks, revoke unknown debugging authorizations and disable the listener afterward. Android debugging restarts; AudioScope checks the authorized endpoint and warms a matching helper before saving current-boot readiness. Android's TCP listener may be accessible over a network, not only loopback. Reboot removes this setup: join a Wi-Fi network, which does not need internet, reconnect and enable it again. Restart helper without Wi-Fi uses the verified endpoint; it does not automatically re-arm calls. Disable requests USB-only ADB; if the device rejects it, toggle debugging in Developer options to close the listener.

USB when screen unlocks offers charging only, debugging only, file/photo transfer, tethering and MIDI. Charging only can reduce USB renegotiation interruptions on affected phones; verify your device. AudioScope confirms changes and reads back the actual default where supported. With Shevery/Shizuku, use that manager's debugging controls.

The optional wireless guard runs with a persistent Disable guard notification. It attempts restoration only on Wi-Fi, with a reachable Embedded ADB helper and without active recording/finalization. It cannot recover lost privilege and is not a replacement for CallVault's recovery engine. A saved enabled preference is shown separately from a live guard.

Battery links explain Samsung Unrestricted and Sleeping/Deep sleeping apps. Optional reboot/update reminders ask you to reconnect and re-arm; they never start microphones by themselves. Force stop and OEM process removal can prevent background delivery. Always inspect actual armed/helper state after reopening.

Playback app selection is under Advanced capture & diagnostics. It limits protected playback capture by an installed app's UID; this is not automatic per-app call detection or an automatic recording policy. Apps sharing a UID may share the filter. Policy changes apply after old pre-armed routes are disarmed.

## Default mix and individual recordings

Mono mix is enabled by default in 0.5, including when upgrading. Settings → Audio quality & formats can disable it or toggle Normalize track levels in mix. Normalization uses a capped peak adjustment before weighted mixing; it does not change the individual recordings. Sessions presents mix.wav first when available. Tap the recording to open its full-screen view and listen to all original source tracks. Format selection and public/custom folder exports continue to apply after capture.
