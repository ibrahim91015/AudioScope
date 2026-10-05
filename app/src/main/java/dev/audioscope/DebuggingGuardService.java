package dev.audioscope;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import org.json.JSONObject;

/** Optional guard only; does not start sources, re-arm calls, or revive unavailable privilege. */
public final class DebuggingGuardService extends Service {
  public static volatile boolean running;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean checking, warned;
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
    getContentResolver()
        .registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer);
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
    startForeground(81, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    running = true;
    handler.removeCallbacks(poll);
    handler.post(poll);
    return START_NOT_STICKY;
  }

  private void check() {
    if (checking || !running || CaptureService.active() || CaptureService.stopping) return;
    if (!DebuggingSettings.embedded()) {
      stopSelf();
      return;
    }
    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return;
    checking = true;
    ScopeApp.IO.execute(
        () -> {
          try {
            ICaptureBridge bridge = ScopeApp.bridge;
            if (bridge == null)
              throw new IllegalStateException("Reconnect the Embedded ADB helper first");
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

  public void onDestroy() {
    running = false;
    handler.removeCallbacksAndMessages(null);
    getContentResolver().unregisterContentObserver(observer);
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
