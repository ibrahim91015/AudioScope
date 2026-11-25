/* Identity persistence and detached launch adapted from CallVault.
 * Copyright (C) 2026 The CallVault Authors. GPL-3.0-or-later + LICENSE Section 7. */
package dev.audioscope;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.*;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;

public final class EmbeddedAdb extends AbsAdbConnectionManager {
  private final PrivateKey key;
  private final Certificate cert;
  private static EmbeddedAdb instance;
  private static final ScheduledExecutorService TIMER =
      Executors.newSingleThreadScheduledExecutor();

  private EmbeddedAdb(Context c) throws Exception {
    setApi(Build.VERSION.SDK_INT);
    setTimeout(8, TimeUnit.SECONDS);
    AdbIdentityStore identity = new AdbIdentityStore(c.getNoBackupFilesDir(), c.getFilesDir());
    key = identity.key;
    cert = identity.cert;
    byte[] fingerprint =
        MessageDigest.getInstance("SHA-256").digest(cert.getPublicKey().getEncoded());
    StringBuilder shortId = new StringBuilder();
    for (int i = 0; i < 8; i++)
      shortId.append(String.format(Locale.US, "%02x", fingerprint[i] & 255));
    ScopeApp.log("INFO", "Saved ADB public identity: " + shortId);
  }

  public PrivateKey getPrivateKey() {
    return key;
  }

  public Certificate getCertificate() {
    return cert;
  }

  public String getDeviceName() {
    return "AudioScope";
  }

  public static synchronized EmbeddedAdb get() throws Exception {
    if (instance == null) instance = new EmbeddedAdb(ScopeApp.app);
    return instance;
  }

  /** libadb stream-open otherwise has no timeout. Interrupt only the opening thread. */
  private AdbStream boundedOpen(String destination) throws Exception {
    Thread caller = Thread.currentThread();
    ScheduledFuture<?> f = TIMER.schedule(caller::interrupt, 6, TimeUnit.SECONDS);
    try {
      return openStream(destination);
    } finally {
      f.cancel(false);
      Thread.interrupted();
    }
  }

  public static void pairDevice(int port, String code) {
    ScopeApp.IO.execute(
        () -> {
          try {
            if (port < 1 || port > 65535 || !code.matches("[0-9]{6}"))
              throw new IllegalArgumentException(
                  "Use the pairing port and six-digit code shown by Android");
            boolean ok = get().pair("127.0.0.1", port, code);
            if (ok) paired();
            ScopeApp.log(
                ok ? "INFO" : "ERROR",
                ok
                    ? "ADB paired; enter the connection port from the Wireless debugging main page"
                    : "Pairing rejected");
          } catch (Throwable e) {
            ScopeApp.log("ERROR", "ADB pairing: " + ShellBridge.root(e));
          }
        });
  }

  static void paired() {
    ScopeApp.prefs()
        .edit()
        .putBoolean("adbPaired", true)
        .putInt("adbRefusals", 0)
        .putString("helperTransport", "embedded")
        .commit();
  }

  public static void reconnectBlocking() throws Exception {
    if (ScopeApp.bridge != null && ScopeApp.bridge.asBinder().isBinderAlive()) return;
    if (reattachExisting()) return;
    LinkedHashSet<Integer> ports = new LinkedHashSet<>();
    if (DebuggingSettings.offlineReady()) ports.add(DebuggingSettings.port());
    try {
      ports.add(Integer.parseInt(ScopeApp.prefs().getString("adbPort", "0")));
    } catch (NumberFormatException ignored) {
    }
    Exception last = null;
    for (int port : ports) {
      if (!listening(port)) continue;
      try {
        launchBlocking(port);
        return;
      } catch (Exception e) {
        last = e;
      }
    }
    int found = AdbDiscovery.findConnection(ScopeApp.app, 12000);
    if (found > 0 && !ports.contains(found)) {
      try {
        launchBlocking(found);
        return;
      } catch (Exception e) {
        last = e;
      }
    }
    if (last != null) throw last;
    throw new IOException("No local ADB connection port found; pairing identity is unchanged");
  }

  static boolean reattachExisting() {
    return HelperRendezvous.request(ScopeApp.app.getApplicationInfo().uid)
        && ScopeApp.bridge != null
        && ScopeApp.bridge.asBinder().isBinderAlive();
  }

  private static boolean listening(int port) {
    if (port < 1 || port > 65535) return false;
    try (java.net.Socket socket = new java.net.Socket()) {
      socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public static void launch(int port) {
    ScopeApp.IO.execute(
        () -> {
          try {
            launchBlocking(port);
          } catch (Throwable e) {
            String error = ShellBridge.root(e);
            ScopeApp.log("ERROR", "ADB launch: " + error);
            Notices.error(
                "ADB connection failed",
                "The saved pairing was kept. Connect paired phone retries the current port. "
                    + "Re-pair only if Android actually rejected the key.\n\n"
                    + error,
                "settings");
          }
        });
  }

  public static synchronized void launchBlocking(int port) throws Exception {
    ICaptureBridge old = ScopeApp.bridge;
    if (old != null && old.asBinder().isBinderAlive()) return;
    if (reattachExisting()) return;
    ScopeApp.bridge = null;
    EmbeddedAdb adb = get();
    try {
      adb.disconnect();
    } catch (Exception ignored) {
    }
    try {
      if (!adb.connect("127.0.0.1", port))
        throw new IOException("ADB transport refused the connection; saved identity was kept");
      ScopeApp.prefs()
          .edit()
          .putBoolean("adbPaired", true)
          .putInt("adbRefusals", 0)
          .putString("adbPort", String.valueOf(port))
          .commit();
    } catch (Exception e) {
      if (ReconnectPolicy.authenticationRejected(e)) {
        int refusals = ReconnectPolicy.refusals(ScopeApp.prefs().getInt("adbRefusals", 0), true);
        ScopeApp.prefs().edit().putInt("adbRefusals", refusals).apply();
        ScopeApp.log("WARN", "ADB authentication rejected (" + refusals + "/3); identity retained");
      }
      throw e;
    }
    String path = ScopeApp.app.getApplicationInfo().sourceDir;
    if (ScopeApp.bridge != null && ScopeApp.bridge.asBinder().isBinderAlive()) return;
    // The ADB daemon can reach the local socket even on ROMs that deny app-to-shell sockets.
    if (requestViaAdb(adb)) return;
    if (!path.matches("/[a-zA-Z0-9_./=+~-]+")) throw new SecurityException("Unexpected APK path");
    String command =
        "setsid sh -c 'CLASSPATH="
            + path
            + " exec app_process / dev.audioscope.ShellBridge "
            + ScopeApp.app.getApplicationInfo().uid
            + "' >/dev/null 2>&1 </dev/null & sleep 3";
    try (AdbStream stream = adb.boundedOpen("shell:" + command)) {
      InputStream in = stream.openInputStream();
      while (in.read() != -1) {}
    }
    ScopeApp.log("INFO", "Detached daemon launch sent • waiting for Binder");
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (ScopeApp.bridge == null && System.nanoTime() < deadline) Thread.sleep(100);
    if (ScopeApp.bridge == null)
      throw new IOException("No daemon Binder received; inspect logcat AudioScopeDaemon");
    ScopeApp.log("INFO", "Offline daemon ready; Wi-Fi is no longer needed for recording");
    HelperConnectionService.start(ScopeApp.app);
  }

  private static boolean requestViaAdb(EmbeddedAdb adb) {
    Thread caller = Thread.currentThread();
    ScheduledFuture<?> timeout = TIMER.schedule(caller::interrupt, 4, TimeUnit.SECONDS);
    try (AdbStream existing =
        adb.openStream(
            "localabstract:dev.audioscope.helper.v6." + ScopeApp.app.getApplicationInfo().uid)) {
      existing.openOutputStream().write(1);
      return existing.openInputStream().read() == 1
          && ScopeApp.bridge != null
          && ScopeApp.bridge.asBinder().isBinderAlive();
    } catch (Exception absent) {
      ScopeApp.log("INFO", "No reusable helper rendezvous; starting one daemon");
      return false;
    } finally {
      timeout.cancel(false);
      Thread.interrupted();
    }
  }

  public static void enableOfflineRestart() {
    ScopeApp.IO.execute(
        () -> {
          try {
            if (CaptureService.active())
              throw new IllegalStateException("Stop recording before changing ADB transport");
            if (!DebuggingSettings.embedded())
              throw new IllegalStateException("Use Embedded ADB for this option");
            DebuggingSettings.refresh();
            if (!DebuggingSettings.snapshot.optString("usb").equals("1"))
              throw new IOException(
                  "Turn USB debugging on and verify it first, then enable Wi-Fi-free helper"
                      + " restart");
            EmbeddedAdb adb = get();
            if (!adb.isConnected())
              throw new IOException("Connect using the Wireless debugging port first");
            try {
              int restartPort =
                  ScopeApp.prefs().getInt("offlinePort", 47000 + new SecureRandom().nextInt(12000));
              ScopeApp.prefs().edit().putInt("offlinePort", restartPort).apply();
              AdbStream s = adb.boundedOpen("tcpip:" + restartPort);
              s.close();
            } catch (Exception ignored) {
            }
            Thread.sleep(2500);
            try {
              adb.disconnect();
            } catch (Exception ignored) {
            }
            if (!adb.connect("127.0.0.1", DebuggingSettings.port()))
              throw new IOException("Offline ADB listener did not become reachable");
            launchBlocking(DebuggingSettings.port());
            ScopeApp.prefs()
                .edit()
                .putBoolean("offlineRestart", true)
                .putInt("offlineBoot", DebuggingSettings.boot())
                .apply();
            ScopeApp.log("INFO", "Wi-Fi-free restart endpoint verified for this boot");
            Notices.event(
                "Wi-Fi-free helper restart ready",
                "The authorized endpoint was reached and AudioScope's helper restarted. Keep USB"
                    + " debugging enabled. After reboot, join Wi-Fi and enable this again.",
                "settings");
          } catch (Throwable e) {
            ScopeApp.prefs().edit().putBoolean("offlineRestart", false).apply();
            ScopeApp.log("ERROR", "Offline restart: " + ShellBridge.root(e));
            Notices.error(
                "Wi-Fi-free restart was not enabled",
                "Connect Embedded ADB over Wireless debugging, enable USB debugging, then retry."
                    + " Android may have restarted other debugging services.\n\n"
                    + ShellBridge.root(e),
                "settings");
          }
        });
  }

  public static void disableOfflineRestart() {
    ScopeApp.IO.execute(
        () -> {
          try {
            if (CaptureService.active()) throw new IllegalStateException("Stop recording first");
            if (!DebuggingSettings.embedded())
              throw new IllegalStateException("Use your helper manager's settings");
            EmbeddedAdb adb = get();
            if (!adb.isConnected() && !adb.connect("127.0.0.1", DebuggingSettings.port()))
              throw new IOException("Reconnect the debugging endpoint before requesting shutdown");
            try {
              AdbStream s = adb.boundedOpen("usb:");
              s.close();
            } catch (Exception ignored) {
            }
            ScopeApp.prefs().edit().putBoolean("offlineRestart", false).apply();
            ScopeApp.log("INFO", "Requested ADB TCP listener shutdown");
            Notices.event(
                "Wi-Fi-free restart disabled",
                "AudioScope requested USB-only ADB and cleared its restart preference. If Android"
                    + " refused transport changes, turn debugging off and on in Developer options"
                    + " to close the listener. Reconnect Wireless debugging when needed.",
                "settings");
          } catch (Throwable e) {
            ScopeApp.log("ERROR", ShellBridge.root(e));
            Notices.error(
                "Restart listener shutdown needs attention",
                "The shutdown request could not be confirmed. Reconnect, or turn Android debugging"
                    + " off and on in Developer options to close the TCP listener.\n\n"
                    + ShellBridge.root(e),
                "settings");
          }
        });
  }
}
