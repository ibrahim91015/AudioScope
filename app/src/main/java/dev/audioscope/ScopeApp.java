package dev.audioscope;

import android.app.Application;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

public class ScopeApp extends Application {
  public static ScopeApp app;
  public static volatile ICaptureBridge bridge;
  public static volatile String backend = "Not connected";
  public static final ExecutorService IO = Executors.newCachedThreadPool();
  private static final ArrayDeque<String> logs = new ArrayDeque<>();
  public static final Handler MAIN = new Handler(Looper.getMainLooper());
  public static final SourceMonitor monitor = new SourceMonitor();
  private boolean requestedShizuku, binding;
  private boolean interactiveShizuku;
  private final IBinder captureOwner = new Binder();
  private IBinder shizukuBridge;
  private int transportSelection;
  private android.telephony.TelephonyCallback callObserver;

  private static final class CallObserver extends android.telephony.TelephonyCallback
      implements android.telephony.TelephonyCallback.CallStateListener {
    public void onCallStateChanged(int state) {
      CallContext.phone(state);
    }
  }

  public void ensureCallObserver() {
    if (callObserver != null || !CallContext.allowed(android.Manifest.permission.READ_PHONE_STATE))
      return;
    try {
      callObserver = new CallObserver();
      getSystemService(android.telephony.TelephonyManager.class)
          .registerTelephonyCallback(getMainExecutor(), callObserver);
    } catch (Exception e) {
      callObserver = null;
    }
  }

  private Shizuku.UserServiceArgs args;
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
          binding = false;
          requestedShizuku = false;
          shizukuBridge = b;
          attach(b, "Shizuku / Shevery");
        }

        public void onServiceDisconnected(ComponentName n) {
          if (bridge != null && bridge.asBinder().equals(shizukuBridge)) {
            bridge = null;
            backend = "Reconnecting";
            log("WARN", "Privileged helper disconnected; authorization retained");
            Notices.error(
                "Capture helper disconnected",
                "The helper connection was lost. Saved pairing and recordings are retained.",
                "settings");
            MAIN.post(() -> HelperConnectionService.start(app));
          }
        }
      };

  public void onCreate() {
    super.onCreate();
    app = this;
    if (!prefs().contains("adbPaired")
        && "embedded".equals(prefs().getString("helperTransport", ""))
        && new File(getFilesDir(), "adb-private.der").isFile()) {
      prefs().edit().putBoolean("adbPaired", true).apply();
    }
    if (!prefs().getBoolean("carrierSplit052", false)) {
      Set<String> previousDefaults = new HashSet<>();
      Set<String> oldCommon =
          new HashSet<>(
              Arrays.asList(
                  "voice_playback",
                  "any_phone_mic",
                  "mic",
                  "voice_call",
                  "uplink",
                  "downlink",
                  "media"));
      for (Source source : Source.ALL)
        if (!oldCommon.contains(source.id)) previousDefaults.add(source.id);
      Set<String> oldHidden = prefs().getStringSet("hiddenSources", null);
      if (oldHidden != null && oldHidden.equals(previousDefaults))
        prefs().edit().remove("hiddenSources").apply();
      SourceLayout.hide("voice_call", true);
      prefs().edit().putBoolean("carrierSplit052", true).apply();
    }
    if (!prefs().getBoolean("micRouting05", false)) {
      android.content.SharedPreferences.Editor e =
          prefs().edit().putBoolean("micRouting05", true).putBoolean("mix", true);
      Set<String> selected =
          new LinkedHashSet<>(
              prefs()
                  .getStringSet(
                      "selected", new LinkedHashSet<>(Arrays.asList("mic", "voice_playback"))));
      if (selected.equals(new HashSet<>(Arrays.asList("mic", "voice_playback")))) {
        selected.remove("mic");
        selected.add("any_phone_mic");
        e.putStringSet("selected", selected);
      }
      e.apply();
    }
    Notices.channels(this);
    Notices.clear("recording");
    Notices.clear("monitoring");
    ensureCallObserver();
    log(
        "INFO",
        "AudioScope "
            + BuildConfig.VERSION_NAME
            + " • "
            + Build.MANUFACTURER
            + " "
            + Build.MODEL
            + " • Android "
            + Build.VERSION.RELEASE);
    android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
    audio.registerAudioDeviceCallback(
        new android.media.AudioDeviceCallback() {
          String previous = BluetoothRouting.signature();

          private void changed() {
            String current = BluetoothRouting.signature();
            if (current.equals(previous)) return;
            previous = current;
            Notices.event(
                current.isEmpty()
                    ? "External microphones disconnected"
                    : "Microphone devices changed",
                current.isEmpty()
                    ? "Disconnected headset, USB and wired inputs disappear from Sources. Named"
                        + " microphone recordings stop if their requested device is lost. Any Phone"
                        + " Mic can follow non-Bluetooth inputs."
                    : BluetoothRouting.description()
                        + "\nNamed Bluetooth, USB and wired inputs appear when Show each connected"
                        + " microphone is enabled. Their previews stay off until you tap Start"
                        + " monitoring.",
                "sources");
          }

          public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] devices) {
            changed();
          }

          public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] devices) {
            changed();
          }
        },
        MAIN);
    Shizuku.addRequestPermissionResultListener(
        (r, g) -> {
          if (g == PackageManager.PERMISSION_GRANTED) bindShizuku();
          else {
            requestedShizuku = false;
            Notices.error(
                "Helper permission declined",
                "Open Shevery / Shizuku → Authorized apps and allow AudioScope, then reconnect.",
                "settings");
          }
        });
    Shizuku.addBinderReceivedListenerSticky(
        () -> {
          if (requestedShizuku) connectShizuku(interactiveShizuku);
        });
  }

  public static void attach(IBinder b, String label) {
    String transport = label.contains("Shizuku") ? "shizuku" : "embedded";
    String selected = prefs().getString("helperTransport", transport);
    if (!transport.equals(selected)) return; // A late delivery must not undo an explicit selection.
    if (bridge != null && bridge.asBinder().equals(b)) return;
    ICaptureBridge incoming = ICaptureBridge.Stub.asInterface(b);
    try {
      if (incoming.apiVersion() != 6) throw new IllegalStateException("Old helper version");
    } catch (Exception e) {
      if (bridge == null) backend = "Reconnect helper after update";
      log("WARN", "Old helper detected; restart it in Settings");
      IO.execute(
          () -> {
            try {
              incoming.shutdown();
            } catch (Exception ignored) {
            }
          });
      return;
    }
    try {
      incoming.registerClient(app.captureOwner);
    } catch (Exception e) {
      log("ERROR", "Helper ownership registration failed: " + ShellBridge.root(e));
      return;
    }
    bridge = incoming;
    backend = label;
    prefs()
        .edit()
        .putString("helperTransport", label.contains("Shizuku") ? "shizuku" : "embedded")
        .apply();
    try {
      b.linkToDeath(
          () -> {
            if (bridge != null && bridge.asBinder().equals(b)) {
              bridge = null;
              backend = "Reconnecting";
              log("ERROR", "Shell daemon died; recording pipes will close");
              MAIN.post(() -> HelperConnectionService.start(app));
              Notices.error(
                  "Capture helper disconnected",
                  "Audio captured so far is retained. AudioScope will try the saved connection"
                      + " again automatically. A disconnected helper does not mean pairing was"
                      + " lost.",
                  "settings");
            }
          },
          0);
    } catch (Exception e) {
      log("ERROR", e.toString());
    }
    if (!label.contains("Shizuku")) EmbeddedAdb.paired();
    MAIN.post(() -> HelperConnectionService.start(app));
    if (CaptureService.armed && !CaptureService.active())
      IO.execute(() -> {
        try {
          incoming.arm("voice_playback", prefs().getInt("rate", 48000),
              prefs().getInt("channels", 1), prefs().getInt("uidFilter", -1));
        } catch (Exception e) { log("WARN", "Reconnected auto-call prearm: " + ShellBridge.root(e)); }
      });
    log("INFO", "Connected via " + label);
    Notices.event(
        "Capture helper connected",
        label + " is ready. Playback and call routes remain subject to this phone's audio policy.",
        "settings");
  }

  public void connectShizuku() {
    connectShizuku(true);
  }

  public void selectTransport(String transport, Runnable ready) {
    if (CaptureService.active() || CaptureService.stopping) {
      Notices.error(
          "Stop recording before switching helpers",
          "The current helper owns the active tracks. Stop and save them before changing"
              + " transport.",
          "settings");
      return;
    }
    if (transport.equals(prefs().getString("helperTransport", ""))) {
      ready.run();
      return;
    }
    int generation = ++transportSelection;
    ICaptureBridge previous = bridge;
    bridge = null;
    backend = "Switching helper";
    prefs().edit().putString("helperTransport", transport).apply();
    requestedShizuku = binding = false;
    IO.execute(
        () -> {
          monitor.stop();
          if (previous != null)
            try {
              previous.shutdown();
            } catch (Exception ignored) {
            }
          MAIN.post(
              () -> {
                if (generation == transportSelection) ready.run();
              });
        });
  }

  public void connectShizuku(boolean interactive) {
    if (bridge != null && bridge.asBinder().isBinderAlive()) return;
    interactiveShizuku = interactive;
    requestedShizuku = true;
    try {
      if (!Shizuku.pingBinder()) {
        requestManagerBinder();
        MAIN.postDelayed(
            () -> {
              if (requestedShizuku && !Shizuku.pingBinder()) {
                requestedShizuku = false;
                if (interactiveShizuku)
                  Notices.error(
                      "Shevery / Shizuku service is not running",
                      "Open the installed manager and start its service using wireless debugging."
                          + " Return to AudioScope, tap Connect, and authorize AudioScope. Embedded"
                          + " ADB is an independent option.",
                      "settings");
              }
            },
            8000);
        return;
      }
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        if (interactiveShizuku) Shizuku.requestPermission(12);
        else requestedShizuku = false;
      } else bindShizuku();
    } catch (Throwable e) {
      binding = false;
      log("ERROR", "Shizuku: " + e);
      Notices.error(
          "Helper connection failed",
          CaptureProblem.of(e.toString()).details(e.toString()),
          "settings");
    }
  }

  // The pinned Shizuku 13.1.5 provider requires its BinderContainer parcel format.
  // Isolated compatibility adapter for managers that miss automatic Binder delivery.
  @android.annotation.SuppressLint("RestrictedApi")
  private void requestManagerBinder() {
    Binder receiver =
        new Binder() {
          protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != 1) return false;
            IBinder b = data.readStrongBinder();
            if (b != null)
              MAIN.post(
                  () -> {
                    try {
                      Bundle delivery = new Bundle();
                      delivery.putParcelable(
                          "moe.shizuku.privileged.api.intent.extra.BINDER",
                          new moe.shizuku.api.BinderContainer(b));
                      getContentResolver()
                          .call(
                              android.net.Uri.parse("content://" + getPackageName() + ".shizuku"),
                              rikka.shizuku.ShizukuProvider.METHOD_SEND_BINDER,
                              null,
                              delivery);
                    } catch (Exception e) {
                      log("ERROR", "Manager Binder delivery: " + e);
                    }
                  });
            return true;
          }
        };
    Bundle bundle = new Bundle();
    bundle.putBinder("binder", receiver);
    for (String pkg : new String[] {"com.hamondev.shevery", "moe.shizuku.privileged.api"}) {
      Intent request =
          new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
              .setPackage(pkg)
              .putExtra("data", bundle);
      sendBroadcast(request);
    }
  }

  private void bindShizuku() {
    if (binding) return;
    binding = true;
    try {
      if (args != null) Shizuku.unbindUserService(args, connection, false);
    } catch (Exception ignored) {
    }
    args =
        new Shizuku.UserServiceArgs(new ComponentName(this, ShellBridge.class))
            .daemon(true)
            .processNameSuffix("capture")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE);
    Shizuku.bindUserService(args, connection);
    MAIN.postDelayed(
        () -> {
          if (binding) {
            binding = false;
            if (interactiveShizuku)
              Notices.error(
                  "Helper did not finish connecting",
                  "Check AudioScope authorization in Shevery / Shizuku. Restart the manager's"
                      + " service and reconnect, or use Embedded ADB.",
                  "settings");
          }
        },
        12000);
  }

  public static File sessions() {
    File d = new File(app.getExternalFilesDir(null), "sessions");
    d.mkdirs();
    return d;
  }

  public static android.content.SharedPreferences prefs() {
    return app.getSharedPreferences("settings", 0);
  }

  public static synchronized void log(String level, String message) {
    String s =
        new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
            + "  "
            + level
            + "  "
            + message;
    logs.add(s);
    while (logs.size() > 1500) logs.remove();
    android.util.Log.i("AudioScope", s);
    if (app != null) {
      File file = new File(app.getFilesDir(), "events.log");
      if (file.length() > 2 * 1024 * 1024) {
        File old = new File(app.getFilesDir(), "events.previous.log");
        old.delete();
        file.renameTo(old);
      }
      try (FileWriter w = new FileWriter(file, true)) {
        w.write(s + "\n");
      } catch (IOException ignored) {
      }
    }
  }

  public static synchronized String logText(String filter) {
    StringBuilder b = new StringBuilder();
    for (String s : logs)
      if (filter.isEmpty() || s.toLowerCase(Locale.US).contains(filter.toLowerCase(Locale.US)))
        b.append(TimeDisplay.log(s)).append('\n');
    return b.toString();
  }

  public static synchronized String diagnosticHistory() {
    StringBuilder text = new StringBuilder();
    for (String name : new String[] {"events.previous.log", "events.log"}) {
      File file = new File(app.getFilesDir(), name);
      if (!file.isFile()) continue;
      text.append("\n").append(name).append("\n");
      ArrayDeque<String> tail = new ArrayDeque<>();
      try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
        String line;
        while ((line = reader.readLine()) != null) {
          tail.addLast(line);
          if (tail.size() > 1500) tail.removeFirst();
        }
        for (String savedLine : tail) text.append(TimeDisplay.log(savedLine)).append('\n');
      } catch (IOException e) {
        text.append("Log unavailable: ").append(e.getClass().getSimpleName());
      }
    }
    return text.toString();
  }
}
