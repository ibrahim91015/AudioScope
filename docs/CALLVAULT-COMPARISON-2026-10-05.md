# CallVault and AudioScope: functionality, settings, reliability, and development priorities

**Audit date:** October 5, 2026. **AudioScope:** 0.4.0 release source, based on 0.3.0 commit `381083e`. **CallVault:** main commit `8ef410766e05f5b0eec3d83da4cbd3faf29728de`; that checkout and the remote HEAD matched during this audit. Its Gradle default version is 2.4.5. This compares implementations, not an assumption that every feature works on every phone.

## 1. Main findings

**CallVault remains ahead as an unattended call recorder and searchable call library.** Its advantages are its recovery lifecycle, incoming/outgoing/contact rules, app-call identification and exclusions, interrupted-recording rescue, compact live encoding, and library/transcription features. AudioScope's automatic-call setting currently arms a live service; it is not a promise of automatic recording after reboot or process death.

**AudioScope is ahead as a source-control and diagnosis tool.** It exposes independent routes, measured source health, simultaneous tracks, multiple recommendations, configurable source layouts, phone-only mic routing with route verification, explicit Bluetooth sources, and configurable stereo/mix/MKA output. Your successful S23 Ultra Wi-Fi voicemail capture through the voice-communication playback route is valuable evidence for that phone and test. It does not establish universal two-party capture or compatibility with every Wi-Fi call.

**AudioScope already has a stronger separation for background playback.** Its player lives in a foreground `PlaybackService` with a system `MediaSession`. The reviewed CallVault player belongs to `HomeViewModel` and releases when that ViewModel clears; no dedicated playback foreground service was found in its manifest. CallVault has a richer recording detail experience, but that is a separate advantage from background-player lifetime.

The best next release should concentrate on **not losing audio**, then **better call/app decisions**, then **scaling the library**. Adding AI before fixing interrupted exports and service recovery would give a more elaborate app with the same recording reliability gaps.

## 2. Scope, evidence, and the attached reports

The Android research report is used as background for source health, Wi-Fi calling, shared capture, timing, and multi-output architecture. The self-hosted AudioVault design report is used as a future integration proposal. Its server, Vexa/Speakr integration, People/Places, uploads, and semantic search are **not existing AudioScope features** and are not treated as instructions to build a server in this task.

This audit inspected CallVault's Settings screen, preferences, manifest, direct recorder and PCM queue, privileged app identity, carrier/VoIP policies, boot and keep-alive services, playback controller, storage/copy/rescue/retention code, naming, and catalog. AudioScope's UI, capture/monitor/bridge, call naming and automation, playback, storage, routing, formats, diagnostics, and manifest were checked against those areas. Conclusions labelled recommendations or estimates below are engineering judgments from that source review. No side-by-side physical-device benchmark or fresh CallVault APK test was performed.

Key code evidence: [CallVault SettingsScreen](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/ui/screens/SettingsScreen.kt), [CallVault AppPreferences](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/data/AppPreferences.kt), [DirectAudioRecorderSession](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/DirectAudioRecorderSession.kt), [CaptureChunkQueue](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/CaptureChunkQueue.kt), [BootReceiver](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/boot/BootReceiver.kt), [DaemonKeepAliveService](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt), [VoipAppIdentity](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/VoipAppIdentity.kt), [RecordingPlaybackController](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/ui/viewmodels/RecordingPlaybackController.kt), [MainActivity.java](../app/src/main/java/dev/audioscope/MainActivity.java), [CaptureService.java](../app/src/main/java/dev/audioscope/CaptureService.java), [PlaybackService.java](../app/src/main/java/dev/audioscope/PlaybackService.java).

## 3. Settings changes completed in AudioScope 0.4.0

The Settings landing page now opens dedicated pages instead of showing all controls in one long form:

| Page | Purpose |
|---|---|
| Audio quality & formats | Global format, resetting source overrides, sample rate, channels, encoded bitrate, duration limit, capture wake lock |
| Save folder & metadata | Folder picker, public copies, metadata sidecar |
| File names & caller details | Naming enablement, optional contact/call-log/notification access, template |
| Automatic call recording | Actual armed state, arm/disarm, phone-state permission, restart explanation |
| Bluetooth & microphones | Nearby permission, connected mic selection, phone preview, headset preparation, sample rate, idle-route release |
| Appearance | Accent, standard/small/large text, motion and waveform smoothing |
| Notifications & history | Android channels, routine updates, history, test notice |
| Connect capture helper | Notification-reply pairing, paired-phone connection, Shevery/Shizuku |
| Background & offline recording | USB debugging, verified debugging state, Wi-Fi-free restart, wireless guard, USB default mode, battery and reboot guidance |
| Advanced capture & diagnostics | Manual ports, pre-arming policies, raw PCM, playback UID/app picker, silence tuning, fallback, output routing |
| About | Version, source, license and upstream links |

Titles and descriptions are separate from the switch's 48 dp interaction row. Descriptions use the full card width, approximately 14 sp text and more line spacing. Titles are about 16 sp, Standard text remains 100%, and settings buttons have at least 48 dp height. The existing system-bar/camera-cutout protection remains. Each category has an All settings button and Android Back returns to the category list. Category state survives activity recreation. No new text scale is forced onto other tabs.

### Advanced settings parity with CallVault

| CallVault option | AudioScope 0.4 behavior | Remaining difference |
|---|---|---|
| USB debugging | Read device state through the privileged helper; turn on/off through a fixed command; consequence dialog before off | Embedded ADB only. Unavailable/redacted readback is shown as unverified. Does not grant broad settings privileges to the UI app |
| Offline recording / restart without Wi-Fi | Opt-in authorized TCP endpoint, USB-on prerequisite, randomized high port, connection verification and helper warm start | No automatic post-boot rearming/recovery engine. Android's listener may be reachable over the network, not strictly loopback-only |
| Preserve debugging availability | Optional wireless-debugging guard with a persistent Stop action and actual readback | Only while its service runs, Wi-Fi is active, and Embedded ADB privilege is reachable. Cannot resurrect lost privilege |
| USB when screen unlocks | Charging only, debugging only, file transfer, PTP, tethering, MIDI; confirmed choice and readback where available | OEM may restart the daemon during the change; AudioScope reports uncertain results and asks for reconnection rather than pretending it succeeded |
| Pair again | Uses notification code entry while Android Settings stays foreground | Same user flow; physical TLS pairing and OEM permission behavior still need device checks |
| Resilient recording / AudioRecord handoff | **Missing for protected shell sources** | This is a native capture-ownership feature, not a switch that can honestly be added without its backend |
| VoIP enablement, Ask me, per-app choices | AudioScope has globally armed phone/communication-mode automation and manual sources | Independent VoIP policy, ask-before-record prompt and per-app automatic rules are still missing |

The copied ideas and explanations have been adapted to AudioScope's actual architecture. There are no placebo switches for handoff, post-boot automatic capture, or AI. CallVault attribution and the existing GPL/Section 7 terms are preserved. Detailed advanced reference: [UsbDefaultConfig](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/integrations/adb/UsbDefaultConfig.kt), [OfflineRecording](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/integrations/adb/OfflineRecording.kt), [DebuggingSettings.java](../app/src/main/java/dev/audioscope/DebuggingSettings.java), [DebuggingGuardService.java](../app/src/main/java/dev/audioscope/DebuggingGuardService.java).

The new reboot/update receiver sends an optional setup reminder. It **does not automatically start a microphone**. A stored offline opt-in is distinguished from an endpoint enabled during the current boot. Force stop can prevent receivers/services from running until the app is opened again; a reminder is not guaranteed recovery.

## 4. Feature-by-feature comparison

### Capture, sources, and audio quality

| Capability | CallVault | AudioScope 0.4 | Assessment |
|---|---|---|---|
| Manual start/stop and call controls | Call-oriented prompts, pause/resume/stop/bookmark | Arbitrary chosen routes, per-source start, record shown, pause/resume/stop/bookmark | AudioScope offers broader manual control |
| Carrier capture | Privileged direct AudioRecord, with scrcpy fallback where supported | Privileged source pipes; explicit voice-call/uplink/downlink | CallVault has a backend fallback that AudioScope lacks |
| Wi-Fi carrier call experimentation | Managed IMS generally kept on carrier path | Voice-communication playback remains selectable and an auto-call candidate | AudioScope better for diagnosing your observed route |
| App-call remote/local capture | Dedicated VoIP capture and timing ledger, far-party diagnostics | Separate voice playback and phone mic tracks, measured independently | Different priorities; CallVault has more call-specific orchestration |
| All-source live dashboard | No comparable editable all-source console | Per-source measured waveform, status, error and format | AudioScope advantage |
| Gray preview / accent recording waves | Not a comparable multi-source state | Explicit source state and strict Record all indicator | AudioScope advantage |
| Hidden sources | Call-oriented supported sources / specialist restrictions | Saved hidden group; previews sleep until expanded | AudioScope advantage; capture pauses unrelated previews |
| Detailed/comfortable/compact/mini layouts | Not equivalent | Four saved layouts, drag order, hide/show | AudioScope advantage |
| Source recommendations | Call routing chosen by policy | Multiple live candidates within 12 dB of strongest above silence threshold | AudioScope useful, but no learned route profile or caller-side confidence |
| Signal fallback | Start/configuration fallback; VoIP far-party health | Optional measured carrier silence fallback adds candidate routes | AudioScope more visible; adding candidates can cause competition |
| Phone mic routing | Call/backend routing priorities | Ordinary mic requests built-in input and rejects wrong/unknown routes | AudioScope directly addresses unwanted headset activation |
| Bluetooth | Call capture with headset support; LE caveats | Separate conditional SCO/BLE headset presets, explicit preview/record, route verification and release | AudioScope gives more control; CMF physical behavior unverified |
| Output codec/container | Opus/Ogg and AAC/M4A, speech-oriented bitrate choices | WAV, AAC/M4A, Opus/WebM, raw PCM | Neither has implemented the proposed FLAC archive workflow here |
| Separate source masters | Typically final call output; side diagnostics/speaker turns | Independent WAV originals and selected-format copies | AudioScope advantage at higher disk cost |
| Stereo / mix / multi-track container | Stereo carrier input often used for speaker turns then downmixed | Explicit L/R output, normalized mono mix, gains/mute, named PCM MKA | AudioScope advantage; player is not a synchronized multitrack mixer |
| Live encoding | Direct PCM → codec/muxer during call | Capture PCM/WAV first; encode exports afterward | CallVault saves disk/I/O and post-stop work |
| Public unprivileged playback capture | Not the main call backend | Consent route for supported media/game usages | Does not make protected calls publicly capturable |

Capture evidence: [DirectAudioRecorderSession](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/DirectAudioRecorderSession.kt), [VoipCaptureSession](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/VoipCaptureSession.kt), [VoipTelephonyGate](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/VoipTelephonyGate.kt), [CaptureService.java](../app/src/main/java/dev/audioscope/CaptureService.java), [SourcePanel.java](../app/src/main/java/dev/audioscope/SourcePanel.java), [PlaybackPolicy.java](../app/src/main/java/dev/audioscope/PlaybackPolicy.java). Android can silence a capture client when concurrent input policy gives another app priority; an initialized recorder or growing file is not proof of both callers being audible. [Android concurrent audio input](https://developer.android.com/media/platform/sharing-audio-input).

### Call detection, automation, and persistence

| Capability | CallVault | AudioScope 0.4 | Gap / direction |
|---|---|---|---|
| Incoming/outgoing automatic recording | Separate switches | One user-armed automatic mode | Add separate carrier direction policies |
| Ask on each call | Carrier/app prompts | Manual UI/record actions, no equivalent automatic prompt | Add a dismissible, call-scoped Record prompt |
| Start outgoing capture after answer | Explicit option / answer handling | Offhook or communication mode; does not reliably distinguish outgoing dialing from answer | Needs call-state reconciliation; app calls may not expose an answer event |
| Anonymous/cross-country/contact filters | Direction-specific filters and contact lists | No equivalent automatic rule engine | Add explicit policy with readable reasons for skips |
| VoIP-only / carrier-only automation | Distinct enablement | Combined call candidates | Separate event type and selected source profile |
| Per-app automatic rule | Privileged audio-owner UID plus package policy | Naming from scoped ongoing notifications; one playback UID filter | UID filter is not automatic app detection |
| Unknown app policy | Records unidentified app when app capture enabled | No separate unknown-app setting | Offer user policy rather than silently copying fail-open behavior |
| Carrier interrupting VoIP | Managed-call gate and suspended-session handling | Broad inCall state; no comparable hold/resume orchestration | Add stable logical call ID and source state machine |
| Post-boot recovery | Boot receiver, bounded live phone monitor, ADB connection, keep-alive | Reminder; manual reconnect and arm | Major CallVault advantage |
| Helper death | Ping/debounce/rewarm/escalation policy | Binder death alert and pipes close | Major CallVault advantage |
| Force stop | Subject to Android stopped-app limitations | Same platform boundary | Neither should promise reliable force-stop survival |
| Interrupted audio rescue | Scheduled staged-recording recovery with matching metadata | WAV-header repair tool; no automatic session/export recovery | Highest-priority AudioScope gap |
| Missed-call/backup health | Call-log reconciliation and actionable health notices | Source/setup/error/history notices, no missed-call ledger | Add detection-to-recording audit records |
| Notification readiness consistency | Shared readiness/call notice coordination | Capture/setup/playback/guard notices separate | AudioScope needs a central actual readiness model |

Evidence: [BootReceiver](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/boot/BootReceiver.kt), [DaemonKeepAliveService](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt), [DaemonRecoveryPolicy](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/DaemonRecoveryPolicy.kt), [VoipCallDetector](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/VoipCallDetector.kt), [RecordingPolicy](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/RecordingPolicy.kt), [VoipAppPolicy](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/data/voip/VoipAppPolicy.kt), [CutOffRescueWorker](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/system/storage/CutOffRescueWorker.kt), [CallStateMachine.java](../app/src/main/java/dev/audioscope/CallStateMachine.java). AudioScope's `autoCalls` preference records intent; `CaptureService.armed` is the actual live state. A durable preference alone cannot recreate a listener or authorize a fresh microphone foreground service.

### Library, playback, files, and intelligence

| Capability | CallVault | AudioScope 0.4 | Assessment |
|---|---|---|---|
| Recording list/details | Call-aware list, contacts, filters, rich details | Compact cards, inline waveform/player and dedicated full-screen tracks | CallVault still richer library |
| Seeking/skips/speed | Scrubbable waveform and controls | Same basics per track; media notification controls | AudioScope covers core listening |
| Background media controls | ViewModel-owned player in reviewed source | Dedicated foreground player and MediaSession | AudioScope architecture advantage |
| Resume after process death | No persistence guarantee found for player | Player START_NOT_STICKY; no durable position | Add persisted last item/position; resume on user tap |
| Playback route/noisy interruption | General player behavior | Audio focus pause; no dedicated becoming-noisy receiver found | Add unplug/noisy pause and transient-focus policy |
| Synchronized multitrack playback | Final call player | One track at a time, not a DAW mixer | Proposed synchronized mute/solo/pan remains missing |
| Search | Contact, transcript/summary search, filters | No general indexed library search | Add title/date/app/source search before AI search |
| Notes/tags/stars | Implemented library extras | Bookmarks in session metadata; no equivalent tags/stars/notes workflow | Add reusable library metadata tables |
| Merge/unmerge | Lossless call merge, restoration/original policy | No merge/unmerge | Prefer non-destructive logical grouped sessions first |
| Import external recordings | Import/catalog paths | Own session folders only | Add SAF import and explicit ownership semantics |
| Folder choice | Persisted SAF destinations | Persisted SAF or default MediaStore | Both configurable; actual Samsung Recent indexing unverified for AudioScope |
| Metadata sidecar | BCR-compatible optional JSON; transcript sidecar | AudioScope multi-source JSON; custom-folder sidecar | Different schemas; AudioScope is not BCR-compatible |
| Caller naming | Phone metadata, contact/voicemail fallback, country placeholder; app-owner identification | Optional call log/contact and ongoing-notification naming; template includes source/app | CallVault stronger attribution; AudioScope richer track distinction |
| Storage targets | Local, Drive, combined/cloud policy | One destination plus safe private originals; provider may be cloud | AudioScope has no durable sync/backup scheduler |
| Backup retry | Bounded WorkManager retry, idempotent copies, visible failure | Immediate post-stop export; private fallback retained | Add persistent export queue |
| Retention/storage cap/short calls | Configurable, starred protection | Duration limit and low-space warnings only | No retention cap policy; duration limit is not retention |
| Transcription | On-device model management, language and scheduled/charging controls | Missing | Optional future local or self-hosted feature |
| Summaries | On-device summary model, requirements and language | Missing | Avoid loading large models during capture |
| Speaker labels | Call channel-based turns and labels | Source labels, not transcript speaker identity | Independent tracks are useful inputs, not speaker recognition |
| Transcript exports | Text/Markdown/SRT/VTT/JSON | Audio/session shares | Missing transcript model and exports |
| AudioVault/Vexa/Speakr integration | No AudioVault contract identified | Not implemented | Attached self-hosted design is a roadmap |

Evidence: [RecordingPlaybackController](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/ui/viewmodels/RecordingPlaybackController.kt), [RecordingDao](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/data/recordings/db/RecordingDao.kt), [RecordingCopyWorker](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/system/storage/RecordingCopyWorker.kt), [RetentionPolicy](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/system/storage/RetentionPolicy.kt), [RecordingFileNameFormatter](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/utils/RecordingFileNameFormatter.kt), [CallVault SettingsScreen](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/ui/screens/SettingsScreen.kt), [LibraryPanel.java](../app/src/main/java/dev/audioscope/LibraryPanel.java), [PublicRecordings.java](../app/src/main/java/dev/audioscope/PublicRecordings.java), [PlaybackWaveform.java](../app/src/main/java/dev/audioscope/PlaybackWaveform.java).

### Privacy, usability, support, and compatibility

| Area | CallVault | AudioScope 0.4 |
|---|---|---|
| Privacy lock | Optional device-authentication gate | No app lock; Android app sandbox only |
| Optional network uses | Update checks/model downloads; optional Drive through provider | Local ADB and selected provider; no AI upload/server connector |
| Theme | System/light/dark and optional wallpaper colors | Dark with multiple accents/dynamic accent; selectable text scale |
| Language | Multiple localized resources and language control | English UI; filenames support Unicode |
| Haptics/toasts | User switches | No equivalent global haptics/toast preferences |
| Settings export/import | Explicit device-independent allow-list | Capture presets only; not a complete portable settings backup |
| Logs | Enable/debug/share/save, redaction practices | Bounded event history and diagnostic export; persistent logging currently always on |
| Debug capture tests | Call-specific diagnostics and health | Five-second probes, source sweep, tone pipeline test, route inspection, WAV repair |
| Update mechanism | Optional release checker and verified user-initiated install | Manual APK installation/public source |
| Android range | min SDK 30, target 36, arm64-only build | min SDK 33, target 35; no equivalent Android 11/12 compatibility |
| Hidden APIs/OEM risk | Present | Present; a wider source catalog creates more experimental failure cases |

Supporting source: [CallVault AppPreferences](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/data/AppPreferences.kt), [CallVault manifest](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/AndroidManifest.xml), [CallVault SettingsScreen](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/ui/screens/SettingsScreen.kt), [AudioScope manifest](../app/src/main/AndroidManifest.xml), [MainActivity.java](../app/src/main/java/dev/audioscope/MainActivity.java), [ScopeApp.java](../app/src/main/java/dev/audioscope/ScopeApp.java).

## 5. What CallVault still does better, in practical order

### 5.1 Keeping the capture backend ready

CallVault separates endpoint availability from daemon liveness. USB debugging may keep `adbd` alive, but it does not itself offer a TCP endpoint. Its keep-alive checks Binder responsiveness, debounces transient failures, chooses recovery actions, rebuilds wedged connections and updates readiness. AudioScope currently detects helper loss and tells the user; it does not automatically restore that backend. The new wireless settings guard is deliberately narrower than a keep-alive engine.

Build a `ReadinessRepository` with states such as permission-needed, pairing-needed, endpoint-missing, connecting, helper-ready, armed, recording, recovering, and blocked. Keep user intent, endpoint state, Binder state and microphone state distinct. Only one coordinator should launch a helper; use a single-flight lock and bounded backoff. A connection failure must not restart a helper already recording. Report repeated failure instead of endlessly retrying an identical launch. Evidence: [DaemonRecoveryPolicy](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/DaemonRecoveryPolicy.kt), [DaemonKeepAliveService](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt).

### 5.2 Recovering interrupted recordings and exports

CallVault stages recordings with recovery notes and later rescues usable audio after process death. AudioScope writes `session.json` directly and checkpoints WAV headers, but final encoding/public copying happens in the live process. A crash can leave a partial manifest, a repairable original or a pending export that is never automatically finished.

Use `AtomicFile` for manifests and a small database journal per session/asset. Record states such as capturing, interrupted, finalizing, exporting and complete. On app start, repair only inactive sessions, preserve nonempty audio, reconstruct missing metadata conservatively and enqueue idempotent exports. Never label interrupted audio complete. Keep checksum/length and destination URI separately. The first milestone is **recover and publish what was captured**, not seamless continuation through a reboot. Evidence: [CutOffRescueWorker](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/system/storage/CutOffRescueWorker.kt), [CaptureService.java](../app/src/main/java/dev/audioscope/CaptureService.java), [PublicRecordings.java](../app/src/main/java/dev/audioscope/PublicRecordings.java).

### 5.3 Deciding which app/call is actually being recorded

CallVault can resolve voice-communication ownership from privileged playback configurations, with bounded `dumpsys audio` fallback for mode owner/active playback. It rejects platform UIDs as app owners and maps the UID to packages. AudioScope's ongoing-notification interpretation is useful for caller text but is a proxy for ownership. Notification categories, text and direction can be absent; multiple app calls can be ambiguous. Evidence: [VoipAppIdentity](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/VoipAppIdentity.kt), [CallContext.java](../app/src/main/java/dev/audioscope/CallContext.java).

Add an owner snapshot to the privileged bridge: UID, candidates, evidence source and timestamp. Join it to only the matching app's ongoing call notification; do not pick the first call notification from any app. Shared UIDs/work profiles need explicit handling. If multiple active candidates cannot be resolved, keep the identity unknown. Then implement per-app Record automatically / Ask / Never / Use default rules. Keep manual source recording available irrespective of automation rules. The new app picker only selects a playback UID; it does not supply this ownership detector.

### 5.4 Managing a useful library over years

CallVault's Room catalog, call metadata, notes, tags, stars and transcript search support a library workflow. AudioScope currently scans session directories, parses manifests and creates views for all eligible folders. Moving Settings to category pages improves settings layout but does not make the Sessions list virtualized.

Add Room and paginated/virtualized session cards. Keep files as the truth for audio, with stable IDs and repairable catalog entries. Store label/app/direction/time/track health as indexed columns, not just filenames. Support search and filters without decoding audio. CallVault also reads all catalog rows in its reviewed DAO; database use alone is not proof of unlimited-library scalability. Evidence: [RecordingDao](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/data/recordings/db/RecordingDao.kt), [LibraryPanel.java](../app/src/main/java/dev/audioscope/LibraryPanel.java).

## 6. Efficiency and memory: specific improvements

### 6.1 Avoid a new PCM array for every chunk

AudioScope allocates one packet array per privileged chunk and copies local AudioRecord buffers into fresh arrays. Its per-track queue is bounded to 150 chunks; that controls queued object count, not a strict byte budget. The stream accepts packets up to 1 MiB, so the theoretical payload bound is much larger than normal operation. CallVault's direct path uses reusable chunk buffers with a 240 × 4096-byte queue and backlog/drop statistics. This is an architectural comparison, not a measured memory benchmark. Evidence: [CaptureService.java](../app/src/main/java/dev/audioscope/CaptureService.java), [CaptureChunkQueue](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/CaptureChunkQueue.kt).

At AudioScope's current local 20 ms chunks, 48 kHz mono PCM16 is 1,920 bytes/chunk. A full 150-chunk queue is approximately **288 KB per track**, or **576 KB stereo**, before objects/buffers. Six mono queues are about 1.73 MB payload. The more noticeable issue is allocating roughly 50 new data arrays per second per local track. Protected packets differ. These are calculated estimates, not profiler results.

Implement a reusable byte-buffer pool and a byte/time-budgeted queue. Transfer ownership to the consumer and return the buffer in `finally`, including pause/drop/error paths. Meter consumers should not retain entire PCM history. Report queue high-water mark, bytes queued, dropped frames and overrun time. Validate with Android Studio allocation profiling and long calls; do not change overflow behavior silently.

### 6.2 Spend less on previewing routes

Each visible preview currently starts capture work and meter consumption; protected routes also need a daemon pump. Hiding sources and explicit Bluetooth preview already reduce work. Add a preview profile: low-rate mono when the route supports it, slower meter publication, viewport-based release, and backoff after a persistent policy rejection. Reopening a blocked source every refresh should not be a retry loop. Source health can be cached briefly, but stale health must never be displayed as a current live waveform.

Record only the chosen route set for normal calls. Keep Record all as a diagnostic action. Multiple recommendations should explain that loudness may select duplicated mixes or only one side, not independent useful speakers. Add a measured device/carrier/route profile and retain a quiet reference only when the user asks for it.

### 6.3 Reduce disk cost without risking originals

At 48 kHz mono PCM16, a WAV original is about **345.6 MB/hour per source** (decimal), before copies. Six sources are roughly 2.07 GB/hour; stereo doubles that. A 128 kbps encoded file is about 57.6 MB/hour, plus container overhead. AudioScope's M4A export currently does not eliminate its WAV original, so changing the dropdown alone does not deliver CallVault-like storage efficiency.

Offer explicit preservation modes: diagnostic originals; verified compressed originals; both. Capture once and fan out to bounded consumers. Start a streaming encoder when suitable, while preserving recovery information; never synchronously block the AudioRecord reader on encoder/storage. Keep masters until the new policy has been selected and the final file is validated. Add lower speech bitrates and FLAC only with real encoding/decoding support. Do not auto-delete existing WAVs as a hidden optimization.

### 6.4 Make waveform/library work proportional to what is visible

AudioScope already stores 360-bin peaks and validates its cache against file size/mtime. It reads PCM sample-by-sample when building that cache, and the fixed two-worker executor still accepts queued work for many cards. Move peak creation to finalization, read blocks, store a versioned peak asset, and load only visible rows. Cancel stale decode jobs and keep an LRU in memory. A long recording needs a small peak array, not its full PCM in RAM. Evidence: [PlaybackWaveform.java](../app/src/main/java/dev/audioscope/PlaybackWaveform.java).

### 6.5 Bound other background work

`ScopeApp.IO` is a cached thread pool, and persistent log writes are synchronous. Give capture-critical work its own bounded executor; use a serial helper-control executor and a small export pool. Do not queue setup operations indefinitely behind a stuck command. Batch log persistence away from the audio loop, keep redaction, and let the user choose routine vs verbose logging. The new system setup commands have an allow-list, bounded output and process timeout, but that alone does not bound all existing app work.

Measure energy, thread count, allocations, audio drops and queue backlog with comparable single-route sessions before claiming an improvement. Smaller APKs or fewer AI libraries do not establish lower capture power usage.

## 7. Better background detection and restart behavior

### Proposed lifecycle

1. Persist user policy separately from live state. Explicitly arm an eligible service while the app is visible.
2. Use phone-state callbacks and audio-mode callbacks as event signals. Reconcile them through one debounced state machine; avoid relying on a one-second poll alone.
3. Query managed Telecom state to classify carrier vs app calls, while allowing the user-selected Wi-Fi playback strategy for carrier IMS. Do not infer Wi-Fi calling merely from Wi-Fi connectivity.
4. Pre-register eligible playback policies while ready. Late app-call capture can miss routing established at call start; readiness matters before the call.
5. Persist call/session IDs and decisions. Carrier interrupts or route changes update an existing logical session instead of creating unrelated duplicates.
6. On helper loss, capture the error and finalize usable audio. Recover privilege independently of microphone startup. A native handoff may keep supported protected streams alive; it cannot recover every OEM route.
7. After reboot/update, check configuration and show actionable setup status. Restore eligible non-microphone work within Android's rules. Never equate a boot receiver with permission to start normal mic recording.

Android imposes background-start and while-in-use permission restrictions on microphone foreground services; a generic START_STICKY change is not a sufficient fix. Force stop, revoked permissions, missing pairing and a stopped `adbd` are distinct failure cases. Use WorkManager for durable exports/sync/repair, not as a real-time call detector. [Foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent).

For Samsung, provide a status checklist with direct app/battery settings and explanations: notifications allowed, unrestricted battery, removed from sleeping/deep-sleeping, helper authorization, USB/endpoint state, and actual armed state. Do not ask for unrelated permissions. A partial wake lock helps screen-off capture; it does not defeat process termination. CallVault's OEM advice and watchdog are a useful reference, not a guarantee.

## 8. Background session playback improvements

Keep AudioScope's existing service-based lifetime. Add a becoming-noisy receiver to pause when headphones unplug; improve transient audio-focus handling; persist last session/track, speed and position; and cleanly restore a paused item on user request. Do not restart playback automatically after boot. Consider Media3 `MediaSessionService` for modern controller interoperability, queues and consistent state instead of adding more static globals. [Android background playback guidance](https://developer.android.com/media/media3/session/background-playback).

A synchronized multitrack player is a separate feature: all tracks must seek on one session clock, use stored offsets/resampling and expose mute/solo/gain. Independent MediaPlayers started together are insufficient for long-term synchronization. Keep a lightweight generated proxy/mix for ordinary listening, and preserve individual masters for editing.

## 9. Suggested settings to add next

| Setting family | Recommended understandable controls | Why |
|---|---|---|
| Automatic calls | Phone calls: Off / Ask / Auto; incoming/outgoing rules; record after answer; VoIP separately | Matches user intent instead of one broad armed mode |
| App rules | App list: Default / Auto / Ask / Never; explicit unknown-app policy | Avoids unwanted recordings and false attribution |
| Source profiles | Known-good phone/Wi-Fi/VoIP profile; reference tracks; limited adaptive fallback | Better than continually recording every loud route |
| Preservation | Keep PCM masters / Compressed original / Both; show estimated MB/hour | Makes M4A space savings understandable |
| Recovery | Auto-repair inactive sessions, retry failed exports, reconnect budget, last recovery result | Handles failures without hiding them |
| Battery/preview | Normal / battery saver preview, visible-row preview, failed-source retry interval | Controls expensive background work |
| Library | Favorites, notes, tags, filters, reversible archive/trash | Improves daily use without requiring AI |
| Housekeeping | Age limit, storage cap, minimum call duration, protected favorites, preview deletions | User-approved retention; defaults keep recordings |
| Privacy | Device authentication before library access; optional caller permissions; log redaction | Phone audio/caller data deserve clear controls |
| Backup/config | Portable settings export/import; independent folder grants; optional server upload | Reinstallation/device migration without exporting credentials |

Do not export ADB private keys, helper runtime flags, folder grants, notification history or device-specific headset IDs as portable settings. CallVault's positive allow-list is a good pattern. Capture presets and complete settings backups should remain different concepts.

## 10. Prioritized development plan

| Priority | Change | Effort estimate | Acceptance criterion |
|---|---|---|---|
| P0 | Atomic manifests + interrupted-session repair + durable exports | Medium | Kill the app mid-capture and mid-export; captured nonempty audio is recoverable, labelled interrupted, and exported exactly once |
| P0 | Unified readiness + single-flight helper recovery | Large | Helper death, Wi-Fi loss and repeated connect failure produce truthful state, bounded retry and no duplicate daemon |
| P0 | Event-driven call identity/state and missed-call ledger | Large | Incoming/outgoing, declined calls, app call + carrier interruption and manual override do not double-start or lose state |
| P1 | Privileged app-owner identity + per-app Ask/Auto/Never | Medium–large | Active app gets correct rule; unrelated notifications cannot change it; unknown/shared UID policy tested |
| P1 | Pooled PCM buffers and byte-budget queues | Medium | Long multi-source runs show fewer allocations without more audio drops; pool returns on every exit path |
| P1 | Room catalog + virtualized Sessions | Medium | Thousands of sessions open without eagerly parsing/decoding every file; visible rows and search remain responsive |
| P1 | Playback noisy/focus handling + saved resume state | Small–medium | Screen-off/other tabs work; unplug pauses; process death restores a paused item without auto-playing |
| P1 | Explicit compressed-original policy + streaming encoding | Large | PCM capture does not block on codec; interruption yields recoverable audio; verified file storage cost matches policy |
| P2 | Supported native capture handoff | Large / device research | Kill helper mid-call on supported routes, record continues; unsupported routes are clearly labelled |
| P2 | Notes/tags/stars, non-destructive groups, retention preview | Medium | Metadata edits survive restart; protected recordings never auto-delete; retention can be reviewed |
| P2 | Settings backup, app lock, import, signed updates | Medium | No secrets/grants leak; imported media ownership and deletion are explicit |
| P3 | Optional AudioVault connector + processing features | Large / separate project | Offline capture remains independent; uploads resume and verify before any local deletion |

Effort labels indicate relative implementation complexity, not delivery dates. Each P0 needs device failure-injection work. No assertion that these roadmap items have been implemented is made.

## 11. Fit with the self-hosted AudioVault proposal

The attached design's `Recording → Assets → Tracks` model fits AudioScope better than a one-call/one-file schema. First add stable session and asset UUIDs, content hashes, structured source/device/route/timing/health metadata and schema versioning locally. An MKA asset can own many tracks; per-source files are separate assets. Retain Android source constants as implementation details beside normalized logical source names.

A future connector should use a stable AudioVault mobile API, a scoped device token, a durable upload queue, Wi-Fi/charging rules, resumable parts and checksum confirmation. It should never make capture wait for the server. Keep original deletion off by default and permit it only after verified server retention. Retries must be idempotent per asset, not per filename.

Vexa-style live captions are optional derived output; final transcripts, speaker diarization, confirmed person identity and summaries should be versioned server-side artifacts. Source labels are not speaker names. Caller-contact names are metadata, not proof that a voice belongs to a person. People/Places, voice profiles, location, meeting imports and semantic search belong to that larger project and remain unimplemented here.

For this phone app, the most useful early server milestone is **upload one complete session with tracks, hashes and diagnostics, then play a proxy and inspect the original metadata**. Transcripts can follow. Native AI models should not compete with capture for CPU/RAM. All decisions in this section are design recommendations drawn from the supplied AudioVault proposal; this audit did not verify current Speakr/Vexa versions or deploy either service.

## 12. Validation and known limits

AudioScope 0.4.0 clean debug/release builds passed, with 22 unit tests and zero lint errors (26 warnings). Three new Settings instrumentation cases verify category navigation, independent switch/description layout, no microphone startup on the reliability page, and stale offline state across boot-count change. Privileged state inspection, a same-value USB write, arbitrary-command rejection, the guard notification Stop action, and rendered emulator screenshots are recorded in the accompanying validation document. Existing 0.3.0 capture/playback/Bluetooth checks are historical evidence, not silently counted as a fresh physical 0.4.0 test.

Still required on the S23 Ultra: actual USB-default churn/screen-lock behavior, real TLS pairing and Wi-Fi-free restart, reboot notifications, Samsung Recent, CMF media playback during phone-only preview, explicit CMF mic routing/release, carrier/app interruption, and long calls. Readback support and debugging behavior vary by OEM. The guard is not equivalent to CallVault's complete recovery engine. Protected stream handoff, per-app automatic rules, AI, library search/retention, and self-hosted uploads remain roadmap gaps.

## Appendix A. Settings inventory by product

### CallVault user-facing families

- **Privilege/setup:** standalone Embedded ADB or Shizuku, authorization/readiness, pair again, developer switches and capability-dependent options.
- **Carrier automation:** carrier master enablement; automatic incoming/outgoing; start outgoing recording after answer; anonymous incoming; cross-country filters; incoming/outgoing contact ignore or only-selected lists; record prompts.
- **App calls:** enable experimental VoIP; automatic vs ask; package exclusions presented as individual app choices.
- **Audio/naming:** source, Opus/AAC codec, bitrate, filename template/presets, date/direction/number/contact/cross-country fields.
- **Storage:** local/Drive/combined target, local and Drive SAF folder, optional BCR metadata and transcript sidecar; preserve originals after merge.
- **Cloud-copy schedule:** immediate/daily/weekly, day/time, retry/failure handling through workers.
- **Retention:** linked or separate local/Drive age, cleanup time, storage cap, minimum duration; favorite protections and import eligibility rules.
- **Transcription:** manual/scheduled, hour/minute, charging requirement, batch limit, confirmation, model selection/download/cancel/delete, language and ask-language preference.
- **Summary:** model management, requirements confirmation, summary language; associated local processing state.
- **Appearance/general:** system/light/dark, dynamic color, language, toasts, vibration, app-lock/device authentication.
- **Experimental resilience:** capture handoff, offline TCP restart, USB debugging, wireless enforcement, default USB configuration; unavailable explanations vary by backend.
- **Support:** logging, developer/debug controls, view/share/save logs, settings export/import, version/license/source/support links, optional update checking and install action.

Some stored preference keys are **internal runtime state**, not settings. The following file index lists the actual preference methods without dumping saved values or keys/credentials. Its purpose is completeness and traceability, not to imply every internal field belongs in the UI.

### AudioScope user-facing families

- **Appearance:** accent, text scale, motion, waveform smoothing.
- **Audio defaults:** format, reset every per-source override, sample rate, mono/stereo, bitrate, duration, wake lock.
- **Source UI:** recommendation mode, density, hidden/order state, per-source format and selection; editing remains on Sources.
- **Outputs:** separate original tracks, L/R assignments, normalized mix, MKA, per-source gain/mute, raw PCM.
- **Storage/naming:** public copies, destination folder, JSON metadata, caller naming, optional access, filename fields.
- **Bluetooth:** Nearby access, device/address preference, phone-preview control, optional communication route preparation, mic rate, explicit preview/record and idle release.
- **Automation:** actual arm/disarm, phone permission, measured fallback and silence thresholds; combined phone/communication-mode trigger.
- **Connections:** notification pairing, discovery/manual ports, helper manager, pre-armed playback policies, disarm, stop helper.
- **Reliability:** USB/debugging state and control, default USB mode, verified current-boot offline restart, opt-in wireless guard, battery links, reboot/update reminder.
- **Notifications/support:** routine event updates, channel management, history/test notice, diagnostics, logs, repair/probes/sweep/generated pipeline self-test.

### Source index

- [CallVault SettingsScreen](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/ui/screens/SettingsScreen.kt)
- [CallVault AppPreferences](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/data/AppPreferences.kt)
- [DirectAudioRecorderSession](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/DirectAudioRecorderSession.kt)
- [CaptureChunkQueue](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/CaptureChunkQueue.kt)
- [BootReceiver](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/boot/BootReceiver.kt)
- [DaemonKeepAliveService](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt)
- [DaemonRecoveryPolicy](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/DaemonRecoveryPolicy.kt)
- [VoipAppIdentity](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/VoipAppIdentity.kt)
- [RecordingPlaybackController](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/ui/viewmodels/RecordingPlaybackController.kt)
- [UsbDefaultConfig](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/integrations/adb/UsbDefaultConfig.kt)
- [OfflineRecording](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/integrations/adb/OfflineRecording.kt)
- [VoipCaptureSession](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/server/VoipCaptureSession.kt)
- [VoipTelephonyGate](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/VoipTelephonyGate.kt)
- [VoipCallDetector](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/VoipCallDetector.kt)
- [RecordingPolicy](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/services/recording/RecordingPolicy.kt)
- [VoipAppPolicy](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/data/voip/VoipAppPolicy.kt)
- [CutOffRescueWorker](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/system/storage/CutOffRescueWorker.kt)
- [RecordingDao](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/data/recordings/db/RecordingDao.kt)
- [RecordingCopyWorker](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/system/storage/RecordingCopyWorker.kt)
- [RetentionPolicy](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/system/storage/RetentionPolicy.kt)
- [RecordingFileNameFormatter](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/java/com/baba/callvault/utils/RecordingFileNameFormatter.kt)
- [CallVault manifest](https://github.com/madkongo/CallVault/blob/8ef410766e05f5b0eec3d83da4cbd3faf29728de/app/src/main/AndroidManifest.xml)
- [MainActivity.java](../app/src/main/java/dev/audioscope/MainActivity.java)
- [CaptureService.java](../app/src/main/java/dev/audioscope/CaptureService.java)
- [PlaybackService.java](../app/src/main/java/dev/audioscope/PlaybackService.java)
- [DebuggingSettings.java](../app/src/main/java/dev/audioscope/DebuggingSettings.java)
- [DebuggingGuardService.java](../app/src/main/java/dev/audioscope/DebuggingGuardService.java)
- [SourcePanel.java](../app/src/main/java/dev/audioscope/SourcePanel.java)
- [PlaybackPolicy.java](../app/src/main/java/dev/audioscope/PlaybackPolicy.java)
- [CallStateMachine.java](../app/src/main/java/dev/audioscope/CallStateMachine.java)
- [LibraryPanel.java](../app/src/main/java/dev/audioscope/LibraryPanel.java)
- [PublicRecordings.java](../app/src/main/java/dev/audioscope/PublicRecordings.java)
- [PlaybackWaveform.java](../app/src/main/java/dev/audioscope/PlaybackWaveform.java)
- [ScopeApp.java](../app/src/main/java/dev/audioscope/ScopeApp.java)
- [CallContext.java](../app/src/main/java/dev/audioscope/CallContext.java)
- [AudioScope manifest](../app/src/main/AndroidManifest.xml)


### Appendix B. CallVault preference accessor index

This is an inventory of source accessors, not a dump of user data. Runtime/recovery/update/model-calibration fields are included for audit traceability; they are not all user-facing toggles. The user-facing families are summarized above.

Accessor | Accessor | Accessor
--- | --- | ---
`getAudioBitRate` | `getAudioCodec` | `getAudioSource`
`getAvailableUpdateTag` | `getDebugCallerNumber` | `getDriveFolderUri`
`getFileNameTemplate` | `getIgnoreContactsModeIncoming` | `getIgnoreContactsModeOutgoing`
`getIgnoredContactsIncoming` | `getIgnoredContactsOutgoing` | `getLastHomeSectionKey`
`getLastNotifiedUpdateTag` | `getLastSeenVersionCode` | `getLastUpdateCheckMillis`
`getLogPseudonymSalt` | `getLogcatRingPreviousKib` | `getLoopbackAdbPort`
`getMinDurationSeconds` | `getPairingRefusals` | `getPendingUpdateTag`
`getPrivilegedMode` | `getRecordOnlyContactsIncoming` | `getRecordOnlyContactsOutgoing`
`getRecordingFolderUri` | `getRetentionDriveDays` | `getRetentionLocalDays`
`getRetentionTimeHour` | `getRetentionTimeMinute` | `getRtfCalibrationThreads`
`getRunCost` | `getShellGrantState` | `getSpeakerMapConfirmed`
`getSpeakerMapOverride` | `getStorageCapBytes` | `getStorageTarget`
`getSummaryConfirmRequirements` | `getSummaryLanguage` | `getSyncDayOfWeek`
`getSyncScheduleMode` | `getSyncTimeHour` | `getSyncTimeMinute`
`getThemeMode` | `getTranscriptionAskLanguage` | `getTranscriptionBatchLimit`
`getTranscriptionConfirmBeforeRun` | `getTranscriptionHour` | `getTranscriptionLanguage`
`getTranscriptionLoadMs` | `getTranscriptionMinute` | `getTranscriptionMode`
`getTranscriptionModelId` | `getTranscriptionRequiresCharging` | `getTranscriptionRtf`
`getUpdatePopupShownTag` | `getUpdateSourceOverrideUrl` | `getUpdateSuccessBannerVersion`
`getUsbDefaultMode` | `getVoipExcludedPackages` | `getWhatsNewSeenVersion`
`isAdbPaired` | `isAppLockEnabled` | `isAutoRecordIncomingEnabled`
`isAutoRecordOutgoingEnabled` | `isCarrierRecordingEnabled` | `isCommunityTucked`
`isDebugEnabled` | `isDeveloperModeUnlocked` | `isDisclaimerAccepted`
`isDynamicColorEnabled` | `isHandoffPersistEnabled` | `isIgnoreAnonymousIncomingEnabled`
`isIgnoreCrossCountryIncomingEnabled` | `isIgnoreCrossCountryOutgoingEnabled` | `isKeepOriginalsAfterMerge`
`isLoggingEnabled` | `isOfflineRecordingEnabled` | `isPersistentServerEnabled`
`isPrivilegedTransportSetUp` | `isRecordFromAnswerEnabled` | `isRetentionLinked`
`isShowToastsEnabled` | `isUpdateCheckEnabled` | `isUpdateInstallArmed`
`isVibrationEnabled` | `isVoipAutoStartEnabled` | `isVoipRecordingEnabled`
`isWdDisableWhenIdle` | `isWirelessDebuggingEnforced` | `isWizardCompleted`
`isWriteMetadataFileEnabled` | `isWriteTranscriptSidecarEnabled` | —
