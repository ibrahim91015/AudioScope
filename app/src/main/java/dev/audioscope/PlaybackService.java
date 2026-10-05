package dev.audioscope;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.session.*;
import android.os.*;
import java.io.File;

/** One player shared by every inline card, with system media controls. */
public final class PlaybackService extends Service {
  public static volatile PlaybackService instance;
  public static volatile File file;
  public static volatile boolean playing, ready;
  public static volatile int duration, position;
  public static volatile float speed = 1;
  private MediaPlayer player;
  private MediaSession session;
  private AudioFocusRequest focus;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private int generation;

  public IBinder onBind(Intent i) {
    return null;
  }

  public void onCreate() {
    super.onCreate();
    instance = this;
    Notices.channels(this);
    session = new MediaSession(this, "AudioScope playback");
    session.setCallback(
        new MediaSession.Callback() {
          public void onPlay() {
            resume();
          }

          public void onPause() {
            pause();
          }

          public void onStop() {
            stopSelf();
          }

          public void onSeekTo(long ms) {
            seek((int) ms);
          }

          public void onFastForward() {
            seek(position + 10000);
          }

          public void onRewind() {
            seek(position - 10000);
          }
        });
    session.setActive(true);
    handler.post(tick);
  }

  public int onStartCommand(Intent i, int flags, int id) {
    if (i == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    String a = i.getAction();
    if ("STOP".equals(a)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    startForeground(71, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    if ("PLAY_FILE".equals(a)) load(new File(i.getStringExtra("file")));
    else if ("TOGGLE".equals(a)) {
      if (playing) pause();
      else resume();
    } else if ("BACK".equals(a)) seek(position - 10000);
    else if ("FORWARD".equals(a)) seek(position + 10000);
    return START_NOT_STICKY;
  }

  private void load(File selected) {
    try {
      String path = selected.getCanonicalPath();
      if (!path.startsWith(ScopeApp.sessions().getCanonicalPath() + File.separator)
          || !selected.isFile()) throw new IllegalArgumentException("Recording file unavailable");
      if (file != null && file.equals(selected) && player != null) {
        if (playing) pause();
        else resume();
        return;
      }
      generation++;
      int token = generation;
      releasePlayer();
      file = selected;
      duration = position = 0;
      ready = playing = false;
      player = new MediaPlayer();
      player.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build());
      player.setDataSource(path);
      player.setOnPreparedListener(
          p -> {
            if (token != generation) return;
            duration = p.getDuration();
            ready = true;
            resume();
          });
      player.setOnCompletionListener(
          p -> {
            playing = false;
            position = duration;
            updateNotification();
          });
      player.setOnErrorListener(
          (p, w, e) -> {
            Notices.error(
                "Audio could not play",
                "The selected file could not be decoded ("
                    + w
                    + " / "
                    + e
                    + "). Try its original WAV from the session's files.",
                "library");
            stopSelf();
            return true;
          });
      player.prepareAsync();
      updateNotification();
    } catch (Exception e) {
      Notices.error("Playback failed", e.toString(), "library");
      stopSelf();
    }
  }

  private void resume() {
    if (!ready || player == null) return;
    AudioManager audio = getSystemService(AudioManager.class);
    if (focus != null) audio.abandonAudioFocusRequest(focus);
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener(
                change -> {
                  if (change < 0) pause();
                })
            .build();
    if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      Notices.event(
          "Playback waiting for audio focus",
          "Another call or app is using audio. Try Play after it finishes.",
          "library");
      return;
    }
    if (position >= duration) player.seekTo(0);
    try {
      player.setPlaybackParams(new PlaybackParams().setSpeed(speed));
      player.start();
      playing = true;
      updateNotification();
    } catch (Exception e) {
      Notices.error("Playback failed", e.toString(), "library");
    }
  }

  private void pause() {
    if (player != null && ready)
      try {
        player.pause();
        position = player.getCurrentPosition();
      } catch (Exception ignored) {
      }
    playing = false;
    updateNotification();
  }

  public void seek(int ms) {
    if (player == null || !ready) return;
    position = Math.max(0, Math.min(duration, ms));
    player.seekTo(position, MediaPlayer.SEEK_CLOSEST);
    updateNotification();
  }

  public void changeSpeed() {
    float[] choices = {.75f, 1, 1.25f, 1.5f, 2};
    int n = 0;
    for (int i = 0; i < choices.length; i++) if (choices[i] == speed) n = i;
    speed = choices[(n + 1) % choices.length];
    if (player != null && ready) {
      boolean wasPlaying = playing;
      player.setPlaybackParams(new PlaybackParams().setSpeed(speed));
      if (!wasPlaying) player.pause();
    }
    updateNotification();
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (player != null && ready)
            try {
              position = player.getCurrentPosition();
            } catch (Exception ignored) {
            }
          handler.postDelayed(this, 250);
        }
      };

  private PendingIntent action(String a, int id) {
    return PendingIntent.getService(
        this,
        id,
        new Intent(this, PlaybackService.class).setAction(a),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private Notification notification() {
    Notification.Builder b =
        new Notification.Builder(this, "playback")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(file == null ? "Preparing playback" : file.getName())
            .setContentText(playing ? "Playing · " + speed + "×" : "Paused")
            .setContentIntent(Notices.open(this, "library", null, null, 71))
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setDeleteIntent(action("STOP", 75));
    b.addAction(
        new Notification.Action.Builder(
                android.R.drawable.ic_media_rew, "Back 10s", action("BACK", 72))
            .build());
    b.addAction(
        new Notification.Action.Builder(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Pause" : "Play",
                action("TOGGLE", 73))
            .build());
    b.addAction(
        new Notification.Action.Builder(
                android.R.drawable.ic_media_ff, "Forward 10s", action("FORWARD", 74))
            .build());
    b.addAction(new Notification.Action.Builder(null, "Close", action("STOP", 75)).build());
    if (session != null)
      b.setStyle(
          new Notification.MediaStyle()
              .setMediaSession(session.getSessionToken())
              .setShowActionsInCompactView(0, 1, 2));
    return b.build();
  }

  private void updateNotification() {
    if (session != null) {
      session.setPlaybackState(
          new PlaybackState.Builder()
              .setActions(
                  PlaybackState.ACTION_PLAY
                      | PlaybackState.ACTION_PAUSE
                      | PlaybackState.ACTION_PLAY_PAUSE
                      | PlaybackState.ACTION_SEEK_TO
                      | PlaybackState.ACTION_STOP
                      | PlaybackState.ACTION_FAST_FORWARD
                      | PlaybackState.ACTION_REWIND)
              .setState(
                  playing
                      ? PlaybackState.STATE_PLAYING
                      : ready ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_BUFFERING,
                  position,
                  speed)
              .build());
      if (file != null)
        session.setMetadata(
            new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, file.getName())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build());
    }
    getSystemService(NotificationManager.class).notify(71, notification());
  }

  public static void play(Context c, File selected) {
    c.startForegroundService(
        new Intent(c, PlaybackService.class)
            .setAction("PLAY_FILE")
            .putExtra("file", selected.getPath()));
  }

  private void releasePlayer() {
    if (player != null) {
      player.release();
      player = null;
    }
    if (focus != null) getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
  }

  public void onDestroy() {
    generation++;
    handler.removeCallbacksAndMessages(null);
    releasePlayer();
    session.release();
    instance = null;
    file = null;
    playing = ready = false;
    duration = position = 0;
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
