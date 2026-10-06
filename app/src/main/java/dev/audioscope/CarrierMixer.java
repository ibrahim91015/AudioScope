package dev.audioscope;

import java.io.*;
import java.util.*;

/** Strong gated RMS balancing for uplink/downlink only, with a soft output limiter. */
public final class CarrierMixer {
  static double level(WavFile.Reader reader) throws IOException {
    double energy = 0;
    long count = 0;
    for (long start = 0; start < reader.frames; start += 512) {
      int n = (int) Math.min(512, reader.frames - start);
      double block = 0;
      for (int f = 0; f < n; f++) {
        double x = reader.sample(start + f);
        block += x * x;
      }
      if (block / n >= 3600) {
        energy += block;
        count += n;
      } // Gate quiet background.
    }
    return count == 0 ? 1 : Math.min(32, 5193 / Math.sqrt(energy / count));
  }

  static double limit(double x) {
    double a = Math.abs(x);
    return a <= 24000 ? x : Math.copySign(24000 + 6000 * (1 - Math.exp(-(a - 24000) / 6000)), x);
  }

  public static void export(File output, List<PcmRouter.Input> inputs, int rate)
      throws IOException {
    Map<PcmRouter.Input, Double> levels = new HashMap<>();
    long end = 0;
    for (PcmRouter.Input in : inputs) {
      levels.put(in, in.gain == 0 ? 1 : level(in.reader));
      end = Math.max(end, in.offsetNs + in.spanNs);
    }
    long frames = (end * rate + 999999999L) / 1000000000L;
    byte[] buffer = new byte[8192];
    try (WavFile wav = new WavFile(output, rate, 1)) {
      for (long start = 0; start < frames; start += 4096) {
        int n = (int) Math.min(4096, frames - start);
        for (int f = 0; f < n; f++) {
          long time = (start + f) * 1000000000L / rate;
          double sum = 0;
          for (PcmRouter.Input in : inputs) {
            sum += in.at(time) * levels.get(in);
          }
          short sample = PcmStats.clamp(limit(sum));
          buffer[f * 2] = (byte) sample;
          buffer[f * 2 + 1] = (byte) (sample >>> 8);
        }
        wav.write(buffer, n * 2);
      }
    }
  }
}
