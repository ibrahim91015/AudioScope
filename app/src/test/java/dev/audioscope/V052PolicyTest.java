package dev.audioscope;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class V052PolicyTest {
  @Test
  public void appAndWifiCallsUseCommunicationMic() {
    assertFalse(CallCapturePlan.carrier(3, 0, 0));
    assertFalse(CallCapturePlan.carrier(2, 2, 18));
    assertArrayEquals(
        new String[] {"voice_playback", "communication_input"},
        CallCapturePlan.sources(false, true));
  }

  @Test
  public void carrierUsesSeparateTracksAndNoHelperReportsOnlyAvailableMic() {
    assertTrue(CallCapturePlan.carrier(2, 2, 13));
    assertArrayEquals(new String[] {"uplink", "downlink"}, CallCapturePlan.sources(true, true));
    assertArrayEquals(new String[] {"communication_input"}, CallCapturePlan.sources(true, false));
  }

  @Test
  public void previewDefaultAllowsExplicitUnrelatedResumeOnly() {
    assertFalse(
        PreviewPolicy.reason("manual", false, true, false, false, false, true, false).isEmpty());
    assertEquals("", PreviewPolicy.reason("manual", true, true, false, false, false, true, false));
    assertFalse(
        PreviewPolicy.reason("manual", true, true, false, false, true, true, false).isEmpty());
  }

  @Test
  public void previewModesNeverAllowDuplicateBusyOrCompetingInputs() {
    assertEquals(
        "", PreviewPolicy.reason("automatic", false, true, false, false, false, true, false));
    assertFalse(
        PreviewPolicy.reason("paused", true, true, false, false, false, false, false).isEmpty());
    assertFalse(
        PreviewPolicy.reason("automatic", true, true, false, true, false, false, false).isEmpty());
    assertFalse(
        PreviewPolicy.reason("automatic", true, true, true, false, false, false, false).isEmpty());
    assertFalse(
        PreviewPolicy.reason("automatic", true, true, false, false, false, false, true).isEmpty());
    assertEquals("", PreviewPolicy.reason("paused", false, false, false, false, true, true, false));
  }

  @Test
  public void recentActivityRetainsQuietSourcesForFiveSeconds() {
    SourceActivityOrder model = new SourceActivityOrder();
    model.observe("a", 1000);
    assertEquals(Arrays.asList("a"), model.recent(Arrays.asList("a", "b"), 6000, 5000));
    assertTrue(model.recent(Arrays.asList("a"), 6001, 5000).isEmpty());
  }

  @Test
  public void promotionSettlesAndRespectsMinimumInterval() {
    SourceActivityOrder m = new SourceActivityOrder();
    List<String> saved = Arrays.asList("a", "b", "c");
    m.observe("c", 1000);
    assertEquals(saved, m.order(saved, true, 1000, 5000, 4000, 15000));
    assertEquals(Arrays.asList("c", "a", "b"), m.order(saved, true, 1700, 5000, 4000, 15000));
    m.observe("b", 2000);
    m.order(saved, true, 2000, 5000, 4000, 15000);
    assertEquals(Arrays.asList("c", "a", "b"), m.order(saved, true, 2700, 5000, 4000, 15000));
    assertEquals(Arrays.asList("b", "c", "a"), m.order(saved, true, 5700, 5000, 4000, 15000));
    assertEquals(Arrays.asList("a", "b", "c"), saved);
  }

  @Test
  public void layoutRestoresAfterQuietOrDisabling() {
    SourceActivityOrder m = new SourceActivityOrder();
    List<String> saved = Arrays.asList("a", "b");
    m.observe("b", 1000);
    m.order(saved, true, 1000, 5000, 4000, 15000);
    assertEquals(Arrays.asList("b", "a"), m.order(saved, true, 1700, 5000, 4000, 15000));
    assertEquals(Arrays.asList("b", "a"), m.order(saved, true, 10000, 5000, 4000, 15000));
    assertEquals(saved, m.order(saved, true, 16001, 5000, 4000, 15000));
    assertEquals(saved, m.order(saved, false, 1700, 5000, 4000, 15000));
  }
}
