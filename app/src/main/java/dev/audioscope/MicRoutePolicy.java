package dev.audioscope;

/** Device classes, not audio processing presets. A telephony route is not a Bluetooth headset. */
public final class MicRoutePolicy {
  public static boolean bluetooth(int type) {
    return type == 7 || type == 8 || type == 23 || type == 26 || type == 27 || type == 30;
  }

  public static boolean phone(int type) {
    return type == 15 || type == 18;
  }

  public static boolean external(int type) {
    return type == 3 || type == 11 || type == 12 || type == 22 || type == 5 || type == 6;
  }

  public static boolean acceptPhone(boolean flexible, int type) {
    // Flexible mode follows any non-Bluetooth microphone Android supplies, including wired input.
    return phone(type) || flexible && !bluetooth(type) && type != 25;
  }
}
