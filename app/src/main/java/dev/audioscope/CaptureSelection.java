package dev.audioscope;

import java.util.Collection;

public final class CaptureSelection {
  public static boolean all(
      boolean explicitlyPressed,
      boolean active,
      boolean paused,
      Collection<String> visible,
      Collection<String> recording) {
    return explicitlyPressed
        && active
        && !paused
        && !visible.isEmpty()
        && recording.containsAll(visible);
  }
}
