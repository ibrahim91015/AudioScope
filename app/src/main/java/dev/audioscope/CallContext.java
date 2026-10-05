package dev.audioscope;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.*;
import android.service.notification.StatusBarNotification;
import java.util.*;
import org.json.*;

/** Local-only call identity; never guesses a direction or scans unrelated message contents. */
public final class CallContext {
  private static final Map<String, JSONObject> calls =
      new java.util.concurrent.ConcurrentHashMap<>();
  public static volatile int phoneState;
  private static volatile String direction = "";
  private static volatile long phoneStarted;

  public static synchronized void phone(int state) {
    if (state == 1) {
      direction = "in";
      phoneStarted = System.currentTimeMillis();
    } else if (state == 2 && phoneState == 0) {
      direction = "";
      phoneStarted = System.currentTimeMillis();
    }
    phoneState = state;
  }

  public static boolean allowed(String permission) {
    return ScopeApp.app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  public static boolean notificationAccess() {
    return ScopeApp.app
        .getSystemService(NotificationManager.class)
        .isNotificationListenerAccessGranted(
            new android.content.ComponentName(ScopeApp.app, CallNotificationListener.class));
  }

  public static void notification(StatusBarNotification sbn) {
    if (!ScopeApp.prefs().getBoolean("autoNaming", true)
        || sbn.getPackageName().equals(ScopeApp.app.getPackageName())) return;
    Notification n = sbn.getNotification();
    boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    boolean call =
        Notification.CATEGORY_CALL.equals(n.category)
            || n.extras.containsKey(Notification.EXTRA_CALL_PERSON);
    boolean knownCallApp =
        sbn.getPackageName()
            .matches(
                "(com\\.whatsapp(\\.w4b)?|org\\.telegram\\..*|org\\.thoughtcrime\\.securesms|com\\.microsoft\\.teams|com\\.skype\\..*|com\\.google\\.android\\.apps\\.(meetings|tachyon)|us\\.zoom\\.videomeetings)");
    if (!call
        && !(knownCallApp
            && ongoing
            && n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
            && ScopeApp.app.getSystemService(AudioManager.class).getMode()
                == AudioManager.MODE_IN_COMMUNICATION)) return;
    if (!ongoing) return;
    try {
      String pkg = sbn.getPackageName();
      String app = pkg;
      try {
        app =
            ScopeApp.app
                .getPackageManager()
                .getApplicationLabel(ScopeApp.app.getPackageManager().getApplicationInfo(pkg, 0))
                .toString();
      } catch (Exception ignored) {
      }
      Person person = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person.class);
      String who = person != null && person.getName() != null ? person.getName().toString() : "";
      String number =
          person != null && person.getUri() != null && person.getUri().startsWith("tel:")
              ? person.getUri().substring(4)
              : "";
      if (who.isBlank()) {
        for (String key : new String[] {Notification.EXTRA_TITLE, Notification.EXTRA_TEXT}) {
          CharSequence value = n.extras.getCharSequence(key);
          if (value == null) continue;
          String candidate = value.toString().trim();
          String lower = candidate.toLowerCase(Locale.ROOT);
          if (candidate.length() <= 60
              && !candidate.equalsIgnoreCase(app)
              && !lower.matches(
                  ".*(ongoing|incoming|outgoing|calling|ringing|voice call|video call|call in"
                      + " progress|missed call).*")) {
            who = candidate;
            break;
          }
        }
      }
      String dir = "";
      int type = n.extras.getInt("android.callType", 0);
      if (type == 1) dir = "in"; // CallStyle incoming type; ongoing does not identify direction.
      calls.put(
          sbn.getKey(),
          new JSONObject()
              .put("package", pkg)
              .put("app", app)
              .put("contact", who)
              .put("number", number)
              .put("direction", dir)
              .put("observedAt", System.currentTimeMillis())
              .put("postedAt", sbn.getPostTime())
              .put("evidence", "call notification"));
    } catch (Exception ignored) {
    }
  }

  public static void removed(String key) {
    calls.remove(key);
  }

  public static void clear() {
    calls.clear();
  }

  public static JSONObject snapshot() {
    JSONObject result = new JSONObject();
    if (!ScopeApp.prefs().getBoolean("autoNaming", true)) return result;
    try {
      if (phoneState != 0
          || ScopeApp.app.getSystemService(AudioManager.class).getMode()
              == AudioManager.MODE_IN_CALL) {
        result
            .put("app", "Phone")
            .put("direction", direction)
            .put("phoneStarted", phoneStarted)
            .put("evidence", "telephony state");
      }
      List<JSONObject> candidates = new ArrayList<>(calls.values());
      candidates.removeIf(
          c -> System.currentTimeMillis() - c.optLong("observedAt") > 6 * 60 * 60 * 1000L);
      if (candidates.size() == 1) {
        JSONObject c = candidates.get(0);
        if (result.length() == 0) result = new JSONObject(c.toString());
        else {
          String dialer =
              ScopeApp.app
                  .getSystemService(android.telecom.TelecomManager.class)
                  .getDefaultDialerPackage();
          if (c.optString("package").equals(dialer)
              || c.optString("package").equals("com.android.phone"))
            result
                .put("contact", c.optString("contact"))
                .put("number", c.optString("number"))
                .put("package", c.optString("package"));
        }
      } else if (candidates.size() > 1)
        result.put("identityUnavailable", "Multiple call notifications; no app or caller assumed");
    } catch (Exception ignored) {
    }
    return result;
  }

  public static JSONObject enrich(JSONObject original, long start, long end) {
    JSONObject result = original;
    if (!ScopeApp.prefs().getBoolean("autoNaming", true)) return new JSONObject();
    try {
      JSONObject current = snapshot();
      // Preserve the original app when a late notification arrives; never attach a different app's
      // caller.
      if (original.length() == 0
          || !original.optString("package").isEmpty()
              && original.optString("package").equals(current.optString("package"))
          || original.optString("package").isEmpty()
              && !original.optString("app").isEmpty()
              && original.optString("app").equals(current.optString("app"))) {
        for (Iterator<String> i = current.keys(); i.hasNext(); ) {
          String key = i.next();
          Object value = current.get(key);
          if (!value.toString().isEmpty()) result.put(key, value);
        }
      }
      if ("Phone".equals(result.optString("app")) && allowed(Manifest.permission.READ_CALL_LOG)) {
        long onset = result.optLong("phoneStarted", start);
        if (onset <= 0) onset = start;
        try (Cursor c =
            ScopeApp.app
                .getContentResolver()
                .query(
                    CallLog.Calls.CONTENT_URI,
                    new String[] {
                      CallLog.Calls.NUMBER,
                      CallLog.Calls.CACHED_NAME,
                      CallLog.Calls.TYPE,
                      CallLog.Calls.DATE,
                      CallLog.Calls.DURATION
                    },
                    "date >= ? AND date <= ? AND (type = 1 OR type = 2)",
                    new String[] {
                      Long.toString(Math.min(onset - 3000, start - 12 * 60 * 60 * 1000L)),
                      Long.toString(end)
                    },
                    "date DESC")) {
          JSONObject match = null;
          boolean ambiguous = false;
          while (c != null && c.moveToNext()) {
            if (c.getLong(3) + c.getLong(4) * 1000 < start - 3000) continue;
            if (match != null) {
              ambiguous = true;
              break;
            }
            match =
                new JSONObject()
                    .put("number", c.getString(0) == null ? "" : c.getString(0))
                    .put("contact", c.getString(1) == null ? "" : c.getString(1))
                    .put("direction", c.getInt(2) == 1 ? "in" : "out");
          }
          if (match != null && !ambiguous) {
            for (Iterator<String> i = match.keys(); i.hasNext(); ) {
              String key = i.next();
              result.put(key, match.get(key));
            }
            result.put("evidence", "matching call log");
          }
        }
      }
      String number = result.optString("number", "");
      if (!number.isEmpty() && allowed(Manifest.permission.READ_CONTACTS)) {
        try (Cursor c =
            ScopeApp.app
                .getContentResolver()
                .query(
                    Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null,
                    null,
                    null)) {
          if (c != null && c.moveToFirst()) result.put("contact", c.getString(0));
        }
      }
    } catch (Exception e) {
      ScopeApp.log(
          "WARN",
          "Call naming information unavailable: "
              + e.getClass().getSimpleName()
              + " (audio remains unaffected)");
    }
    return result;
  }

  public static String label(JSONObject c) {
    return CallNames.label(
        c.optString("app"),
        c.optString("direction"),
        c.optString("contact"),
        c.optString("number"));
  }
}
