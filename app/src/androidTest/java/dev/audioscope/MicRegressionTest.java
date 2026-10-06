package dev.audioscope;

import android.app.*;
import android.content.*;
import android.media.*;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Actual PCM and Sources navigation; run on the owned emulator with mic/notification permission.
 */
@SuppressWarnings("deprecation")
public class MicRegressionTest extends InstrumentationTestCase {
  private Map<String, ?> saved;
  private ICaptureBridge bridge;
  private Activity activity;

  protected void setUp() throws Exception {
    saved = new HashMap<>(ScopeApp.prefs().getAll());
    bridge = ScopeApp.bridge;
    ScopeApp.monitor.stop();
    ScopeApp.prefs()
        .edit()
        .putInt("rate", 48000)
        .putInt("channels", 1)
        .putString("codec", "WAV")
        .putBoolean("mix", true)
        .putBoolean("normalizeMix", true)
        .putBoolean("publicFiles", false)
        .putBoolean("hiddenExpanded", true)
        .putBoolean("phoneMicPreview", true)
        .putString("recordingPreviews", "manual")
        .putBoolean("helperCommunicationMic", true)
        .commit();
  }

  @SuppressWarnings("unchecked")
  protected void tearDown() throws Exception {
    if (CaptureService.active()) CaptureService.instance.stopSession();
    waitFor(() -> !CaptureService.stopping, 12000);
    ScopeApp.monitor.stop();
    ScopeApp.bridge = bridge;
    if (activity != null) activity.finish();
    android.content.SharedPreferences.Editor e = ScopeApp.prefs().edit().clear();
    for (Map.Entry<String, ?> entry : saved.entrySet()) {
      Object v = entry.getValue();
      String k = entry.getKey();
      if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
      else if (v instanceof Integer) e.putInt(k, (Integer) v);
      else if (v instanceof String) e.putString(k, (String) v);
      else if (v instanceof Float) e.putFloat(k, (Float) v);
      else if (v instanceof Long) e.putLong(k, (Long) v);
      else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
    }
    e.commit();
    super.tearDown();
  }

  private void waitFor(java.util.function.BooleanSupplier ready, long ms) throws Exception {
    long until = System.currentTimeMillis() + ms;
    while (!ready.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(50);
    assertTrue("Timed out waiting for capture state", ready.getAsBoolean());
  }

  private Activity foreground(String screen) {
    Instrumentation.ActivityMonitor m =
        getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
    ScopeApp.MAIN.post(
        () ->
            ScopeApp.app.startActivity(
                new Intent(ScopeApp.app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra("screen", screen)));
    Activity a = m.waitForActivityWithTimeout(8000);
    getInstrumentation().removeMonitor(m);
    assertNotNull(a);
    return a;
  }

  private View find(View root, String text) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View v = find(((ViewGroup) root).getChildAt(i), text);
        if (v != null) return v;
      }
    return null;
  }

  private View findPrefix(View root, String text) {
    if (root instanceof TextView && ((TextView) root).getText().toString().startsWith(text))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View v = findPrefix(((ViewGroup) root).getChildAt(i), text);
        if (v != null) return v;
      }
    return null;
  }

  public void testSourcesNavigationCannotStealRunningRegularMicrophone() throws Exception {
    activity = foreground("record");
    AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
    ScopeApp.bridge =
        new ShellBridge(ScopeApp.app) {
          public android.os.ParcelFileDescriptor open(String id, int rate, int channels, int uid) {
            opens.incrementAndGet();
            throw new AssertionError("Preview opened during recording: " + id);
          }

          public synchronized void close(String id) {
            closes.incrementAndGet();
          }
        };
    ScopeApp.app.startForegroundService(
        new Intent(ScopeApp.app, CaptureService.class)
            .setAction("RECORD")
            .putExtra("sources", new String[] {"mic"})
            .putExtra("label", "Mic regression"));
    waitFor(
        () ->
            CaptureService.active()
                && CaptureService.tracks.get("mic") != null
                && CaptureService.tracks.get("mic").frames > 24000,
        6000);
    CaptureService.Track mic = CaptureService.tracks.get("mic");
    long before = mic.frames;
    File folder = CaptureService.session;
    Activity record = activity;
    activity = foreground("sources");
    record.finish();
    List<String> available = new ArrayList<>();
    for (Source source : Source.available()) available.add(source.id);
    ScopeApp.monitor.setWanted(available);
    ScopeApp.monitor.enable();
    ScopeApp.monitor.reconcile();
    ScopeApp.monitor.startBluetooth("communication_input");
    Thread.sleep(1800);
    assertTrue(mic.running);
    assertEquals("", mic.error);
    assertTrue(mic.frames > before + 24000);
    assertEquals(0, opens.get());
    assertEquals(0, closes.get());
    for (Source s : Source.available())
      assertNull("Extra preview: " + s.id, ScopeApp.monitor.get(s.id));
    assertTrue(ScopeApp.monitor.blocked("communication_input").contains("recording"));
    getInstrumentation()
        .runOnMainSync(
            () ->
                ((MainActivity) activity)
                    .onNewIntent(
                        new Intent(ScopeApp.app, MainActivity.class).putExtra("screen", "record")));
    ScopeApp.monitor.stop();
    CaptureService.instance.stopSession();
    waitFor(() -> !CaptureService.stopping, 12000);
    assertTrue(new File(folder, "mix.wav").length() > 44);
    assertTrue(new File(folder, "mic.wav").length() > 44);
    assertEquals(0, closes.get());
    getInstrumentation()
        .runOnMainSync(
            () -> {
              LibraryPanel.Actions actions =
                  new LibraryPanel.Actions() {
                    public void share(File f) {}

                    public void browse(File f) {}

                    public void details(String t, String d) {}
                  };
              LibraryPanel list = new LibraryPanel(activity, actions);
              assertNotNull(find(list.view(), "Mono mix · WAV"));
              LibraryPanel detail = new LibraryPanel(activity, actions, folder);
              assertNotNull(find(detail.view(), "Microphone · WAV"));
              assertNotNull(find(detail.view(), "Mono mix · WAV"));
            });
  }

  public void testAnyPhoneMicIsTheOnlyAutomaticPhonePreview() throws Exception {
    activity = foreground("record");
    ScopeApp.bridge = null;
    ScopeApp.monitor.setWanted(
        Arrays.asList("any_phone_mic", "mic", "communication_input", "unprocessed"));
    ScopeApp.monitor.enable();
    ScopeApp.monitor.reconcile();
    waitFor(
        () ->
            ScopeApp.monitor.get("any_phone_mic") != null
                && ScopeApp.monitor.get("any_phone_mic").frames > 24000,
        6000);
    assertEquals("", ScopeApp.monitor.get("any_phone_mic").error);
    assertNull(ScopeApp.monitor.get("mic"));
    assertNull(ScopeApp.monitor.get("communication_input"));
    assertNull(ScopeApp.monitor.get("unprocessed"));
  }

  public void testLocalPreviewReleaseNeverClosesAShellRecordingSource() throws Exception {
    activity = foreground("record");
    AtomicInteger closed = new AtomicInteger();
    ScopeApp.bridge =
        new ShellBridge(ScopeApp.app) {
          public synchronized void close(String id) {
            closed.incrementAndGet();
          }
        };
    CaptureService.Track preview = new CaptureService.Track(Source.get("any_phone_mic"), null);
    try {
      ScopeApp.IO.execute(preview::run);
      waitFor(() -> preview.frames > 24000 || !preview.error.isEmpty(), 6000);
      assertEquals("", preview.error);
    } finally {
      preview.stop();
      preview.done.await(5, TimeUnit.SECONDS);
    }
    assertEquals(0, closed.get());
  }

  public void testExplicitUnrelatedPreviewResumesWhileRecordingMicStaysProtected()
      throws Exception {
    activity = foreground("record");
    java.util.concurrent.atomic.AtomicBoolean pumping =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    ScopeApp.bridge =
        new ShellBridge(ScopeApp.app) {
          public android.os.ParcelFileDescriptor open(String id, int rate, int channels, int uid) {
            assertEquals("media", id);
            try {
              android.os.ParcelFileDescriptor[] pipe =
                  android.os.ParcelFileDescriptor.createReliablePipe();
              new Thread(
                      () -> {
                        try (DataOutputStream out =
                            new DataOutputStream(
                                new android.os.ParcelFileDescriptor.AutoCloseOutputStream(
                                    pipe[1]))) {
                          out.writeInt(0x41534350);
                          out.writeInt(rate);
                          out.writeInt(channels);
                          out.flush();
                          byte[] data = new byte[rate * channels * 2 / 50];
                          for (int i = 0; i < data.length; i += 2) {
                            data[i] = (byte) 1200;
                            data[i + 1] = (byte) (1200 >>> 8);
                          }
                          long frame = 0;
                          while (pumping.get()) {
                            out.writeLong(System.nanoTime());
                            out.writeLong(frame);
                            out.writeInt(data.length);
                            out.write(data);
                            out.flush();
                            frame += data.length / (channels * 2);
                            Thread.sleep(20);
                          }
                        } catch (Exception ignored) {
                        }
                      })
                  .start();
              return pipe[0];
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }

          public synchronized void close(String id) {
            if (id.equals("media")) pumping.set(false);
          }
        };
    ScopeApp.app.startForegroundService(
        new Intent(ScopeApp.app, CaptureService.class)
            .setAction("RECORD")
            .putExtra("sources", new String[] {"mic"}));
    try {
      waitFor(
          () ->
              CaptureService.active()
                  && CaptureService.tracks.get("mic") != null
                  && CaptureService.tracks.get("mic").frames > 24000,
          6000);
      CaptureService.Track recording = CaptureService.tracks.get("mic");
      long before = recording.frames;
      ScopeApp.monitor.setWanted(Arrays.asList("media", "communication_input"));
      ScopeApp.monitor.enable();
      ScopeApp.monitor.reconcile();
      assertNull(ScopeApp.monitor.get("media"));
      ScopeApp.monitor.startBluetooth("media");
      ScopeApp.monitor.reconcile();
      waitFor(
          () ->
              ScopeApp.monitor.get("media") != null && ScopeApp.monitor.get("media").frames > 24000,
          6000);
      ScopeApp.monitor.startBluetooth("communication_input");
      assertNull(ScopeApp.monitor.get("communication_input"));
      assertTrue(ScopeApp.monitor.blocked("communication_input").contains("microphone"));
      assertTrue(recording.running);
      assertEquals("", recording.error);
      assertTrue(recording.frames > before);
      ScopeApp.prefs().edit().putString("recordingPreviews", "paused").commit();
      ScopeApp.monitor.reconcile();
      assertNull(ScopeApp.monitor.get("media"));
    } finally {
      pumping.set(false);
      ScopeApp.monitor.stop();
    }
  }

  public void testCarrierExportIsDefaultAndOriginalTracksRemainInDetail() throws Exception {
    activity = foreground("record");
    File folder =
        new File(ScopeApp.sessions(), "self-test-carrier-052-" + System.currentTimeMillis());
    assertTrue(folder.mkdir());
    org.json.JSONArray tracks = new org.json.JSONArray();
    for (String id : new String[] {"uplink", "downlink"}) {
      byte[] data = new byte[9600];
      int value = id.equals("uplink") ? 500 : 10000;
      for (int i = 0; i < data.length; i += 2) {
        data[i] = (byte) value;
        data[i + 1] = (byte) (value >>> 8);
      }
      try (WavFile wav = new WavFile(new File(folder, id + ".wav"), 48000, 1)) {
        wav.write(data, data.length);
      }
      tracks.put(
          new org.json.JSONObject()
              .put("id", id)
              .put("title", Source.get(id).title)
              .put("file", id + ".wav")
              .put("sampleRate", 48000)
              .put("channels", 1)
              .put("firstNs", 0)
              .put("lastNs", 100000000)
              .put("codec", "WAV"));
    }
    try (FileWriter out = new FileWriter(new File(folder, "session.json"))) {
      out.write(
          new org.json.JSONObject()
              .put("label", "Carrier mix verification")
              .put("tracks", tracks)
              .put("mix", true)
              .toString());
    }
    Exports.finish(folder);
    assertTrue(new File(folder, "carrier_mix.wav").length() > 44);
    assertFalse(new File(folder, "mix.wav").exists());
    getInstrumentation()
        .runOnMainSync(
            () -> {
              LibraryPanel.Actions actions =
                  new LibraryPanel.Actions() {
                    public void share(File f) {}

                    public void browse(File f) {}

                    public void details(String a, String b) {}
                  };
              assertNotNull(
                  find(
                      new LibraryPanel(activity, actions).view(),
                      "Carrier mix · strong normalization · WAV"));
              View detail = new LibraryPanel(activity, actions, folder).view();
              assertNotNull(find(detail, Source.get("uplink").title + " · WAV"));
              assertNotNull(find(detail, Source.get("downlink").title + " · WAV"));
            });
    assertTrue(SourceLayout.hidden().contains("voice_call"));
  }

  public void testHelperCommunicationContinuesAfterHomeAndReportsActualInput() throws Exception {
    activity = foreground("record");
    waitFor(() -> ScopeApp.bridge != null, 12000);
    assertEquals(4, ScopeApp.bridge.apiVersion());
    ScopeApp.app.startForegroundService(
        new Intent(ScopeApp.app, CaptureService.class)
            .setAction("RECORD")
            .putExtra("sources", new String[] {"communication_input"})
            .putExtra("label", "Helper communication verification"));
    waitFor(
        () ->
            CaptureService.active()
                && CaptureService.tracks.get("communication_input") != null
                && CaptureService.tracks.get("communication_input").frames > 24000,
        8000);
    CaptureService.Track t = CaptureService.tracks.get("communication_input");
    long before = t.frames;
    try (android.os.ParcelFileDescriptor command =
        getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 3")) {
      new FileInputStream(command.getFileDescriptor()).readAllBytes();
    }
    Thread.sleep(1600);
    assertTrue(t.running);
    assertEquals("", t.error);
    assertTrue(t.frames > before + 24000);
    assertEquals("Capture helper", t.captureBackend);
    assertFalse(t.actualPreset.isEmpty());
    assertFalse(t.device.equals("Unknown"));
    Set<String> hidden = SourceLayout.hidden();
    hidden.remove("communication_input");
    ScopeApp.prefs().edit().putInt("sourceView", 0).putStringSet("hiddenSources", hidden).commit();
    Activity prior = activity;
    activity = foreground("sources");
    prior.finish();
    Thread.sleep(700);
    getInstrumentation()
        .runOnMainSync(
            () ->
                assertNotNull(findPrefix(activity.getWindow().getDecorView(), "Actually using:")));
    android.graphics.Bitmap screen = getInstrumentation().getUiAutomation().takeScreenshot();
    try (FileOutputStream out =
        new FileOutputStream(
            new File(ScopeApp.app.getExternalFilesDir(null), "communication052.png"))) {
      assertTrue(screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      screen.recycle();
    }
    getInstrumentation()
        .runOnMainSync(
            () ->
                ((MainActivity) activity)
                    .onNewIntent(
                        new Intent(ScopeApp.app, MainActivity.class).putExtra("screen", "record")));
    ScopeApp.monitor.stop();
  }

  public void testExistingSessionsFollowClockPreferenceAndAllMetadataIsVisible() throws Exception {
    activity = foreground("record");
    File folder = new File(ScopeApp.sessions(), "clock-test-052-" + System.currentTimeMillis());
    assertTrue(folder.mkdir());
    try (WavFile wav = new WavFile(new File(folder, "mic.wav"), 48000, 1)) {
      wav.write(new byte[9600], 9600);
    }
    Calendar calendar = Calendar.getInstance();
    calendar.set(Calendar.HOUR_OF_DAY, 13);
    calendar.set(Calendar.MINUTE, 7);
    calendar.set(Calendar.SECOND, 0);
    long stamp = calendar.getTimeInMillis();
    try (FileWriter out = new FileWriter(new File(folder, "session.json"))) {
      out.write(
          new org.json.JSONObject()
              .put("label", "Clock and metadata verification")
              .put("timestampUnixMs", stamp)
              .put(
                  "extensions",
                  new org.json.JSONObject().put("unknownField", "Unlisted metadata stays visible"))
              .put(
                  "tracks",
                  new org.json.JSONArray()
                      .put(new org.json.JSONObject().put("id", "mic").put("file", "mic.wav")))
              .toString());
    }
    ScopeApp.prefs().edit().putBoolean("clock24", false).commit();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              LibraryPanel.Actions actions =
                  new LibraryPanel.Actions() {
                    public void share(File f) {}

                    public void browse(File f) {}

                    public void details(String a, String b) {}
                  };
              LibraryPanel list = new LibraryPanel(activity, actions);
              assertTrue(TimeDisplay.date(stamp).endsWith("1:07 PM"));
              assertNotNull(find(list.view(), "1 audio track · " + TimeDisplay.date(stamp)));
              View detail = new LibraryPanel(activity, actions, folder).view();
              assertNotNull(find(detail, "Session metadata"));
              assertNotNull(find(detail, "Unlisted metadata stays visible"));
              assertNotNull(
                  find(detail, TimeDisplay.full(stamp) + " · Unix milliseconds " + stamp));
              ScopeApp.prefs().edit().putBoolean("clock24", true).commit();
              list.update();
              assertTrue(TimeDisplay.date(stamp).endsWith("13:07"));
              assertNotNull(find(list.view(), "1 audio track · " + TimeDisplay.date(stamp)));
              assertNotNull(
                  find(
                      new LibraryPanel(activity, actions, folder).view(),
                      TimeDisplay.full(stamp) + " · Unix milliseconds " + stamp));
            });
  }
}
