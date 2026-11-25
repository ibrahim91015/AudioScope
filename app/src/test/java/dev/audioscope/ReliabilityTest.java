package dev.audioscope;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReliabilityTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void savedIdentitySurvivesNewManagerAndIgnoresInterruptedTemporaryFile() throws Exception {
    File store = temporary.newFolder(), legacy = temporary.newFolder();
    AdbIdentityStore original = new AdbIdentityStore(store, legacy);
    Files.write(new File(store, "adb-identity.tmp").toPath(), new byte[] {1, 2, 3});
    AdbIdentityStore reopened = new AdbIdentityStore(store, legacy);
    assertArrayEquals(original.key.getEncoded(), reopened.key.getEncoded());
    assertArrayEquals(original.cert.getEncoded(), reopened.cert.getEncoded());
  }

  @Test
  public void legacyPairingIsMigratedWithoutChangingPublicKey() throws Exception {
    File old = temporary.newFolder(), first = temporary.newFolder(), next = temporary.newFolder();
    AdbIdentityStore original = new AdbIdentityStore(first, old);
    Files.write(new File(old, "adb-private.der").toPath(), original.key.getEncoded());
    Files.write(new File(old, "adb-cert.der").toPath(), original.cert.getEncoded());
    AdbIdentityStore migrated = new AdbIdentityStore(next, old);
    assertArrayEquals(
        original.cert.getPublicKey().getEncoded(), migrated.cert.getPublicKey().getEncoded());
    assertArrayEquals(original.key.getEncoded(), migrated.key.getEncoded());
  }

  @Test
  public void missingLegacyCertificateRetainsTheSameTrustedRsaKey() throws Exception {
    File old = temporary.newFolder(), first = temporary.newFolder(), next = temporary.newFolder();
    AdbIdentityStore original = new AdbIdentityStore(first, old);
    Files.write(new File(old, "adb-private.der").toPath(), original.key.getEncoded());
    AdbIdentityStore repaired = new AdbIdentityStore(next, old);
    assertArrayEquals(
        original.cert.getPublicKey().getEncoded(), repaired.cert.getPublicKey().getEncoded());
  }

  @Test
  public void corruptIdentityFailsWithoutSilentlyReplacingIt() throws Exception {
    File store = temporary.newFolder(), old = temporary.newFolder();
    File identity = new File(store, "adb-identity.bin");
    byte[] damaged = {4, 5, 6};
    Files.write(identity.toPath(), damaged);
    assertThrows(IOException.class, () -> new AdbIdentityStore(store, old));
    assertArrayEquals(damaged, Files.readAllBytes(identity.toPath()));
  }

  @Test
  public void independentWriterSavesWithoutAnyLiveWaveformConsumerAndFinalizesHeader()
      throws Exception {
    File file = temporary.newFile();
    byte[] pcm = new byte[1920];
    for (int i = 0; i < pcm.length; i += 2) {
      pcm[i] = 0x34;
      pcm[i + 1] = 0x12;
    }
    try (PcmWaveSink sink = new PcmWaveSink(new FileOutputStream(file), 48000, 1)) {
      // Ten seconds, well beyond the bounded preview queue, with no app/UI reader.
      for (int i = 0; i < 500; i++) sink.write(pcm, pcm.length, 1000000000L + i * 20000000L);
      assertEquals(480000, sink.frames());
      assertTrue(sink.audible());
      assertEquals(1000000000L, sink.firstNs());
      assertEquals(11000000000L, sink.lastNs());
    }
    try (WavFile.Reader reader = new WavFile.Reader(file)) {
      assertEquals(480000, reader.frames);
      assertEquals(0x1234, reader.sample(479999), 0);
    }
    byte[] data = Files.readAllBytes(file.toPath());
    java.nio.ByteBuffer header =
        java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    assertEquals(data.length - 44, header.getInt(40));
  }

  @Test
  public void helperPauseDiscardsAudioAndResumesOnTheSameTimeline() throws Exception {
    File file = temporary.newFile();
    byte[] pcm = new byte[96000];
    try (PcmWaveSink sink = new PcmWaveSink(new FileOutputStream(file), 48000, 1)) {
      sink.write(pcm, pcm.length, 1000000000L);
      sink.pause(true, 2000000000L);
      sink.write(pcm, pcm.length, 2000000000L);
      sink.write(pcm, pcm.length, 3000000000L);
      sink.pause(false, 4000000000L);
      sink.write(pcm, pcm.length, 4000000000L);
      assertEquals(96000, sink.frames());
      assertEquals(3000000000L, sink.lastNs());
    }
    assertEquals(44 + 192000, file.length());
  }

  private static final class AdbPairingRequiredException extends IOException {}

  @Test
  public void stoppedAdbdRepairRequiresEvidenceAndWaitsTenMinutes() {
    assertTrue(
        AdbdRepairPolicy.allowed(
            true, true, true, false, false, false, false, true, "stopped", 600000));
    for (String state : new String[] {"running", "restarting", "", "unknown"})
      assertFalse(
          AdbdRepairPolicy.allowed(
              true, true, true, false, false, false, false, true, state, 600000));
    assertFalse(
        AdbdRepairPolicy.allowed(
            true, true, true, false, false, false, false, true, "stopped", 599999));
  }

  @Test
  public void debuggingRepairCannotInterruptACallRecordingHelperOrManager() {
    assertFalse(
        AdbdRepairPolicy.allowed(
            true, true, true, true, false, false, false, true, "stopped", 600000));
    assertFalse(
        AdbdRepairPolicy.allowed(
            true, true, true, false, true, false, false, true, "stopped", 600000));
    assertFalse(
        AdbdRepairPolicy.allowed(
            true, true, true, false, false, true, false, true, "stopped", 600000));
    assertFalse(
        AdbdRepairPolicy.allowed(
            true, true, true, false, false, false, true, true, "stopped", 600000));
    assertFalse(
        AdbdRepairPolicy.allowed(
            false, true, true, false, false, false, false, true, "stopped", 600000));
    assertFalse(
        AdbdRepairPolicy.allowed(
            true, false, true, false, false, false, false, true, "stopped", 600000));
    assertFalse(
        AdbdRepairPolicy.allowed(
            true, true, false, false, false, false, false, true, "stopped", 600000));
    assertFalse(
        AdbdRepairPolicy.allowed(
            true, true, true, false, false, false, false, false, "stopped", 600000));
  }

  @Test
  public void closedPortAndTimeoutDoNotMeanPairingWasRevoked() {
    assertFalse(ReconnectPolicy.authenticationRejected(new java.net.ConnectException()));
    assertFalse(ReconnectPolicy.authenticationRejected(new java.net.SocketTimeoutException()));
    assertTrue(
        ReconnectPolicy.authenticationRejected(new IOException(new AdbPairingRequiredException())));
    assertEquals(2, ReconnectPolicy.refusals(2, false));
    assertEquals(3, ReconnectPolicy.refusals(2, true));
    assertEquals(3, ReconnectPolicy.refusals(3, true));
    assertEquals(5000, ReconnectPolicy.delay(0));
    assertEquals(300000, ReconnectPolicy.delay(100));
  }
}
