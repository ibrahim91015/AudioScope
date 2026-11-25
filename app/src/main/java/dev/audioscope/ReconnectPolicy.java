package dev.audioscope;

/** Connection loss is separate from Android revoking the trusted key. */
final class ReconnectPolicy {
  static long delay(int failures) {
    return Math.min(300000L, 5000L << Math.min(6, Math.max(0, failures)));
  }

  static boolean authenticationRejected(Throwable failure) {
    for (Throwable e = failure; e != null; e = e.getCause()) {
      if (e.getClass().getSimpleName().equals("AdbPairingRequiredException")) return true;
      if (e == e.getCause()) break;
    }
    return false;
  }

  static int refusals(int previous, boolean rejected) {
    return rejected ? Math.min(3, previous + 1) : previous;
  }
}
