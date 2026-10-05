package dev.audioscope;

import java.util.*;

/** Layout state is independent of capture presets and source format settings. */
public final class SourceLayout {
  public static final Set<String> COMMON =
      new LinkedHashSet<>(
          Arrays.asList(
              "voice_playback",
              "any_phone_mic",
              "mic",
              "voice_call",
              "uplink",
              "downlink",
              "media"));

  public static List<Source> ordered() {
    List<Source> available = Source.available();
    List<Source> result = new ArrayList<>();
    Set<String> used = new HashSet<>();
    String order =
        ScopeApp.prefs()
            .getString(
                "sourceOrder", "voice_playback,any_phone_mic,mic,voice_call,uplink,downlink,media");
    for (String id : order.split(","))
      try {
        if (available.stream().anyMatch(x -> x.id.equals(id)) && used.add(id))
          result.add(Source.get(id));
      } catch (IllegalArgumentException ignored) {
      }
    for (Source source : available) if (used.add(source.id)) result.add(source);
    // An existing custom order gains the new default directly before the regular mic.
    if (!order.contains("any_phone_mic")) {
      Source flexible = Source.get("any_phone_mic");
      result.remove(flexible);
      int mic = 0;
      while (mic < result.size() && !result.get(mic).id.equals("mic")) mic++;
      result.add(mic, flexible);
    }
    return result;
  }

  public static Set<String> hidden() {
    Set<String> defaults = new HashSet<>();
    for (Source s : Source.ALL) if (!COMMON.contains(s.id)) defaults.add(s.id);
    return new HashSet<>(ScopeApp.prefs().getStringSet("hiddenSources", defaults));
  }

  public static void hide(String id, boolean hide) {
    Set<String> hidden = hidden();
    if (hide) hidden.add(id);
    else hidden.remove(id);
    ScopeApp.prefs().edit().putStringSet("hiddenSources", hidden).apply();
  }

  public static void move(String from, String before) {
    List<String> ids = new ArrayList<>();
    for (Source s : ordered()) ids.add(s.id);
    if (from.equals(before)) return;
    if (!ids.contains(from)) return;
    ids.remove(from);
    int index = ids.indexOf(before);
    if (index < 0) return;
    ids.add(index, from);
    ScopeApp.prefs().edit().putString("sourceOrder", String.join(",", ids)).apply();
  }
}
