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

    void recordAll(String[] ids);

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
  private String bluetoothSignature = BluetoothRouting.signature();
  private long lastDevices;
  private List<String> recommended = new ArrayList<>();
  private final SourceActivityOrder activityOrder = new SourceActivityOrder();
  private LinearLayout visibleList;
  private final Map<String, View> visibleCards = new LinkedHashMap<>();
  private List<Source> visibleSources = new ArrayList<>();
  private List<String> displayedOrder = new ArrayList<>();
  private int density;
  private long lastMonitorRefresh;

  private static final class Row {
    MaterialButton record, preview;
    TextView status, route, name;
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
    visibleCards.clear();
    displayedOrder.clear();
    recommended.clear();
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
    density = mode;
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
              java.util.List<String> ids = nonHidden();
              if (CaptureService.allRecording(ids)) CaptureService.instance.stopSession();
              else if (CaptureService.active()) {
                CaptureService.recordAllRequested = true;
                ScopeApp.IO.execute(
                    () -> {
                      for (String id : ids) {
                        CaptureService.Track t = CaptureService.tracks.get(id);
                        if (t == null || !t.running) {
                          monitor.release(id);
                          if (CaptureService.active()) CaptureService.instance.startTrack(id);
                        }
                      }
                    });
              } else actions.recordAll(ids.toArray(new String[0]));
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
    auto.setText("Auto · recommend active sources");
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
              "Record recommended sources",
              true,
              () -> {
                if (recommended.isEmpty()) {
                  actions.details(
                      "No signal to recommend",
                      "Start your call or audio, then wait for the live meters. AudioScope only"
                          + " recommends sources after measuring real PCM above the silence"
                          + " threshold.");
                  return;
                }
                List<String> ids = new ArrayList<>(recommended);
                if (CaptureService.active()) {
                  ScopeApp.IO.execute(
                      () -> {
                        for (String id : ids) {
                          CaptureService.Track t = CaptureService.tracks.get(id);
                          if (t == null || !t.running) {
                            monitor.release(id);
                            if (CaptureService.active()) CaptureService.instance.startTrack(id);
                          }
                        }
                      });
                } else actions.record(ids.toArray(new String[0]));
              }));
    Set<String> hidden = SourceLayout.hidden();
    List<Source> visible = new ArrayList<>(), concealed = new ArrayList<>();
    for (Source s : SourceLayout.ordered()) (hidden.contains(s.id) ? concealed : visible).add(s);
    visibleSources = visible;
    visibleList = Ui.column(activity);
    host.addView(visibleList);
    for (Source source : visible) visibleCards.put(source.id, card(source, mode, false));
    layoutVisible(
        visible.stream().map(x -> x.id).collect(java.util.stream.Collectors.toList()), false);
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
              "Playback previews run while visible and idle. Microphone presets start explicitly;"
                  + " Any Phone Mic is the default phone preview. All previews pause during"
                  + " recording.",
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
    Ui.enter(host);
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
        dp(mode == 2 ? 1 : mode == 1 ? 6 : 10),
        dp(mode == 2 ? 6 : 12),
        dp(mode == 2 ? 1 : mode == 1 ? 6 : 10));
    if (mode == 2) ((LinearLayout.LayoutParams) c.getLayoutParams()).bottomMargin = dp(2);
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
    int control = mode == 2 ? 28 : 44;
    line.addView(r.record, new LinearLayout.LayoutParams(dp(control), dp(control)));
    if (mode == 2) {
      TextView name = text(shortName(s), 12, Ui.INK);
      r.name = name;
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
    {
      r.preview =
          Ui.button(
              activity,
              "Start monitoring",
              false,
              () -> {
                CaptureService.Track meter = monitor.get(s.id);
                if (monitor.manual(s.id) && meter != null && meter.running)
                  monitor.stopBluetooth(s.id);
                else {
                  monitor.startBluetooth(s.id);
                  ScopeApp.MAIN.postDelayed(
                      () -> {
                        CaptureService.Track started = monitor.get(s.id);
                        if (started != null && !started.error.isEmpty())
                          Notices.problem(s, started.error, true);
                      },
                      1200);
                }
              });
      line.addView(r.preview, new LinearLayout.LayoutParams(0, -2, 1));
      boolean pausedPreview = s.manualPreview() || !SourceMonitor.reason(s, false).isEmpty();
      r.preview.setVisibility(pausedPreview ? View.VISIBLE : View.GONE);
      r.wave.setVisibility(pausedPreview ? View.GONE : View.VISIBLE);
    }
    line.addView(r.wave, wp);
    r.wave.setContentDescription(s.title + " live waveform; tap for source details");
    r.wave.setOnClickListener(v -> showDetails(s));
    c.addView(line);
    if (mode != 2) {
      LinearLayout info = Ui.row(activity);
      TextView name = text(mode == 0 ? s.title : shortName(s), mode == 3 ? 13 : 15, Ui.INK);
      r.name = name;
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
      if (mode == 0) {
        c.addView(text(s.detail, 13, Ui.MUTED));
        if (s.systemSelectedMic()) {
          r.route = text("Actual communication input appears when this source starts.", 13, Ui.INK);
          c.addView(r.route);
        }
      }
    }
    if (s.bluetooth()) c.addView(text("May interrupt non-call media audio.", 12, Ui.MUTED));
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
    return CaptureService.active() && t != null && (t.running || monitor.get(id) == null)
        ? t
        : monitor.get(id);
  }

  private void record(Source s) {
    CaptureService.Track t = track(s.id);
    if (t != null && !t.error.isEmpty() && !t.running && !t.monitor) {
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
                ? monitor.blocked(s.id).isEmpty() ? "Not being monitored" : monitor.blocked(s.id)
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

  private List<String> nonHidden() {
    List<String> result = new ArrayList<>();
    Set<String> hidden = SourceLayout.hidden();
    for (Source source : SourceLayout.ordered())
      if (!hidden.contains(source.id)) result.add(source.id);
    return result;
  }

  private void layoutVisible(List<String> order, boolean animate) {
    if (order.equals(displayedOrder)) return;
    Map<String, int[]> before = new HashMap<>();
    if (animate && Ui.motion())
      for (String id : displayedOrder) {
        View card = visibleCards.get(id);
        int[] xy = new int[2];
        card.getLocationOnScreen(xy);
        before.put(id, xy);
      }
    for (View card : visibleCards.values()) {
      card.animate().cancel();
      card.setTranslationX(0);
      card.setTranslationY(0);
      if (card.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup) card.getParent()).removeView(card);
    }
    visibleList.removeAllViews();
    for (int i = 0; i < order.size(); ) {
      if (density != 3) {
        visibleList.addView(visibleCards.get(order.get(i++)));
        continue;
      }
      LinearLayout pair = Ui.row(activity);
      for (int column = 0; column < 2 && i < order.size(); column++) {
        View card = visibleCards.get(order.get(i++));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(0, 0, column == 0 ? dp(4) : 0, dp(6));
        pair.addView(card, lp);
      }
      if (pair.getChildCount() == 1)
        pair.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
      visibleList.addView(pair);
    }
    displayedOrder = new ArrayList<>(order);
    if (!before.isEmpty()) {
      android.view.ViewTreeObserver observer = visibleList.getViewTreeObserver();
      LinearLayout animatedList = visibleList;
      Map<String, View> animationCards = new HashMap<>(visibleCards);
      observer.addOnPreDrawListener(
          new android.view.ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
              if (observer.isAlive()) observer.removeOnPreDrawListener(this);
              if (!animatedList.isAttachedToWindow()) return true;
              for (String id : order) {
                int[] old = before.get(id);
                if (old == null) continue;
                View card = animationCards.get(id);
                int[] now = new int[2];
                card.getLocationOnScreen(now);
                card.setTranslationX(old[0] - now[0]);
                card.setTranslationY(old[1] - now[1]);
                card.animate()
                    .translationX(0)
                    .translationY(0)
                    .setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
              }
              return true;
            }
          });
    }
  }

  public void update() {
    long now = android.os.SystemClock.elapsedRealtime();
    long hold = ScopeApp.prefs().getInt("recommendHoldSeconds", 5) * 1000L;
    boolean auto = ScopeApp.prefs().getBoolean("autoSuggest", false);
    if (now - lastMonitorRefresh > 1000) {
      lastMonitorRefresh = now;
      monitor.refresh();
    }
    if (android.os.SystemClock.elapsedRealtime() - lastDevices > 1500) {
      lastDevices = android.os.SystemClock.elapsedRealtime();
      String current = BluetoothRouting.signature();
      if (!current.equals(bluetoothSignature)) {
        bluetoothSignature = current;
        render();
        return;
      }
    }
    boolean allOn = CaptureService.allRecording(nonHidden());
    Ui.tint(all, allOn ? Ui.RED : 0xff938d9c);
    all.setContentDescription(
        allOn ? "Stop all non-hidden sources" : "Record all non-hidden sources");
    for (Map.Entry<String, Row> e : rows.entrySet()) {
      CaptureService.Track t = track(e.getKey());
      Row r = e.getValue();
      boolean recording = CaptureService.active() && t != null && !t.monitor && t.running;
      Ui.tint(r.record, recording && !CaptureService.paused ? Ui.RED : 0xff938d9c);
      r.record.setContentDescription(
          (recording ? "Stop " : "Record ") + Source.get(e.getKey()).title);
      r.wave.recording(recording && !CaptureService.paused);
      r.wave.update(t);
      if (r.preview != null) {
        boolean watching = recording || t != null && t.running && t.error.isEmpty();
        r.wave.setVisibility(watching ? View.VISIBLE : View.GONE);
        r.wave.setContentDescription(
            recording
                ? "Recording microphone waveform; tap for details"
                : "Live microphone waveform; tap to stop monitoring");
        r.preview.setVisibility(watching ? View.GONE : View.VISIBLE);
        r.preview.setText(
            monitor.manual(e.getKey()) && (t == null || t.running)
                ? "Stop monitoring"
                : "Start monitoring");
        // Tapping the monitored waveform stops only this explicit microphone preview.
        r.wave.setOnClickListener(
            v -> {
              if (!recording && monitor.manual(e.getKey())) monitor.stopBluetooth(e.getKey());
              else showDetails(Source.get(e.getKey()));
            });
      }
      if (r.format != null) r.format.setEnabled(!CaptureService.active());
      String blocked = monitor.blocked(e.getKey());
      if (t != null) activityOrder.observe(e.getKey(), t.lastAudibleMs);
      boolean recent =
          auto && !activityOrder.recent(Collections.singleton(e.getKey()), now, hold).isEmpty();
      if (r.name != null) Ui.tint(r.name, recent ? ThemePalette.accent(activity) : Ui.INK);
      if (r.route != null) {
        String route =
            t == null
                ? "No communication input open. Android chooses the device when started."
                : "Actually using: "
                    + t.device
                    + "\n"
                    + t.captureBackend
                    + " · "
                    + t.actualPreset
                    + (t.systemSilenced ? "\nAndroid is silencing this input during the call." : "")
                    + (t.routeNote.isEmpty() ? "" : "\n" + t.routeNote);
        if (!route.contentEquals(r.route.getText())) r.route.setText(route);
      }
      String status =
          t == null
              ? !blocked.isEmpty()
                  ? blocked
                  : Source.get(e.getKey()).manualPreview()
                      ? "Monitoring off · start explicitly"
                      : "Live preview off"
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
      if (recent) status = "Recent activity · " + status;
      if (r.status != null && !status.contentEquals(r.status.getText())) {
        r.status.setText(status);
        r.status.setTextColor(
            !blocked.isEmpty() || t != null && !t.error.isEmpty() ? Ui.RED : Ui.MUTED);
      }
      r.record.setTooltipText(status);
      r.score = t != null && t.running && t.error.isEmpty() ? r.score * .75 + t.db * .25 : -120;
    }
    recommended.clear();
    recommended.addAll(activityOrder.recent(rows.keySet(), now, hold));
    if (!recommended.isEmpty()) {
      List<String> names = new ArrayList<>();
      for (String id : recommended) names.add(Source.get(id).title);
      suggestion.setText(
          "Active in the last "
              + (hold / 1000)
              + " seconds · "
              + recommended.size()
              + " sources\n"
              + String.join(" · ", names)
              + "\nActive visible sources move up temporarily. Your saved layout is unchanged.");
    } else
      suggestion.setText("No recent activity. Your saved layout returns after 15 quiet seconds.");
    List<String> saved =
        visibleSources.stream().map(x -> x.id).collect(java.util.stream.Collectors.toList());
    layoutVisible(
        activityOrder.order(
            saved,
            auto && !editing,
            now,
            hold,
            ScopeApp.prefs().getInt("recommendMoveSeconds", 4) * 1000L,
            15000),
        true);
  }
}
