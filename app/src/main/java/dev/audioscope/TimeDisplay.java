package dev.audioscope;

/** App-wide preference, read at display time so old sessions immediately use the new style. */
public final class TimeDisplay {
  public static boolean twentyFour() {
    return ScopeApp.prefs().getBoolean("clock24", false);
  }

  public static String date(long ms) {
    return ClockText.date(ms, twentyFour(), java.time.ZoneId.systemDefault(), false);
  }

  public static String full(long ms) {
    return ClockText.date(ms, twentyFour(), java.time.ZoneId.systemDefault(), true);
  }

  public static String file(long ms) {
    return ClockText.file(ms, twentyFour(), java.time.ZoneId.systemDefault());
  }

  public static String log(String raw) {
    return ClockText.log(raw, twentyFour());
  }
}
