/* Local-device NSD flow adapted from CallVault / Shizuku. See NOTICE. */
package dev.audioscope;

import android.content.Context;
import android.net.nsd.*;
import java.net.*;
import java.util.*;
import java.util.function.IntConsumer;

/** Resolve only services belonging to this phone, never a neighboring ADB device. */
public final class AdbDiscovery {
  // Android allows only one legacy resolve at a time, including across pairing/connect searches.
  private static final ArrayDeque<Runnable> RESOLVES = new ArrayDeque<>();
  private static boolean globalResolving;
  private final NsdManager manager;
  private final String type;
  private final IntConsumer found;
  private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
  private boolean resolving;
  private volatile boolean closed;
  private final NsdManager.DiscoveryListener listener =
      new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String t) {
          ScopeApp.log("INFO", "Discovering local ADB service " + t);
        }

        public void onDiscoveryStopped(String t) {}

        public void onStartDiscoveryFailed(String t, int e) {
          ScopeApp.log("WARN", "ADB discovery could not start (" + e + ")");
        }

        public void onStopDiscoveryFailed(String t, int e) {}

        public void onServiceLost(NsdServiceInfo s) {}

        public void onServiceFound(NsdServiceInfo info) {
          ScopeApp.log("INFO", "Local ADB service advertised for " + type);
          synchronized (queue) {
            if (closed) return;
            queue.add(info);
          }
          resolveNext();
        }
      };

  public AdbDiscovery(Context c, String type, IntConsumer found) {
    this.manager = c.getSystemService(NsdManager.class);
    this.type = type;
    this.found = found;
  }

  public void start() {
    if (closed) return;
    try {
      manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
    } catch (RuntimeException e) {
      ScopeApp.log("WARN", "ADB discovery: " + e.getClass().getSimpleName());
    }
  }

  private static void scheduleResolve(Runnable work) {
    ScopeApp.MAIN.post(
        () -> {
          RESOLVES.add(work);
          nextResolve();
        });
  }

  private static void nextResolve() {
    if (globalResolving || RESOLVES.isEmpty()) return;
    globalResolving = true;
    RESOLVES.remove().run();
  }

  private static void resolved() {
    ScopeApp.MAIN.post(
        () -> {
          globalResolving = false;
          nextResolve();
        });
  }

  static int findConnection(Context context, long timeoutMs) throws InterruptedException {
    java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger port =
        new java.util.concurrent.atomic.AtomicInteger();
    AdbDiscovery discovery =
        new AdbDiscovery(
            context,
            "_adb-tls-connect._tcp",
            p -> {
              port.set(p);
              ready.countDown();
            });
    ScopeApp.MAIN.post(discovery::start);
    try {
      ready.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    } finally {
      discovery.stop();
    }
    return port.get();
  }

  @SuppressWarnings("deprecation")
  private void resolveNext() {
    synchronized (queue) {
      if (closed || resolving || queue.isEmpty()) return;
      resolving = true;
      NsdServiceInfo info = queue.remove();
      scheduleResolve(
          () -> {
            if (closed) {
              resolved();
              return;
            }
            NsdManager.ResolveListener callback =
                new NsdManager.ResolveListener() {
                  private final java.util.concurrent.atomic.AtomicBoolean done =
                      new java.util.concurrent.atomic.AtomicBoolean();

                  public void onResolveFailed(NsdServiceInfo s, int e) {
                    complete();
                  }

                  public void onServiceResolved(NsdServiceInfo s) {
                    if (done.get()) return;
                    if (!closed && local(s))
                      ScopeApp.MAIN.post(
                          () -> {
                            if (!closed) found.accept(s.getPort());
                          });
                    complete();
                  }

                  private void complete() {
                    if (!done.compareAndSet(false, true)) return;
                    synchronized (queue) {
                      resolving = false;
                    }
                    resolveNext();
                    resolved();
                  }
                };
            try {
              manager.resolveService(info, callback);
            } catch (RuntimeException e) {
              callback.onResolveFailed(info, -1);
            }
            ScopeApp.MAIN.postDelayed(() -> callback.onResolveFailed(info, -2), 10000);
          });
    }
  }

  private boolean local(NsdServiceInfo info) {
    try {
      InetAddress host = info.getHost();
      boolean ours = host != null && host.isLoopbackAddress();
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (!ours && interfaces.hasMoreElements()) {
        Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
        while (addresses.hasMoreElements())
          if (addresses.nextElement().equals(host)) {
            ours = true;
            break;
          }
      }
      if (!ours || info.getPort() < 1) return false;
      try (ServerSocket socket = new ServerSocket()) {
        socket.bind(new InetSocketAddress("127.0.0.1", info.getPort()));
        return false;
      } catch (BindException inUse) {
        return true;
      }
    } catch (Exception e) {
      ScopeApp.log("WARN", "ADB local-service check: " + e.getClass().getSimpleName());
      return false;
    }
  }

  public void stop() {
    closed = true;
    try {
      manager.stopServiceDiscovery(listener);
    } catch (Exception ignored) {
    }
    synchronized (queue) {
      queue.clear();
    }
  }
}
