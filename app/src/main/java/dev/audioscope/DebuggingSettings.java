/* Behavior informed by CallVault's UsbDefaultConfig and offline settings.
 * Copyright (C) 2026 The CallVault Authors. GPL-3.0-or-later + LICENSE Section 7.
 * AudioScope implementation: fixed command allow-list and verified state snapshots. */
package dev.audioscope;

import android.provider.Settings;
import java.util.*;
import org.json.JSONObject;

public final class DebuggingSettings {
  public static final String[] USB_VALUES = SystemSetupPolicy.USB_VALUES;
  public static final String[] USB_LABELS = {
    "Charging only · recommended",
    "Debugging only",
    "File transfer",
    "Photo transfer (PTP)",
    "USB tethering",
    "MIDI"
  };
  public static volatile JSONObject snapshot = new JSONObject();

  public static boolean embedded() {
    return ScopeApp.prefs().getString("helperTransport", "embedded").equals("embedded");
  }

  public static String state(String key) {
    String value = snapshot.optString(key, "unknown");
    return value.equals("1") ? "On" : value.equals("0") ? "Off" : "Not verified";
  }

  public static int boot() {
    return Settings.Global.getInt(
        ScopeApp.app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
  }

  public static boolean offlineReady() {
    return ScopeApp.prefs().getBoolean("offlineRestart", false)
        && boot() >= 0
        && ScopeApp.prefs().getInt("offlineBoot", -2) == boot();
  }

  public static int port() {
    return ScopeApp.prefs().getInt("offlinePort", 5555);
  }

  public static void refresh() throws Exception {
    ICaptureBridge bridge = ScopeApp.bridge;
    if (bridge == null) {
      snapshot = new JSONObject();
      return;
    }
    snapshot = new JSONObject(bridge.systemSetup("READ", ""));
  }
}
