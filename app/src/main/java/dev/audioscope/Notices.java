package dev.audioscope;

import android.app.*;
import android.content.*;
import java.util.*;
import org.json.*;

public final class Notices {
  private static final Map<String, Long> last = new HashMap<>();

  public static void channels(Context c) {
    NotificationManager n = c.getSystemService(NotificationManager.class);
    n.createNotificationChannel(new NotificationChannel("reliability", "Debugging guard status", NotificationManager.IMPORTANCE_LOW));
    n.createNotificationChannel(
        new NotificationChannel(
            "capture", "Recording controls", NotificationManager.IMPORTANCE_LOW));
    NotificationChannel setup =
        new NotificationChannel(
            "pairing", "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH);
    setup.setSound(null, null);
    setup.enableVibration(false);
    n.createNotificationChannel(setup);
    n.createNotificationChannel(
        new NotificationChannel(
            "problems",
            "Capture problems and repair steps",
            NotificationManager.IMPORTANCE_DEFAULT));
    n.createNotificationChannel(
        new NotificationChannel(
            "events", "Recording and setup updates", NotificationManager.IMPORTANCE_LOW));
    n.createNotificationChannel(
        new NotificationChannel(
            "playback", "Audio playback controls", NotificationManager.IMPORTANCE_LOW));
  }

  public static PendingIntent open(Context c, String screen, String title, String detail, int id) {
    Intent i =
        new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("screen", screen);
    if (title != null) i.putExtra("noticeTitle", title).putExtra("noticeDetail", detail);
    return PendingIntent.getActivity(
        c, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  public static void event(String title, String detail, String screen) {
    post("events", title, detail, screen, false, null);
  }

  public static void problem(Source source, String error, boolean explicit) {
    CaptureProblem p = CaptureProblem.of(error);
    post(
        "problems",
        source.title + ": " + p.title,
        p.details(error),
        "sources",
        explicit,
        source.id + error);
  }

  public static void error(String title, String detail, String screen) {
    post("problems", title, detail, screen, true, null);
  }

  private static synchronized void post(
      String channel, String title, String detail, String screen, boolean explicit, String key) {
    Context c = ScopeApp.app;
    if (c == null) return;
    long now = System.currentTimeMillis();
    if (key != null && !explicit && now - last.getOrDefault(key, 0L) < 60000) return;
    if (key != null) last.put(key, now);
    int id = 1000 + Math.floorMod((key == null ? title : key).hashCode(), 20000);
    try {
      JSONArray history = new JSONArray(ScopeApp.prefs().getString("noticeHistory", "[]"));
      JSONArray trimmed = new JSONArray();
      trimmed.put(new JSONObject().put("time", now).put("title", title).put("detail", detail));
      for (int i = 0; i < Math.min(49, history.length()); i++) trimmed.put(history.get(i));
      ScopeApp.prefs().edit().putString("noticeHistory", trimmed.toString()).apply();
    } catch (Exception ignored) {
    }
    if (channel.equals("events") && !ScopeApp.prefs().getBoolean("eventNotifications", true))
      return;
    PendingIntent open = open(c, screen, title, detail, id);
    Notification notification =
        new Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail.split("\n")[0])
            .setStyle(new Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Details", open).build())
            .setAutoCancel(true)
            .setGroup(channel.equals("problems") ? "audioscope.problems" : null)
            .build();
    try {
      c.getSystemService(NotificationManager.class).notify(id, notification);
    } catch (SecurityException e) {
      ScopeApp.log("WARN", "Notification permission unavailable: " + title);
    }
  }
}
