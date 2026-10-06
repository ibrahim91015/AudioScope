package dev.audioscope;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class ClockTextTest {
  private final ZoneId zone = ZoneOffset.UTC;

  private long time(String hour) {
    return Instant.parse("2026-10-05T" + hour + ":00Z").toEpochMilli();
  }

  @Test
  public void twelveHourHandlesMidnightNoonAndAfternoon() {
    assertEquals("Oct 5 · 12:00 AM", ClockText.date(time("00:00"), false, zone, false));
    assertEquals("Oct 5 · 12:00 PM", ClockText.date(time("12:00"), false, zone, false));
    assertEquals("Oct 5 · 1:07 PM", ClockText.date(time("13:07"), false, zone, false));
  }

  @Test
  public void twentyFourHourAndFullMetadataUseSameMoment() {
    assertEquals("Oct 5 · 13:07", ClockText.date(time("13:07"), true, zone, false));
    assertEquals("Oct 5 2026 · 1:07:00 PM", ClockText.date(time("13:07"), false, zone, true));
  }

  @Test
  public void existingLogAndNewFileNamesRespectClockStyle() {
    assertEquals("1:07:02.123 PM  INFO", ClockText.log("13:07:02.123  INFO", false));
    assertEquals("13:07:02.123  INFO", ClockText.log("13:07:02.123  INFO", true));
    assertEquals("2026-10-05_01-07-00-000_PM", ClockText.file(time("13:07"), false, zone));
  }
}
