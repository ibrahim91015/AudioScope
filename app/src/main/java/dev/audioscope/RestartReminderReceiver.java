package dev.audioscope;

import android.content.*;

/** No automatic microphone foreground-service start from a boot broadcast. */
public final class RestartReminderReceiver extends BroadcastReceiver {
  public void onReceive(Context context, Intent intent) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
    if (!ScopeApp.prefs().getBoolean("restartReminder", true)) return;
    if (!ScopeApp.prefs().contains("helperTransport")
        && !ScopeApp.prefs().getBoolean("autoCalls", false)) return;
    Notices.error(
        "AudioScope setup needs reconnecting",
        "Android restarted or updated the app. Reconnect your capture helper, then re-arm automatic"
            + " calls from AudioScope. Wi-Fi-free helper restart must be enabled again after a"
            + " reboot. A setup reminder does not mean automatic recording is running.",
        "settings");
  }
}
