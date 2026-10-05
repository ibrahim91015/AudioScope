package dev.audioscope;

import android.content.SharedPreferences;
import java.util.Arrays;

/** UI names describe the actual container; legacy preference values stay compatible. */
public final class Formats {
  public static final String[] VALUES = {"AAC", "WAV", "Opus", "PCM"};
  public static final String[] SOURCE_LABELS = {"M4A", "WAV", "Opus", "PCM"};
  public static final String[] LABELS = {"M4A · AAC", "WAV", "Opus · WebM", "Raw PCM"};

  public static String current(String id) {
    return ScopeApp.prefs().getString("format_" + id, ScopeApp.prefs().getString("codec", "WAV"));
  }

  public static int index(String value) {
    return Math.max(0, Arrays.asList(VALUES).indexOf(value));
  }

  public static String label(String value) {
    return LABELS[index(value)];
  }

  public static void setDefault(String value) {
    SharedPreferences.Editor edit = ScopeApp.prefs().edit().putString("codec", value);
    for (String key : ScopeApp.prefs().getAll().keySet())
      if (key.startsWith("format_")) edit.remove(key);
    edit.apply();
  }
}
