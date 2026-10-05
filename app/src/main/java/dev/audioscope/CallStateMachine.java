package dev.audioscope;

/** Debounce transient audio modes; never stop a manual session on call-end. */
public final class CallStateMachine {
  public enum Action {
    NONE,
    START,
    STOP
  }

  private boolean wasCall, suppressed;
  private long began, lastActive;

  public static boolean call(int mode, int phoneState) {
    return mode == 2 || mode == 3 || phoneState == 2;
  }

  public void suppress() {
    suppressed = true;
  }

  public Action update(boolean call, long now, boolean recording, boolean automatic, boolean busy) {
    if (call) {
      lastActive = now;
      if (!wasCall) {
        began = now;
        wasCall = true;
        suppressed = false;
      }
      if (!recording && !busy && !suppressed && now - began >= 1000) return Action.START;
    } else if (wasCall && now - lastActive > 3500) {
      wasCall = false;
      suppressed = false;
      if (recording && automatic) return Action.STOP;
    }
    return Action.NONE;
  }
}
