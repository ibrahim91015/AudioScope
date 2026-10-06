package dev.audioscope;

/** Audio mode distinguishes app calls; IWLAN identifies exposed Wi-Fi telephony registration. */
public final class CallCapturePlan {
  public static boolean carrier(int mode, int phoneState, int voiceNetwork) {
    return voiceNetwork != 18 && mode != 3 && (mode == 2 || phoneState == 2);
  }

  public static String[] sources(boolean carrier, boolean helper) {
    return carrier && helper
        ? new String[] {"uplink", "downlink"}
        : helper
            ? new String[] {"voice_playback", "communication_input"}
            : new String[] {"communication_input"};
  }
}
