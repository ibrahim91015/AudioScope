# Android 17 connection and screen-off recording investigation

Prepared November 25, 2026. The initial AudioScope 0.5.3-rc1/helper API 5 work
was locally validated. Follow-up phone testing produced
[0.5.3-rc2/helper API 6](RELEASE-0.5.3-rc2.md); see its
[completed checks and remaining acceptance](VALIDATION-0.5.3-rc2.md).

## What the upstream projects changed

| Project reviewed | Relevant findings |
| --- | --- |
| [CallVault Android 17 prerelease, November 23](https://github.com/madkongo/CallVault/releases/tag/v2.5.2.0-a17) | It tries the paired connection before trusting a possibly hidden Wireless debugging setting. It also handles Android reporting debugging enabled while adbd is stopped, with guarded restart attempts and a cooldown. The older pairing diagnostics prereleases mainly improve observability. |
| [CallVault 2.5.0](https://github.com/madkongo/CallVault/releases/tag/v2.5.0) | It moved recording into the recorder process for phones that suspend the main app during calls. This is relevant to screen-off failures as well, although the cause on this Samsung phone is not yet measured. |
| [Shevery 14.6.4](https://github.com/HmnDev-Tech/shevery/releases/tag/14.6.4), [14.6.5](https://github.com/HmnDev-Tech/shevery/releases/tag/14.6.5) | Recent fixes cover Android 17 permission arguments, ADB framing, serialized discovery, coordinated launches and recovery. Version 14.6.5 rewrites native components in Rust. Its starter attempts to leave the parent cgroup; that is relevant when adbd's process group is killed. Neither release promises permanent availability on every OEM. |
| [ShizuCallRecorder 2.0.0](https://github.com/kitsumed/ShizuCallRecorder/releases/tag/v2.0.0) | It replaces scrcpy with native recording, supports more app-call capture and updates Android 17 reflection. It still depends on an external Shizuku-compatible service; its release does not implement its own persistent embedded ADB pairing. |

Sources were reviewed at CallVault commit `1561721d21321cf84a251093075b59b351b2e8c0`,
Shevery `ea7b84dc31dc2a7cf6c93aa62a6e9cd7eb673d4b`, and ShizuCallRecorder
`ea62e3abe665a08ac5399ffef4ac8dc392f0a7b4`, including recent prerelease notes.

## What AudioScope's code showed

These are source findings, not a claim that all failures on the phone have the same cause:

- The ADB key was already stored in private files, but key and certificate were separate writes. There was no atomic identity container or migration consistency check.
- Pairing discovery depended on reading `adb_wifi_enabled`. A hidden or redacted reading could prevent discovery altogether.
- There was no persistent reconnect service. Boot only displayed a reminder. A stopped helper or changing port could therefore look like lost pairing even when Android still trusted the key.
- Connecting could shut down an existing helper. Shevery rebinding also requested removal of the old user service.
- The privileged helper captured PCM, but the app wrote WAVs. Its bounded waveform pipe could back up if the app stopped draining it. A pipe failure stopped capture.
- The shell launch used `setsid` and redirected standard streams, but did not attempt to leave adbd's cgroup.

## Changes in this candidate

**Identity and reconnection.** A single atomically replaced private identity file lives in no-backup storage. Existing RSA keys migrate unchanged; a missing certificate can be reconstructed from the same private key. Corruption fails visibly instead of silently generating another trusted identity. Logs include a short public-key fingerprint, never the private key.

Discovery starts without relying on debugging-setting reads. Legacy mDNS resolves are serialized across searches and have a bounded wait. Reconnection checks the saved local port, a verified current-boot offline endpoint when enabled, and newly discovered local ports. It authenticates with the existing key. Closed ports and timeouts do not clear pairing state. Repeated explicit authentication rejection is reported separately.

A notification-controlled foreground service retries the selected transport with backoff and reacts to network availability/unlock. It restarts after boot/update where Android permits. It never opens an audio source. An authorized Shevery service is rebound without removing a healthy recorder. Returning to AudioScope re-arms automatic calls if that was the user's saved choice and microphone permission is available.

**Independent recording.** Protected-source WAVs are now written directly in the helper through an app-provided file descriptor. The UI pipe is only for waveforms and statistics. A blocked or broken pipe does not stop that file writer. Pause and stop are sent to the helper; WAV headers are checkpointed and finalized there. Final session metadata uses helper frame counts and timestamps. This works through Embedded ADB and the Shevery user service.

The helper attempts cgroup detachment and reports both the outcome and actual membership. It closes active captures when the app process actually dies, so Force stop is not treated as permission to keep recording invisibly.

**Optional Android repair.** The existing debugging guard can now request the development permission `WRITE_SECURE_SETTINGS`, after an explanation in Settings. This allows a repair attempt even if its privileged helper died. It cycles Wireless debugging only with Wi-Fi, confirmed `adbd=stopped`, no answering helper/manager, no call/recording, and at least ten minutes since the last attempt. Unknown states never trigger a cycle. The user explicitly opts into restoring Wireless debugging even if it was switched off; disabling the guard ends that behavior. Shevery transport leaves debugging switches to its manager.

**Optional authorization retention.** Settings can disable Android's ADB authorization timeout and restore the previous value. The confirmation explains that this affects all authorized debugging keys, not just AudioScope. It does not itself enable a listener.

Diagnostics retain bounded tails of the current and previous event logs across app restarts, plus screen transitions, reconnect outcomes, actual helper cgroups, and saved capture statistics.

## Limits and phone acceptance

Reboot destroys processes and may change/close the connection port. Retaining a pairing key does not keep a process running. Android may require Wi-Fi and Wireless debugging to be enabled before a previously paired client reconnects. Force stop prevents normal app background work until the user opens the app again. Revoked keys, cleared app data, uninstalling, or Android's authorization expiry can still require another code.

Android restricts microphone foreground services started directly from boot; the reconnect service restores the helper without secretly starting a microphone. See [Android's foreground service type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types).

The cgroup move can be rejected by Samsung/SELinux. A direct writer cannot save audio if Android kills the helper itself, denies capture, or routes silence. Ordinary phone and Bluetooth microphone sources still run in the app's microphone foreground service.

After USB connection, verify the actual Android build and failure logs first, then update in place. Confirm the same public identity after reopening/reboot, changed-port reconnection, screen-off carrier and app calls, pause/stop integrity, and Shevery operation separately. A prepared device check intentionally leaves the waveform pipe unread to verify file growth. It has not been run on the phone. Keep existing recordings and app data throughout.
