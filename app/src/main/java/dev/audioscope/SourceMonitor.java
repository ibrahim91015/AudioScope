package dev.audioscope;

import java.util.*;
import java.util.concurrent.*;

/** Foreground-only metering. Monitoring never creates a recording file. */
public final class SourceMonitor {
  private final Map<String, CaptureService.Track> meters = new ConcurrentHashMap<>();
  private volatile boolean enabled;
  private final Set<String> bluetoothManual = ConcurrentHashMap.newKeySet();

  private final Map<String, String> blocked = new ConcurrentHashMap<>();

  public String blocked(String id) {
    return blocked.getOrDefault(id, "");
  }

  public boolean manual(String id) {
    return bluetoothManual.contains(id);
  }

  public void startBluetooth(String id) {
    if (CaptureService.active() || CaptureService.stopping || CaptureService.preparingAuto()) {
      blocked.put(id, "Another recording is in progress; monitoring is paused to protect it");
      Notices.error("Monitoring paused", blocked(id), "sources");
      return;
    }
    // Only one physical-input preview may own the phone/headset at a time.
    bluetoothManual.clear();
    bluetoothManual.add(id);
    enabled = true;
    ScopeApp.IO.execute(
        () -> {
          release(id);
          refresh();
        });
  }

  public void stopBluetooth(String id) {
    bluetoothManual.remove(id);
    refresh();
  }

  public void toggleBluetooth(String id) {
    if (manual(id)) stopBluetooth(id);
    else startBluetooth(id);
  }

  private volatile Set<String> wanted = new HashSet<>(SourceLayout.COMMON);

  public void setWanted(Collection<String> ids) {
    wanted = new HashSet<>(ids);
    refresh();
  }

  private final java.util.concurrent.atomic.AtomicBoolean updating =
      new java.util.concurrent.atomic.AtomicBoolean();
  private android.os.IBinder backend;

  public void enable() {
    enabled = true;
  }

  public void disable() {
    enabled = false;
    bluetoothManual.clear();
    ScopeApp.IO.execute(this::closeAll);
  }

  /**
   * Stop scheduling previews immediately; the caller reserves a headset before closing previews.
   */
  public void suspendForRecording() {
    enabled = false;
    bluetoothManual.clear();
  }

  public void refresh() {
    if (updating.compareAndSet(false, true))
      ScopeApp.IO.execute(
          () -> {
            try {
              reconcile();
            } finally {
              updating.set(false);
            }
          });
  }

  public CaptureService.Track get(String id) {
    return meters.get(id);
  }

  public synchronized void reconcile() {
    if (!enabled) return;
    if (CaptureService.active() || CaptureService.stopping || CaptureService.preparingAuto()) {
      closeAll();
      for (String id : wanted)
        if (!CaptureService.tracks.containsKey(id))
          blocked.put(id, "Another recording is in progress; monitoring is paused to protect it");
      return;
    }
    blocked.clear();
    // Finalization closes pipes sequentially. Do not reopen their source IDs
    // until all recording readers have finished, even though active() is false.
    if (CaptureService.instance != null && CaptureService.stopping) return;
    android.os.IBinder current = ScopeApp.bridge == null ? null : ScopeApp.bridge.asBinder();
    if (current != backend) {
      for (String id : new ArrayList<>(meters.keySet()))
        if (Source.get(id).privileged()) release(id);
      backend = current;
    }
    Set<String> requested = wanted;
    for (String id : new ArrayList<>(meters.keySet()))
      if (!requested.contains(id)
          || Source.get(id).manualPreview() && !manual(id)
          || !bluetoothManual.isEmpty()
              && !manual(id)
              && (Source.get(id).phoneMic()
                  || Source.get(id).bluetooth()
                  || Source.get(id).external())
          || Source.get(id).phoneMic() && !ScopeApp.prefs().getBoolean("phoneMicPreview", true))
        release(id);
    for (Source source : Source.available()) {
      if (!enabled || CaptureService.active() || CaptureService.stopping) break;
      boolean input = source.phoneMic() || source.bluetooth() || source.external();
      if (input && !bluetoothManual.isEmpty() && !manual(source.id)) {
        release(source.id);
        blocked.put(
            source.id, "Another microphone preview owns the input; stop it to resume this preview");
        continue;
      }
      if (source.phoneMic() && !ScopeApp.prefs().getBoolean("phoneMicPreview", true)) continue;
      if (!requested.contains(source.id) || source.manualPreview() && !manual(source.id)) continue;
      CaptureService.Track recording = CaptureService.tracks.get(source.id);
      if (CaptureService.active()
          && recording != null
          && (recording.running || recording.done.getCount() != 0)) continue;
      if (!meters.containsKey(source.id)) {
        CaptureService.Track meter = new CaptureService.Track(source, null);
        meters.put(source.id, meter);
        ScopeApp.IO.execute(meter::run);
      }
    }
  }

  public void stop() {
    enabled = false;
    bluetoothManual.clear();
    closeAll();
  }

  private synchronized void closeAll() {
    for (CaptureService.Track meter : meters.values()) meter.stop();
    for (CaptureService.Track meter : meters.values())
      try {
        meter.done.await(3, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    meters.clear();
  }

  public synchronized void release(String id) {
    CaptureService.Track meter = meters.remove(id);
    if (meter == null) return;
    meter.stop();
    try {
      meter.done.await(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
