package dev.audioscope;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class CaptureService extends Service {
  public static volatile CaptureService instance;
  public static final Map<String, Track> tracks = new ConcurrentHashMap<>();
  private static final List<Track> allTracks = new CopyOnWriteArrayList<>();
  public static volatile boolean paused, stopping, recordAllRequested;

  public static boolean allRecording(java.util.Collection<String> ids) {
    java.util.Set<String> recording = new java.util.HashSet<>();
    for (Track t : tracks.values()) if (t.running && t.frames > 0) recording.add(t.source.id);
    return CaptureSelection.all(recordAllRequested, active(), paused, ids, recording);
  }

  public static volatile long startedNs, pausedNs, pauseAt;
  public static volatile File session;
  public static volatile String sessionName = "Ready to capture";
  public static volatile String exportStatus = "";
  public static volatile boolean armed;
  private boolean automatic, autoStarting;
  private int phoneState;
  private final CallStateMachine callLogic = new CallStateMachine();
  private android.telephony.TelephonyCallback phoneCallback;
  private String label = "Recording";
  private MediaProjection projection;
  private PowerManager.WakeLock lock;
  private final Handler timer = new Handler(Looper.getMainLooper());
  private final JSONArray markers = new JSONArray();
  private int fallbackStage;
  private JSONObject exportSettings;
  private JSONObject callIdentity = new JSONObject();
  private long captureWallStart;
  private boolean automaticLabel;

  public static boolean preparingAuto() {
    return instance != null && instance.autoStarting;
  }

  public static boolean active() {
    return instance != null && session != null && !stopping;
  }

  public IBinder onBind(Intent i) {
    return null;
  }

  public void onCreate() {
    super.onCreate();
    instance = this;
    Notices.channels(this);
  }

  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    String action = intent.getAction();
    if ("ARM_AUTO".equals(action)) {
      armed = true;
      ScopeApp.prefs().edit().putBoolean("autoCalls", true).apply();
      startForeground(42, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      registerPhone();
      timer.removeCallbacks(autoPoll);
      timer.post(autoPoll);
      ScopeApp.IO.execute(
          () -> {
            try {
              if (ScopeApp.bridge != null)
                ScopeApp.bridge.arm(
                    "voice_playback",
                    ScopeApp.prefs().getInt("rate", 48000),
                    ScopeApp.prefs().getInt("channels", 1),
                    ScopeApp.prefs().getInt("uidFilter", -1));
            } catch (Exception e) {
              ScopeApp.log("WARN", "Auto-call prearm: " + e);
            }
          });
      Notices.event(
          "Automatic call recording armed",
          "AudioScope watches phone and communication audio state. Wi-Fi calls include VoIP"
              + " playback plus microphone; verify the live signal on your phone.",
          "record");
      return START_NOT_STICKY;
    }
    if ("DISARM_AUTO".equals(action)) {
      armed = false;
      ScopeApp.prefs().edit().putBoolean("autoCalls", false).apply();
      timer.removeCallbacks(autoPoll);
      unregisterPhone();
      if (!active()) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
      }
      Notices.event(
          "Automatic call recording disarmed",
          "Calls will record only when you press Record.",
          "record");
      return START_NOT_STICKY;
    }
    if ("STOP".equals(action)) {
      stopSession();
      return START_NOT_STICKY;
    }
    if ("PAUSE".equals(action)) {
      togglePause();
      return START_NOT_STICKY;
    }
    if ("MARK".equals(action)) {
      mark("Notification bookmark");
      return START_NOT_STICKY;
    }
    if (session != null) return START_NOT_STICKY;
    String[] selected = intent.getStringArrayExtra("sources");
    if (selected == null || selected.length == 0) {
      Notices.error(
          "No sources selected",
          "Choose at least one source with a live signal before recording.",
          "sources");
      if (!armed) stopSelf();
      return START_NOT_STICKY;
    }
    try {
      for (String source : selected) Source.get(source);
      boolean project = intent.hasExtra("projection");
      int type =
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
              | (project ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0);
      startForeground(42, notification(), type);
      if (project) {
        Intent data = intent.getParcelableExtra("projection");
        projection =
            getSystemService(MediaProjectionManager.class)
                .getMediaProjection(Activity.RESULT_OK, data);
        projection.registerCallback(
            new MediaProjection.Callback() {
              public void onStop() {
                ScopeApp.log("WARN", "Android revoked playback-capture consent");
                for (Track t : tracks.values()) if (t.publicPlayback) t.stop();
              }
            },
            timer);
      }
      tracks.clear();
      allTracks.clear();
      while (markers.length() > 0) markers.remove(0);
      markers.put(new JSONObject().put("event", "session_start").put("atMs", 0));
      paused = false;
      stopping = false;
      pausedNs = 0;
      fallbackStage = 0;
      startedNs = System.nanoTime();
      automatic = intent.getBooleanExtra("automatic", false);
      recordAllRequested = intent.getBooleanExtra("recordAll", false);
      label = intent.getStringExtra("label");
      if (label == null || label.isBlank()) label = automatic ? "Call" : "Recording";
      automaticLabel =
          intent.getBooleanExtra("autoLabel", label.equals("Call") || label.equals("Recording"));
      captureWallStart = System.currentTimeMillis();
      registerPhone();
      callIdentity = CallContext.snapshot();
      if (automaticLabel && callIdentity.length() > 0) label = CallContext.label(callIdentity);
      exportSettings = new JSONObject();
      for (String key :
          new String[] {
            "routeLeft",
            "routeRight",
            "stereo",
            "mix",
            "mka",
            "codec",
            "bitrate",
            "publicFiles",
            "publicMetadata"
          }) {
        Object value = ScopeApp.prefs().getAll().get(key);
        if (value != null) exportSettings.put(key, value);
      }
      exportSettings.put("saveTree", ScopeApp.prefs().getString("saveTree", ""));
      exportSettings.put("saveFolderName", StorageFolders.label());
      sessionName = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(new Date());
      session = new File(ScopeApp.sessions(), sessionName);
      if (!session.mkdirs()) throw new IOException("Cannot create session directory");
      if (ScopeApp.prefs().getBoolean("wakelock", true)) {
        lock =
            getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioScope:capture");
        lock.acquire(12 * 60 * 60 * 1000L);
      }
      writeManifest(false);
      for (String s : new LinkedHashSet<>(Arrays.asList(selected))) startTrack(s);
      ScopeApp.log("INFO", "Session started: " + sessionName);
      Notices.event(
          automatic ? "Call capture started" : "Capture started",
          selected.length + " routes requested. Check the live waveforms to confirm audio.",
          "record");
      timer.post(tick);
    } catch (Throwable e) {
      ScopeApp.log("ERROR", "Session startup: " + ShellBridge.root(e));
      Notices.error(
          "Recording could not start",
          CaptureProblem.of(ShellBridge.root(e)).details(ShellBridge.root(e)),
          "sources");
      stopSession();
    }
    return START_NOT_STICKY;
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (!active()) return;
          try {
            writeManifest(false);
            getSystemService(NotificationManager.class).notify(42, notification());
            fallback();
            boolean data = allTracks.stream().anyMatch(t -> t.frames > 0);
            boolean pending = allTracks.stream().anyMatch(t -> t.running || t.done.getCount() != 0);
            if (!data && (!pending || elapsedMs() > 8000)) {
              Notices.error(
                  "No audio recording started",
                  "None of the requested routes produced PCM. No empty session will be kept. Open"
                      + " Sources and tap a failed route for its repair steps; reconnect the helper"
                      + " if needed.",
                  "sources");
              stopSession();
              return;
            }
            if (data && !pending) {
              Notices.error(
                  "All recording sources stopped",
                  "The captured audio is being saved. Open Sources to inspect errors before"
                      + " starting another session.",
                  "sources");
              stopSession();
              return;
            }
            long max = ScopeApp.prefs().getInt("maxMinutes", 0);
            if (max > 0 && elapsedMs() > max * 60000L) {
              Notices.event(
                  "Recording limit reached",
                  "The configured session duration was reached. AudioScope is saving the audio.",
                  "library");
              stopSession();
              return;
            }
            if (ScopeApp.app.getExternalFilesDir(null).getUsableSpace() < 100 * 1024 * 1024L) {
              Notices.error(
                  "Low storage · saving recording",
                  "Less than 100 MiB remains. Free storage before recording again.",
                  "settings");
              stopSession();
              return;
            }
          } catch (Throwable e) {
            ScopeApp.log("WARN", "Checkpoint: " + e);
          }
          timer.postDelayed(this, 1000);
        }
      };

  private void fallback() {
    if (!ScopeApp.prefs().getBoolean("fallback", false) || ScopeApp.bridge == null || paused)
      return;
    int seconds = ScopeApp.prefs().getInt("silenceSeconds", 5);
    if (elapsedMs() < (seconds + fallbackStage * seconds) * 1000L) return;
    Track call = tracks.get("voice_call");
    if (call == null || call.everAudible) return;
    if (fallbackStage == 0) {
      fallbackStage = 1;
      ScopeApp.log(
          "WARN", "VOICE_CALL has no measured signal; trying uplink + downlink alongside it");
      startTrack("uplink");
      startTrack("downlink");
    } else if (fallbackStage == 1) {
      Track u = tracks.get("uplink"), d = tracks.get("downlink");
      if (u != null && u.everAudible && d != null && d.everAudible) return;
      fallbackStage = 2;
      ScopeApp.log("WARN", "Trying communication playback + mic; existing tracks retained");
      startTrack("voice_playback");
      startTrack("mic");
    }
  }

  public synchronized void startTrack(String id) {
    if (session == null || stopping) return;
    Track previous = tracks.get(id);
    if (previous != null && (previous.running || previous.done.getCount() != 0)) return;
    Track t = new Track(Source.get(id), session);
    tracks.put(id, t);
    allTracks.add(t);
    ScopeApp.IO.execute(t::run);
  }

  public static long elapsedMs() {
    if (startedNs == 0) return 0;
    return Math.max(
        0,
        (System.nanoTime() - startedNs - pausedNs - (paused ? System.nanoTime() - pauseAt : 0))
            / 1000000);
  }

  public void togglePause() {
    if (!active()) return;
    if (paused) {
      pausedNs += System.nanoTime() - pauseAt;
      paused = false;
    } else {
      pauseAt = System.nanoTime();
      paused = true;
    }
    ScopeApp.log(
        "INFO", paused ? "Session paused; capture drained, audio discarded" : "Session resumed");
    Notices.event(
        paused ? "Recording paused" : "Recording resumed",
        paused
            ? "Audio during the pause is discarded. Use Resume in the recording notification to"
                + " continue."
            : "Audio is being saved again.",
        "record");
    getSystemService(NotificationManager.class).notify(42, notification());
  }

  public synchronized void mark(String title) {
    if (!active()) return;
    try {
      markers.put(new JSONObject().put("atMs", elapsedMs()).put("label", title));
      ScopeApp.log("INFO", "Bookmark: " + title);
      Notices.event("Bookmark added", title + " at " + formatTime(elapsedMs()), "record");
    } catch (Exception ignored) {
    }
  }

  private Notification notification() {
    PendingIntent open = Notices.open(this, "record", null, null, 42);
    boolean idle = session == null;
    String title =
        idle
            ? (armed ? "Auto call recording armed" : "Preparing capture")
            : stopping
                ? "Saving recording…"
                : paused ? "AudioScope · paused" : "AudioScope · recording";
    String detail =
        idle
            ? "Waiting for a phone or app call. Tap to open controls."
            : tracks.values().stream().filter(t -> t.running && t.frames > 0).count()
                + " sources receiving audio · "
                + formatTime(elapsedMs());
    StringBuilder status = new StringBuilder(detail);
    if (!idle)
      for (Track t : tracks.values())
        status
            .append("\n")
            .append(t.source.title)
            .append(": ")
            .append(t.error.isEmpty() ? t.state : CaptureProblem.of(t.error).title);
    Notification.Builder b =
        new Notification.Builder(this, "capture")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(new Notification.BigTextStyle().bigText(status))
            .setContentIntent(open)
            .setOngoing(true)
            .setGroup("audioscope.active.capture")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true);
    if (idle) {
      if (armed)
        b.addAction(
            new Notification.Action.Builder(null, "Disarm auto", action("DISARM_AUTO", 4)).build());
      return b.build();
    }
    b.addAction(
        new Notification.Action.Builder(null, paused ? "Resume" : "Pause", action("PAUSE", 1))
            .build());
    b.addAction(new Notification.Action.Builder(null, "Bookmark", action("MARK", 2)).build());
    b.addAction(new Notification.Action.Builder(null, "Stop", action("STOP", 3)).build());
    return b.build();
  }

  private PendingIntent action(String a, int id) {
    return PendingIntent.getService(
        this,
        id,
        new Intent(this, CaptureService.class).setAction(a),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  public static String formatTime(long ms) {
    long s = ms / 1000;
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
  }

  public synchronized void stopSession() {
    if (stopping || session == null) {
      if (!armed && session == null) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
      }
      return;
    }
    stopping = true;
    if (inCall()) callLogic.suppress();
    timer.removeCallbacks(tick);
    getSystemService(NotificationManager.class).notify(42, notification());
    ScopeApp.IO.execute(
        () -> {
          File finished = session;
          for (Track t : allTracks) t.stop();
          for (Track t : allTracks)
            try {
              t.done.await(6, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
          try {
            callIdentity =
                CallContext.enrich(callIdentity, captureWallStart, System.currentTimeMillis());
            if ("Phone".equals(callIdentity.optString("app"))
                && callIdentity.optString("number").isEmpty()
                && CallContext.phoneState == 0
                && CallContext.allowed(android.Manifest.permission.READ_CALL_LOG)) {
              for (int attempt = 0;
                  attempt < 3 && callIdentity.optString("number").isEmpty();
                  attempt++) {
                Thread.sleep(350);
                callIdentity =
                    CallContext.enrich(callIdentity, captureWallStart, System.currentTimeMillis());
              }
            }
            if (automaticLabel && callIdentity.length() > 0)
              label = CallContext.label(callIdentity);
            writeManifest(true);
            if (finished != null)
              try (FileWriter w = new FileWriter(new File(finished, "events.log"))) {
                w.write(ScopeApp.logText(""));
              }
          } catch (Throwable e) {
            ScopeApp.log("ERROR", "Finalize: " + e);
          }
          if (projection != null) {
            projection.stop();
            projection = null;
          }
          if (lock != null && lock.isHeld()) lock.release();
          boolean valid = allTracks.stream().anyMatch(t -> t.frames > 0);
          if (finished != null) {
            File[] empty = finished.listFiles((d, n) -> n.endsWith(".wav"));
            if (empty != null)
              for (File f : empty)
                if (f.length() <= 44) {
                  f.delete();
                  new File(finished, f.getName() + ".timestamps.jsonl").delete();
                  new File(finished, f.getName().replace(".wav", ".pcm")).delete();
                }
            if (!valid) {
              File[] files = finished.listFiles();
              if (files != null) for (File f : files) f.delete();
              finished.delete();
            }
          }
          session = null;
          startedNs = 0;
          ScopeApp.log("INFO", "Recording stopped; files finalized");
          if (finished != null && valid) {
            exportStatus = "Preparing saved audio…";
            try {
              Exports.finish(finished);
              int published = PublicRecordings.publish(finished);
              exportStatus =
                  "Saved · " + published + " files in " + PublicRecordings.destination(finished);
              Notices.event(
                  "Recording saved",
                  published > 0
                      ? published
                          + " audio files saved in "
                          + PublicRecordings.destination(finished)
                          + ". Files indexing was requested for Recent. Tap to listen."
                      : "Audio is ready in Sessions. Public file copies are disabled in Settings.",
                  "library");
            } catch (Throwable e) {
              exportStatus = "Public export needs attention";
              ScopeApp.log("ERROR", "Export failed: " + ShellBridge.root(e));
              Notices.error(
                  "Audio saved · export needs attention",
                  "Original audio is safe in Sessions.\n\n" + ShellBridge.root(e),
                  "library");
            }
          }
          timer.post(
              () -> {
                stopping = false;
                automatic = false;
                if (armed) {
                  getSystemService(NotificationManager.class).notify(42, notification());
                } else {
                  stopForeground(STOP_FOREGROUND_REMOVE);
                  stopSelf();
                }
              });
        });
  }

  private synchronized void writeManifest(boolean complete) throws Exception {
    if (session == null) return;
    JSONObject j = new JSONObject();
    j.put("app", "AudioScope");
    j.put("version", BuildConfig.VERSION_NAME);
    j.put("label", label);
    j.put("automaticLabel", automaticLabel);
    j.put("call", callIdentity);
    j.put("timestampUnixMs", captureWallStart);
    j.put(
        "namingTemplate",
        ScopeApp.prefs()
            .getString("namingTemplate", "{date}_{app}_{direction}_{contact}_{source}"));
    j.put("automatic", automatic);
    j.put("device", Build.MANUFACTURER + " " + Build.MODEL);
    j.put("android", Build.VERSION.RELEASE);
    j.put("backend", ScopeApp.backend);
    j.put("session", sessionName);
    j.put("completed", complete);
    j.put("elapsedMs", elapsedMs());
    j.put("clock", "CLOCK_MONOTONIC; PCM16 little-endian; timestamps adjusted for global pauses");
    j.put("markers", markers);
    JSONArray ts = new JSONArray();
    for (Track t : allTracks) ts.put(t.json());
    j.put("tracks", ts);
    if (exportSettings != null) {
      Iterator<String> keys = exportSettings.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        j.put(key, exportSettings.get(key));
      }
    }
    File tmp = new File(session, "session.json.tmp");
    try (FileWriter w = new FileWriter(tmp)) {
      w.write(j.toString(2));
    }
    if (!tmp.renameTo(new File(session, "session.json")))
      throw new IOException("Manifest replace failed");
  }

  private boolean inCall() {
    int mode = getSystemService(AudioManager.class).getMode();
    if (BluetoothRouting.busy()
        && phoneState != android.telephony.TelephonyManager.CALL_STATE_OFFHOOK)
      mode = AudioManager.MODE_NORMAL;
    return mode == AudioManager.MODE_IN_CALL
        || mode == AudioManager.MODE_IN_COMMUNICATION
        || phoneState == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK;
  }

  private final Runnable autoPoll =
      new Runnable() {
        public void run() {
          if (!armed) return;
          CallStateMachine.Action action =
              callLogic.update(
                  inCall(),
                  System.currentTimeMillis(),
                  active(),
                  automatic,
                  stopping || autoStarting);
          if (action == CallStateMachine.Action.START) {
            autoStarting = true;
            ScopeApp.monitor.disable();
            ScopeApp.IO.execute(
                () -> {
                  ScopeApp.monitor.stop();
                  timer.post(
                      () -> {
                        autoStarting = false;
                        if (!armed || !inCall() || active() || stopping) return;
                        String[] candidates =
                            ScopeApp.bridge == null
                                ? new String[] {"mic"}
                                : new String[] {"voice_playback", "mic", "voice_call"};
                        Intent capture =
                            new Intent()
                                .setAction("RECORD")
                                .putExtra("sources", candidates)
                                .putExtra("automatic", true)
                                .putExtra("label", "Call");
                        onStartCommand(capture, 0, 0);
                      });
                });
          } else if (action == CallStateMachine.Action.STOP) stopSession();
          timer.postDelayed(this, 1000);
        }
      };

  private final class PhoneWatcher extends android.telephony.TelephonyCallback
      implements android.telephony.TelephonyCallback.CallStateListener {
    public void onCallStateChanged(int state) {
      phoneState = state;
      CallContext.phone(state);
    }
  }

  private void registerPhone() {
    if (phoneCallback != null
        || checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    try {
      phoneCallback = new PhoneWatcher();
      getSystemService(android.telephony.TelephonyManager.class)
          .registerTelephonyCallback(getMainExecutor(), phoneCallback);
    } catch (Exception e) {
      phoneCallback = null;
      ScopeApp.log("WARN", "Phone state: " + e);
    }
  }

  private void unregisterPhone() {
    if (phoneCallback != null) {
      getSystemService(android.telephony.TelephonyManager.class)
          .unregisterTelephonyCallback(phoneCallback);
      phoneCallback = null;
    }
  }

  public void onDestroy() {
    timer.removeCallbacksAndMessages(null);
    unregisterPhone();
    armed = false;
    if (active()) for (Track t : allTracks) t.stop();
    instance = null;
    super.onDestroy();
  }

  public static final class Chunk {
    final byte[] data;
    final long time, frame;

    Chunk(byte[] d, long t, long f) {
      data = d;
      time = t;
      frame = f;
    }
  }

  public static final class Track {
    public final Source source;
    public volatile String state = "STARTING", error = "", device = "Unknown";
    public volatile boolean running = true, everAudible, publicPlayback;
    public volatile double rms, peak, db = -120, nonzero;
    public volatile long frames, dropped, clipped, silentMs, firstNs, lastNs;
    public volatile int rate, channels;
    public final float[] history = new float[160];
    public volatile int histPos;
    public volatile double gain;
    public volatile boolean muted;
    public final boolean monitor;
    public final String codec;
    private final File folder;
    private final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(150);
    public final CountDownLatch done = new CountDownLatch(1);
    private AudioRecord local;
    private ParcelFileDescriptor pipe;
    private volatile boolean reading = true;
    private WavFile wav;
    private Thread consumer;
    private long lastLive = System.nanoTime();

    Track(Source s, File f) {
      source = s;
      folder = f;
      monitor = f == null;
      codec =
          ScopeApp.prefs().getString("format_" + s.id, ScopeApp.prefs().getString("codec", "WAV"));
      rate =
          s.bluetooth()
              ? ScopeApp.prefs().getInt("bluetoothRate", 16000)
              : ScopeApp.prefs().getInt("rate", 48000);
      channels = s.bluetooth() ? 1 : ScopeApp.prefs().getInt("channels", 1);
      gain = ScopeApp.prefs().getFloat("gain_" + s.id, 1);
      muted = ScopeApp.prefs().getBoolean("mute_" + s.id, false);
    }

    private ICaptureBridge openedBridge;
    private boolean bluetoothLease;

    void run() {
      if (!running) {
        done.countDown();
        return;
      }
      try {
        ICaptureBridge bridge = ScopeApp.bridge;
        openedBridge = bridge;
        if (bridge != null && !source.phoneMic() && !source.bluetooth()) {
          pipe = bridge.open(source.id, rate, channels, ScopeApp.prefs().getInt("uidFilter", -1));
          try (DataInputStream in =
              new DataInputStream(
                  new BufferedInputStream(
                      new ParcelFileDescriptor.AutoCloseInputStream(pipe), 32768))) {
            if (in.readInt() != 0x41534350) throw new IOException("Bad capture stream");
            rate = in.readInt();
            channels = in.readInt();
            beginConsumer();
            while (running) {
              long time = in.readLong(), frame = in.readLong();
              int n = in.readInt();
              if (n <= 0 || n > 1048576 || n % (channels * 2) != 0)
                throw new IOException("Invalid PCM packet length");
              byte[] b = new byte[n];
              in.readFully(b);
              accept(new Chunk(b, time, frame));
            }
          }
        } else {
          int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO,
              min = AudioRecord.getMinBufferSize(rate, mask, 2);
          if (min < 0) throw new IOException("Unsupported sample format");
          AudioRecord.Builder b =
              new AudioRecord.Builder()
                  .setAudioFormat(
                      new AudioFormat.Builder()
                          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                          .setSampleRate(rate)
                          .setChannelMask(mask)
                          .build())
                  .setBufferSizeInBytes(Math.max(min * 4, rate * channels / 5));
          if (source.playback()) {
            if (source.usage != 0 && source.usage != 1 && source.usage != 14)
              throw new SecurityException(
                  "This source needs the shell helper. Connect in Settings.");
            if (monitor || instance == null || instance.projection == null)
              throw new SecurityException("Playback-capture consent required");
            publicPlayback = true;
            AudioPlaybackCaptureConfiguration.Builder config =
                new AudioPlaybackCaptureConfiguration.Builder(instance.projection)
                    .addMatchingUsage(source.usage);
            int uid = ScopeApp.prefs().getInt("uidFilter", -1);
            if (uid >= 0) config.addMatchingUid(uid);
            b.setAudioPlaybackCaptureConfig(config.build());
          } else {
            if (source.privileged())
              throw new SecurityException("Telephony and submix need the shell helper");
            b.setAudioSource(source.input);
          }
          if (ScopeApp.app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
              != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Microphone permission was revoked");
          if (source.bluetooth()) bluetoothLease = BluetoothRouting.acquire(source);
          local = b.build();
          if (source.phoneMic() || source.bluetooth()) {
            if (!local.setPreferredDevice(BluetoothRouting.select(source)))
              throw new IOException("Microphone route rejected by Android");
          }
          if (local.getState() != 1) throw new IOException("AudioRecord initialization failed");
          local.startRecording();
          if (local.getRecordingState() != 3) throw new IOException("AudioRecord not recording");
          rate = local.getSampleRate();
          channels = local.getChannelCount();
          beginConsumer();
          long frame = 0;
          byte[] bytes = new byte[rate * channels * 2 / 50];
          while (running) {
            int n = local.read(bytes, 0, bytes.length);
            if (n < 0) throw new IOException("Read error " + n);
            if (n == 0) continue;
            AudioDeviceInfo route = local.getRoutedDevice();
            if (source.phoneMic() || source.bluetooth()) {
              BluetoothRouting.verify(source, route);
              if (route == null) {
                frame += n / (channels * 2);
                if (frame > rate * 2L)
                  throw new IOException(
                      "Microphone route unavailable; cannot verify the requested device");
                continue;
              }
            }
            if (route != null) device = route.getProductName() + " • type " + route.getType();
            AudioTimestamp ts = new AudioTimestamp();
            long time = System.nanoTime() - n * 1000000000L / (rate * channels * 2);
            if (local.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == 0)
              time = ts.nanoTime + (frame - ts.framePosition) * 1000000000L / rate;
            accept(new Chunk(Arrays.copyOf(bytes, n), time, frame));
            frame += n / (channels * 2);
          }
        }
      } catch (Throwable e) {
        if (running) {
          error = (source.bluetooth() ? "Bluetooth source: " : "") + ShellBridge.root(e);
          state = e instanceof SecurityException ? "BLOCKED" : "FAILED";
          ScopeApp.log("ERROR", source.id + " • " + error);
          if (!monitor) Notices.problem(source, error, false);
        }
      } finally {
        running = false;
        reading = false;
        if (local != null) {
          try {
            local.stop();
          } catch (Exception ignored) {
          }
          local.release();
        }
        try {
          BluetoothRouting.release(bluetoothLease);
        } catch (Exception e) {
          ScopeApp.log("WARN", "Bluetooth route release: " + ShellBridge.root(e));
        }
        if (pipe != null)
          try {
            pipe.close();
          } catch (Exception ignored) {
          }
        if (openedBridge != null)
          try {
            openedBridge.close(source.id);
          } catch (Exception ignored) {
          }
        if (consumer != null)
          try {
            consumer.join(5000);
          } catch (Exception ignored) {
          }
        if (error.isEmpty()) state = "STOPPED";
        if (!monitor)
          ScopeApp.log(
              "INFO",
              source.id + " stopped • " + frames + " frames • " + dropped + " dropped chunks");
        done.countDown();
      }
    }

    private void beginConsumer() throws IOException {
      if (monitor) {
        state = "MONITORING";
        consumer = new Thread(this::consumeMonitor, "meter-" + source.id);
        consumer.start();
        return;
      }
      File path = new File(folder, source.id + ".wav");
      if (path.exists()) {
        int suffix = 2;
        while (new File(folder, source.id + "_" + suffix + ".wav").exists()) suffix++;
        path = new File(folder, source.id + "_" + suffix + ".wav");
      }
      wavName = path.getName();
      wav = new WavFile(path, rate, channels);
      state = "WAITING FOR SIGNAL";
      ScopeApp.log("INFO", source.id + " started • " + rate + " Hz • " + channels + " ch");
      consumer = new Thread(this::consume, "write-" + source.id);
      consumer.start();
    }

    private void measure(Chunk c) {
      PcmStats stats = new PcmStats(c.data, c.data.length);
      rms = stats.rms;
      peak = stats.peak;
      db = stats.db;
      nonzero = stats.nonzero;
      clipped += stats.clipped;
      history[histPos % history.length] = (float) stats.peak;
      histPos++;
      state =
          db > ScopeApp.prefs().getInt("silenceDb", -60)
              ? "MONITORING • SIGNAL"
              : "MONITORING • SILENT";
    }

    private void consumeMonitor() {
      try {
        while (reading || !queue.isEmpty()) {
          Chunk c = queue.poll(100, TimeUnit.MILLISECONDS);
          if (c != null) {
            measure(c);
            frames += c.data.length / (channels * 2);
          }
        }
      } catch (Exception e) {
        error = e.toString();
        state = "FAILED";
        running = false;
      }
    }

    private String wavName = "";

    private void accept(Chunk c) {
      if (!queue.offer(c)) dropped++;
    }

    private void consume() {
      long expected = -1, checkpoint = System.nanoTime();
      byte[] zeros = new byte[8192];
      try (FileWriter stamps = new FileWriter(new File(folder, wavName + ".timestamps.jsonl"));
          OutputStream raw =
              (codec.equals("PCM") || ScopeApp.prefs().getBoolean("raw", false))
                  ? new FileOutputStream(new File(folder, wavName.replace(".wav", ".pcm")))
                  : OutputStream.nullOutputStream()) {
        while (reading || !queue.isEmpty()) {
          Chunk c = queue.poll(100, TimeUnit.MILLISECONDS);
          if (c == null) continue;
          int count = c.data.length / (channels * 2);
          if (paused) {
            expected = c.frame + count;
            continue;
          }
          PcmStats stats = new PcmStats(c.data, c.data.length);
          rms = stats.rms;
          peak = stats.peak;
          db = stats.db;
          nonzero = stats.nonzero;
          clipped += stats.clipped;
          double threshold = ScopeApp.prefs().getInt("silenceDb", -60);
          long now = System.nanoTime();
          if (db > threshold) {
            everAudible = true;
            lastLive = now;
            silentMs = 0;
            state = "LIVE";
          } else {
            silentMs = (now - lastLive) / 1000000;
            state =
                silentMs >= ScopeApp.prefs().getInt("silenceSeconds", 5) * 1000L
                    ? "SILENT"
                    : "LOW SIGNAL";
          }
          history[histPos % history.length] = (float) stats.peak;
          histPos++;
          if (expected >= 0 && c.frame > expected) {
            long gap = (c.frame - expected) * channels * 2;
            while (gap > 0) {
              int n = (int) Math.min(gap, zeros.length);
              wav.write(zeros, n);
              raw.write(zeros, 0, n);
              frames += n / (channels * 2);
              gap -= n;
            }
            dropped++;
          }
          expected = c.frame + count;
          long adjusted = c.time - pausedNs;
          if (firstNs == 0) firstNs = adjusted;
          lastNs = adjusted + count * 1000000000L / rate;
          stamps.write(
              "{\"timeNs\":"
                  + adjusted
                  + ",\"frame\":"
                  + frames
                  + ",\"sourceFrame\":"
                  + c.frame
                  + ",\"count\":"
                  + count
                  + "}\n");
          wav.write(c.data, c.data.length);
          raw.write(c.data);
          frames += count;
          if (now - checkpoint > 5_000_000_000L) {
            wav.checkpoint();
            stamps.flush();
            checkpoint = now;
          }
        }
      } catch (Throwable e) {
        error = ShellBridge.root(e);
        state = "FAILED";
        running = false;
        ScopeApp.log("ERROR", source.id + " writer: " + error);
      } finally {
        try {
          wav.close();
        } catch (Exception e) {
          error = e.toString();
          state = "FAILED";
        }
      }
    }

    public void stop() {
      running = false;
      if (local != null)
        try {
          local.stop();
        } catch (Exception ignored) {
        }
      if (pipe != null)
        try {
          pipe.close();
        } catch (Exception ignored) {
        }
      ICaptureBridge b = openedBridge;
      if (b != null)
        try {
          b.close(source.id);
        } catch (Exception ignored) {
        }
    }

    JSONObject json() throws JSONException {
      JSONObject j = new JSONObject();
      j.put("id", source.id);
      j.put("title", source.title);
      j.put("file", wavName);
      j.put("codec", codec);
      j.put("sampleRate", rate);
      j.put("channels", channels);
      j.put("frames", frames);
      j.put("firstNs", firstNs);
      j.put("lastNs", lastNs);
      j.put("offsetNs", Math.max(0, firstNs - startedNs));
      j.put("state", state);
      j.put("everAudible", everAudible);
      j.put("dropped", dropped);
      j.put("clipped", clipped);
      j.put("error", error);
      j.put("device", device);
      j.put("gain", gain);
      j.put("muted", muted);
      return j;
    }
  }
}
