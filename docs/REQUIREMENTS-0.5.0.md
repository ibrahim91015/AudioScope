# Latest microphone request — implementation audit

| Requested behavior | Implementation and verification |
| --- | --- |
| Regular mic survives opening Sources | All extra previews pause while capture/finalization/auto preparation is active; Sources uses existing tracks. Actual emulator mic WAV continues growing across navigation; no helper preview opens. |
| Telephony/communication routing is not mistaken for an external mic | Regular phone input accepts built-in (15) and telephony (18), using recorder-specific routing/configuration. Pure policy test verifies classification. The supplied diagnostics contain the old false external-route error but not the exact route at failure. |
| Default Any Phone Mic, starting ordinary Microphone | MIC first, then other local presets only if opening fails; non-Bluetooth changes accepted, remote submix excluded, wrong Bluetooth PCM discarded, dead recorder retried up to three times. Actual emulator PCM/idle preview passed; Samsung route changes remain physical acceptance. |
| A competing preview cannot stop recording | Manual action is rejected with a readable Sources message and notification; reconcile and Track opening both guard active/finalizing capture. Mic regression checks no additional meters and no helper close calls. |
| Keep Bluetooth mic and offer direct recording in Settings | Route leases span preview-to-record handoff and last until the last user-stopped/failed headset track; periodic route repair; Record Bluetooth mic now. Bluetooth absent/no-auto-preview test passed. Physical headset routing remains unverified. |
| Multiple named Bluetooth/external inputs and Any Bluetooth Mic | Dynamic Android-provided names and per-device identifiers; Show each connected microphone setting; preferred/first Any Bluetooth Mic; explicit preview only. Android’s available-input limitations apply. |
| Mono mix default and configurable normalization | First-run/upgrade default enabled; separate normalization switch defaults on; capped peak pass, weighted mix, originals unchanged. Unit test measures the normalized output and verifies original samples. |
| Sessions defaults to mix; full-screen view keeps originals | mix.wav sorted first, manifest track names retained. Device regression verifies Mono mix on the list and Microphone + Mono mix in detail. |
| Explain and rename Wi-Fi-free helper, security popup | Restart capture helper without Wi-Fi; full explanation and explicit TCP/key/network/TLS/shutdown/reboot information before opt-in. Rendered popup reviewed; no TCP listener was enabled during this release’s checks. |

No raw user diagnostics, recording samples, device addresses, caller information or private signing material are added to source control. Existing CallVault comparison is a historical 0.4 report; the 0.5 changes above supersede its microphone/mix observations.
