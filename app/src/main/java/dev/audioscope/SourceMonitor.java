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

  public static String reason(Source source, boolean requested) {
    CaptureService.Track existing = CaptureService.tracks.get(source.id);
    boolean same =
        CaptureService.startingSources.contains(source.id)
            || existing != null && (existing.running || existing.done.getCount() != 0);
    boolean input =
        CaptureService.startingSources.stream().anyMatch(id -> Source.get(id).physicalInput())
            || CaptureService.tracks.values().stream()
                .anyMatch(t -> t.running && t.source.physicalInput());
    return PreviewPolicy.reason(
        ScopeApp.prefs().getString("recordingPreviews", "manual"),
        requested,
        CaptureService.active(),
        CaptureService.stopping || CaptureService.preparingAuto(),
        same,
        source.physicalInput(),
        input,
        source.id.equals("remote_submix"));
  }

  public void pauseConflicts(Source source) {
    for (String id : new ArrayList<>(meters.keySet()))
      if (id.equals(source.id)
          || source.physicalInput() && Source.get(id).physicalInput()
          || id.equals("remote_submix")) release(id);
  }

  public void startBluetooth(String id) {
    String conflict = reason(Source.get(id), true);
    if (!conflict.isEmpty()) {
      blocked.put(id, conflict);
      Notices.error("Monitoring paused", blocked(id), "sources");
      return;
    }
    enabled = true;
    ScopeApp.IO.execute(
        () -> {
          synchronized (this) {
            String currentConflict = reason(Source.get(id), true);
            if (!wanted.contains(id) || !currentConflict.isEmpty()) {
              if (!currentConflict.isEmpty()) blocked.put(id, currentConflict);
              return;
            }
            release(id);
            // Publish the request only after teardown; reconciliation must not open and then
            // immediately close the new preview during this handoff.
            if (Source.get(id).physicalInput())
              bluetoothManual.removeIf(x -> Source.get(x).physicalInput());
            bluetoothManual.add(id);
          }
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
    if (CaptureService.stopping || CaptureService.preparingAuto()) {
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
        if (Source.get(id).privileged() || Source.get(id).systemSelectedMic()) release(id);
      backend = current;
    }
    Set<String> requested = wanted;
    bluetoothManual.retainAll(requested);
    boolean manualInput = bluetoothManual.stream().anyMatch(id -> Source.get(id).physicalInput());
    for (String id : new ArrayList<>(meters.keySet()))
      if (!requested.contains(id)
          || Source.get(id).manualPreview() && !manual(id)
          || manualInput
              && !manual(id)
              && (Source.get(id).phoneMic()
                  || Source.get(id).bluetooth()
                  || Source.get(id).external())
          || !reason(Source.get(id), manual(id)).isEmpty()
          || Source.get(id).phoneMic()
              && !manual(id)
              && !ScopeApp.prefs().getBoolean("phoneMicPreview", true)) release(id);
    for (Source source : Source.available()) {
      if (!enabled || CaptureService.stopping) break;
      String conflict = reason(source, manual(source.id));
      if (!conflict.isEmpty()) {
        blocked.put(source.id, conflict);
        continue;
      }
      boolean input = source.phoneMic() || source.bluetooth() || source.external();
      if (input && manualInput && !manual(source.id)) {
        release(source.id);
        blocked.put(
            source.id, "Another microphone preview owns the input; stop it to resume this preview");
        continue;
      }
      if (source.phoneMic()
          && !manual(source.id)
          && !ScopeApp.prefs().getBoolean("phoneMicPreview", true)) continue;
      if (!requested.contains(source.id) || source.manualPreview() && !manual(source.id)) continue;
      CaptureService.Track recording = CaptureService.tracks.get(source.id);
      if (CaptureService.active()
          && recording != null
          && (recording.running || recording.done.getCount() != 0)) continue;
      if (!meters.containsKey(source.id)) {
        CaptureService.Track meter = new CaptureService.Track(source, null);
        meter.previewRequested = manual(source.id);
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
