package dev.audioscope;

/** A manual override can resume unrelated previews, never competing input or duplicate pipes. */
public final class PreviewPolicy {
  public static String reason(
      String mode,
      boolean requested,
      boolean active,
      boolean busy,
      boolean sameSource,
      boolean input,
      boolean recordingInput,
      boolean dangerous) {
    if (busy) return "Monitoring paused while a recording starts or saves";
    if (!active) return "";
    if (sameSource) return "This recording already supplies its live waveform";
    if (input && recordingInput || dangerous)
      return "Another recording is using the microphone; this preview cannot compete with it";
    if (mode.equals("paused")) return "Monitoring paused by Settings until recording ends";
    if (!mode.equals("automatic") && !requested)
      return "Monitoring paused during recording · tap Start monitoring to resume this source";
    return "";
  }
}
