package dev.audioscope;

/** Controlled app messages share a lifecycle; unrelated source failures remain separate. */
final class NotificationPolicy {
  static String topic(String title) {
    if (title == null) return "";
    switch (title) {
      case "Capture helper connected":
      case "Offline helper connected":
      case "Capture helper disconnected":
      case "ADB connection failed":
      case "Helper permission declined":
      case "Helper connection failed":
      case "Shevery / Shizuku service is not running":
      case "Helper did not finish connecting":
      case "Helper could not connect":
      case "AudioScope setup needs reconnecting":
        return "helper";
      case "Wireless debugging paired":
      case "Wireless setup timed out":
        return "pairing";
      case "Capture started":
      case "Call capture started":
      case "Recording paused":
      case "Recording resumed":
      case "Carrier tracks quiet · checking Wi-Fi call routes":
        return "recording";
      case "Automatic call recording armed":
      case "Automatic call recording disarmed":
      case "Open AudioScope to resume automatic calls":
        return "automation";
      case "Bluetooth microphone held":
      case "Bluetooth microphone released":
        return "bluetooth";
      case "External microphones disconnected":
      case "Microphone devices changed":
        return "devices";
      case "Debugging guard needs setup":
      case "Debugging repair needs setup":
      case "Wireless debugging restored":
      case "Debugging service repair requested":
        return "guard";
      case "Wi-Fi-free helper restart ready":
      case "Wi-Fi-free restart was not enabled":
      case "Wi-Fi-free restart disabled":
      case "Restart listener shutdown needs attention":
        return "offline";
      case "Playback failed":
      case "Audio could not play":
      case "Playback waiting for audio focus":
        return "playback";
      case "Monitoring paused":
        return "monitoring";
      case "Android silenced Communication mic":
        return "source:communication_input";
      default:
        return "message:" + title;
    }
  }

  static long lifetime(boolean problem, String title) {
    if (problem) return 0; // Keep unresolved failures until a matching recovery or dismissal.
    return title.equals("Recording saved") ? 600000 : 20000;
  }

  static String[] clearedBy(String title) {
    switch (title) {
      case "Capture helper connected":
      case "Offline helper connected":
        return new String[] {"helper", "pairing"};
      case "Capture started":
      case "Call capture started":
        return new String[] {
          "recording",
          "message:No sources selected",
          "message:Recording could not start",
          "message:No audio recording started",
          "message:All recording sources stopped"
        };
      case "Recording saved":
      case "Audio saved · export needs attention":
      case "No audio recording started":
      case "All recording sources stopped":
        return new String[] {"recording"};
      default:
        return new String[0];
    }
  }
}
