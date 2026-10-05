package dev.audioscope;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Matroska PCM16: one independently named audio track per capture source. */
public final class MkaWriter {
  public static final class Track {
    public final String name;
    public final File file;
    public final int rate, channels;
    public final long offsetNs;

    public Track(String n, File f, int r, int c, long o) {
      name = n;
      file = f;
      rate = r;
      channels = c;
      offsetNs = Math.max(0, o);
    }
  }

  public static void export(File output, List<Track> tracks) throws IOException {
    List<RandomAccessFile> files = new ArrayList<>();
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output), 65536)) {
      out.write(
          el(
              0x1a45dfa3,
              cat(
                  uint(0x4286, 1),
                  uint(0x42f7, 1),
                  uint(0x42f2, 4),
                  uint(0x42f3, 8),
                  str(0x4282, "matroska"),
                  uint(0x4287, 4),
                  uint(0x4285, 2))));
      out.write(id(0x18538067));
      out.write(new byte[] {1, -1, -1, -1, -1, -1, -1, -1});
      long endMs = 0;
      for (Track t : tracks) {
        long frames = Math.max(0, (t.file.length() - 44) / (t.channels * 2));
        endMs = Math.max(endMs, t.offsetNs / 1000000 + frames * 1000 / t.rate);
      }
      out.write(
          el(
              0x1549a966,
              cat(
                  uint(0x2ad7b1, 1000000),
                  str(0x4d80, "AudioScope"),
                  str(0x5741, "AudioScope"),
                  floating(0x4489, endMs))));
      ByteArrayOutputStream entries = new ByteArrayOutputStream();
      for (int i = 0; i < tracks.size(); i++) {
        Track t = tracks.get(i);
        entries.write(
            el(
                0xae,
                cat(
                    uint(0xd7, i + 1),
                    uint(0x73c5, i + 1),
                    uint(0x83, 2),
                    str(0x536e, t.name),
                    str(0x86, "A_PCM/INT/LIT"),
                    el(
                        0xe1,
                        cat(floating(0xb5, t.rate), uint(0x9f, t.channels), uint(0x6264, 16))))));
        RandomAccessFile f = new RandomAccessFile(t.file, "r");
        f.seek(44);
        files.add(f);
      }
      out.write(el(0x1654ae6b, entries.toByteArray()));
      long[] positions = new long[tracks.size()];
      long[] total = new long[tracks.size()];
      for (int i = 0; i < tracks.size(); i++)
        total[i] = Math.max(0, (files.get(i).length() - 44) / (tracks.get(i).channels * 2));
      for (long cluster = 0; cluster <= endMs; cluster += 1000) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(uint(0xe7, cluster));
        for (int i = 0; i < tracks.size(); i++) {
          Track t = tracks.get(i);
          long offset = t.offsetNs / 1000000;
          while (positions[i] < total[i]) {
            long time = offset + positions[i] * 1000 / t.rate;
            if (time >= cluster + 1000) break;
            int samples = (int) Math.min(t.rate / 50, total[i] - positions[i]);
            byte[] data = new byte[samples * t.channels * 2];
            files.get(i).readFully(data);
            ByteArrayOutputStream block = new ByteArrayOutputStream();
            block.write(vint(i + 1));
            int relative = (int) (time - cluster);
            block.write(relative >>> 8);
            block.write(relative);
            block.write(0x80);
            block.write(data);
            body.write(el(0xa3, block.toByteArray()));
            positions[i] += samples;
          }
        }
        out.write(el(0x1f43b675, body.toByteArray()));
      }
    } finally {
      for (RandomAccessFile f : files) f.close();
    }
  }

  private static byte[] id(long value) {
    int n = 1;
    while (n < 4 && (value >>> (n * 8)) != 0) n++;
    byte[] b = new byte[n];
    for (int i = 0; i < n; i++) b[n - 1 - i] = (byte) (value >>> (i * 8));
    return b;
  }

  private static byte[] vint(long value) {
    int n = 1;
    while (n < 8 && value >= (1L << (7 * n)) - 1) n++;
    long v = value | (1L << (7 * n));
    byte[] b = new byte[n];
    for (int i = 0; i < n; i++) b[n - 1 - i] = (byte) (v >>> (8 * i));
    return b;
  }

  private static byte[] el(long key, byte[] data) {
    return cat(id(key), vint(data.length), data);
  }

  private static byte[] uint(long key, long value) {
    int n = 1;
    while (n < 8 && (value >>> (n * 8)) != 0) n++;
    byte[] b = new byte[n];
    for (int i = 0; i < n; i++) b[n - 1 - i] = (byte) (value >>> (i * 8));
    return el(key, b);
  }

  private static byte[] str(long key, String value) throws IOException {
    return el(key, value.getBytes("UTF-8"));
  }

  private static byte[] floating(long key, double value) {
    return el(key, ByteBuffer.allocate(8).putDouble(value).array());
  }

  private static byte[] cat(byte[]... arrays) {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    for (byte[] a : arrays) b.write(a, 0, a.length);
    return b.toByteArray();
  }
}
