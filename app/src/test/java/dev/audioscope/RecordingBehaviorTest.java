package dev.audioscope;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecordingBehaviorTest {
  @Test
  public void policyRejectionExplainsPrivilegeAndAlternatives() {
    CaptureProblem p =
        CaptureProblem.of(
            "IllegalStateException: FAILED SecurityException Audio Policy registration rejected");
    assertTrue(p.title.contains("rejected"));
    assertTrue(p.steps.contains("Wi-Fi"));
    assertTrue(p.steps.contains("unavailable"));
  }

  @Test
  public void initializationFailureExplainsConcurrentInputs() {
    CaptureProblem p = CaptureProblem.of("IllegalStateException: AudioRecord did not initialize");
    assertTrue(p.steps.contains("48,000"));
    assertTrue(p.steps.contains("alone"));
  }

  @Test
  public void callStartAndEndAreDebounced() {
    CallStateMachine c = new CallStateMachine();
    assertEquals(CallStateMachine.Action.NONE, c.update(true, 1000, false, false, false));
    assertEquals(CallStateMachine.Action.NONE, c.update(true, 1800, false, false, false));
    assertEquals(CallStateMachine.Action.START, c.update(true, 2000, false, false, false));
    assertEquals(CallStateMachine.Action.NONE, c.update(false, 4000, true, true, false));
    assertEquals(CallStateMachine.Action.STOP, c.update(false, 5501, true, true, false));
  }

  @Test
  public void manualRecordingSurvivesCallEnd() {
    CallStateMachine c = new CallStateMachine();
    c.update(true, 1000, true, false, false);
    assertEquals(CallStateMachine.Action.NONE, c.update(false, 5000, true, false, false));
  }

  @Test
  public void stoppingDoesNotRestartDuringSameCall() {
    CallStateMachine c = new CallStateMachine();
    c.update(true, 1000, true, true, false);
    c.suppress();
    assertEquals(CallStateMachine.Action.NONE, c.update(true, 5000, false, false, false));
    c.update(false, 9000, false, false, false);
    c.update(true, 10000, false, false, false);
    assertEquals(CallStateMachine.Action.START, c.update(true, 11000, false, false, false));
  }

  @Test
  public void ringingAndNormalModeDoNotCountAsAnsweredCalls() {
    assertFalse(CallStateMachine.call(1, 1));
    assertFalse(CallStateMachine.call(0, 0));
    assertTrue(CallStateMachine.call(3, 0));
    assertTrue(CallStateMachine.call(0, 2));
  }
}
