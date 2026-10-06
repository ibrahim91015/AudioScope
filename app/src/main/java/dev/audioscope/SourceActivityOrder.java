package dev.audioscope;

import java.util.*;

/** Recent activity and temporary promotion; never writes the saved source layout. */
public final class SourceActivityOrder {
  private final Map<String, Long> noise = new HashMap<>();
  private List<String> promoted = new ArrayList<>(), pending = new ArrayList<>();
  private long lastMove = Long.MIN_VALUE / 2, pendingSince;

  public void observe(String id, long at) {
    if (at > 0) noise.merge(id, at, Math::max);
  }

  public List<String> recent(Collection<String> ids, long now, long hold) {
    List<String> result = new ArrayList<>();
    for (String id : ids) {
      Long at = noise.get(id);
      if (at != null && now >= at && now - at <= hold) result.add(id);
    }
    return result;
  }

  public List<String> order(
      List<String> saved, boolean enabled, long now, long hold, long interval, long restore) {
    long lastNoise = 0;
    for (String id : saved) lastNoise = Math.max(lastNoise, noise.getOrDefault(id, 0L));
    if (!enabled || lastNoise == 0 || now - lastNoise > restore) {
      promoted.clear();
      pending.clear();
      return new ArrayList<>(saved);
    }
    List<String> active = recent(saved, now, hold);
    // Keep the last promotion through brief pauses; revert after the full quiet interval.
    if (!active.isEmpty()) {
      if (!active.equals(pending)) {
        pending = active;
        pendingSince = now;
      }
      if (now - pendingSince >= 700 && now - lastMove >= interval && !pending.equals(promoted)) {
        promoted = new ArrayList<>(pending);
        lastMove = now;
      }
    }
    List<String> result = new ArrayList<>();
    for (String id : saved) if (promoted.contains(id)) result.add(id);
    for (String id : saved) if (!result.contains(id)) result.add(id);
    return result;
  }
}
