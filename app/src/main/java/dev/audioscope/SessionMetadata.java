package dev.audioscope;

import android.app.Activity;
import android.widget.*;
import java.io.File;
import java.util.*;
import org.json.*;

/** All persisted manifest fields, including extensions, plus the actual session file inventory. */
public final class SessionMetadata {
  public static LinearLayout view(Activity activity, File folder, JSONObject data) {
    LinearLayout card = Ui.card(activity);
    TextView title = Ui.text(activity, "Session metadata", 18, Ui.INK);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    card.addView(title);
    card.addView(
        Ui.text(
            activity,
            "All saved session fields, individual-track health, routes, call details, bookmarks and"
                + " exports. Ns/frame fields describe capture timing; clock timestamps use your"
                + " Appearance setting.",
            13,
            Ui.MUTED));
    fields(activity, card, data, 0);
    card.addView(Ui.text(activity, "Session files", 16, Ui.INK));
    File[] files = folder.listFiles(File::isFile);
    if (files != null) {
      Arrays.sort(files, Comparator.comparing(File::getName));
      for (File f : files)
        item(
            activity,
            card,
            f.getName(),
            f.length() + " bytes · modified " + TimeDisplay.full(f.lastModified()),
            0);
    }
    return card;
  }

  private static void fields(Activity a, LinearLayout host, JSONObject data, int depth) {
    List<String> keys = new ArrayList<>();
    data.keys().forEachRemaining(keys::add);
    Collections.sort(keys);
    for (String key : keys) value(a, host, key, data.opt(key), depth);
  }

  private static void value(Activity a, LinearLayout host, String key, Object data, int depth) {
    if (data instanceof JSONObject) {
      item(a, host, key, "", depth);
      fields(a, host, (JSONObject) data, depth + 1);
    } else if (data instanceof JSONArray) {
      JSONArray array = (JSONArray) data;
      item(a, host, key, array.length() + " entries", depth);
      for (int i = 0; i < array.length(); i++)
        value(a, host, key + " [" + (i + 1) + "]", array.opt(i), depth + 1);
    } else {
      String text =
          data == null || data == JSONObject.NULL ? "Not available" : String.valueOf(data);
      if (data instanceof Number
          && Arrays.asList("timestampUnixMs", "time", "observedAt", "postedAt", "phoneStarted")
              .contains(key)) {
        long time = ((Number) data).longValue();
        if (time > 0) text = TimeDisplay.full(time) + " · Unix milliseconds " + time;
      }
      item(a, host, key, text.isEmpty() ? "Empty" : text, depth);
    }
  }

  private static void item(Activity a, LinearLayout host, String label, String value, int depth) {
    LinearLayout row = Ui.column(a);
    row.setPadding(Ui.dp(a, Math.min(depth, 3) * 8), Ui.dp(a, 8), 0, Ui.dp(a, 4));
    row.addView(
        Ui.text(a, label.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' '), 12, Ui.MUTED));
    if (!value.isEmpty()) {
      TextView text = Ui.text(a, value, 14, Ui.INK);
      text.setTextIsSelectable(true);
      row.addView(text);
    }
    host.addView(row);
  }
}
