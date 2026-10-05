package dev.audioscope;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

public class AudioPipelineTest {
  private File folder;

  @Before
  public void setup() throws Exception {
    folder = Files.createTempDirectory("audioscope-test").toFile();
  }

  private File constant(String name, int frames, short sample) throws Exception {
    File f = new File(folder, name);
    try (WavFile w = new WavFile(f, 48000, 1)) {
      byte[] b = new byte[frames * 2];
      for (int i = 0; i < frames; i++) {
        b[i * 2] = (byte) sample;
        b[i * 2 + 1] = (byte) (sample >>> 8);
      }
      w.write(b, b.length);
    }
    return f;
  }

  @Test
  public void signalHealthDistinguishesZeroBuffersAndClipping() {
    PcmStats silent = new PcmStats(new byte[1000], 1000);
    assertEquals(0, silent.nonzero, 0);
    assertEquals(-120, silent.db, .001);
    byte[] b = {-1, 127, 0, -128, 0, 0};
    PcmStats p = new PcmStats(b, 6);
    assertEquals(2, p.clipped);
    assertEquals(2.0 / 3, p.nonzero, .001);
    assertEquals(1, p.peak, 0);
    assertEquals(32767, PcmStats.clamp(90000));
    assertEquals(-32768, PcmStats.clamp(-90000));
  }

  @Test
  public void wavHeaderAndInterruptedRepairPreserveSamples() throws Exception {
    File f = constant("raw.wav", 4800, (short) 500);
    try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
      r.seek(4);
      r.writeInt(0);
      r.seek(40);
      r.writeInt(0);
    }
    WavFile.repair(f);
    byte[] h = Files.readAllBytes(f.toPath());
    assertEquals(9636, ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
    assertEquals(9600, ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(40));
    try (WavFile.Reader r = new WavFile.Reader(f)) {
      assertEquals(4800, r.frames);
      assertEquals(500, r.sample(2500), 0);
    }
  }

  @Test
  public void stereoAlignsDelayedTrackAndKeepsChannelSeparation() throws Exception {
    File a = constant("a.wav", 4800, (short) 1000),
        b = constant("b.wav", 4800, (short) 2000),
        out = new File(folder, "stereo.wav");
    List<PcmRouter.Input> ins =
        Arrays.asList(
            new PcmRouter.Input("a", a, 0, 100000000, 1),
            new PcmRouter.Input("b", b, 50000000, 100000000, 1));
    try {
      PcmRouter.export(out, ins, 48000, "a", "b", false);
    } finally {
      for (PcmRouter.Input i : ins) i.close();
    }
    byte[] pcm = Files.readAllBytes(out.toPath());
    ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(1000, samples.getShort(44 + 100 * 4));
    assertEquals(0, samples.getShort(44 + 100 * 4 + 2));
    assertEquals(1000, samples.getShort(44 + 3000 * 4));
    assertEquals(2000, samples.getShort(44 + 3000 * 4 + 2));
    try (WavFile.Reader r = new WavFile.Reader(out)) {
      assertEquals(2, r.channels);
      assertEquals(7200, r.frames);
    }
  }

  @Test
  public void mixNormalizesGainAndMuteRetainsOriginal() throws Exception {
    File a = constant("a.wav", 4800, (short) 20000),
        b = constant("b.wav", 4800, (short) 10000),
        out = new File(folder, "mix.wav");
    List<PcmRouter.Input> ins =
        Arrays.asList(
            new PcmRouter.Input("a", a, 0, 100000000, 2),
            new PcmRouter.Input("b", b, 0, 100000000, 0));
    try {
      PcmRouter.export(out, ins, 48000, "", "", true);
    } finally {
      for (PcmRouter.Input i : ins) i.close();
    }
    try (WavFile.Reader r = new WavFile.Reader(out)) {
      assertEquals(20000, r.sample(1000), 0);
    }
    try (WavFile.Reader r = new WavFile.Reader(b)) {
      assertEquals(10000, r.sample(1000), 0);
    }
  }

  @Test
  public void optionalTrackNormalizationBalancesLevelsAndCanBeDisabled() throws Exception {
    File a = constant("loud.wav", 4800, (short) 20000),
        b = constant("quiet.wav", 4800, (short) 10000);
    List<PcmRouter.Input> ins =
        Arrays.asList(
            new PcmRouter.Input("a", a, 0, 100000000, 1),
            new PcmRouter.Input("b", b, 0, 100000000, 1));
    try {
      File normalized = new File(folder, "normalized.wav"), plain = new File(folder, "plain.wav");
      PcmRouter.export(normalized, ins, 48000, "", "", true, true);
      PcmRouter.export(plain, ins, 48000, "", "", true, false);
      try (WavFile.Reader r = new WavFile.Reader(normalized)) {
        assertEquals(23197, r.sample(1000), 1);
      }
      try (WavFile.Reader r = new WavFile.Reader(plain)) {
        assertEquals(15000, r.sample(1000), 1);
      }
      try (WavFile.Reader r = new WavFile.Reader(b)) {
        assertEquals(10000, r.sample(1000), 0);
      }
    } finally {
      for (PcmRouter.Input i : ins) i.close();
    }
  }

  @Test
  public void multitrackMatroskaContainsSeparateNamedPcmTracks() throws Exception {
    File a = constant("a.wav", 4800, (short) 1000),
        b = constant("b.wav", 4800, (short) 2000),
        out = new File(folder, "tracks.mka");
    MkaWriter.export(
        out,
        Arrays.asList(
            new MkaWriter.Track("Microphone", a, 48000, 1, 0),
            new MkaWriter.Track("Remote", b, 48000, 1, 50000000)));
    byte[] data = Files.readAllBytes(out.toPath());
    assertEquals(0x1a, data[0] & 255);
    assertEquals(0x45, data[1] & 255);
    String decoded = new String(data, "ISO-8859-1");
    assertTrue(decoded.contains("Microphone"));
    assertTrue(decoded.contains("Remote"));
    assertEquals(2, decoded.split("A_PCM/INT/LIT", -1).length - 1);
    assertTrue(data.length > 19200);
  }
}
