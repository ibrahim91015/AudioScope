package dev.audioscope;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class SourceRecommendationsTest {
  @Test
  public void selectsMultipleIndependentAudibleRoutes() {
    assertEquals(
        Arrays.asList("voice", "mic", "media"),
        SourceRecommendations.select(
            Map.of("voice", -20d, "mic", -26d, "media", -31d, "quiet", -44d, "silent", -80d), -60));
  }

  @Test
  public void silenceAndInvalidLevelsNeverRecommend() {
    assertTrue(
        SourceRecommendations.select(Map.of("silence", -65d, "failed", Double.NaN), -60).isEmpty());
  }

  @Test
  public void filenameKeepsUnicodeAndOnlyKnownIdentity() {
    String name =
        CallNames.filename(
            "{date}_{app}_{direction}_{contact}_{source}",
            "2026-10-05_12-00-00",
            "WhatsApp",
            "",
            "علي/Smith",
            "",
            "Call",
            "VoIP",
            0,
            "m4a");
    assertEquals("2026-10-05_12-00-00_WhatsApp_علي_Smith_VoIP_0.m4a", name);
    assertFalse(name.contains("out"));
  }

  @Test
  public void directionAndNumberFallbackAreExplicit() {
    assertEquals(
        "Phone · Incoming · +15551234567", CallNames.label("Phone", "in", "", "+15551234567"));
    assertEquals("Call", CallNames.label("", "", "", ""));
  }
}
