/* Binder delivery adapted from CallVault BinderDelivery / RecorderServer.
 * Copyright (C) 2026 The CallVault Authors. GPL-3.0-or-later + LICENSE Section 7.
 * AudioScope: independent manual sources and timestamped PCM packet transport. */
package dev.audioscope;

import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class ShellBridge extends ICaptureBridge.Stub {
  private final Map<String, PlaybackPolicy> policies = new HashMap<>();
  private final Map<String, Pump> pumps = new HashMap<>();
  private final Set<String> pinned = new HashSet<>();

  public int apiVersion() {
    check();
    return 4;
  }

  private final int allowedUid;

  public synchronized String systemSetup(String action, String value) {
    check();
    long identity = Binder.clearCallingIdentity();
    try {
      if (!action.equals("READ")) {
        if (pumps.values().stream().anyMatch(p -> p.active))
          throw new IllegalStateException("Stop protected recording and monitoring first");
        runSystem(SystemSetupPolicy.command(action, value));
      }
      JSONObject result = new JSONObject();
      result.put(
          "usb", runSystem(new String[] {"settings", "get", "global", "adb_enabled"}).trim());
      result.put(
          "wireless",
          runSystem(new String[] {"settings", "get", "global", "adb_wifi_enabled"}).trim());
      result.put("usbMode", SystemSetupPolicy.usbMode(runSystem(new String[] {"dumpsys", "usb"})));
      result.put("tcpPort", runSystem(new String[] {"getprop", "service.adb.tcp.port"}).trim());
      return result.toString();
    } catch (Throwable e) {
      throw new IllegalStateException(root(e));
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  private String runSystem(String[] command) throws Exception {
    java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Thread drain =
        new Thread(
            () -> {
              try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) > 0) {
                  if (bytes.size() + count <= 65536) bytes.write(buffer, 0, count);
                }
              } catch (IOException ignored) {
              }
            },
            "AudioScope-system-setup");
    drain.start();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      drain.join(1000);
      throw new IOException("System setup command timed out");
    }
    drain.join(1000);
    String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    if (process.exitValue() != 0)
      throw new IOException(output.isBlank() ? "System setup was rejected" : output);
    return output;
  }

  public ShellBridge() {
    allowedUid = resolveAppUid();
  }

  public ShellBridge(Context c) {
    allowedUid = c.getApplicationInfo().uid;
  }

  private ShellBridge(int uid) {
    allowedUid = uid;
  }

  private static int resolveAppUid() {
    try {
      Object b =
          Class.forName("android.os.ServiceManager")
              .getMethod("getService", String.class)
              .invoke(null, "package");
      Object pm =
          Class.forName("android.content.pm.IPackageManager$Stub")
              .getMethod("asInterface", IBinder.class)
              .invoke(null, b);
      for (Method m : pm.getClass().getMethods())
        if (m.getName().equals("getPackageUid") && m.getParameterCount() == 3)
          return (Integer) m.invoke(pm, "dev.audioscope", 0L, 0);
      throw new IllegalStateException("Package UID unavailable");
    } catch (Throwable e) {
      throw new SecurityException("Cannot resolve authorized app UID", e);
    }
  }

  private void check() {
    int u = Binder.getCallingUid();
    if (allowedUid >= 0 && u != allowedUid && u != android.os.Process.myUid())
      throw new SecurityException("Untrusted bridge caller");
  }

  public synchronized String arm(String id, int rate, int channels, int uid) {
    check();
    long identity = Binder.clearCallingIdentity();
    try {
      Source s = Source.get(id);
      if (!s.playback()) return "Input sources open on Record";
      if (!policies.containsKey(id))
        policies.put(id, new PlaybackPolicy(s.usage, rate, channels, uid));
      pinned.add(id);
      return "ARMED • " + id;
    } catch (Throwable e) {
      return "FAILED • " + root(e);
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  @android.annotation.SuppressLint(
      "MissingPermission") // Runs as shell/root; Android enforces privilege and all failures are
  // reported.
  public synchronized ParcelFileDescriptor open(String id, int rate, int channels, int uid) {
    check();
    if (pumps.containsKey(id)) {
      if (pumps.get(id).active) throw new IllegalStateException("Source already recording");
      pumps.remove(id);
    }
    long identity = Binder.clearCallingIdentity();
    try {
      Source s = Source.get(id);
      AudioRecord record;
      if (s.playback()) {
        if (!policies.containsKey(id))
          policies.put(id, new PlaybackPolicy(s.usage, rate, channels, uid));
        record = policies.get(id).sink();
      } else {
        int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int min = AudioRecord.getMinBufferSize(rate, mask, 2);
        if (min < 0) throw new IllegalArgumentException("Unsupported format");
        record = inputRecord(s.input, rate, channels);
      }
      if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
        if (record != null) record.release();
        throw new IllegalStateException("AudioRecord did not initialize");
      }
      ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
      Pump p = new Pump(record, pipe[1]);
      try {
        p.start();
        pumps.put(id, p);
        return pipe[0];
      } catch (Throwable e) {
        record.release();
        pipe[0].close();
        pipe[1].close();
        throw e;
      }
    } catch (Throwable e) {
      throw new IllegalStateException(root(e));
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  public synchronized void close(String id) {
    check();
    Pump p = pumps.remove(id);
    if (p != null) p.stop();
    if (!pinned.contains(id)) {
      PlaybackPolicy policy = policies.remove(id);
      if (policy != null) policy.close();
    }
  }

  @android.annotation.SuppressLint("MissingPermission")
  private static AudioRecord inputRecord(int preset, int rate, int channels) {
    int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
    int min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
    if (min <= 0) throw new IllegalArgumentException("Unsupported input format");
    if (preset == 7)
      return new AudioRecord.Builder()
          .setAudioSource(preset)
          .setPrivacySensitive(false)
          .setAudioFormat(
              new AudioFormat.Builder()
                  .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                  .setSampleRate(rate)
                  .setChannelMask(mask)
                  .build())
          .setBufferSizeInBytes(Math.max(min * 4, rate * channels / 5))
          .build();
    return new AudioRecord(preset, rate, mask, 2, Math.max(min * 4, rate * channels / 5));
  }

  public synchronized ParcelFileDescriptor openCommunication(
      int rate, int channels, boolean callActive, boolean recover) {
    check();
    try {
      ParcelFileDescriptor fd = open("communication_input", rate, channels, -1);
      Pump p = pumps.get("communication_input");
      p.communication = true;
      p.callActive = callActive;
      p.recover = recover;
      return fd;
    } catch (IllegalStateException e) {
      Pump old = pumps.get("communication_input");
      if (!recover || !callActive || old != null && old.active) throw e;
      long identity = Binder.clearCallingIdentity();
      try {
        AudioRecord record = inputRecord(1, rate, channels);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        Pump p = new Pump(record, pipe[1]);
        p.communication = true;
        p.callActive = true;
        p.recover = true;
        p.note = "Communication preset could not open; using call-safe helper MIC";
        try {
          p.start();
          pumps.put("communication_input", p);
          return pipe[0];
        } catch (Throwable failure) {
          record.release();
          pipe[0].close();
          pipe[1].close();
          throw failure;
        }
      } catch (Throwable failure) {
        throw new IllegalStateException(root(failure));
      } finally {
        Binder.restoreCallingIdentity(identity);
      }
    }
  }

  public synchronized String sourceStatus(String id, boolean callActive) {
    check();
    long identity = Binder.clearCallingIdentity();
    try {
      Pump p = pumps.get(id);
      if (p == null) return "{}";
      if (p.communication) p.callActive = callActive;
      return p.describe().toString();
    } catch (Exception e) {
      return "{}";
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  public synchronized void disarm() {
    check();
    for (PlaybackPolicy p : policies.values()) p.close();
    policies.clear();
    pinned.clear();
  }

  public synchronized void shutdown() {
    check();
    for (Pump p : pumps.values()) p.stop();
    pumps.clear();
    disarm();
    android.os.Process.killProcess(android.os.Process.myPid());
  }

  public void destroy() {
    shutdown();
  }

  public synchronized String inspect() {
    check();
    long identity = Binder.clearCallingIdentity();
    try {
      JSONObject j = new JSONObject();
      j.put("uid", android.os.Process.myUid());
      j.put("pid", android.os.Process.myPid());
      j.put("armed", new JSONArray(policies.keySet()));
      JSONObject running = new JSONObject();
      for (Map.Entry<String, Pump> e : pumps.entrySet())
        running.put(e.getKey(), e.getValue().describe());
      j.put("sources", running);
      JSONObject perms = new JSONObject();
      Class<?> sm = Class.forName("android.os.ServiceManager");
      Object binder = sm.getMethod("getService", String.class).invoke(null, "package");
      Object pm =
          Class.forName("android.content.pm.IPackageManager$Stub")
              .getMethod("asInterface", IBinder.class)
              .invoke(null, binder);
      for (String p :
          new String[] {
            "RECORD_AUDIO",
            "CAPTURE_AUDIO_OUTPUT",
            "CAPTURE_MEDIA_OUTPUT",
            "CAPTURE_VOICE_COMMUNICATION_OUTPUT",
            "MODIFY_AUDIO_ROUTING"
          })
        try {
          perms.put(
              p,
              (Integer)
                      pm.getClass()
                          .getMethod("checkUidPermission", String.class, int.class)
                          .invoke(pm, "android.permission." + p, android.os.Process.myUid())
                  == 0);
        } catch (Exception e) {
          perms.put(p, "unknown: " + root(e));
        }
      j.put("permissions", perms);
      j.put("audio_dump", dumpAudio());
      return j.toString(2);
    } catch (Throwable e) {
      return "Inspection failed: " + root(e);
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  private String dumpAudio() {
    try {
      java.lang.Process p =
          new ProcessBuilder("dumpsys", "audio").redirectErrorStream(true).start();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Thread t =
          new Thread(
              () -> {
                try {
                  byte[] b = new byte[4096];
                  InputStream in = p.getInputStream();
                  int n;
                  while ((n = in.read(b)) > 0) {
                    if (out.size() + n > 192000) break;
                    out.write(b, 0, n);
                  }
                } catch (Exception ignored) {
                }
              });
      t.start();
      if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
      t.join(1000);
      return out.toString("UTF-8");
    } catch (Exception e) {
      return e.toString();
    }
  }

  static String root(Throwable e) {
    while (e.getCause() != null && e.getCause() != e) e = e.getCause();
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  private static final class Packet {
    final byte[] data;
    final long time, frame;

    Packet(byte[] d, long t, long f) {
      data = d;
      time = t;
      frame = f;
    }
  }

  private static final class Pump {
    volatile AudioRecord record;
    final int rate, channels;
    volatile boolean communication, callActive, recover;
    volatile String note = "";
    volatile int preset;
    final ParcelFileDescriptor fd;
    final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(100);
    volatile boolean active = true;
    volatile long dropped, frames;
    volatile String error = "";
    Thread reader, writer;

    Pump(AudioRecord r, ParcelFileDescriptor f) {
      record = r;
      rate = r.getSampleRate();
      channels = r.getChannelCount();
      preset = r.getAudioSource();
      fd = f;
    }

    void start() {
      record.startRecording();
      if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IllegalStateException("Not recording");
      reader =
          new Thread(
              () -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                byte[] b = new byte[Math.max(4096, rate * channels * 2 / 50)];
                long zeroSince = 0, silencedSince = 0, frameBase = 0;
                int restarts = 0;
                try {
                  while (active) {
                    int n = record.read(b, 0, b.length);
                    if (!active) break;
                    if (n == AudioRecord.ERROR_DEAD_OBJECT
                        && communication
                        && recover
                        && restarts++ < 3) {
                      record.release();
                      record = inputRecord(callActive ? 1 : preset, rate, channels);
                      record.startRecording();
                      preset = record.getAudioSource();
                      frameBase = frames;
                      note = "Microphone was lost; helper reopened it within the same track";
                      zeroSince = silencedSince = 0;
                      continue;
                    }
                    if (n < 0) throw new IOException("AudioRecord.read=" + n);
                    if (n == 0) continue;
                    if (communication && recover && callActive && preset == 7) {
                      long now = android.os.SystemClock.elapsedRealtime();
                      boolean zero = true;
                      for (int i = 0; i < n; i++)
                        if (b[i] != 0) {
                          zero = false;
                          break;
                        }
                      zeroSince = zero ? zeroSince == 0 ? now : zeroSince : 0;
                      AudioRecordingConfiguration config = record.getActiveRecordingConfiguration();
                      boolean silenced = config != null && config.isClientSilenced();
                      silencedSince = silenced ? silencedSince == 0 ? now : silencedSince : 0;
                      if (silencedSince != 0 && now - silencedSince >= 1500
                          || zeroSince != 0 && now - zeroSince >= 5000) {
                        record.stop();
                        record.release();
                        record = inputRecord(1, rate, channels);
                        record.startRecording();
                        preset = 1;
                        frameBase = frames;
                        note =
                            "Call policy silenced/zero-filled VOICE_COMMUNICATION; using call-safe"
                                + " helper MIC";
                        android.util.Log.i("AudioScopeDaemon", note);
                        continue;
                      }
                    }
                    long first = frames;
                    frames += n / (channels * 2);
                    AudioTimestamp ts = new AudioTimestamp();
                    long time = System.nanoTime() - n * 1000000000L / (rate * channels * 2);
                    if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC)
                        == AudioRecord.SUCCESS)
                      time =
                          ts.nanoTime + (first - frameBase - ts.framePosition) * 1000000000L / rate;
                    Packet p = new Packet(Arrays.copyOf(b, n), time, first);
                    if (!queue.offer(p)) dropped++;
                  }
                } catch (Throwable e) {
                  error = root(e);
                } finally {
                  active = false;
                }
              },
              "capture-reader");
      writer =
          new Thread(
              () -> {
                try (DataOutputStream out =
                    new DataOutputStream(
                        new BufferedOutputStream(
                            new ParcelFileDescriptor.AutoCloseOutputStream(fd), 32768))) {
                  out.writeInt(0x41534350);
                  out.writeInt(rate);
                  out.writeInt(channels);
                  out.flush();
                  while (active || !queue.isEmpty()) {
                    Packet p = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (p == null) continue;
                    out.writeLong(p.time);
                    out.writeLong(p.frame);
                    out.writeInt(p.data.length);
                    out.write(p.data);
                    out.flush();
                  }
                } catch (Throwable e) {
                  error = root(e);
                } finally {
                  active = false;
                  try {
                    record.stop();
                  } catch (Exception ignored) {
                  }
                  record.release();
                }
              },
              "capture-pipe");
      reader.start();
      writer.start();
    }

    void stop() {
      active = false;
      try {
        record.stop();
      } catch (Exception ignored) {
      }
      try {
        reader.join(1500);
        writer.join(2000);
        if (writer.isAlive()) fd.close();
      } catch (Exception ignored) {
      }
    }

    JSONObject describe() throws JSONException {
      JSONObject j = new JSONObject();
      j.put("frames", frames);
      j.put("queue_dropped", dropped);
      j.put("error", error);
      j.put("active", active);
      j.put(
          "preset",
          preset == 7
              ? "VOICE_COMMUNICATION (7)"
              : preset == 1 ? "MIC (1) · call-safe input" : "Input preset " + preset);
      j.put("note", note);
      try {
        AudioRecordingConfiguration config = record.getActiveRecordingConfiguration();
        j.put("silenced", config != null && config.isClientSilenced());
        AudioDeviceInfo d = record.getRoutedDevice();
        if (d == null && config != null) d = config.getAudioDevice();
        j.put("device", BluetoothRouting.inputDescription(d));
      } catch (Exception ignored) {
      }
      return j;
    }
  }

  public static void main(String[] args) {
    try {
      if (args.length != 1) throw new IllegalArgumentException("Expected application UID");
      Looper.prepareMainLooper();
      ShellBridge bridge = new ShellBridge(Integer.parseInt(args[0]));
      deliver(bridge);
      new Handler()
          .postDelayed(
              new Runnable() {
                public void run() {
                  try {
                    deliver(bridge);
                  } catch (Throwable ignored) {
                  }
                  new Handler().postDelayed(this, 15000);
                }
              },
              15000);
      Looper.loop();
    } catch (Throwable e) {
      android.util.Log.e("AudioScopeDaemon", root(e), e);
    }
  }

  private static void deliver(IBinder b) throws Exception {
    String authority = "dev.audioscope.bridge";
    Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
    Method get = null;
    for (Method m : am.getClass().getMethods())
      if (m.getName().equals("getContentProviderExternal") && m.getParameterCount() == 4) get = m;
    if (get == null) throw new NoSuchMethodException("getContentProviderExternal");
    Object holder = get.invoke(am, authority, 0, null, authority);
    if (holder == null) throw new IllegalStateException("Provider unavailable");
    try {
      Object provider = holder.getClass().getField("provider").get(holder);
      Bundle extra = new Bundle();
      extra.putBinder("bridge", b);
      for (Method m : provider.getClass().getMethods())
        if (m.getName().equals("call")
            && m.getParameterCount() == 5
            && m.getParameterTypes()[0].getName().equals("android.content.AttributionSource")) {
          Class<?> cls = Class.forName("android.content.AttributionSource$Builder");
          Object builder = cls.getConstructor(int.class).newInstance(android.os.Process.myUid());
          cls.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
          Object attribution = cls.getMethod("build").invoke(builder);
          m.invoke(provider, attribution, authority, "deliver", null, extra);
          return;
        }
      throw new NoSuchMethodException("IContentProvider.call");
    } finally {
      for (Method m : am.getClass().getMethods())
        if (m.getName().equals("removeContentProviderExternal") && m.getParameterCount() == 2)
          m.invoke(am, authority, null);
    }
  }
}
