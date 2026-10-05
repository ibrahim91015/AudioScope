# AudioScope 0.2 setup

Install the 0.2 APK over 0.1 to preserve data. Both use the same signing key. Finish a current recording before updating. Reconnect the helper after an update; an old helper is detected and stopped so capture uses matching code.

## Embedded ADB pairing

1. Enable Android Developer options if necessary (tap Build number seven times in About phone / Software information).
2. Grant AudioScope notification and microphone permissions. Settings → Embedded ADB → Pair in Wireless debugging.
3. AudioScope starts its pairing foreground service and opens Android's Wireless debugging page, with a highlighted Developer-options fallback if the direct activity is unavailable.
4. Enable Wireless debugging and approve Android's Wi-Fi network prompt.
5. Tap **Pair device with pairing code** in Android Settings. Keep this dialog open.
6. Pull down notifications. In AudioScope's pairing notification, tap **Enter code**, enter the six digits and send. This does not switch away from Android Settings. The pairing port is discovered automatically.
7. After Android accepts the code, AudioScope discovers the connection port and starts its offline helper. Wait for **Offline helper connected** and return to AudioScope.

If the phone is already paired, use Connect paired phone to rediscover the changing connection port. Setup has a Cancel action and a five-minute timeout. If discovery fails on a particular ROM/network, Advanced setup offers manual ports and connection. Never confuse the pairing port with the connection port; Android uses different sockets.

## Shevery / Shizuku

Start the installed manager's service. In AudioScope Settings tap Connect, then authorize AudioScope when prompted. AudioScope explicitly requests missed Binder delivery and binds a user service versioned to the installed APK. Open helper manager launches Shevery first if installed. Connection or authorization problems produce repair messages. If a manager remains incompatible, Embedded ADB provides a separate capture path.

## Recording a call

For Wi-Fi/Teams/app calls, compare VoIP / Wi-Fi call playback and Microphone on Sources. Advanced setup can arm communication playback before joining a call. Automatic calls also pre-arms this playback policy when a helper is connected and starts VoIP playback + microphone + carrier capture when a call is detected.

For carrier calls, probe both-sides or separate local/remote sources. Audio Policy rejected means that privilege or OEM policy refused that route; it does not mean all sources fail. AudioRecord could not open can reflect an unavailable route, sample format or too many concurrent inputs. Try one source, 48 kHz Mono, and inspect actual signal.

Automatic call mode must be armed while AudioScope is visible. It watches phone state (optional permission) and communication audio mode. Some calling apps/OEMs do not expose a reliable mode; test your actual app. It does not arm automatically after reboot. The persistent notification gives clear status and Disarm controls.

## Offline and files

The app processes audio locally. A connected detached helper uses Binder and continues without Wi-Fi; pairing/reconnection still needs Android's Wireless debugging transport. Optional TCP restart on port 5555 is in Advanced setup and is separate from ordinary capture.

Default format is in Settings. Changing it resets every source selector. Individual overrides can be set afterward. Encoded copies are produced on stop. Public copies appear under Recordings/AudioScope (PCM under Downloads/AudioScope) through MediaStore and should be listed by file managers' Recent view. Private WAV originals, timing, bookmarks and logs are accessible through Sessions → Files & details / Share session.

Notifications are separated into recording controls, playback, setup, problems and routine updates. Enable Android notifications for inline pairing. Alerts stores notification details inside the app as well.
