package dev.audioscope;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class SourceBehaviorTest {
  @Test
  public void oneSourceDoesNotLightRecordAll() {
    assertFalse(
        CaptureSelection.all(false, true, false, Arrays.asList("mic"), Arrays.asList("mic")));
  }

  @Test
  public void explicitAllRequiresEveryNonHiddenSourceAndActualRecording() {
    List<String> shown = Arrays.asList("mic", "voice_playback");
    assertFalse(CaptureSelection.all(true, true, false, shown, Arrays.asList("mic")));
    assertTrue(CaptureSelection.all(true, true, false, shown, shown));
    assertFalse(CaptureSelection.all(true, true, true, shown, shown));
    assertFalse(CaptureSelection.all(true, false, false, shown, shown));
    assertFalse(CaptureSelection.all(true, true, false, Collections.emptyList(), shown));
  }

  @Test
  public void onlyPhysicalPhoneInputPresetsArePinnedToPhoneMics() {
    for (Source s : Source.ALL) assertEquals(s.group.equals("INPUT"), s.phoneMic());
    for (Source s : Source.BLUETOOTH) {
      assertTrue(s.bluetooth());
      assertFalse(s.phoneMic());
    }
    assertFalse(Source.get("voice_playback").phoneMic());
    assertFalse(Source.get("voice_call").phoneMic());
  }

  @Test
  public void routingAndMisspelledInitializationErrorsAreReadable() {
    assertEquals(
        "Microphone routing needs attention",
        CaptureProblem.of("Bluetooth microphone route rejected").title);
    assertEquals(
        "The audio input could not open",
        CaptureProblem.of("Audio record did not initilize").title);
  }
}
