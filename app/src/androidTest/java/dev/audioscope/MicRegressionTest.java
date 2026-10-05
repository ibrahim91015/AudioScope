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
            CaptureService.tracks.get("mic") != null
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
}
