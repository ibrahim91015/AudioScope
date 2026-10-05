package dev.audioscope;

import android.service.notification.*;

/** Optional notification access used only to identify call notifications for offline naming. */
public final class CallNotificationListener extends NotificationListenerService {
  public void onListenerConnected() {
    CallContext.clear();
    for (StatusBarNotification n : getActiveNotifications()) CallContext.notification(n);
  }

  public void onListenerDisconnected() {
    CallContext.clear();
  }

  public void onNotificationPosted(StatusBarNotification n) {
    CallContext.notification(n);
  }

  public void onNotificationRemoved(StatusBarNotification n) {
    CallContext.removed(n.getKey());
  }
}
