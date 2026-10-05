package dev.audioscope;

import java.util.*;

/** Recommend every measured route within 12 dB of the strongest, above the silence threshold. */
public final class SourceRecommendations {
  public static List<String> select(Map<String, Double> levels, double silence) {
    double strongest =
        levels.values().stream()
            .filter(Double::isFinite)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(-120);
    List<String> result = new ArrayList<>();
    levels.entrySet().stream()
        .filter(
            e ->
                Double.isFinite(e.getValue())
                    && e.getValue() > silence
                    && e.getValue() >= strongest - 12)
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .forEach(e -> result.add(e.getKey()));
    return result;
  }
}
