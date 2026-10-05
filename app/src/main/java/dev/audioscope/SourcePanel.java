package dev.audioscope;

import android.app.AlertDialog;
import android.content.ClipData;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import java.util.*;

/** Source cards share a layout model, but only displayed routes acquire meters. */
public final class SourcePanel {
  public interface Actions {
    void record(String[] ids);

    void details(String title, String detail);
  }

  private final MainActivity activity;
  private final SourceMonitor monitor;
  private final Actions actions;
  private final LinearLayout host;
  private final Map<String, Row> rows = new LinkedHashMap<>();
  private boolean editing, expanded;
  private MaterialButton all;
  private TextView suggestion;
  private List<String> recommended = new ArrayList<>();

  private static final class Row {
    MaterialButton record;
    TextView status;
    WaveformView wave;
    Spinner format;
    double score = -120;
  }

  public SourcePanel(MainActivity a, SourceMonitor m, Actions x) {
    activity = a;
    monitor = m;
    actions = x;
    host = Ui.column(a);
    expanded = ScopeApp.prefs().getBoolean("hiddenExpanded", false);
    render();
  }

  public LinearLayout view() {
    return host;
  }

  private int dp(float n) {
    return Ui.dp(activity, n);
  }

  private TextView text(String s, int size, int color) {
    return Ui.text(activity, s, size, color);
  }

  private void render() {
    host.removeAllViews();
    rows.clear();
    int accent = ThemePalette.accent(activity);
    LinearLayout heading = Ui.row(activity);
    TextView title = text("Sources", 20, Ui.INK);
    title.setTypeface(null, Typeface.BOLD);
    heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(
        Ui.icon(
            activity,
            editing ? "✓" : "✎",
            editing ? "Finish editing sources" : "Edit source order and visibility",
            () -> {
              editing = !editing;
              render();
            }),
        new LinearLayout.LayoutParams(dp(48), dp(48)));
    host.addView(heading);
    String[] views = {"Detailed", "Comfortable", "Compact", "Mini"};
    int mode = ScopeApp.prefs().getInt("sourceView", 1);
    Spinner view = Ui.spinner(activity, views, mode);
    view.setContentDescription("Source view density");
    view.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
            if (i != ScopeApp.prefs().getInt("sourceView", 1)) {
              ScopeApp.prefs().edit().putInt("sourceView", i).apply();
              render();
            }
          }
        });
    LinearLayout controls = Ui.row(activity);
    all =
        Ui.icon(
            activity,
            "●",
            "Record all shown sources",
            () -> {
              if (CaptureService.active()) CaptureService.instance.stopSession();
              else actions.record(rows.keySet().toArray(new String[0]));
            });
    all.setTextColor(0xff938d9c);
    controls.addView(all, new LinearLayout.LayoutParams(dp(44), dp(44)));
    TextView label = text("Record all", 14, Ui.INK);
    label.setPadding(dp(8), 0, 0, 0);
    controls.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    controls.addView(
        view,
        new LinearLayout.LayoutParams(
            dp(activity.getResources().getConfiguration().fontScale > 1.2f ? 204 : 144), dp(44)));
    host.addView(controls);
    host.addView(
        text(
            editing
                ? "Drag handles to reorder. Hide releases the live monitor."
                : "Tap a waveform for details. Hidden routes stay asleep.",
            13,
            Ui.MUTED));
    com.google.android.material.materialswitch.MaterialSwitch auto =
        new com.google.android.material.materialswitch.MaterialSwitch(activity);
    auto.setText("Auto · recommend a source");
    auto.setTextSize(Ui.sp(14));
    auto.setTextColor(Ui.INK);
    auto.setChecked(ScopeApp.prefs().getBoolean("autoSuggest", false));
    auto.setOnCheckedChangeListener(
        (v, on) -> {
          ScopeApp.prefs().edit().putBoolean("autoSuggest", on).apply();
          render();
        });
    host.addView(auto);
    suggestion = text("Listening for an active source…", 13, accent);
    suggestion.setVisibility(auto.isChecked() ? View.VISIBLE : View.GONE);
    host.addView(suggestion);
    if (auto.isChecked())
      host.addView(
          Ui.button(
              activity,
              "Record recommendation",
              true,
              () -> {
                if (recommended.isEmpty()) {
                  actions.details(
                      "No signal to recommend",
                      "Start your call or audio, then wait for the live meters. AudioScope only"
                          + " recommends a source after measuring real PCM above the silence"
                          + " threshold.");
                  return;
                }
                actions.record(recommended.toArray(new String[0]));
              }));
    Set<String> hidden = SourceLayout.hidden();
    List<Source> visible = new ArrayList<>(), concealed = new ArrayList<>();
    for (Source s : SourceLayout.ordered()) (hidden.contains(s.id) ? concealed : visible).add(s);
    addCards(visible, mode, false);
    MaterialButton fold =
        Ui.button(
            activity,
            (expanded ? "▾" : "▸") + " Hidden sources · " + concealed.size(),
            false,
            () -> {
              expanded = !expanded;
              ScopeApp.prefs().edit().putBoolean("hiddenExpanded", expanded).apply();
              render();
            });
    host.addView(fold);
    if (expanded) {
      host.addView(
          text(
              "Expanded routes are monitored while this tab is visible. Collapse to release them."
                  + " Some advanced routes compete for the same input.",
              13,
              Ui.MUTED));
      addCards(concealed, mode, true);
    }
    host.addView(
        Ui.button(
            activity,
            "Retry live monitors",
            false,
            () -> {
              monitor.disable();
              ScopeApp.IO.execute(
                  () -> {
                    monitor.stop();
                    ScopeApp.MAIN.post(
                        () -> {
                          monitor.enable();
                          monitor.refresh();
                        });
                  });
            }));
    monitor.setWanted(rows.keySet());
    monitor.refresh();
  }

  private void addCards(List<Source> sources, int mode, boolean hidden) {
    if (mode != 3) {
      for (Source s : sources) host.addView(card(s, mode, hidden));
      return;
    }
    for (int i = 0; i < sources.size(); i += 2) {
      LinearLayout pair = Ui.row(activity);
      pair.setGravity(Gravity.TOP);
      LinearLayout left = card(sources.get(i), mode, hidden);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      lp.rightMargin = dp(4);
      lp.bottomMargin = dp(8);
      pair.addView(left, lp);
      if (i + 1 < sources.size()) {
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
        rp.leftMargin = dp(4);
        rp.bottomMargin = dp(8);
        pair.addView(card(sources.get(i + 1), mode, hidden), rp);
      } else pair.addView(new android.view.View(activity), new LinearLayout.LayoutParams(0, 1, 1));
      host.addView(pair);
    }
  }

  private LinearLayout card(Source s, int mode, boolean hidden) {
    Row r = new Row();
    rows.put(s.id, r);
    LinearLayout c = Ui.card(activity);
    c.setPadding(
        dp(mode == 2 ? 6 : 12),
        dp(mode == 2 ? 3 : mode == 1 ? 6 : 10),
        dp(mode == 2 ? 6 : 12),
        dp(mode == 2 ? 3 : mode == 1 ? 6 : 10));
    c.setOnLongClickListener(
        v -> {
          showDetails(s);
          return true;
        });
    if (editing) {
      LinearLayout tools = Ui.row(activity);
      MaterialButton drag =
          Ui.icon(
              activity,
              "☰",
              "Drag " + s.title,
              () ->
                  actions.details(
                      "Reorder sources",
                      "Hold this handle and drag it onto another card. You can also use Move up"
                          + " below."));
      drag.setOnLongClickListener(
          v -> {
            v.startDragAndDrop(
                ClipData.newPlainText("source", s.id), new View.DragShadowBuilder(v), s.id, 0);
            return true;
          });
      tools.addView(drag, new LinearLayout.LayoutParams(dp(44), dp(44)));
      MaterialButton toggle =
          Ui.button(
              activity,
              hidden ? "Show" : "Hide",
              false,
              () -> {
                SourceLayout.hide(s.id, !hidden);
                render();
              });
      tools.addView(toggle, new LinearLayout.LayoutParams(0, -2, 1));
      MaterialButton up =
          Ui.icon(
              activity,
              "↑",
              "Move " + s.title + " up",
              () -> {
                List<Source> order = SourceLayout.ordered();
                for (int i = 1; i < order.size(); i++)
                  if (order.get(i).id.equals(s.id)) {
                    SourceLayout.move(s.id, order.get(i - 1).id);
                    render();
                    break;
                  }
              });
      tools.addView(up, new LinearLayout.LayoutParams(dp(44), dp(44)));
      c.addView(tools);
      c.setOnDragListener(
          (v, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED)
              return event.getLocalState() instanceof String;
            if (event.getAction() == DragEvent.ACTION_DROP) {
              SourceLayout.move((String) event.getLocalState(), s.id);
              render();
              return true;
            }
            return true;
          });
    }
    LinearLayout line = Ui.row(activity);
    r.record = Ui.icon(activity, "●", "Record " + s.title, () -> record(s));
    r.record.setTextColor(0xff938d9c);
    r.record.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, mode == 2 ? 14 : 20);
    int control = mode == 2 ? 32 : 44;
    line.addView(r.record, new LinearLayout.LayoutParams(dp(control), dp(control)));
    if (mode == 2) {
      TextView name = text(shortName(s), 12, Ui.INK);
      name.setSingleLine(true);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      name.setPadding(dp(6), 0, dp(4), 0);
      line.addView(name, new LinearLayout.LayoutParams(dp(108), -2));
      name.setOnClickListener(v -> showDetails(s));
    }
    r.wave = new WaveformView(activity, ThemePalette.accent(activity));
    LinearLayout.LayoutParams wp =
        new LinearLayout.LayoutParams(
            0, dp(mode == 2 ? 20 : mode == 3 ? 28 : mode == 1 ? 32 : 44), 1);
    wp.leftMargin = dp(8);
    line.addView(r.wave, wp);
    r.wave.setContentDescription(s.title + " live waveform; tap for source details");
    r.wave.setOnClickListener(v -> showDetails(s));
    c.addView(line);
    if (mode != 2) {
      LinearLayout info = Ui.row(activity);
      TextView name = text(mode == 0 ? s.title : shortName(s), mode == 3 ? 13 : 15, Ui.INK);
      name.setTypeface(null, Typeface.BOLD);
      info.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      name.setOnClickListener(v -> showDetails(s));
      if (mode != 3) {
        r.format = format(s);
        info.addView(r.format, new LinearLayout.LayoutParams(dp(115), dp(44)));
      }
      c.addView(info);
      if (mode == 3) {
        r.format = format(s);
        c.addView(r.format, new LinearLayout.LayoutParams(-1, dp(40)));
      }
      r.status = text("Opening live monitor…", 13, Ui.MUTED);
      r.status.setMaxLines(mode == 0 ? 3 : 1);
      r.status.setEllipsize(android.text.TextUtils.TruncateAt.END);
      r.status.setOnClickListener(v -> showDetails(s));
      c.addView(r.status);
      if (mode == 0) c.addView(text(s.detail, 13, Ui.MUTED));
    }
    // Compact mode keeps its waveform thin; all formats and errors remain in the detail sheet.
    if (mode == 2 && activity.getResources().getConfiguration().fontScale > 1.2f)
      line.setMinimumHeight(dp(44));
    return c;
  }

  private Spinner format(Source s) {
    Spinner spinner =
        Ui.spinner(activity, Formats.SOURCE_LABELS, Formats.index(Formats.current(s.id)));
    spinner.setContentDescription(s.title + " audio format");
    spinner.setEnabled(!CaptureService.active());
    final String initial = Formats.current(s.id);
    spinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          String previous = initial;

          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
            String value = Formats.VALUES[i];
            if (!value.equals(previous)) {
              ScopeApp.prefs().edit().putString("format_" + s.id, value).apply();
              previous = value;
            }
          }
        });
    return spinner;
  }

  private String shortName(Source s) {
    switch (s.id) {
      case "voice_playback":
        return "VoIP / Wi-Fi call";
      case "voice_call":
        return "Carrier · both";
      case "uplink":
        return "Carrier · local";
      case "downlink":
        return "Carrier · remote";
      case "media":
        return "Media playback";
      default:
        return s.title;
    }
  }

  private CaptureService.Track track(String id) {
    CaptureService.Track t = CaptureService.tracks.get(id);
    return CaptureService.active() && t != null ? t : monitor.get(id);
  }

  private void record(Source s) {
    CaptureService.Track t = track(s.id);
    if (t != null && !t.error.isEmpty() && !t.running) {
      Notices.problem(s, t.error, true);
      showDetails(s);
      return;
    }
    if (CaptureService.active()) {
      CaptureService.Track saved = CaptureService.tracks.get(s.id);
      if (saved != null && saved.running) {
        ScopeApp.IO.execute(
            () -> {
              saved.stop();
              try {
                saved.done.await(6, java.util.concurrent.TimeUnit.SECONDS);
              } catch (Exception ignored) {
              }
              if (CaptureService.active()
                  && CaptureService.tracks.values().stream().noneMatch(x -> x.running))
                CaptureService.instance.stopSession();
            });
      } else
        ScopeApp.IO.execute(
            () -> {
              monitor.release(s.id);
              if (CaptureService.active()) CaptureService.instance.startTrack(s.id);
            });
    } else actions.record(new String[] {s.id});
  }

  private void showDetails(Source s) {
    CaptureService.Track t = track(s.id);
    LinearLayout content = Ui.column(activity);
    content.setPadding(dp(20), dp(12), dp(20), dp(16));
    String detail =
        s.detail
            + "\n\n"
            + (t == null
                ? "Not being monitored"
                : t.error.isEmpty()
                    ? String.format(
                        Locale.US,
                        "%s\nLevel %.1f dBFS · peak %.0f%%\n%,d frames · %d dropped chunks",
                        t.state,
                        t.db,
                        t.peak * 100,
                        t.frames,
                        t.dropped)
                    : CaptureProblem.of(t.error).details(t.error));
    content.addView(text(detail, 14, Ui.INK));
    content.addView(text("Save format for this source", 13, Ui.MUTED));
    content.addView(format(s));
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(content);
    new AlertDialog.Builder(activity)
        .setTitle(s.title)
        .setView(scroll)
        .setPositiveButton(
            "Retry record",
            (d, w) -> {
              if (CaptureService.active())
                ScopeApp.IO.execute(
                    () -> {
                      monitor.release(s.id);
                      CaptureService.instance.startTrack(s.id);
                    });
              else actions.record(new String[] {s.id});
            })
        .setNeutralButton("Close", null)
        .show();
  }

  public void update() {
    all.setTextColor(CaptureService.active() ? Ui.RED : 0xff938d9c);
    double strongest = -120;
    String best = null;
    for (Map.Entry<String, Row> e : rows.entrySet()) {
      CaptureService.Track t = track(e.getKey());
      Row r = e.getValue();
      boolean recording = CaptureService.active() && t != null && !t.monitor && t.running;
      r.record.setTextColor(recording ? Ui.RED : 0xff938d9c);
      r.record.setContentDescription(
          (recording ? "Stop " : "Record ") + Source.get(e.getKey()).title);
      r.wave.update(t);
      if (r.format != null) r.format.setEnabled(!CaptureService.active());
      String status =
          t == null
              ? "Waiting for monitor"
              : !t.error.isEmpty()
                  ? CaptureProblem.of(t.error).title + " · tap for help"
                  : String.format(
                      Locale.US,
                      "%s · %.0f dBFS",
                      recording
                          ? "Recording"
                          : t.db > ScopeApp.prefs().getInt("silenceDb", -60)
                              ? "Signal detected"
                              : "No signal yet",
                      t.db);
      if (r.status != null && !status.contentEquals(r.status.getText())) {
        r.status.setText(status);
        r.status.setTextColor(t != null && !t.error.isEmpty() ? Ui.RED : Ui.MUTED);
      }
      r.record.setTooltipText(status);
      r.score = t != null && t.running && t.error.isEmpty() ? r.score * .75 + t.db * .25 : -120;
      if (r.score > strongest && t != null && t.nonzero > 0) {
        strongest = r.score;
        best = e.getKey();
      }
    }
    recommended.clear();
    if (best != null && strongest > ScopeApp.prefs().getInt("silenceDb", -60)) {
      recommended.add(best);
      if (Source.get(best).playback() && rows.containsKey("mic")) {
        CaptureService.Track mic = track("mic");
        if (mic != null && mic.running && mic.error.isEmpty()) recommended.add("mic");
      }
      suggestion.setText(
          "Suggested: "
              + Source.get(best).title
              + " · "
              + Math.round(strongest)
              + " dBFS"
              + (recommended.size() > 1 ? " + microphone" : ""));
    } else suggestion.setText("No active signal yet. Start a call or play audio.");
  }
}
