/* Identity persistence and detached launch adapted from CallVault.
 * Copyright (C) 2026 The CallVault Authors. GPL-3.0-or-later + LICENSE Section 7. */
package dev.audioscope;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.*;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.x509.X509V3CertificateGenerator;

public final class EmbeddedAdb extends AbsAdbConnectionManager {
  private final PrivateKey key;
  private final Certificate cert;
  private static EmbeddedAdb instance;
  private static final ScheduledExecutorService TIMER =
      Executors.newSingleThreadScheduledExecutor();

  @SuppressWarnings("deprecation")
  private EmbeddedAdb(Context c) throws Exception {
    setApi(Build.VERSION.SDK_INT);
    setTimeout(12, TimeUnit.SECONDS);
    File k = new File(c.getFilesDir(), "adb-private.der"),
        crt = new File(c.getFilesDir(), "adb-cert.der");
    if (k.exists() && crt.exists()) {
      key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(k)));
      try (InputStream in = new FileInputStream(crt)) {
        cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
      }
    } else {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      KeyPair pair = gen.generateKeyPair();
      key = pair.getPrivate();
      X509V3CertificateGenerator cg = new X509V3CertificateGenerator();
      cg.setSerialNumber(new BigInteger(120, new SecureRandom()));
      X509Name name = new X509Name("CN=AudioScope");
      cg.setIssuerDN(name);
      cg.setSubjectDN(name);
      cg.setNotBefore(new Date(System.currentTimeMillis() - 86400000));
      cg.setNotAfter(new Date(System.currentTimeMillis() + 10L * 365 * 86400000));
      cg.setPublicKey(pair.getPublic());
      cg.setSignatureAlgorithm("SHA256withRSA");
      cert = cg.generate(key);
      write(k, key.getEncoded());
      write(crt, cert.getEncoded());
    }
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

  private static byte[] read(File f) throws IOException {
    try (InputStream in = new FileInputStream(f)) {
      return in.readAllBytes();
    }
  }

  private static void write(File f, byte[] b) throws IOException {
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(b);
      out.getFD().sync();
    }
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
                "Use Connect paired phone to discover the current connection port, or pair"
                    + " again.\n\n"
                    + error,
                "settings");
          }
        });
  }

  public static synchronized void launchBlocking(int port) throws Exception {
    if (CaptureService.active())
      throw new IllegalStateException("Stop recording before restarting the helper");
    ICaptureBridge old = ScopeApp.bridge;
    ScopeApp.bridge = null;
    if (old != null)
      try {
        old.shutdown();
      } catch (Exception ignored) {
      }
    EmbeddedAdb adb = get();
    try {
      adb.disconnect();
    } catch (Exception ignored) {
    }
    if (!adb.connect("127.0.0.1", port))
      throw new IOException("ADB connection refused; check port and pairing");
    String path = ScopeApp.app.getApplicationInfo().sourceDir;
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
