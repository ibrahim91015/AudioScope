package dev.audioscope;

import android.content.Context;

public final class ThemePalette {
  public static final String[] NAMES = {
    "Purple", "Lilac", "Blue", "Teal", "Rose", "Amber", "Wallpaper / Material You"
  };
  private static final int[] COLORS = {
    0xffbe9bff, 0xffd0bcff, 0xffa2c9ff, 0xff89dfc5, 0xffffb1ce, 0xfff5cd87
  };

  public static int accent(Context c) {
    String name = ScopeApp.prefs().getString("accent", "Purple");
    for (int i = 0; i < COLORS.length; i++) if (name.equals(NAMES[i])) return COLORS[i];
    return c.getColor(android.R.color.system_accent1_200);
  }
}
