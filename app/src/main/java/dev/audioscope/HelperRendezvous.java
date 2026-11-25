package dev.audioscope;

import android.net.*;
import java.io.*;

/** Credential-checked, local-only rendezvous; rebinding does not fork another recorder. */
final class HelperRendezvous {
  private static String name(int appUid) {
    return "dev.audioscope.helper.v6." + appUid;
  }

  static LocalServerSocket claim(int appUid) throws IOException {
    return new LocalServerSocket(name(appUid));
  }

  static void serve(LocalServerSocket server, int appUid, Runnable deliver) {
    Thread thread =
        new Thread(
            () -> {
              for (; ; ) {
                try (LocalSocket socket = server.accept()) {
                  int peer = socket.getPeerCredentials().getUid();
                  if (peer != appUid && peer != 2000 && peer != 0) continue;
                  socket.setSoTimeout(2000);
                  if (socket.getInputStream().read() != 1) continue;
                  deliver.run();
                  socket.getOutputStream().write(1);
                } catch (IOException e) {
                  if (Thread.currentThread().isInterrupted()) return;
                } catch (RuntimeException e) {
                  android.util.Log.w("AudioScopeDaemon", "Rendezvous delivery failed", e);
                }
              }
            },
            "helper-rendezvous");
    thread.setDaemon(true);
    thread.start();
  }

  static boolean request(int appUid) {
    try (LocalSocket socket = new LocalSocket()) {
      socket.connect(new LocalSocketAddress(name(appUid)));
      socket.setSoTimeout(2500);
      int peer = socket.getPeerCredentials().getUid();
      if (peer != 2000 && peer != 0) return false;
      socket.getOutputStream().write(1);
      return socket.getInputStream().read() == 1;
    } catch (IOException e) {
      return false;
    }
  }
}
