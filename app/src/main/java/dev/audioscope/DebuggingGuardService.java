package dev.audioscope;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import org.json.JSONObject;

/** Opt-in debugging repair. Never opens audio, cycles a live helper or repairs during a call. */
public final class DebuggingGuardService extends Service {
  public static volatile boolean running;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean checking, warned;
  private boolean observing;
  private final ContentObserver observer =
      new ContentObserver(handler) {
        public void onChange(boolean self) {
          check();
        }
      };
  private final Runnable poll =
      new Runnable() {
        public void run() {
          check();
          handler.postDelayed(this, 30000);
        }
      };

  public IBinder onBind(Intent i) {
    return null;
  }

  public void onCreate() {
    super.onCreate();
    Notices.channels(this);
    try {
      getContentResolver()
          .registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer);
      observing = true;
    } catch (SecurityException ignored) {
      ScopeApp.log("INFO", "Debugging guard uses polling; Android hides the setting observer");
    }
  }

  public int onStartCommand(Intent i, int flags, int id) {
    if ((i != null && "STOP".equals(i.getAction()))
        || !DebuggingSettings.embedded()
        || !ScopeApp.prefs().getBoolean("enforceWireless", false)) {
      ScopeApp.prefs().edit().putBoolean("enforceWireless", false).apply();
      stopSelf();
      return START_NOT_STICKY;
    }
    PendingIntent stop =
        PendingIntent.getService(
            this,
            82,
            new Intent(this, DebuggingGuardService.class).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE);
    Notification n =
        new Notification.Builder(this, "reliability")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Wireless debugging guard")
            .setContentText("Opt-in guard · no microphones are opened")
            .setContentIntent(Notices.open(this, "settings", null, null, 81))
            .addAction(new Notification.Action.Builder(null, "Disable guard", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    if (Build.VERSION.SDK_INT >= 34)
      startForeground(81, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else startForeground(81, n);
    running = true;
    handler.removeCallbacks(poll);
    handler.post(poll);
    return START_STICKY;
  }

  private void check() {
    if (checking || !running || CaptureService.active() || CaptureService.stopping) return;
    if (!DebuggingSettings.embedded()) {
      stopSelf();
      return;
    }
    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    boolean wifi = false;
    for (Network network : cm.getAllNetworks()) {
      NetworkCapabilities caps = cm.getNetworkCapabilities(network);
      if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
    }
    if (!wifi) return;
    checking = true;
    ScopeApp.IO.execute(
        () -> {
          try {
            ICaptureBridge bridge = ScopeApp.bridge;
            if (bridge == null) {
              repairStoppedAdbd();
              return;
            }
            JSONObject state = new JSONObject(bridge.systemSetup("READ", ""));
            if (state.optString("wireless").equals("0")) {
              if (CaptureService.active()
                  || !running
                  || !ScopeApp.prefs().getBoolean("enforceWireless", false)) return;
              JSONObject after = new JSONObject(bridge.systemSetup("WIRELESS", "1"));
              if (!after.optString("wireless").equals("1"))
                throw new IllegalStateException("Android did not confirm Wireless debugging on");
              Notices.event(
                  "Wireless debugging restored",
                  "Your enabled guard restored Wireless debugging on Wi-Fi. Disable the guard to"
                      + " leave it switched off.",
                  "settings");
            }
            warned = false;
          } catch (Exception e) {
            if (!warned) {
              warned = true;
              Notices.error(
                  "Debugging guard needs setup",
                  "The guard cannot restore settings without a reachable privileged helper. Open"
                      + " Connect capture helper. It does not bypass Android's Wi-Fi or reboot"
                      + " requirements.\n\n"
                      + ShellBridge.root(e),
                  "settings");
            }
          } finally {
            checking = false;
          }
        });
  }

  private boolean callActive() {
    int mode = getSystemService(android.media.AudioManager.class).getMode();
    return CallContext.phoneState != 0
        || mode == android.media.AudioManager.MODE_IN_CALL
        || mode == android.media.AudioManager.MODE_IN_COMMUNICATION;
  }

  private void repairStoppedAdbd() throws Exception {
    boolean granted =
        checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    String adbd;
    java.lang.Process process = new ProcessBuilder("getprop", "init.svc.adbd").start();
    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      return;
    }
    try (java.io.InputStream in = process.getInputStream()) {
      adbd = new String(in.readNBytes(256), java.nio.charset.StandardCharsets.UTF_8).trim();
    }
    long now = System.currentTimeMillis();
    long previous = ScopeApp.prefs().getLong("lastAdbdRepair", 0);
    boolean manager = rikka.shizuku.Shizuku.pingBinder();
    boolean allowed =
        AdbdRepairPolicy.allowed(
            running && ScopeApp.prefs().getBoolean("enforceWireless", false),
            DebuggingSettings.embedded(),
            true,
            CaptureService.active() || CaptureService.stopping,
            callActive(),
            ScopeApp.bridge != null,
            manager,
            granted,
            adbd,
            now - previous);
    if (!allowed) return;
    ScopeApp.prefs().edit().putLong("lastAdbdRepair", now).apply();
    ScopeApp.log(
        "WARN",
        "Opt-in guard: adbd confirmed stopped; cycling Wireless debugging (10 minute cooldown)");
    // The opt-in explicitly allows restoring the switch. Unknown/adbd-running states never get
    // cycled.
    Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
    Thread.sleep(800);
    // Recheck after the first write: a call or helper can appear while Android processes the
    // change.
    if (!running || !ScopeApp.prefs().getBoolean("enforceWireless", false)) return;
    Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
    Notices.event(
        "Debugging service repair requested",
        "Your enabled guard found Android's debugging service stopped and requested a restart. The"
            + " saved pairing is unchanged. The connection notification confirms when a helper"
            + " returns.",
        "settings");
    ScopeApp.MAIN.post(() -> HelperConnectionService.start(this));
  }

  public void onDestroy() {
    running = false;
    handler.removeCallbacksAndMessages(null);
    if (observing) getContentResolver().unregisterContentObserver(observer);
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
