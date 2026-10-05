# AudioScope 0.5.1 — system-selected Communication mic

Communication mic now leaves input-device selection to Android instead of requesting the built-in phone mic. It accepts Android-selected Bluetooth, wired, USB and phone inputs, including routing changes while previewing/recording. Unreported communication routing no longer triggers the phone-only routing failure. The source description explains this behavior and possible media-playback effects.

This applies only to Communication mic. Regular Microphone stays on phone/telephony input, Any Phone Mic still excludes confirmed Bluetooth, named external/headset inputs remain pinned, and Communication mic still requires an explicit monitoring tap. Extra previews remain blocked during an existing recording.

Build: versionCode 6, versionName 0.5.1, same signing key. Unit coverage verifies system-selected input accepts Bluetooth/external routes while both phone-only choices continue rejecting Bluetooth. Real headset routing remains device acceptance.
