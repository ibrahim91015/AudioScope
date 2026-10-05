package dev.audioscope;

import android.app.*;
import android.content.*;
import android.media.*;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.util.*;
import org.json.*;

@SuppressWarnings("deprecation")
public class FeatureRegressionTest extends InstrumentationTestCase {
  private Map<String, ?> original;

  protected void setUp() {
    original = new HashMap<>(ScopeApp.prefs().getAll());
  }

  @SuppressWarnings("unchecked")
  protected void tearDown() throws Exception {
    if (CaptureService.active()) CaptureService.instance.stopSession();
    ScopeApp.app.stopService(new Intent(ScopeApp.app, PlaybackService.class));
    ScopeApp.monitor.stop();
    android.content.SharedPreferences.Editor e = ScopeApp.prefs().edit().clear();
    for (Map.Entry<String, ?> item : original.entrySet()) {
      Object v = item.getValue();
      String k = item.getKey();
      if (v instanceof String) e.putString(k, (String) v);
      else if (v instanceof Integer) e.putInt(k, (Integer) v);
      else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
      else if (v instanceof Float) e.putFloat(k, (Float) v);
      else if (v instanceof Long) e.putLong(k, (Long) v);
      else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
    }
    e.commit();
    super.tearDown();
  }

  private Activity foreground(String screen) {
    android.app.Instrumentation.ActivityMonitor m =
        getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
    ScopeApp.MAIN.post(
        () ->
            ScopeApp.app.startActivity(
                new Intent(ScopeApp.app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra("screen", screen)));
    Activity a = m.waitForActivityWithTimeout(8000);
    getInstrumentation().removeMonitor(m);
    assertNotNull("Requested Activity must start without waiting for animated UI idleness", a);
    return a;
  }

  private void waitFor(String message, java.util.function.BooleanSupplier ok, long timeout)
      throws Exception {
    long end = System.currentTimeMillis() + timeout;
    while (!ok.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(50);
    assertTrue(message, ok.getAsBoolean());
  }

  private Notification notice(int id) {
    for (android.service.notification.StatusBarNotification n :
        ScopeApp.app.getSystemService(NotificationManager.class).getActiveNotifications())
      if (n.getId() == id) return n.getNotification();
    throw new AssertionError("Missing notification " + id);
  }

  public void testPhoneMicBypassesHelperAndVerifiesBuiltInRoute() throws Exception {
    Activity a = foreground("record");
    ICaptureBridge previous = ScopeApp.bridge;
    java.util.concurrent.atomic.AtomicBoolean helperOpened =
        new java.util.concurrent.atomic.AtomicBoolean();
    ScopeApp.bridge =
        new ShellBridge(ScopeApp.app) {
          public android.os.ParcelFileDescriptor open(String id, int rate, int channels, int uid) {
            helperOpened.set(true);
            throw new AssertionError("Phone mic must use explicit local device routing");
          }
        };
    CaptureService.Track mic = new CaptureService.Track(Source.get("mic"), null);
    try {
      ScopeApp.IO.execute(mic::run);
      waitFor(
          "Phone mic must receive actual PCM",
          () -> mic.frames > 48000 || !mic.error.isEmpty(),
          5000);
      assertEquals("", mic.error);
      assertFalse(helperOpened.get());
      assertTrue("Actual route should be the built-in microphone", mic.device.contains("type 15"));
    } finally {
      mic.stop();
      mic.done.await(5, java.util.concurrent.TimeUnit.SECONDS);
      ScopeApp.bridge = previous;
      a.finish();
    }
  }

  public void testBluetoothIsAbsentAndNeverAutomaticallyMonitoredWithoutHeadset() throws Exception {
    assertTrue(
        "This test requires an emulator without a headset", BluetoothRouting.inputs().isEmpty());
    assertEquals(Source.ALL.size(), Source.available().size());
    ScopeApp.monitor.setWanted(Arrays.asList("bluetooth_mic", "bluetooth_communication"));
    ScopeApp.monitor.enable();
    ScopeApp.monitor.reconcile();
    assertNull(ScopeApp.monitor.get("bluetooth_mic"));
    assertFalse(ScopeApp.monitor.manual("bluetooth_mic"));
    for (Source s : Source.BLUETOOTH)
      ScopeApp.prefs().edit().putString("format_" + s.id, "Opus").commit();
    Formats.setDefault("AAC");
    for (Source s : Source.BLUETOOTH) assertEquals("AAC", Formats.current(s.id));
  }

  public void testNotificationCaptureM4aPublicCopyAndClickableWaveform() throws Exception {
    Activity a = foreground("record");
    StorageFolders.reset();
    Formats.setDefault("AAC");
    ScopeApp.prefs()
        .edit()
        .putBoolean("publicFiles", true)
        .putBoolean("publicMetadata", false)
        .commit();
    ScopeApp.app.startForegroundService(
        new Intent(ScopeApp.app, CaptureService.class)
            .setAction("RECORD")
            .putExtra("sources", new String[] {"mic"})
            .putExtra("label", "Playback verification"));
    waitFor(
        "Microphone capture",
        () ->
            CaptureService.active()
                && CaptureService.tracks.get("mic") != null
                && CaptureService.tracks.get("mic").frames > 144000,
        7000);
    File folder = CaptureService.session;
    assertFalse(
        "A single source must not light Record all",
        CaptureService.allRecording(Collections.singleton("mic")));
    getInstrumentation().runOnMainSync(() -> CaptureService.recordAllRequested = true);
    assertTrue(CaptureService.allRecording(Collections.singleton("mic")));
    assertFalse(CaptureService.allRecording(Arrays.asList("mic", "voice_playback")));
    Notification capture = notice(42);
    assertEquals("audioscope.active.capture", capture.getGroup());
    capture.actions[0].actionIntent.send();
    waitFor("Pause action", () -> CaptureService.paused, 2000);
    assertFalse(CaptureService.allRecording(Collections.singleton("mic")));
    capture.actions[1].actionIntent.send();
    Thread.sleep(300);
    notice(42).actions[0].actionIntent.send();
    waitFor("Resume action", () -> !CaptureService.paused, 2000);
    Thread.sleep(2200);
    notice(42).actions[2].actionIntent.send();
    waitFor(
        "Save and encode", () -> CaptureService.session == null && !CaptureService.stopping, 30000);
    JSONObject manifest = new JSONObject(Exports.read(new File(folder, "session.json")));
    assertTrue(manifest.getJSONArray("markers").toString().contains("Notification bookmark"));
    JSONObject track = manifest.getJSONArray("tracks").getJSONObject(0);
    assertEquals("AAC", track.getString("codec"));
    try (android.database.Cursor c =
        ScopeApp.app
            .getContentResolver()
            .query(
                android.net.Uri.parse(track.getString("publicUri")),
                new String[] {"is_pending", "relative_path", "mime_type"},
                null,
                null,
                null)) {
      assertNotNull(c);
      assertTrue(c.moveToFirst());
      assertEquals(0, c.getInt(0));
      assertEquals("Recordings/AudioScope/", c.getString(1));
      assertEquals("audio/mp4", c.getString(2));
    }
    File encoded = new File(folder, "mic.m4a");
    assertTrue(encoded.length() > 0);
    assertFalse(new File(folder, "mic.m4a.tmp").exists());
    android.util.Log.i(
        "FeatureTest", "Capture, encoding, public copy and notification actions passed");
    android.app.Instrumentation.ActivityMonitor detailMonitor =
        getInstrumentation().addMonitor(SessionActivity.class.getName(), null, false);
    ScopeApp.MAIN.post(
        () ->
            ScopeApp.app.startActivity(
                new Intent(ScopeApp.app, SessionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra("session", folder.getName())));
    Activity library = detailMonitor.waitForActivityWithTimeout(8000);
    getInstrumentation().removeMonitor(detailMonitor);
    assertNotNull("Full-screen recording must open", library);
    android.util.Log.i("FeatureTest", "Sessions Activity opened");
    PlaybackService.play(ScopeApp.app, encoded);
    waitFor("M4A player", () -> PlaybackService.ready && PlaybackService.playing, 5000);
    notice(71).actions[1].actionIntent.send();
    waitFor("Media pause", () -> !PlaybackService.playing, 2000);
    final PlaybackWaveform[] waveform = new PlaybackWaveform[1];
    getInstrumentation()
        .runOnMainSync(() -> waveform[0] = find(library.getWindow().getDecorView()));
    assertNotNull("A visible Sessions waveform", waveform[0]);
    getInstrumentation()
        .runOnMainSync(
            () -> {
              android.os.Bundle args = new android.os.Bundle();
              args.putFloat(
                  android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                  50);
              waveform[0].performAccessibilityAction(
                  android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                      .ACTION_SET_PROGRESS
                      .getId(),
                  args);
            });
    waitFor(
        "Waveform must seek actual media",
        () -> Math.abs(PlaybackService.position - PlaybackService.duration / 2) < 500,
        3000);
    getInstrumentation().runOnMainSync(() -> PlaybackService.instance.changeSpeed());
    assertEquals(1.25f, PlaybackService.speed);
    notice(71).actions[1].actionIntent.send();
    waitFor("Media resume", () -> PlaybackService.playing, 2000);
    notice(71).deleteIntent.send();
    waitFor("Media close", () -> PlaybackService.instance == null, 2000);
    library.finish();
    a.finish();
  }

  public void testCallNamesAreScopedToOngoingCallsAndMissingDirectionStaysEmpty() throws Exception {
    ScopeApp.prefs().edit().putBoolean("autoNaming", true).commit();
    CallContext.clear();
    CallContext.phone(0);
    Notification n =
        new Notification.Builder(ScopeApp.app, "events")
            .setSmallIcon(R.drawable.ic_scope)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentTitle("Alex")
            .build();
    n.extras.putParcelable(
        Notification.EXTRA_CALL_PERSON, new Person.Builder().setName("Alex").build());
    android.service.notification.StatusBarNotification call =
        new android.service.notification.StatusBarNotification(
            "com.example.call",
            "com.example.call",
            901,
            null,
            12345,
            1,
            0,
            n,
            android.os.Process.myUserHandle(),
            System.currentTimeMillis());
    CallContext.notification(call);
    JSONObject identity = CallContext.snapshot();
    assertEquals("Alex", identity.getString("contact"));
    assertEquals("com.example.call", identity.getString("package"));
    assertEquals("", identity.optString("direction"));
    JSONObject phone =
        CallContext.enrich(
            new JSONObject().put("app", "Phone"),
            System.currentTimeMillis() - 1000,
            System.currentTimeMillis());
    assertEquals(
        "An unrelated VoIP call must not rename an existing phone recording",
        "Phone",
        phone.getString("app"));
    assertFalse(phone.has("contact"));
    CallContext.removed(call.getKey());
    Notification message =
        new Notification.Builder(ScopeApp.app, "events")
            .setSmallIcon(R.drawable.ic_scope)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle("Private message text")
            .build();
    CallContext.notification(
        new android.service.notification.StatusBarNotification(
            "com.example.call",
            "com.example.call",
            902,
            null,
            12345,
            1,
            0,
            message,
            android.os.Process.myUserHandle(),
            System.currentTimeMillis()));
    assertEquals(0, CallContext.snapshot().length());
    CallContext.clear();
  }

  public void testCompletedPhoneCallNamingUsesOnlyAnOverlappingCallLogEntry() throws Exception {
    ScopeApp.prefs().edit().putBoolean("autoNaming", true).commit();
    assertTrue(
        "Disposable emulator needs call-log permission for this fixture",
        CallContext.allowed(android.Manifest.permission.READ_CALL_LOG));
    CallContext.clear();
    CallContext.phone(0);
    long date = System.currentTimeMillis() - 10000;
    android.net.Uri row = null;
    try {
      android.content.ContentValues values = new android.content.ContentValues();
      values.put("number", "15550102030");
      values.put("name", "FixtureCaller");
      values.put("date", date);
      values.put("type", 1);
      values.put("duration", 15);
      getInstrumentation()
          .getUiAutomation()
          .adoptShellPermissionIdentity("android.permission.WRITE_CALL_LOG");
      try {
        row =
            ScopeApp.app
                .getContentResolver()
                .insert(android.provider.CallLog.Calls.CONTENT_URI, values);
      } finally {
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
      }
      assertNotNull("Own synthetic call-log row inserted", row);
      JSONObject identity =
          CallContext.enrich(
              new JSONObject().put("app", "Phone").put("phoneStarted", date),
              date + 2000,
              System.currentTimeMillis());
      assertEquals("15550102030", identity.getString("number"));
      assertEquals("FixtureCaller", identity.getString("contact"));
      assertEquals("in", identity.getString("direction"));
      JSONObject unrelated =
          CallContext.enrich(
              new JSONObject().put("app", "Phone").put("phoneStarted", date + 60000),
              date + 60000,
              date + 65000);
      assertFalse("Earlier unrelated calls must not label a recording", unrelated.has("number"));
    } finally {
      if (row != null) {
        getInstrumentation()
            .getUiAutomation()
            .adoptShellPermissionIdentity("android.permission.WRITE_CALL_LOG");
        try {
          ScopeApp.app
              .getContentResolver()
              .delete(
                  android.provider.CallLog.Calls.CONTENT_URI,
                  "_id=?",
                  new String[] {Long.toString(android.content.ContentUris.parseId(row))});
        } finally {
          getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        }
      }
    }
  }

  private PlaybackWaveform find(android.view.View v) {
    if (v instanceof PlaybackWaveform) return (PlaybackWaveform) v;
    if (v instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) {
        PlaybackWaveform found = find(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }
}
