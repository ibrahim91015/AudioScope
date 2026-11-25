package dev.audioscope;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import java.util.*;
import org.json.*;

public final class Notices {
  private static final Map<String, Long> last = new HashMap<>();

  public static void channels(Context c) {
    NotificationManager n = c.getSystemService(NotificationManager.class);
    n.createNotificationChannel(
        new NotificationChannel(
            "reliability", "Debugging guard status", NotificationManager.IMPORTANCE_LOW));
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
    post("events", title, detail, screen, false, null, NotificationPolicy.topic(title));
  }

  public static void problem(Source source, String error, boolean explicit) {
    CaptureProblem p = CaptureProblem.of(error);
    post(
        "problems",
        source.title + ": " + p.title,
        p.details(error),
        "sources",
        explicit,
        "source:" + source.id + "|" + error,
        "source:" + source.id);
  }

  public static void error(String title, String detail, String screen) {
    post("problems", title, detail, screen, true, null, NotificationPolicy.topic(title));
  }

  public static void clear(String topic) {
    clear(topic, -1);
  }

  public static void reconcile() {
    if (ScopeApp.bridge != null && ScopeApp.bridge.asBinder().isBinderAlive()) {
      clear("helper");
      clear("pairing");
    }
    if (!CaptureService.active()) clear("recording");
    if (ScopeApp.app.getExternalFilesDir(null).getUsableSpace() >= 100 * 1024 * 1024L)
      clear("message:Low storage · saving recording");
  }

  private static void clear(String topic, int keep) {
    Context c = ScopeApp.app;
    if (c == null) return;
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    int stableId = 1000 + Math.floorMod(topic.hashCode(), 20000);
    if (stableId != keep) manager.cancel(stableId); // Include notifications still being enqueued.
    for (android.service.notification.StatusBarNotification posted :
        manager.getActiveNotifications()) {
      if (posted.getId() < 1000 || posted.getId() == keep)
        continue; // Service controls own their lifecycle.
      Bundle extras = posted.getNotification().extras;
      String previous = extras.getString("audioscope.topic");
      if (previous == null)
        previous = NotificationPolicy.topic(extras.getString(Notification.EXTRA_TITLE));
      if (topic.equals(previous)) manager.cancel(posted.getId());
    }
  }

  public static void sourceReady(Source source) {
    clear("source:" + source.id);
    synchronized (Notices.class) {
      last.keySet().removeIf(key -> key.startsWith("source:" + source.id + "|"));
    }
    // Migrate old notifications which only used the human-readable source title.
    NotificationManager manager = ScopeApp.app.getSystemService(NotificationManager.class);
    for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications()) {
      if (n.getId() < 1000) continue;
      String title = n.getNotification().extras.getString(Notification.EXTRA_TITLE, "");
      if (title.startsWith(source.title + ": ")) manager.cancel(n.getId());
    }
  }

  private static synchronized void post(
      String channel,
      String title,
      String detail,
      String screen,
      boolean explicit,
      String key,
      String topic) {
    Context c = ScopeApp.app;
    if (c == null) return;
    long now = System.currentTimeMillis();
    if (key != null && !explicit && now - last.getOrDefault(key, 0L) < 60000) return;
    if (key != null) last.put(key, now);
    int id = 1000 + Math.floorMod(topic.hashCode(), 20000);
    for (String resolved : NotificationPolicy.clearedBy(title)) clear(resolved, id);
    clear(topic, id);
    try {
      JSONArray history = new JSONArray(ScopeApp.prefs().getString("noticeHistory", "[]"));
      JSONArray trimmed = new JSONArray();
      trimmed.put(new JSONObject().put("time", now).put("title", title).put("detail", detail));
      for (int i = 0; i < Math.min(49, history.length()); i++) trimmed.put(history.get(i));
      ScopeApp.prefs().edit().putString("noticeHistory", trimmed.toString()).apply();
    } catch (Exception ignored) {
    }
    if (channel.equals("events") && !ScopeApp.prefs().getBoolean("eventNotifications", true)) {
      clear(topic);
      return;
    }
    PendingIntent open = open(c, screen, title, detail, id);
    Bundle extras = new Bundle();
    extras.putString("audioscope.topic", topic);
    Notification notification =
        new Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail.split("\n")[0])
            .setStyle(new Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Details", open).build())
            .setAutoCancel(true)
            .addExtras(extras)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(NotificationPolicy.lifetime(channel.equals("problems"), title))
            .setGroup(channel.equals("problems") ? "audioscope.problems" : null)
            .build();
    try {
      c.getSystemService(NotificationManager.class).notify(id, notification);
    } catch (SecurityException e) {
      ScopeApp.log("WARN", "Notification permission unavailable: " + title);
    }
  }
}
