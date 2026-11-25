package dev.audioscope;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class NotificationPolicyTest {
  @Test
  public void helperRecoveryReplacesErrorsAndClearsPairingWarnings() {
    assertEquals(
        NotificationPolicy.topic("Capture helper disconnected"),
        NotificationPolicy.topic("Capture helper connected"));
    assertEquals(
        NotificationPolicy.topic("ADB connection failed"),
        NotificationPolicy.topic("Offline helper connected"));
    assertTrue(
        Arrays.asList(NotificationPolicy.clearedBy("Capture helper connected"))
            .contains("pairing"));
  }

  @Test
  public void pausedAndResumedShareOneCurrentRecordingStatus() {
    assertEquals(
        NotificationPolicy.topic("Capture started"), NotificationPolicy.topic("Recording paused"));
    assertEquals(
        NotificationPolicy.topic("Recording paused"),
        NotificationPolicy.topic("Recording resumed"));
    assertTrue(
        Arrays.asList(NotificationPolicy.clearedBy("Recording saved")).contains("recording"));
  }

  @Test
  public void reopeningAndArmingResolvesTheBootReminder() {
    assertEquals(
        NotificationPolicy.topic("Automatic call recording armed"),
        NotificationPolicy.topic("Open AudioScope to resume automatic calls"));
    assertEquals(
        NotificationPolicy.topic("Automatic call recording armed"),
        NotificationPolicy.topic("Automatic call recording disarmed"));
  }

  @Test
  public void opposingBluetoothAndOfflineStatesReplaceEachOther() {
    assertEquals(
        NotificationPolicy.topic("Bluetooth microphone held"),
        NotificationPolicy.topic("Bluetooth microphone released"));
    assertEquals(
        NotificationPolicy.topic("Wi-Fi-free helper restart ready"),
        NotificationPolicy.topic("Wi-Fi-free restart disabled"));
  }

  @Test
  public void unrelatedFailuresAreNotDismissedByHelperRecovery() {
    assertNotEquals(
        NotificationPolicy.topic("Save folder unavailable"),
        NotificationPolicy.topic("Capture helper connected"));
    assertFalse(
        Arrays.asList(NotificationPolicy.clearedBy("Capture helper connected"))
            .contains("source:mic"));
    assertFalse(
        Arrays.asList(NotificationPolicy.clearedBy("Recording saved")).contains("automation"));
  }

  @Test
  public void transientMessagesExpireButUnresolvedProblemsRemain() {
    assertEquals(20000, NotificationPolicy.lifetime(false, "Capture helper connected"));
    assertEquals(600000, NotificationPolicy.lifetime(false, "Recording saved"));
    assertEquals(0, NotificationPolicy.lifetime(true, "Capture helper disconnected"));
  }
}
