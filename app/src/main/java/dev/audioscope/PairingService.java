/* Inline notification pairing flow adapted from CallVault / Shizuku. See NOTICE. */
package dev.audioscope;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.os.*;
import android.provider.Settings;

public final class PairingService extends Service {
  public static final int ID = 61;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private AdbDiscovery pairing, connection;
  private ContentObserver observer;
  private int pairPort, connectPort;
  private boolean busy, paired, started, connectOnly, cancelled;

  public IBinder onBind(Intent i) {
    return null;
  }

  public void onCreate() {
    super.onCreate();
    Notices.channels(this);
  }

  public int onStartCommand(Intent i, int flags, int startId) {
    if (i == null) return START_NOT_STICKY;
    String action = i.getAction();
    if ("CANCEL".equals(action)) {
      cancelled = true;
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }
    if ("REPLY".equals(action)) {
      Bundle result = RemoteInput.getResultsFromIntent(i);
      String code = result == null ? "" : String.valueOf(result.getCharSequence("code", ""));
      if (!busy) {
        if (!code.matches("[0-9]{6}")) {
          show(
              "Enter the six-digit code",
              "Keep Android's pairing dialog open, then reply with its six digits.",
              true);
        } else doPair(code, i.getIntExtra("port", pairPort));
      }
      return START_NOT_STICKY;
    }
    if (started) return START_NOT_STICKY;
    started = true;
    connectOnly = "CONNECT".equals(action);
    Notification n =
        notification(
            "Waiting for Wireless debugging",
            "Enable Wireless debugging, then choose Pair device with pairing code.",
            false);
    if (Build.VERSION.SDK_INT >= 34)
      startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else startForeground(ID, n);
    observer =
        new ContentObserver(handler) {
          public void onChange(boolean self) {
            if (wirelessEnabled()) discover();
          }
        };
    getContentResolver()
        .registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer);
    if (wirelessEnabled()) discover();
    handler.postDelayed(
        () -> {
          if (!cancelled) {
            Notices.error(
                "Wireless setup timed out",
                "Open Settings → Embedded ADB and retry. Stay in Android's pairing-code dialog and"
                    + " enter the code through AudioScope's notification. Manual ports are"
                    + " available in Advanced setup.",
                "settings");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
          }
        },
        300000);
    return START_NOT_STICKY;
  }

  private boolean wirelessEnabled() {
    return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) != 0;
  }

  private void discover() {
    if (connection != null || pairing != null) return;
    show(
        connectOnly ? "Finding this phone's connection port" : "Open Android's pairing-code dialog",
        connectOnly
            ? "Searching the local Wireless debugging service…"
            : "Tap Pair device with pairing code. Then enter its code in this notification without"
                + " leaving Settings.",
        false);
    if (connectOnly) discoverConnection();
    if (!connectOnly) {
      pairing =
          new AdbDiscovery(
              this,
              "_adb-tls-pairing._tcp",
              port -> {
                if (busy || paired) return;
                pairPort = port;
                show(
                    "Pairing code ready · port found",
                    "Stay in Android Settings. Tap Enter code below and send the six digits shown"
                        + " on your phone.",
                    true);
              });
      pairing.start();
    }
  }

  private void discoverConnection() {
    if (connection != null) return;
    connection =
        new AdbDiscovery(
            this,
            "_adb-tls-connect._tcp",
            port -> {
              connectPort = port;
              ScopeApp.prefs().edit().putString("adbPort", String.valueOf(port)).apply();
              connect();
            });
    connection.start();
  }

  private void doPair(String code, int port) {
    if (port < 1) {
      show(
          "Pairing port not found",
          "Close and reopen Android's pairing-code dialog. AudioScope will discover its new port.",
          false);
      return;
    }
    busy = true;
    show(
        "Pairing this phone",
        "Leave Android's pairing dialog open while the secure handshake finishes.",
        false);
    ScopeApp.IO.execute(
        () -> {
          try {
            if (!EmbeddedAdb.get().pair("127.0.0.1", port, code))
              throw new IllegalStateException("Android rejected the pairing code");
            handler.post(
                () -> {
                  if (cancelled) return;
                  busy = false;
                  paired = true;
                  if (pairing != null) {
                    pairing.stop();
                    pairing = null;
                  }
                  Notices.event(
                      "Wireless debugging paired",
                      "Your code was accepted. AudioScope is starting the offline capture helper.",
                      "settings");
                  show(
                      "Paired · starting helper",
                      "Discovering the connection port. You can return to AudioScope.",
                      false);
                  discoverConnection();
                });
          } catch (Throwable e) {
            handler.post(
                () -> {
                  if (cancelled) return;
                  busy = false;
                  show(
                      "Pairing was not accepted",
                      "Check the current six-digit code. Keep the pairing dialog open and try Enter"
                          + " code again.",
                      true);
                  ScopeApp.log("ERROR", "ADB pairing: " + ShellBridge.root(e));
                });
          }
        });
  }

  private void connect() {
    if (busy || cancelled) return;
    busy = true;
    show("Starting the offline helper", "Connecting to this phone's discovered ADB port…", false);
    ScopeApp.IO.execute(
        () -> {
          try {
            EmbeddedAdb.launchBlocking(connectPort);
            handler.post(
                () -> {
                  if (cancelled) return;
                  Notices.event(
                      "Offline helper connected",
                      "Recording now uses the on-device helper. Wi-Fi is no longer required for"
                          + " capture. Reconnect after reboot.",
                      "settings");
                  stopForeground(STOP_FOREGROUND_REMOVE);
                  stopSelf();
                });
          } catch (Throwable e) {
            handler.post(
                () -> {
                  if (cancelled) return;
                  Notices.error(
                      "Helper could not connect",
                      CaptureProblem.of(ShellBridge.root(e)).details(ShellBridge.root(e))
                          + "\n\n"
                          + "Wireless connection ports can change. Use Connect paired phone to"
                          + " rediscover the port.",
                      "settings");
                  stopForeground(STOP_FOREGROUND_REMOVE);
                  stopSelf();
                });
          }
        });
  }

  private void show(String title, String message, boolean reply) {
    getSystemService(NotificationManager.class).notify(ID, notification(title, message, reply));
  }

  private Notification notification(String title, String message, boolean reply) {
    Notification.Builder b =
        new Notification.Builder(this, "pairing")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .setContentIntent(Notices.open(this, "settings", null, null, ID))
            .setOngoing(true)
            .setGroup("audioscope.active.pairing")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true);
    if (reply) {
      PendingIntent input =
          PendingIntent.getForegroundService(
              this,
              62,
              new Intent(this, PairingService.class).setAction("REPLY").putExtra("port", pairPort),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
      RemoteInput remote =
          new RemoteInput.Builder("code").setLabel("Six-digit pairing code").build();
      b.addAction(
          new Notification.Action.Builder(null, "Enter code", input)
              .addRemoteInput(remote)
              .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
              .build());
    }
    PendingIntent cancel =
        PendingIntent.getService(
            this,
            63,
            new Intent(this, PairingService.class).setAction("CANCEL"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return b.addAction(new Notification.Action.Builder(null, "Cancel", cancel).build()).build();
  }

  public void onDestroy() {
    cancelled = true;
    handler.removeCallbacksAndMessages(null);
    if (pairing != null) pairing.stop();
    if (connection != null) connection.stop();
    if (observer != null) getContentResolver().unregisterContentObserver(observer);
    super.onDestroy();
  }

  public static void openWireless(Activity a) {
    Intent direct =
        new Intent()
            .setClassName(
                "com.android.settings",
                "com.android.settings.Settings$AdbWirelessSettingsActivity");
    try {
      a.startActivity(direct);
      return;
    } catch (ActivityNotFoundException ignored) {
    }
    Intent developer =
        new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
    Bundle args = new Bundle();
    args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
    developer.putExtra(":settings:show_fragment_args", args);
    try {
      a.startActivity(developer);
    } catch (ActivityNotFoundException e) {
      a.startActivity(new Intent(Settings.ACTION_SETTINGS));
    }
  }
}
