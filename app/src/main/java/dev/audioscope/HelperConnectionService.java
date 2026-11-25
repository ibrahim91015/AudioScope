package dev.audioscope;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;

/** Restores the selected helper without asking for a new pairing code or opening any source. */
public final class HelperConnectionService extends Service {
  public static volatile String status = "Waiting for helper";
  public static volatile boolean running;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean busy;
  private int failures;
  private long nextAttempt;
  private final ConnectivityManager.NetworkCallback networks =
      new ConnectivityManager.NetworkCallback() {
        public void onAvailable(Network n) {
          handler.post(
              () -> {
                nextAttempt = 0;
                check();
              });
        }
      };
  private final BroadcastReceiver awake =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          nextAttempt = 0;
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
    running = true;
    IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
    filter.addAction(Intent.ACTION_SCREEN_ON);
    registerReceiver(awake, filter, Context.RECEIVER_NOT_EXPORTED);
    try {
      getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networks);
    } catch (Exception e) {
      ScopeApp.log("WARN", "Reconnect network observer unavailable");
    }
  }

  public int onStartCommand(Intent i, int flags, int id) {
    if (i != null && "STOP".equals(i.getAction())) {
      ScopeApp.prefs().edit().putBoolean("helperReconnect", false).apply();
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!ScopeApp.prefs().getBoolean("helperReconnect", true)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (Build.VERSION.SDK_INT >= 34)
      startForeground(83, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else startForeground(83, notification());
    if (i != null && "RETRY".equals(i.getAction())) nextAttempt = 0;
    handler.removeCallbacks(poll);
    handler.post(poll);
    return START_STICKY;
  }

  private Notification notification() {
    PendingIntent stop =
        PendingIntent.getService(
            this,
            84,
            new Intent(this, HelperConnectionService.class).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, "reliability")
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("AudioScope helper connection")
        .setContentText(status)
        .setStyle(new Notification.BigTextStyle().bigText(status))
        .setContentIntent(Notices.open(this, "settings", null, null, 83))
        .addAction(new Notification.Action.Builder(null, "Stop reconnecting", stop).build())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build();
  }

  private void state(String text) {
    if (text.equals(status)) return;
    status = text;
    ScopeApp.log("INFO", "Helper connection: " + text);
    getSystemService(NotificationManager.class).notify(83, notification());
  }

  private void check() {
    if (busy || !running) return;
    if (!ScopeApp.prefs().getBoolean("helperReconnect", true)) {
      stopSelf();
      return;
    }
    ICaptureBridge current = ScopeApp.bridge;
    if (current != null && current.asBinder().isBinderAlive()) {
      failures = 0;
      state("Connected · pairing retained · no audio sources opened by reconnection");
      return;
    }
    if (PairingService.running
        || CaptureService.stopping
        || SystemClock.elapsedRealtime() < nextAttempt) return;
    boolean embedded = DebuggingSettings.embedded();
    if (embedded && !ScopeApp.prefs().getBoolean("adbPaired", false)) {
      state("Pair once in Settings → Connect capture helper");
      return;
    }
    busy = true;
    state(
        embedded
            ? "Reconnecting using the saved ADB identity…"
            : "Waiting for authorized Shevery / Shizuku…");
    ScopeApp.IO.execute(
        () -> {
          String problem = null;
          try {
            if (embedded) EmbeddedAdb.reconnectBlocking();
            else {
              ScopeApp.MAIN.post(() -> ScopeApp.app.connectShizuku(false));
              long end = SystemClock.elapsedRealtime() + 14000;
              while (running && ScopeApp.bridge == null && SystemClock.elapsedRealtime() < end)
                Thread.sleep(100);
              if (ScopeApp.bridge == null)
                throw new java.io.IOException(
                    "Start the helper manager; AudioScope authorization is retained");
            }
          } catch (Throwable e) {
            problem = ShellBridge.root(e);
            ScopeApp.log("WARN", "Helper reconnect attempt " + (failures + 1) + ": " + problem);
          }
          final String error = problem;
          handler.post(
              () -> {
                busy = false;
                if (!running) return;
                if (ScopeApp.bridge != null) {
                  failures = 0;
                  nextAttempt = 0;
                  state("Connected · ready");
                } else {
                  nextAttempt = SystemClock.elapsedRealtime() + ReconnectPolicy.delay(failures++);
                  state(
                      embedded
                          ? "Pairing saved · connection unavailable. Join Wi-Fi and enable Wireless"
                              + " debugging; retry is automatic."
                          : "Start Shevery / Shizuku in its manager. AudioScope will reconnect"
                              + " automatically.");
                  if (error != null && ScopeApp.prefs().getInt("adbRefusals", 0) >= 3)
                    state(
                        "Android repeatedly rejected the saved key. Pair again in Settings;"
                            + " recordings are safe.");
                }
              });
        });
  }

  public void onDestroy() {
    running = false;
    handler.removeCallbacksAndMessages(null);
    unregisterReceiver(awake);
    try {
      getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networks);
    } catch (Exception ignored) {
    }
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  static void start(Context c) {
    if (!ScopeApp.prefs().getBoolean("helperReconnect", true)) return;
    if (!ScopeApp.prefs().contains("helperTransport")) return;
    try {
      c.startForegroundService(new Intent(c, HelperConnectionService.class).setAction("RETRY"));
    } catch (RuntimeException e) {
      ScopeApp.log(
          "WARN", "Open AudioScope to resume helper reconnection: " + e.getClass().getSimpleName());
    }
  }
}
