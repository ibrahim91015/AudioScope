package dev.audioscope;

import static org.junit.Assert.*;

import org.junit.Test;

public class SystemSetupPolicyTest {
  @Test
  public void rejectsArbitraryCommandsAndUntrustedValues() {
    for (String[] input :
        new String[][] {
          {"RUN", "id"},
          {"USB", "1;id"},
          {"WIRELESS", "true"},
          {"USB_MODE", "mtp;id"},
          {"USB_MODE", "unknown"}
        }) {
      try {
        SystemSetupPolicy.command(input[0], input[1]);
        fail("Untrusted system setup accepted");
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void chargingDoesNotPassAnInventedUsbFunction() {
    assertArrayEquals(
        new String[] {"svc", "usb", "setScreenUnlockedFunctions"},
        SystemSetupPolicy.command("USB_MODE", "none"));
    assertArrayEquals(
        new String[] {"settings", "put", "global", "adb_enabled", "1"},
        SystemSetupPolicy.command("USB", "1"));
  }

  @Test
  public void readsDefaultRatherThanCurrentUsbFunctions() {
    assertEquals(
        "mtp",
        SystemSetupPolicy.usbMode("current_functions=adb\nscreen_unlocked_functions=MTP,ADB\n"));
    assertEquals("none", SystemSetupPolicy.usbMode("screen_unlocked_functions=none\n"));
    assertEquals("unknown", SystemSetupPolicy.usbMode("current_functions=adb\n"));
  }
}
