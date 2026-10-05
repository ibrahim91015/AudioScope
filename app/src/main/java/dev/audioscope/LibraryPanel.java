package dev.audioscope;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import java.io.*;
import java.util.*;
import org.json.*;

public final class LibraryPanel {
  public interface Actions {
    void share(File f);

    void browse(File f);

    void details(String title, String detail);
  }

  private final MainActivity activity;
  private final Actions actions;
  private final LinearLayout host;
  private final List<PlayerRow> players = new ArrayList<>();

  private static final class PlayerRow {
    File file;
    MaterialButton play, speed;
    PlaybackWaveform wave;
    TextView elapsed;
    long duration;
  }

  public LibraryPanel(MainActivity a, Actions x) {
    activity = a;
    actions = x;
    host = Ui.column(a);
    render();
  }

  public LinearLayout view() {
    return host;
  }

  private int dp(float n) {
    return Ui.dp(activity, n);
  }

  private TextView text(String v, int s, int c) {
    return Ui.text(activity, v, s, c);
  }

  private void render() {
    host.removeAllViews();
    players.clear();
    TextView heading = text("Sessions", 20, Ui.INK);
    heading.setTypeface(null, Typeface.BOLD);
    host.addView(heading);
    host.addView(
        text(
            "Tap Play to listen. Tap or drag the waveform to jump through a track.", 14, Ui.MUTED));
    if (!CaptureService.exportStatus.isEmpty())
      host.addView(text(CaptureService.exportStatus, 13, ThemePalette.accent(activity)));
    host.addView(Ui.button(activity, "Refresh recordings", false, this::render));
    File[] folders = ScopeApp.sessions().listFiles(File::isDirectory);
    if (folders == null || folders.length == 0) {
      host.addView(
          text(
              "Your recordings will appear here and in Files → Recordings → AudioScope.",
              16,
              Ui.MUTED));
      return;
    }
    Arrays.sort(folders, Comparator.comparing(File::getName).reversed());
    for (int i = 0; i < folders.length; i++) {
      File folder = folders[i];
      if (folder.equals(CaptureService.session)) continue;
      File[] wavs = folder.listFiles((d, n) -> n.endsWith(".wav"));
      if (wavs == null || Arrays.stream(wavs).noneMatch(f -> f.length() > 44)) continue;
      LinearLayout c = Ui.card(activity);
      JSONObject manifest = new JSONObject();
      try {
        manifest = new JSONObject(Exports.read(new File(folder, "session.json")));
      } catch (Exception ignored) {
      }
      TextView title =
          text(manifest.optString("label", folder.getName().replace('_', ' ')), 17, Ui.INK);
      title.setTypeface(null, Typeface.BOLD);
      c.addView(title);
      title.setOnClickListener(v -> rename(folder));
      title.setContentDescription("Rename " + title.getText());
      int count = (int) Arrays.stream(wavs).filter(f -> f.length() > 44).count();
      c.addView(
          text(
              count
                  + " audio tracks · "
                  + (folder.getName().startsWith("self-test")
                      ? "Generated test tones"
                      : recordedAt(folder)),
              13,
              Ui.MUTED));
      LinearLayout tracks = Ui.column(activity);
      Arrays.sort(wavs, Comparator.comparing(File::getName));
      boolean expanded = players.isEmpty();
      if (expanded) for (File wav : wavs) if (wav.length() > 44) tracks.addView(player(wav));
      c.addView(tracks);
      tracks.setVisibility(expanded ? android.view.View.VISIBLE : android.view.View.GONE);
      c.addView(
          Ui.button(
              activity,
              "Show / hide tracks",
              false,
              () -> {
                if (tracks.getChildCount() == 0)
                  for (File wav : wavs) if (wav.length() > 44) tracks.addView(player(wav));
                tracks.setVisibility(
                    tracks.getVisibility() == android.view.View.VISIBLE
                        ? android.view.View.GONE
                        : android.view.View.VISIBLE);
              }));
      LinearLayout buttons = Ui.row(activity);
      MaterialButton share =
          Ui.button(
              activity,
              "Share session",
              false,
              () ->
                  ScopeApp.IO.execute(
                      () -> {
                        try {
                          File zip = Exports.zip(folder);
                          ScopeApp.MAIN.post(() -> actions.share(zip));
                        } catch (Exception e) {
                          Notices.error("Share failed", e.toString(), "library");
                        }
                      }));
      LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
      left.rightMargin = dp(4);
      buttons.addView(share, left);
      MaterialButton files =
          Ui.button(activity, "Files & details", false, () -> actions.browse(folder));
      LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
      right.leftMargin = dp(4);
      buttons.addView(files, right);
      c.addView(buttons);
      host.addView(c);
    }
  }

  private LinearLayout player(File wav) {
    PlayerRow r = new PlayerRow();
    String base = wav.getName().replace(".wav", "");
    File m4a = new File(wav.getParent(), base + ".m4a"),
        opus = new File(wav.getParent(), base + ".webm");
    r.file = m4a.isFile() ? m4a : opus.isFile() ? opus : wav;
    try (WavFile.Reader info = new WavFile.Reader(wav)) {
      r.duration = info.frames * 1000 / info.rate;
    } catch (Exception ignored) {
    }
    LinearLayout c = Ui.column(activity);
    c.setPadding(0, dp(14), 0, dp(14));
    String label = base;
    try {
      label = Source.get(base.replaceFirst("_\\d+$", "")).title;
    } catch (Exception ignored) {
    }
    c.addView(
        text(
            label + " · " + (r.file.equals(m4a) ? "M4A" : r.file.equals(opus) ? "Opus" : "WAV"),
            14,
            Ui.INK));
    LinearLayout line = Ui.row(activity);
    r.play = Ui.icon(activity, "▶", "Play " + label, () -> PlaybackService.play(activity, r.file));
    line.addView(r.play, new LinearLayout.LayoutParams(dp(48), dp(48)));
    r.wave =
        new PlaybackWaveform(
            activity,
            wav,
            fraction -> {
              int ms = (int) (r.duration * fraction);
              if (PlaybackService.instance != null && r.file.equals(PlaybackService.file))
                PlaybackService.instance.seek(ms);
              else {
                PlaybackService.play(activity, r.file);
                ScopeApp.MAIN.postDelayed(
                    new Runnable() {
                      int tries;

                      public void run() {
                        if (PlaybackService.ready
                            && r.file.equals(PlaybackService.file)
                            && PlaybackService.instance != null) PlaybackService.instance.seek(ms);
                        else if (tries++ < 30) ScopeApp.MAIN.postDelayed(this, 100);
                      }
                    },
                    100);
              }
            });
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, dp(54), 1);
    wp.leftMargin = dp(10);
    line.addView(r.wave, wp);
    c.addView(line);
    LinearLayout times = Ui.row(activity);
    r.elapsed = text("0:00", 13, Ui.MUTED);
    times.addView(r.elapsed, new LinearLayout.LayoutParams(0, -2, 1));
    times.addView(text(clock(r.duration), 13, Ui.MUTED));
    c.addView(times);
    LinearLayout tools = Ui.row(activity);
    MaterialButton back =
        Ui.button(
            activity,
            "−10 s",
            false,
            () -> {
              if (PlaybackService.instance != null && r.file.equals(PlaybackService.file))
                PlaybackService.instance.seek(PlaybackService.position - 10000);
            });
    MaterialButton forward =
        Ui.button(
            activity,
            "+10 s",
            false,
            () -> {
              if (PlaybackService.instance != null && r.file.equals(PlaybackService.file))
                PlaybackService.instance.seek(PlaybackService.position + 10000);
            });
    r.speed =
        Ui.button(
            activity,
            "1×",
            false,
            () -> {
              if (PlaybackService.instance != null) PlaybackService.instance.changeSpeed();
            });
    for (MaterialButton b : new MaterialButton[] {back, forward, r.speed}) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
      p.setMargins(dp(3), 0, dp(3), 0);
      tools.addView(b, p);
    }
    c.addView(tools);
    players.add(r);
    return c;
  }

  public void update() {
    for (PlayerRow r : players) {
      boolean current = r.file.equals(PlaybackService.file);
      r.play.setText(current && PlaybackService.playing ? "Ⅱ" : "▶");
      r.play.setContentDescription(
          (current && PlaybackService.playing ? "Pause " : "Play ") + r.file.getName());
      r.wave.progress(
          current ? (float) PlaybackService.position / Math.max(1, PlaybackService.duration) : 0);
      r.elapsed.setText(current ? clock(PlaybackService.position) : "0:00");
      r.speed.setText(PlaybackService.speed + "×");
    }
  }

  private String recordedAt(File folder) {
    try {
      java.text.SimpleDateFormat parser =
          new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
      return new java.text.SimpleDateFormat("MMM d · HH:mm", Locale.US)
          .format(parser.parse(folder.getName()));
    } catch (Exception e) {
      return folder.getName();
    }
  }

  private void rename(File folder) {
    if (CaptureService.stopping) {
      actions.details(
          "Finishing recording",
          "Wait for the saved-file notification before renaming this session.");
      return;
    }
    EditText name = new EditText(activity);
    name.setTextSize(Ui.sp(14));
    name.setTextColor(Ui.INK);
    name.setHint("Session name");
    name.setSingleLine(true);
    name.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(60)});
    new AlertDialog.Builder(activity)
        .setTitle("Rename recording")
        .setView(name)
        .setPositiveButton(
            "Save",
            (dialog, which) -> {
              String label = name.getText().toString().trim();
              if (label.isEmpty()) return;
              ScopeApp.IO.execute(
                  () -> {
                    try {
                      File metadata = new File(folder, "session.json");
                      JSONObject manifest = new JSONObject(Exports.read(metadata));
                      manifest.put("label", label);
                      JSONArray tracks = manifest.optJSONArray("tracks");
                      if (tracks != null)
                        for (int i = 0; i < tracks.length(); i++) {
                          JSONObject track = tracks.getJSONObject(i);
                          if (!track.has("publicUri")) continue;
                          android.net.Uri uri = android.net.Uri.parse(track.getString("publicUri"));
                          String ext = "wav";
                          try (android.database.Cursor cursor =
                              activity
                                  .getContentResolver()
                                  .query(
                                      uri,
                                      new String[] {
                                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME
                                      },
                                      null,
                                      null,
                                      null)) {
                            if (cursor != null && cursor.moveToFirst()) {
                              String old = cursor.getString(0);
                              ext = old.substring(old.lastIndexOf('.') + 1);
                            }
                          }
                          android.content.ContentValues values =
                              new android.content.ContentValues();
                          values.put(
                              android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                              PublicRecordings.name(
                                  folder,
                                  label,
                                  track.optString("title", track.optString("id")),
                                  i,
                                  ext));
                          activity.getContentResolver().update(uri, values, null, null);
                        }
                      try (FileWriter out = new FileWriter(metadata)) {
                        out.write(manifest.toString(2));
                      }
                      ScopeApp.MAIN.post(this::render);
                    } catch (Exception e) {
                      Notices.error("Rename needs attention", e.toString(), "library");
                    }
                  });
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  public static String clock(long ms) {
    long s = ms / 1000;
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
  }
}
