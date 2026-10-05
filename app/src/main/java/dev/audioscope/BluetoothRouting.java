package dev.audioscope;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.*;
import java.util.*;

/** Explicit headset ownership; ordinary previews never request a communication route. */
public final class BluetoothRouting {
  private static int leases, oldMode;
  private static AudioDeviceInfo chosenOutput;
  private static boolean transfer;

  public static boolean bluetooth(int type) {
    return MicRoutePolicy.bluetooth(type);
  }

  private static AudioManager audio() {
    return ScopeApp.app.getSystemService(AudioManager.class);
  }

  public static boolean permitted() {
    return ScopeApp.app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        == PackageManager.PERMISSION_GRANTED;
  }

  public static String key(AudioDeviceInfo d) {
    String address = d.getAddress();
    if (address.isEmpty() && bluetooth(d.getType()) && permitted()) {
      for (AudioDeviceInfo output : audio().getAvailableCommunicationDevices())
        if (same(d, output) && !output.getAddress().isEmpty()) {
          address = output.getAddress();
          break;
        }
    }
    String identity =
        address.isEmpty() ? d.getProductName() + ":" + d.getType() + ":" + d.getId() : address;
    return UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }

  public static List<AudioDeviceInfo> inputs() {
    List<AudioDeviceInfo> result = new ArrayList<>();
    if (permitted())
      for (AudioDeviceInfo d : audio().getDevices(AudioManager.GET_DEVICES_INPUTS))
        if (bluetooth(d.getType())) result.add(d);
    return result;
  }

  public static List<AudioDeviceInfo> externalInputs() {
    List<AudioDeviceInfo> result = new ArrayList<>();
    for (AudioDeviceInfo d : audio().getDevices(AudioManager.GET_DEVICES_INPUTS))
      if (MicRoutePolicy.external(d.getType())) result.add(d);
    return result;
  }

  public static List<AudioDeviceInfo> connected() {
    List<AudioDeviceInfo> result = inputs();
    if (permitted())
      for (AudioDeviceInfo d : audio().getAvailableCommunicationDevices())
        if (bluetooth(d.getType()) && result.stream().noneMatch(x -> same(x, d))) result.add(d);
    return result;
  }

  public static boolean same(AudioDeviceInfo a, AudioDeviceInfo b) {
    if (a == null || b == null) return false;
    if (a.getId() == b.getId()) return true;
    if (!a.getAddress().isEmpty() && !b.getAddress().isEmpty())
      return a.getAddress().equals(b.getAddress());
    return a.getProductName().toString().equals(b.getProductName().toString())
        && bluetooth(a.getType()) == bluetooth(b.getType());
  }

  private static AudioDeviceInfo requested(Source source) {
    List<AudioDeviceInfo> list = source.external() ? externalInputs() : connected();
    if (!source.deviceKey().isEmpty()) {
      for (AudioDeviceInfo d : list) if (key(d).equals(source.deviceKey())) return d;
      throw new IllegalStateException(
          "Requested microphone disconnected; reconnect " + source.title);
    }
    String address = ScopeApp.prefs().getString("bluetoothInputAddress", "");
    int id = ScopeApp.prefs().getInt("bluetoothInput", -1);
    for (AudioDeviceInfo d : list)
      if (!address.isEmpty() && address.equals(d.getAddress()) || d.getId() == id) return d;
    if (list.isEmpty())
      throw new IllegalStateException(
          "Bluetooth microphone disconnected or Nearby devices permission missing");
    return list.get(0);
  }

  public static String signature() {
    StringBuilder s = new StringBuilder();
    for (AudioDeviceInfo d : connected())
      s.append(key(d)).append(':').append(d.getProductName()).append(';');
    for (AudioDeviceInfo d : externalInputs())
      s.append(key(d)).append(':').append(d.getProductName()).append(';');
    return s.toString();
  }

  public static String description() {
    List<AudioDeviceInfo> list = connected();
    if (list.isEmpty())
      return permitted()
          ? "No Bluetooth microphone connected. Enable Calls for your headset in Android Bluetooth"
              + " settings."
          : "Allow Nearby devices to show Bluetooth microphones.";
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
    if (source.systemSelectedMic()) return null; // Android owns the communication input.
    if (source.phoneMic()) {
      for (AudioDeviceInfo d : audio().getDevices(AudioManager.GET_DEVICES_INPUTS))
        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return d;
      if (source.flexiblePhone()) return null;
      throw new IllegalStateException(
          "Phone microphone route unavailable; no built-in microphone is listed");
    }
    AudioDeviceInfo target = requested(source);
    if (source.external()) return target;
    for (int attempt = 0; attempt < 40; attempt++) {
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
        "Bluetooth microphone input unavailable; enable Calls for this headset, then retry");
  }

  public static synchronized boolean acquire(Source source) {
    if (!source.bluetooth()) return false;
    AudioDeviceInfo target = requested(source);
    if (!ScopeApp.prefs().getBoolean("bluetoothCommunication", true)) return false;
    if (leases > 0) {
      if (!same(target, chosenOutput))
        throw new IllegalStateException(
            "Another Bluetooth microphone is in use; stop it before changing headset");
      leases++;
      return true;
    }
    AudioManager a = audio();
    // Telecom retains authority during a managed phone call. Use its existing route.
    if (a.getMode() == AudioManager.MODE_IN_CALL) return false;
    if (a.getMode() != AudioManager.MODE_NORMAL
        && !ScopeApp.prefs().getBoolean("holdBluetoothMic", true)) return false;
    AudioDeviceInfo output = null;
    for (AudioDeviceInfo d : a.getAvailableCommunicationDevices())
      if (bluetooth(d.getType()) && same(d, target)) {
        output = d;
        break;
      }
    if (output == null)
      throw new IllegalStateException(
          "Bluetooth communication route unavailable; enable the headset Calls profile");
    oldMode = a.getMode();
    a.setMode(AudioManager.MODE_IN_COMMUNICATION);
    if (!a.setCommunicationDevice(output)) {
      a.setMode(oldMode);
      throw new IllegalStateException("Bluetooth microphone route rejected by Android");
    }
    chosenOutput = output;
    leases = 1;
    Notices.event(
        "Bluetooth microphone held",
        "The headset stays in call audio until its preview or recording stops. Music/YouTube"
            + " playback may change.",
        "sources");
    return true;
  }

  public static synchronized void maintain() {
    if (leases == 0
        || chosenOutput == null
        || !ScopeApp.prefs().getBoolean("holdBluetoothMic", true)) return;
    AudioManager a = audio();
    if (a.getMode() == AudioManager.MODE_IN_CALL) return;
    AudioDeviceInfo output = null;
    for (AudioDeviceInfo d : a.getAvailableCommunicationDevices())
      if (same(d, chosenOutput)) {
        output = d;
        break;
      }
    if (output == null)
      throw new IllegalStateException("Bluetooth microphone disconnected while active");
    if (a.getMode() != AudioManager.MODE_IN_COMMUNICATION)
      a.setMode(AudioManager.MODE_IN_COMMUNICATION);
    if (!same(a.getCommunicationDevice(), output) && !a.setCommunicationDevice(output))
      throw new IllegalStateException("Bluetooth microphone route could not be restored");
  }

  public static synchronized void reserveForRecording(String[] ids) {
    if (transfer) return;
    for (String id : ids)
      if (Source.get(id).bluetooth()) {
        transfer = acquire(Source.get(id));
        return;
      }
  }

  public static synchronized void finishTransfer() {
    if (transfer) {
      transfer = false;
      release(true);
    }
  }

  public static synchronized void release(boolean leased) {
    if (!leased || leases == 0 || --leases > 0) return;
    AudioManager a = audio();
    // Clear only the route this app acquired, not another app's replacement.
    if (same(a.getCommunicationDevice(), chosenOutput)) a.clearCommunicationDevice();
    if (a.getMode() == AudioManager.MODE_IN_COMMUNICATION) a.setMode(oldMode);
    chosenOutput = null;
    Notices.event(
        "Bluetooth microphone released",
        "AudioScope released its headset route because the last preview/recording ended.",
        "sources");
  }

  public static void verify(Source source, AudioDeviceInfo actual) {
    if (actual == null) return;
    if (source.phoneMic()
        && !MicRoutePolicy.acceptInput(
            source.systemSelectedMic(), source.flexiblePhone(), actual.getType()))
      throw new IllegalStateException(
          "Phone microphone route changed to "
              + actual.getProductName()
              + " (type "
              + actual.getType()
              + "). Bluetooth or an unsupported input is not allowed for this source");
  }

  public static boolean matches(Source source, AudioDeviceInfo actual, AudioDeviceInfo pinned) {
    if (actual == null) return false;
    if (source.bluetooth()) return bluetooth(actual.getType()) && same(actual, pinned);
    if (source.external()) return same(actual, pinned);
    return MicRoutePolicy.acceptInput(
        source.systemSelectedMic(), source.flexiblePhone(), actual.getType());
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
