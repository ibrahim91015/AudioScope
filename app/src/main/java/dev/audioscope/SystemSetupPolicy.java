package dev.audioscope;

import java.util.*;

public final class SystemSetupPolicy {
  public static final String[] USB_VALUES = {"none", "adb", "mtp", "ptp", "rndis", "midi"};

  /** No free-form shell command is exposed to the app or to Binder callers. */
  public static String[] command(String action, String value) {
    if (action.equals("USB") || action.equals("WIRELESS")) {
      if (!value.equals("0") && !value.equals("1"))
        throw new IllegalArgumentException("Use on or off");
      return new String[] {
        "settings",
        "put",
        "global",
        action.equals("USB") ? "adb_enabled" : "adb_wifi_enabled",
        value
      };
    }
    if (action.equals("USB_MODE") && Arrays.asList(USB_VALUES).contains(value))
      return value.equals("none")
          ? new String[] {"svc", "usb", "setScreenUnlockedFunctions"}
          : new String[] {"svc", "usb", "setScreenUnlockedFunctions", value};
    throw new IllegalArgumentException("Unknown system setup action");
  }

  public static String usbMode(String output) {
    for (String line : output.split("\n")) {
      if (!line.contains("screen_unlocked_functions=")) continue;
      String v = line.substring(line.indexOf('=') + 1).trim().toLowerCase(Locale.ROOT);
      for (String mode : new String[] {"mtp", "ptp", "rndis", "midi", "adb"})
        if (v.contains(mode)) return mode;
      if (v.equals("none") || v.equals("0")) return "none";
    }
    return "unknown";
  }
}
