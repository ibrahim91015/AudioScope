package dev.audioscope;

import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;
import java.util.*;

/** Run on a disposable emulator; real SharedPreferences and real source-monitor teardown. */
@SuppressWarnings("deprecation")
public class SettingsRegressionTest extends InstrumentationTestCase {
  private Map<String, ?> original;

  protected void setUp() throws Exception {
    super.setUp();
    original = new HashMap<>(ScopeApp.prefs().getAll());
  }

  @SuppressWarnings("unchecked")
  protected void tearDown() throws Exception {
    ScopeApp.monitor.stop();
    SharedPreferences.Editor edit = ScopeApp.prefs().edit().clear();
    for (Map.Entry<String, ?> entry : original.entrySet()) {
      Object value = entry.getValue();
      String key = entry.getKey();
      if (value instanceof String) edit.putString(key, (String) value);
      else if (value instanceof Integer) edit.putInt(key, (Integer) value);
      else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
      else if (value instanceof Float) edit.putFloat(key, (Float) value);
      else if (value instanceof Long) edit.putLong(key, (Long) value);
      else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
    }
    edit.commit();
    super.tearDown();
  }

  public void testDefaultFormatResetsEverySourceOverride() {
    for (Source source : Source.ALL)
      ScopeApp.prefs().edit().putString("format_" + source.id, "WAV").commit();
    Formats.setDefault("AAC");
    assertEquals("AAC", ScopeApp.prefs().getString("codec", ""));
    for (Source source : Source.ALL) {
      assertEquals("AAC", Formats.current(source.id));
      assertFalse(ScopeApp.prefs().contains("format_" + source.id));
    }
    ScopeApp.prefs().edit().putString("format_mic", "Opus").commit();
    assertEquals("Opus", Formats.current("mic"));
    assertEquals("AAC", Formats.current("voice_playback"));
    Formats.setDefault("PCM");
    for (Source source : Source.ALL) assertEquals("PCM", Formats.current(source.id));
  }

  public void testReorderAndHidePreserveSourceCatalog() {
    ScopeApp.prefs().edit().remove("sourceOrder").remove("hiddenSources").commit();
    SourceLayout.move("mic", "voice_playback");
    assertEquals("mic", SourceLayout.ordered().get(0).id);
    assertEquals(Source.ALL.size(), SourceLayout.ordered().size());
    assertEquals(Source.ALL.size(), new HashSet<>(SourceLayout.ordered()).size());
    SourceLayout.hide("mic", true);
    assertTrue(SourceLayout.hidden().contains("mic"));
    SourceLayout.hide("mic", false);
    assertFalse(SourceLayout.hidden().contains("mic"));
    assertTrue(SourceLayout.hidden().contains("notification_delayed"));
  }

  public void testHiddenSourceIsNeverOpenedAndCollapsingReleasesMeter() throws Exception {
    SourceMonitor monitor = ScopeApp.monitor;
    monitor.setWanted(Collections.singleton("any_phone_mic"));
    monitor.enable();
    monitor.reconcile();
    assertNotNull(monitor.get("any_phone_mic"));
    assertNull(monitor.get("voice_playback"));
    monitor.setWanted(Collections.emptySet());
    monitor.reconcile();
    assertNull(monitor.get("any_phone_mic"));
    monitor.stop();
  }

  public void testFailedRoutesDoNotKeepAnEmptySession() throws Exception {
    android.app.Activity activity =
        getInstrumentation()
            .startActivitySync(
                new android.content.Intent(ScopeApp.app, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    int before = ScopeApp.sessions().listFiles(java.io.File::isDirectory).length;
    ScopeApp.bridge = null;
    ScopeApp.app.startForegroundService(
        new android.content.Intent(ScopeApp.app, CaptureService.class)
            .setAction("RECORD")
            .putExtra("sources", new String[] {"voice_call"}));
    Thread.sleep(3000);
    assertFalse(CaptureService.active());
    assertNull(CaptureService.session);
    assertEquals(before, ScopeApp.sessions().listFiles(java.io.File::isDirectory).length);
    activity.finish();
  }

  public void testPairingDiscoveryOffersInlineReplyWhileSettingsIsOpen() throws Exception {
    android.app.Activity activity =
        getInstrumentation()
            .startActivitySync(
                new android.content.Intent(ScopeApp.app, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    android.net.nsd.NsdManager nsd =
        ScopeApp.app.getSystemService(android.net.nsd.NsdManager.class);
    java.util.concurrent.CountDownLatch registered = new java.util.concurrent.CountDownLatch(1);
    android.net.nsd.NsdManager.RegistrationListener registration =
        new android.net.nsd.NsdManager.RegistrationListener() {
          public void onServiceRegistered(android.net.nsd.NsdServiceInfo i) {
            registered.countDown();
          }

          public void onRegistrationFailed(android.net.nsd.NsdServiceInfo i, int e) {
            registered.countDown();
          }

          public void onServiceUnregistered(android.net.nsd.NsdServiceInfo i) {}

          public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo i, int e) {}
        };
    int previous =
        android.provider.Settings.Global.getInt(
            ScopeApp.app.getContentResolver(), "adb_wifi_enabled", 0);
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      try (android.os.ParcelFileDescriptor command =
          getInstrumentation()
              .getUiAutomation()
              .executeShellCommand("settings put global adb_wifi_enabled 1")) {
        new java.io.FileInputStream(command.getFileDescriptor()).readAllBytes();
      }
      android.net.nsd.NsdServiceInfo info = new android.net.nsd.NsdServiceInfo();
      info.setServiceName("AudioScopePairingRegression");
      info.setServiceType("_adb-tls-pairing._tcp");
      info.setPort(socket.getLocalPort());
      nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, registration);
      assertTrue(registered.await(10, java.util.concurrent.TimeUnit.SECONDS));
      ScopeApp.app.startForegroundService(
          new android.content.Intent(ScopeApp.app, PairingService.class).setAction("START"));
      ScopeApp.app.startActivity(
          new android.content.Intent(
                  android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
      android.app.Notification.Action reply = null;
      long deadline = System.currentTimeMillis() + 15000;
      while (reply == null && System.currentTimeMillis() < deadline) {
        for (android.service.notification.StatusBarNotification n :
            ScopeApp.app
                .getSystemService(android.app.NotificationManager.class)
                .getActiveNotifications())
          if (n.getId() == PairingService.ID && n.getNotification().actions != null)
            for (android.app.Notification.Action a : n.getNotification().actions)
              if (a.getRemoteInputs() != null) reply = a;
        Thread.sleep(200);
      }
      assertNotNull("Local pairing discovery should expose an inline code action", reply);
      assertEquals("code", reply.getRemoteInputs()[0].getResultKey());
      android.content.Intent response = new android.content.Intent();
      android.os.Bundle input = new android.os.Bundle();
      input.putCharSequence("code", "123");
      android.app.RemoteInput.addResultsToIntent(reply.getRemoteInputs(), response, input);
      reply.actionIntent.send(ScopeApp.app, 0, response);
      Thread.sleep(500);
      boolean retry = false;
      for (android.service.notification.StatusBarNotification n :
          ScopeApp.app
              .getSystemService(android.app.NotificationManager.class)
              .getActiveNotifications())
        if (n.getId() == PairingService.ID)
          retry =
              n.getNotification()
                  .extras
                  .getString(android.app.Notification.EXTRA_TITLE, "")
                  .contains("six-digit");
      assertTrue("Invalid codes must keep the reply notification retryable", retry);
    } finally {
      ScopeApp.app.stopService(new android.content.Intent(ScopeApp.app, PairingService.class));
      try {
        nsd.unregisterService(registration);
      } catch (Exception ignored) {
      }
      try (android.os.ParcelFileDescriptor command =
          getInstrumentation()
              .getUiAutomation()
              .executeShellCommand("settings put global adb_wifi_enabled " + previous)) {
        new java.io.FileInputStream(command.getFileDescriptor()).readAllBytes();
      }
      activity.finish();
    }
  }
}
