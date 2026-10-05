# AudioScope 0.4.0 — clearer Settings and verified debugging controls

Settings now opens eleven focused pages. Switch titles and explanations have separate layout space; Standard text remains the default, with full-width descriptions and consistent touch targets. Existing capture, Bluetooth, format, naming and output options remain available.

Background & offline recording explains local capture separately from privileged-helper availability. New controls read actual USB/Wireless debugging state, change default USB functions, enable and verify an opt-in Wi-Fi-free restart endpoint, provide an optional wireless-debugging guard with a persistent Stop action, link battery settings and offer reboot/update setup reminders. Playback app selection uses installed launcher apps rather than requiring a guessed UID. The privileged bridge has a fixed command allow-list and a versioned API; reconnect after updating.

Offline TCP setup can restart Android debugging and may be reachable over the local network with an authorized key. It is opt-in, needs USB debugging and must be enabled again after reboot. The guard does not provide automatic helper revival or post-boot recording. CallVault's native resilient handoff, full recovery engine and per-app automatic rules remain gaps, documented with a development plan.

The source-based CallVault comparison covers settings, source capture, automation, naming, storage, playback, library/AI, memory/disk costs, restart persistence and a future AudioVault connector. It distinguishes implemented features from recommendations and physical-device checks.

Validation: clean signed/debug/test builds, 22 unit tests, zero lint errors (26 warnings), three Settings emulator cases and rendered-layout review. Same signing certificate as earlier releases. Physical Samsung/CMF/debugging behavior remains device acceptance. See VALIDATION.md and CALLVAULT-COMPARISON-2026-10-05.md.
