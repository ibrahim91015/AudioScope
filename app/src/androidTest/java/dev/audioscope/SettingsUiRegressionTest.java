package dev.audioscope;

import android.app.Activity;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;

@SuppressWarnings("deprecation")
public final class SettingsUiRegressionTest extends InstrumentationTestCase {
  private Activity activity;

  protected void setUp() throws Exception {
    super.setUp();
    activity =
        getInstrumentation()
            .startActivitySync(
                new Intent(ScopeApp.app, MainActivity.class)
                    .putExtra("screen", "settings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    getInstrumentation().waitForIdleSync();
  }

  protected void tearDown() throws Exception {
    getInstrumentation().runOnMainSync(() -> activity.finish());
    super.tearDown();
  }

  private View find(View root, String text) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
      return root;
    if (root instanceof ViewGroup)
      for (int n = 0; n < ((ViewGroup) root).getChildCount(); n++) {
        View v = find(((ViewGroup) root).getChildAt(n), text);
        if (v != null) return v;
      }
    return null;
  }

  private View find(String text) {
    return find(activity.getWindow().getDecorView(), text);
  }

  private void open(String text) {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              View v = find(text);
              assertNotNull(text, v);
              while (!v.isClickable() && v.getParent() instanceof View) v = (View) v.getParent();
              assertTrue(v.performClick());
            });
    getInstrumentation().waitForIdleSync();
  }

  public void testCategoriesNavigateAndSwitchExplanationsUseFullWidth() {
    assertNotNull(find("Audio quality & formats"));
    assertNull(find("Default audio format"));
    open("Audio quality & formats");
    assertNotNull(find("Default audio format"));
    assertNotNull(find("Capture channels"));
    open("‹ All settings");
    open("Appearance");
    View label = find("Smooth interface animations");
    assertNotNull(label);
    ViewGroup row = (ViewGroup) label.getParent();
    assertEquals(2, row.getChildCount());
    assertTrue(
        row.getChildAt(1) instanceof com.google.android.material.materialswitch.MaterialSwitch);
    ViewGroup item = (ViewGroup) row.getParent();
    assertEquals(2, item.getChildCount());
    assertTrue(item.getChildAt(1) instanceof TextView);
    assertEquals(item.getWidth(), item.getChildAt(1).getWidth());
  }

  public void testReliabilityPageDoesNotStartMicrophonesOrMisreportRebootState() {
    open("Background & offline recording");
    assertNotNull(find("Recording is always local"));
    assertNotNull(find("USB when screen unlocks"));
    assertNotNull(find("Restart helper without Wi-Fi"));
    assertFalse(CaptureService.active());
    assertFalse(CaptureService.armed);
    for (Source source : Source.ALL) assertNull(ScopeApp.monitor.get(source.id));
    int previous = ScopeApp.prefs().getInt("offlineBoot", -2);
    boolean opted = ScopeApp.prefs().getBoolean("offlineRestart", false);
    try {
      ScopeApp.prefs()
          .edit()
          .putBoolean("offlineRestart", true)
          .putInt("offlineBoot", DebuggingSettings.boot() - 1)
          .commit();
      assertFalse(DebuggingSettings.offlineReady());
    } finally {
      ScopeApp.prefs()
          .edit()
          .putBoolean("offlineRestart", opted)
          .putInt("offlineBoot", previous)
          .commit();
    }
  }

  public void testPrivilegedSetupReadbackAndGuardNotificationStop() throws Exception {
    long until = System.currentTimeMillis() + 10000;
    while (ScopeApp.bridge == null && System.currentTimeMillis() < until) Thread.sleep(100);
    assertNotNull("Start the emulator helper fixture before this class", ScopeApp.bridge);
    assertEquals(4, ScopeApp.bridge.apiVersion());
    org.json.JSONObject state = new org.json.JSONObject(ScopeApp.bridge.systemSetup("READ", ""));
    String usb = state.getString("usb");
    assertTrue(usb.equals("0") || usb.equals("1"));
    assertEquals(
        usb, new org.json.JSONObject(ScopeApp.bridge.systemSetup("USB", usb)).getString("usb"));
    try {
      ScopeApp.bridge.systemSetup("RUN", "id");
      fail("Arbitrary shell operation accepted");
    } catch (IllegalStateException expected) {
    }
    boolean previous = ScopeApp.prefs().getBoolean("enforceWireless", false);
    String transport = ScopeApp.prefs().getString("helperTransport", "embedded");
    ICaptureBridge bridge = ScopeApp.bridge;
    try {
      ScopeApp.bridge = null; // Guard must report missing privilege without opening a microphone.
      ScopeApp.prefs()
          .edit()
          .putBoolean("enforceWireless", true)
          .putString("helperTransport", "embedded")
          .commit();
      ScopeApp.app.startForegroundService(new Intent(ScopeApp.app, DebuggingGuardService.class));
      Thread.sleep(800);
      assertTrue(DebuggingGuardService.running);
      android.app.Notification.Action stop = null;
      for (android.service.notification.StatusBarNotification n :
          ScopeApp.app
              .getSystemService(android.app.NotificationManager.class)
              .getActiveNotifications()) if (n.getId() == 81) stop = n.getNotification().actions[0];
      assertNotNull(stop);
      stop.actionIntent.send();
      Thread.sleep(500);
      assertFalse(DebuggingGuardService.running);
      assertFalse(ScopeApp.prefs().getBoolean("enforceWireless", true));
      assertFalse(CaptureService.active());
    } finally {
      ScopeApp.app.stopService(new Intent(ScopeApp.app, DebuggingGuardService.class));
      ScopeApp.prefs()
          .edit()
          .putBoolean("enforceWireless", previous)
          .putString("helperTransport", transport)
          .commit();
      ScopeApp.bridge = bridge;
    }
  }
}
