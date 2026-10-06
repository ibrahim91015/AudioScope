package dev.audioscope;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Display clocks only; elapsed durations and stored identifiers remain independent. */
public final class ClockText {
  public static String date(long time, boolean twentyFour, ZoneId zone, boolean full) {
    String pattern =
        (full ? "MMM d yyyy · " : "MMM d · ")
            + (twentyFour ? "HH:mm" : "h:mm")
            + (full ? ":ss" : "")
            + (twentyFour ? "" : " a");
    return DateTimeFormatter.ofPattern(pattern, Locale.US)
        .format(Instant.ofEpochMilli(time).atZone(zone));
  }

  public static String file(long time, boolean twentyFour, ZoneId zone) {
    return DateTimeFormatter.ofPattern(
            twentyFour ? "yyyy-MM-dd_HH-mm-ss-SSS" : "yyyy-MM-dd_hh-mm-ss-SSS_a", Locale.US)
        .format(Instant.ofEpochMilli(time).atZone(zone));
  }

  public static String log(String raw, boolean twentyFour) {
    try {
      LocalTime time =
          LocalTime.parse(
              raw.substring(0, 12), DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US));
      return time.format(
              DateTimeFormatter.ofPattern(twentyFour ? "HH:mm:ss.SSS" : "h:mm:ss.SSS a", Locale.US))
          + raw.substring(12);
    } catch (Exception e) {
      return raw;
    }
  }
}
