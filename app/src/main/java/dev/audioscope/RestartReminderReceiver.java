package dev.audioscope;

import android.content.*;

/** No automatic microphone foreground-service start from a boot broadcast. */
public final class RestartReminderReceiver extends BroadcastReceiver {
  public void onReceive(Context context, Intent intent) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
    HelperConnectionService.start(context);
    if (DebuggingSettings.embedded() && ScopeApp.prefs().getBoolean("enforceWireless", false))
      try {
        context.startForegroundService(new Intent(context, DebuggingGuardService.class));
      } catch (RuntimeException e) {
        ScopeApp.log("WARN", "Open AudioScope to resume the debugging guard");
      }
    if (!ScopeApp.prefs().getBoolean("restartReminder", true)) return;
    if (!ScopeApp.prefs().contains("helperTransport")
        && !ScopeApp.prefs().getBoolean("autoCalls", false)) return;
    Notices.error(
        "AudioScope setup needs reconnecting",
        "AudioScope is retrying your saved helper connection. If needed, join Wi-Fi and enable"
            + " Wireless debugging; reboot does not normally require another pairing code."
            + " Open AudioScope to re-arm automatic call recording. Android blocks microphone"
            + " service starts directly from boot.",
        "settings");
  }
}
