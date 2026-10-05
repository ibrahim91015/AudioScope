package dev.audioscope;

import java.util.Locale;

/** Translate known failures while retaining the exact exception for diagnostics. */
public final class CaptureProblem {
  public final String title, explanation, steps;

  private CaptureProblem(String title, String explanation, String steps) {
    this.title = title;
    this.explanation = explanation;
    this.steps = steps;
  }

  public static CaptureProblem of(String error) {
    String e = error.toLowerCase(Locale.US);
    if (e.contains("microphone route") || e.contains("bluetooth"))
      return new CaptureProblem(
          "Microphone routing needs attention",
          "The requested phone or Bluetooth microphone was unavailable, disconnected, or rejected"
              + " by Android. AudioScope stops instead of silently using another microphone.",
          "For ordinary sources: keep Phone mic selected and retry Microphone alone. For headset"
              + " sources: allow Nearby devices, enable Calls for your CMF headset in Android"
              + " Bluetooth settings, then tap Start monitoring. Stop Bluetooth sources to restore"
              + " media playback. Do not reset a route during a call.");
    if (e.contains("policy")
        && (e.contains("reject") || e.contains("registration") || e.contains("security")))
      return new CaptureProblem(
          "Android rejected this playback route",
          "The helper could not register an audio policy. Its shell permissions or this phone's"
              + " audio policy do not allow this route.",
          "1. Reconnect Embedded ADB or authorize Shevery in Settings.\n"
              + "2. Stop other experiments, then retry this source alone.\n"
              + "3. For Wi-Fi calls, try VoIP / Wi-Fi call playback plus Microphone.\n"
              + "A protected OEM route may remain unavailable even with a connected helper.");
    if (e.contains("initiliz")
        || e.contains("initialize")
        || e.contains("initialization")
        || e.contains("not recording")
        || e.contains("unsupported format"))
      return new CaptureProblem(
          "The audio input could not open",
          "Android could not allocate an AudioRecord for this route. Concurrent inputs, an"
              + " unsupported format, or an unavailable call route can cause this.",
          "1. Stop other sources and record this one alone.\n"
              + "2. Set 48,000 Hz and Mono in Settings.\n"
              + "3. Start the call or audio you want to capture, then retry.\n"
              + "If a carrier route fails, try VoIP / Wi-Fi call playback. An input can be"
              + " unsupported on this phone.");
    if (e.contains("helper")
        || e.contains("consent")
        || e.contains("permission")
        || e.contains("securityexception"))
      return new CaptureProblem(
          "Permission or helper needed",
          "This source needs microphone consent, playback consent, or the privileged shell helper.",
          "Open Settings and grant microphone access. Connect Embedded ADB or authorize a running"
              + " Shevery / Shizuku service. Protected call routes need the helper; screen-capture"
              + " consent only covers eligible media and games.");
    if (e.contains("eof")
        || e.contains("deadobject")
        || e.contains("broken pipe")
        || e.contains("disconnected"))
      return new CaptureProblem(
          "The capture helper disconnected",
          "The pipe to the shell helper closed before capture finished.",
          "Reconnect the helper in Settings, then retry. After updating AudioScope, restart the"
              + " helper so its code matches the new app. Existing completed audio is retained.");
    if (e.contains("space") || e.contains("storage") || e.contains("directory"))
      return new CaptureProblem(
          "Storage is unavailable",
          "AudioScope could not safely write the recording.",
          "Free phone storage, then retry. AudioScope stops when less than 100 MiB remains to"
              + " protect recordings.");
    return new CaptureProblem(
        "This source stopped with an error",
        "The source did not complete normally. The exact error below helps identify the cause.",
        "Try this source alone, check helper status in Settings, and use Tools → Inspect device to"
            + " collect a report. Other sources with a live signal can still record.");
  }

  public String details(String raw) {
    return explanation + "\n\nWhat to try\n" + steps + "\n\nTechnical detail\n" + raw;
  }
}
