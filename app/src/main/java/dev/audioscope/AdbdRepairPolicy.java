package dev.audioscope;

final class AdbdRepairPolicy {
  static boolean allowed(
      boolean enabled,
      boolean embedded,
      boolean wifi,
      boolean recording,
      boolean call,
      boolean helperAlive,
      boolean managerAlive,
      boolean grant,
      String adbd,
      long sinceRepairMs) {
    return enabled
        && embedded
        && wifi
        && !recording
        && !call
        && !helperAlive
        && !managerAlive
        && grant
        && "stopped".equals(adbd)
        && sinceRepairMs >= 600000;
  }
}
