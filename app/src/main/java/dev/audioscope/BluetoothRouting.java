package dev.audioscope;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.*;
import java.util.*;

/**
 * Microphone device routing is explicit; normal inputs never request a Bluetooth communication
 * route.
 */
public final class BluetoothRouting {
  private static int leases, oldMode;
  private static AudioDeviceInfo chosenOutput;

  public static boolean bluetooth(int type) {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET;
  }

  private static AudioManager audio() {
    return ScopeApp.app.getSystemService(AudioManager.class);
  }

  public static boolean permitted() {
    return ScopeApp.app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        == PackageManager.PERMISSION_GRANTED;
  }

  @android.annotation.SuppressLint("MissingPermission")
  public static List<AudioDeviceInfo> inputs() {
    List<AudioDeviceInfo> result = new ArrayList<>();
    if (!permitted()) return result;
    for (AudioDeviceInfo d : audio().getDevices(AudioManager.GET_DEVICES_INPUTS))
      if (bluetooth(d.getType())) result.add(d);
    return result;
  }

  /**
   * Connected call-capable outputs also identify headsets whose input appears only after SCO opens.
   */
  public static List<AudioDeviceInfo> connected() {
    List<AudioDeviceInfo> result = inputs();
    if (!permitted()) return result;
    for (AudioDeviceInfo d : audio().getAvailableCommunicationDevices()) {
      if (bluetooth(d.getType()) && result.stream().noneMatch(x -> same(x, d))) result.add(d);
    }
    return result;
  }

  private static boolean same(AudioDeviceInfo a, AudioDeviceInfo b) {
    if (a.getId() == b.getId()) return true;
    if (!a.getAddress().isEmpty() && !b.getAddress().isEmpty())
      return a.getAddress().equals(b.getAddress());
    return a.getProductName().toString().equals(b.getProductName().toString());
  }

  private static AudioDeviceInfo requested() {
    List<AudioDeviceInfo> list = connected();
    int preferred = ScopeApp.prefs().getInt("bluetoothInput", -1);
    String address = ScopeApp.prefs().getString("bluetoothInputAddress", "");
    for (AudioDeviceInfo d : list)
      if (!address.isEmpty() && address.equals(d.getAddress())) return d;
    for (AudioDeviceInfo d : list) if (d.getId() == preferred) return d;
    if (list.isEmpty())
      throw new IllegalStateException(
          "Bluetooth microphone disconnected or Nearby devices permission missing");
    return list.get(0);
  }

  public static String signature() {
    StringBuilder s = new StringBuilder();
    for (AudioDeviceInfo d : connected())
      s.append(d.getId()).append(':').append(d.getProductName()).append(';');
    return s.toString();
  }

  public static String description() {
    if (!permitted()) return "Allow Nearby devices to show connected Bluetooth microphone sources.";
    List<AudioDeviceInfo> list = connected();
    if (list.isEmpty())
      return "No Bluetooth microphone connected. Connect your CMF earbuds with Calls enabled in"
          + " Android Bluetooth settings.";
    StringBuilder s = new StringBuilder();
    for (AudioDeviceInfo d : list)
      s.append(d.getProductName())
          .append(" · ")
          .append(
              d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET
                  ? "LE Audio"
                  : "Bluetooth headset / SCO")
          .append('\n');
    return s.toString().trim();
  }

  public static AudioDeviceInfo select(Source source) {
    if (!source.bluetooth()) {
      for (AudioDeviceInfo d : audio().getDevices(AudioManager.GET_DEVICES_INPUTS))
        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return d;
      throw new IllegalStateException(
          "Phone microphone route unavailable. No external microphone will be used.");
    }
    AudioDeviceInfo target = requested();
    // Bluetooth's input can arrive a moment after an explicitly requested communication route.
    for (int attempt = 0; attempt < 30; attempt++) {
      for (AudioDeviceInfo d : inputs()) if (same(d, target)) return d;
      if (!busy()) break;
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new IllegalStateException(
        "Bluetooth microphone input unavailable; enable Calls for this headset, then retry"
            + " monitoring");
  }

  public static synchronized boolean acquire(Source source) {
    if (!source.bluetooth()) return false;
    AudioDeviceInfo input = requested();
    if (!ScopeApp.prefs().getBoolean("bluetoothCommunication", true)) return false;
    if (leases > 0) {
      leases++;
      return true;
    }
    AudioManager a = audio();
    // Do not seize audio mode from a phone or video call owned by another application.
    if (a.getMode() != AudioManager.MODE_NORMAL) return false;
    AudioDeviceInfo output = null;
    for (AudioDeviceInfo d : a.getAvailableCommunicationDevices())
      if (bluetooth(d.getType())
          && (d.getAddress().equals(input.getAddress())
              || d.getProductName().toString().equals(input.getProductName().toString()))) {
        output = d;
        break;
      }
    if (output == null)
      throw new IllegalStateException(
          "Bluetooth communication route unavailable; enable Calls for this headset in Android"
              + " Bluetooth settings");
    oldMode = a.getMode();
    a.setMode(AudioManager.MODE_IN_COMMUNICATION);
    if (!a.setCommunicationDevice(output)) {
      a.setMode(oldMode);
      throw new IllegalStateException("Bluetooth communication route rejected by Android");
    }
    chosenOutput = output;
    leases = 1;
    Notices.event(
        "Bluetooth microphone activated",
        "The headset may switch from media audio to call audio. Stop Bluetooth monitoring or"
            + " recording to release it.",
        "sources");
    return true;
  }

  public static synchronized void release(boolean leased) {
    if (!leased || leases == 0 || --leases > 0) return;
    AudioManager a = audio();
    a.clearCommunicationDevice();
    if (a.getMode() == AudioManager.MODE_IN_COMMUNICATION) a.setMode(oldMode);
    chosenOutput = null;
    Notices.event(
        "Bluetooth microphone released",
        "AudioScope released its headset route. Media audio can resume. If needed, pause and resume"
            + " YouTube or reconnect the earbuds.",
        "sources");
  }

  public static void verify(Source source, AudioDeviceInfo actual) {
    if (actual == null) return;
    if (source.phoneMic() && actual.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC)
      throw new IllegalStateException(
          "Phone microphone route rejected; Android selected an external microphone. Capture"
              + " stopped to protect headset media playback.");
    if (source.bluetooth() && (!bluetooth(actual.getType()) || !same(actual, requested())))
      throw new IllegalStateException(
          "Bluetooth microphone route rejected; Android selected the phone microphone. No silent"
              + " fallback is allowed.");
  }

  public static synchronized boolean busy() {
    return leases > 0;
  }

  public static synchronized void resetIdleRoute() {
    if (leases > 0)
      throw new IllegalStateException("Stop Bluetooth sources before releasing the route");
    audio().clearCommunicationDevice();
  }
}
