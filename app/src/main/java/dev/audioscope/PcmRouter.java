package dev.audioscope;

import java.io.*;
import java.util.*;

/** Offline timestamp alignment, with linear interpolation for independent capture clocks. */
public final class PcmRouter {
  public static final class Input implements Closeable {
    public final String id;
    public final WavFile.Reader reader;
    public final long offsetNs, spanNs;
    public final double gain;

    public Input(String id, File file, long offsetNs, long spanNs, double gain) throws IOException {
      this.id = id;
      reader = new WavFile.Reader(file);
      this.offsetNs = Math.max(0, offsetNs);
      this.spanNs = spanNs > 0 ? spanNs : reader.frames * 1_000_000_000L / reader.rate;
      this.gain = gain;
    }

    double at(long time) throws IOException {
      double position = (time - offsetNs) * (double) reader.frames / spanNs;
      if (position < 0 || position >= reader.frames) return 0;
      long f = (long) position;
      return (reader.sample(f) * (1 - (position - f)) + reader.sample(f + 1) * (position - f))
          * gain;
    }

    public void close() throws IOException {
      reader.close();
    }
  }

  public static void export(
      File output, List<Input> inputs, int rate, String left, String right, boolean mix)
      throws IOException {
    long end = 0;
    for (Input i : inputs) end = Math.max(end, i.offsetNs + i.spanNs);
    long frames = (end * rate + 999999999) / 1000000000L;
    int channels = mix ? 1 : 2;
    byte[] buffer = new byte[4096 * channels * 2];
    try (WavFile wav = new WavFile(output, rate, channels)) {
      for (long start = 0; start < frames; start += 4096) {
        int n = (int) Math.min(4096, frames - start);
        for (int f = 0; f < n; f++) {
          long time = (start + f) * 1000000000L / rate;
          double a = 0, b = 0;
          if (mix) {
            double weight = 0;
            for (Input i : inputs) {
              a += i.at(time);
              if (time >= i.offsetNs && time < i.offsetNs + i.spanNs) weight += Math.abs(i.gain);
            }
            a /= Math.max(1, weight);
          } else {
            for (Input i : inputs) {
              if (i.id.equals(left)) a += i.at(time);
              if (i.id.equals(right)) b += i.at(time);
            }
          }
          put(buffer, f * channels * 2, PcmStats.clamp(a));
          if (channels == 2) put(buffer, f * 4 + 2, PcmStats.clamp(b));
        }
        wav.write(buffer, n * channels * 2);
      }
    }
  }

  private static void put(byte[] b, int p, short v) {
    b[p] = (byte) v;
    b[p + 1] = (byte) (v >>> 8);
  }
}
