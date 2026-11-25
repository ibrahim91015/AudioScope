package dev.audioscope;

import android.os.ParcelFileDescriptor;
import android.test.InstrumentationTestCase;
import java.io.File;
import org.json.JSONObject;

/** Run only after a helper is connected and no real call/recording is active. */
@SuppressWarnings("deprecation")
public class HelperReliabilityDeviceTest extends InstrumentationTestCase {
  public void testResolvedHelperNotificationClearsOnlyItsOwnTopic() throws Exception {
    awaitHelper();
    android.app.NotificationManager manager =
        ScopeApp.app.getSystemService(android.app.NotificationManager.class);
    int oldId = 210001, unrelatedId = 210002;
    try {
      for (int id : new int[] {oldId, unrelatedId}) {
        android.os.Bundle extras = new android.os.Bundle();
        extras.putString("audioscope.topic", id == oldId ? "helper" : "verification.unrelated");
        manager.notify(
            id,
            new android.app.Notification.Builder(ScopeApp.app, "events")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Notification cleanup verification")
                .setContentText("Temporary local test notification")
                .addExtras(extras)
                .build());
      }
      waitForNotice(manager, oldId, true);
      waitForNotice(manager, unrelatedId, true);
      Notices.event(
          "Capture helper connected", "The current helper connection is verified.", "settings");
      waitForNotice(manager, oldId, false);
      waitForNotice(manager, unrelatedId, true);
    } finally {
      manager.cancel(oldId);
      manager.cancel(unrelatedId);
    }
  }

  public void testConnectionSnapshot() throws Exception {
    ICaptureBridge helper = awaitHelper();
    AdbIdentityStore identity =
        new AdbIdentityStore(ScopeApp.app.getNoBackupFilesDir(), ScopeApp.app.getFilesDir());
    byte[] digest =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.cert.getPublicKey().getEncoded());
    StringBuilder fingerprint = new StringBuilder();
    for (byte b : digest) fingerprint.append(String.format(java.util.Locale.US, "%02x", b & 255));
    JSONObject snapshot = new JSONObject();
    snapshot.put("publicIdentitySHA256", fingerprint);
    snapshot.put("helperTransport", ScopeApp.prefs().getString("helperTransport", ""));
    snapshot.put("adbPaired", ScopeApp.prefs().getBoolean("adbPaired", false));
    snapshot.put("recoveryEnabled", ScopeApp.prefs().getBoolean("enforceWireless", false));
    snapshot.put("autoCallsSaved", ScopeApp.prefs().getBoolean("autoCalls", false));
    snapshot.put("helperReconnect", ScopeApp.prefs().getBoolean("helperReconnect", true));
    snapshot.put("sdk", android.os.Build.VERSION.SDK_INT);
    try {
      snapshot.put(
          "wirelessReadingFromApp",
          android.provider.Settings.Global.getInt(
              ScopeApp.app.getContentResolver(), "adb_wifi_enabled", -1));
    } catch (SecurityException e) {
      snapshot.put("wirelessReadingFromApp", "hidden");
    }
    JSONObject inspection = new JSONObject(helper.inspect());
    for (String key :
        new String[] {"pid", "uid", "processProtection", "cgroups", "api", "permissions"})
      snapshot.put(key, inspection.opt(key));
    snapshot.put("systemSetup", new JSONObject(helper.systemSetup("READ", "")));
    android.os.Bundle status = new android.os.Bundle();
    status.putString("stream", "CONNECTION_SNAPSHOT " + snapshot + "\n");
    getInstrumentation().sendStatus(0, status);
  }

  private void waitForNotice(android.app.NotificationManager manager, int id, boolean expected)
      throws Exception {
    long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
    do {
      boolean present = false;
      for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications())
        present |= n.getId() == id;
      if (present == expected) return;
      Thread.sleep(50);
    } while (android.os.SystemClock.elapsedRealtime() < deadline);
    fail(
        "Notification "
            + id
            + " expected present="
            + expected
            + "; event channel importance="
            + manager.getNotificationChannel("events").getImportance());
  }

  private ICaptureBridge awaitHelper() throws Exception {
    long deadline = android.os.SystemClock.elapsedRealtime() + 25000;
    while (ScopeApp.bridge == null && android.os.SystemClock.elapsedRealtime() < deadline)
      Thread.sleep(100);
    assertNotNull("Connect the helper first", ScopeApp.bridge);
    return ScopeApp.bridge;
  }

  public void testSavedAudioContinuesWhenAppDoesNotDrainTheWaveformPipe() throws Exception {
    captureWithBlockedPreview(false);
  }

  public void testScreenOffAudioContinuesWithoutTheWaveformReader() throws Exception {
    captureWithBlockedPreview(true);
  }

  private void captureWithBlockedPreview(boolean screenOff) throws Exception {
    assertFalse("Stop the current recording first", CaptureService.active());
    ScopeApp.monitor.stop();
    ICaptureBridge helper = awaitHelper();
    assertEquals(6, helper.apiVersion());
    File file =
        File.createTempFile(
            "helper-pipe-", ".wav", getInstrumentation().getTargetContext().getCacheDir());
    ParcelFileDescriptor preview = null;
    try {
      try (ParcelFileDescriptor output =
          ParcelFileDescriptor.open(
              file, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE)) {
        preview = helper.openSaved("communication_input", 48000, 1, -1, true, false, false, output);
      }
      if (screenOff) screen(223);
      // Deliberately do not read the preview pipe. Its buffer fills, exactly as with a frozen app.
      Thread.sleep(8000);
      JSONObject result = new JSONObject(helper.finishSaved("communication_input"));
      assertTrue(result.optBoolean("savedByHelper"));
      assertTrue(
          "Independent audio must keep growing past the preview buffer",
          result.optLong("savedFrames") > 48000 * 6);
      assertEquals("", result.optString("error"));
      try (WavFile.Reader wav = new WavFile.Reader(file)) {
        assertEquals(result.getLong("savedFrames"), wav.frames);
        assertEquals(48000, wav.rate);
      }
    } finally {
      helper.close("communication_input");
      if (preview != null) preview.close();
      file.delete();
      if (screenOff) screen(224);
    }
  }

  private void screen(int key) throws Exception {
    try (ParcelFileDescriptor command =
            getInstrumentation().getUiAutomation().executeShellCommand("input keyevent " + key);
        java.io.InputStream in = new java.io.FileInputStream(command.getFileDescriptor())) {
      in.readAllBytes();
    }
  }
}
