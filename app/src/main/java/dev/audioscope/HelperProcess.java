package dev.audioscope;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Best effort removal from adbd's kill group; the actual outcome is retained for diagnostics. */
final class HelperProcess {
  // Root membership approach used by Shizuku/Shevery's starter (see NOTICE).
  static String detach() {
    int uid = android.os.Process.myUid();
    if (uid != 2000 && uid != 0) return "Not a shell process";
    int pid = android.os.Process.myPid();
    String result = "Cgroup move unavailable; OEM may stop the helper with adbd";
    for (String root :
        new String[] {"/acct", "/dev/cg2_bpf", "/sys/fs/cgroup", "/dev/memcg/apps"}) {
      File file = new File(root, "cgroup.procs");
      if (!file.exists()) continue;
      try (FileOutputStream out = new FileOutputStream(file, true)) {
        out.write((pid + "\n").getBytes(StandardCharsets.US_ASCII));
        result = "Moved to " + root;
        break;
      } catch (IOException ignored) {
      }
    }
    android.util.Log.i("AudioScopeDaemon", result);
    return result;
  }

  static String groups() {
    try (InputStream in = new FileInputStream("/proc/self/cgroup")) {
      return new String(in.readNBytes(8192), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return e.getClass().getSimpleName();
    }
  }
}
