package dev.audioscope;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

public class MainActivity extends androidx.appcompat.app.AppCompatActivity {
  private static final int BG = 0xff141019,
      CARD = 0xff231c2e,
      INK = 0xffeee7f5,
      MUTED = 0xffb1a6bd,
      RED = 0xffffb4ab;
  private int ACCENT;
  private final SourceMonitor monitors = ScopeApp.monitor;
  private SourcePanel sourcePanel;
  private LibraryPanel libraryPanel;
  private String setupAction;
  private boolean launching;
  private EditText recordingName;
  private final Map<String, Row> sourceRows = new HashMap<>();
  private Button recordAll;
  private final Map<String, Spinner> sourceFormats = new HashMap<>();
  private boolean foreground;
  private long lastMonitor;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private LinearLayout root, body, nav;
  private TextView engine, time, sessionState, logView, inspectView;
  private Button record, pause;
  private EditText logFilter;
  private int tab = 0, lastMode = -1;
  private String[] pendingSources;
  private boolean pendingRecordAll;
  private final LinkedHashSet<String> selected = new LinkedHashSet<>();
  private final Map<String, Row> rows = new HashMap<>();
  private boolean destroyed;

  private static final class Row {
    TextView state, stats;
    Button button;
    WaveformView wave;
    CheckBox selected;
  }

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (saved != null) tab = saved.getInt("tab", 0);
    selected.addAll(
        ScopeApp.prefs()
            .getStringSet("selected", new LinkedHashSet<>(Arrays.asList("mic", "voice_playback"))));
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    if (saved == null) routeIntent(getIntent());
    render();
    showNotice(getIntent());
  }

  public void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    routeIntent(i);
    render();
    showNotice(i);
  }

  private void routeIntent(Intent i) {
    if (i == null) return;
    String screen = i.getStringExtra("screen");
    if (screen != null)
      tab =
          screen.equals("sources")
              ? 5
              : screen.equals("library") ? 1 : screen.equals("settings") ? 4 : 0;
  }

  private void showNotice(Intent i) {
    if (i != null && i.hasExtra("noticeTitle")) {
      String title = i.getStringExtra("noticeTitle"), detail = i.getStringExtra("noticeDetail");
      i.removeExtra("noticeTitle");
      handler.post(() -> showText(title, detail));
    }
  }

  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putInt("tab", tab);
  }

  public void onResume() {
    super.onResume();
    foreground = true;
    ScopeApp.app.ensureCallObserver();
    if (tab == 4) render();
    if (tab == 5) enableMeters();
    handler.post(update);
  }

  public void onPause() {
    super.onPause();
    foreground = false;
    monitors.disable();
    handler.removeCallbacks(update);
  }

  public void onDestroy() {
    destroyed = true;
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  private int dp(float n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout horizontal() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView text(String value, int size, int color) {
    return Ui.text(
        this, value, size <= 12 ? 13 : size <= 14 ? 14 : size <= 18 ? 16 : size - 1, color);
  }

  private TextView title(String value) {
    TextView t = text(value, 21, INK);
    t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  private GradientDrawable background(int color, int radius) {
    return Ui.background(this, color, radius);
  }

  private LinearLayout card() {
    return Ui.card(this);
  }

  private Button button(String label, int color, Runnable action) {
    return Ui.button(this, label, color == ACCENT, action);
  }

  private void two(LinearLayout parent, Button a, Button b) {
    LinearLayout row = horizontal();
    LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(0, -2, 1);
    pa.setMargins(0, dp(6), dp(4), 0);
    a.setLayoutParams(pa);
    LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(0, -2, 1);
    pb.setMargins(dp(4), dp(6), 0, 0);
    b.setLayoutParams(pb);
    row.addView(a);
    row.addView(b);
    parent.addView(row);
  }

  private void section(String s) {
    TextView t = text(s, 11, MUTED);
    t.setTypeface(null, Typeface.BOLD);
    t.setLetterSpacing(.16f);
    t.setPadding(0, dp(16), 0, dp(10));
    body.addView(t);
  }

  private void render() {
    ACCENT = ThemePalette.accent(this);
    rows.clear();
    sourceRows.clear();
    sourceFormats.clear();
    sourcePanel = null;
    libraryPanel = null;
    logView = null;
    inspectView = null;
    if (tab != 5) monitors.disable();
    root = column();
    root.setBackgroundColor(BG);
    setContentView(root);
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          androidx.core.graphics.Insets safe =
              insets.getInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars()
                      | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
          androidx.core.graphics.Insets keyboard =
              insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
          v.setPadding(
              dp(16) + safe.left,
              dp(12) + safe.top,
              dp(16) + safe.right,
              dp(10) + Math.max(safe.bottom, keyboard.bottom));
          return insets;
        });
    androidx.core.view.ViewCompat.requestApplyInsets(root);
    LinearLayout head = horizontal();
    TextView name = text("AudioScope", 19, INK);
    name.setTypeface(null, Typeface.BOLD);
    head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    Button history = button("Alerts", CARD, this::notificationHistory);
    history.setContentDescription("Notification history");
    head.addView(history, new LinearLayout.LayoutParams(-2, -2));
    root.addView(head);
    engine = text("Helper: " + ScopeApp.backend + " · tap to set up", 12, MUTED);
    engine.setOnClickListener(
        v -> {
          tab = 4;
          render();
        });
    root.addView(engine);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.setClipToPadding(false);
    body = column();
    body.setPadding(0, dp(12), 0, dp(16));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    com.google.android.material.bottomnavigation.BottomNavigationView bottom =
        new com.google.android.material.bottomnavigation.BottomNavigationView(this);
    bottom.setBackgroundColor(BG);
    bottom.setLabelVisibilityMode(
        com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
    bottom.setItemActiveIndicatorEnabled(true);
    bottom.setItemActiveIndicatorColor(android.content.res.ColorStateList.valueOf(0xff453154));
    bottom.setItemTextAppearanceActive(R.style.NavText);
    bottom.setItemTextAppearanceInactive(R.style.NavText);
    android.content.res.ColorStateList colors =
        new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {ACCENT, MUTED});
    bottom.setItemIconTintList(colors);
    bottom.setItemTextColor(colors);
    String[] labels = {"Record", "Sources", "Sessions", "Tools", "Settings"};
    int[] ids = {0, 5, 1, 3, 4};
    int[] icons = {
      R.drawable.ic_record,
      R.drawable.ic_sources,
      R.drawable.ic_library,
      R.drawable.ic_tools,
      R.drawable.ic_settings
    };
    for (int n = 0; n < ids.length; n++)
      bottom.getMenu().add(0, 100 + ids[n], n, labels[n]).setIcon(icons[n]);
    bottom.setSelectedItemId(100 + (tab == 2 ? 3 : tab));
    bottom.setOnItemSelectedListener(
        item -> {
          tab = item.getItemId() - 100;
          render();
          return true;
        });
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        bottom, (v, insets) -> androidx.core.view.WindowInsetsCompat.CONSUMED);
    root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
    Ui.enter(body);
    switch (tab) {
      case 0:
        capture();
        break;
      case 1:
        sessions();
        break;
      case 2:
        body.addView(
            button(
                "‹ Back to Tools",
                CARD,
                () -> {
                  tab = 3;
                  render();
                }));
        routing();
        break;
      case 3:
        lab();
        break;
      case 4:
        settings();
        break;
      case 5:
        sources();
        break;
    }
  }

  private void enableMeters() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED) {
      monitors.enable();
      monitors.refresh();
    } else requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 24);
  }

  private void sources() {
    sourcePanel =
        new SourcePanel(
            this,
            monitors,
            new SourcePanel.Actions() {
              public void record(String[] ids) {
                startRecord(ids);
              }

              public void recordAll(String[] ids) {
                startRecord(ids, true);
              }

              public void details(String title, String detail) {
                showText(title, detail);
              }
            });
    body.addView(sourcePanel.view());
    if (foreground) enableMeters();
  }

  private void capture() {
    body.addView(title("Record"));
    body.addView(
        text("Pick the routes you need. Sources shows which ones carry real audio.", 14, MUTED));
    LinearLayout hero = card();
    time = text(CaptureService.formatTime(CaptureService.elapsedMs()), 29, INK);
    time.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    hero.addView(time);
    sessionState = text("Ready", 14, ACCENT);
    hero.addView(sessionState);
    recordingName =
        input("Recording name · optional", ScopeApp.prefs().getString("recordingLabel", ""), false);
    recordingName.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(60)});
    recordingName.setEnabled(!CaptureService.active());
    recordingName.setOnFocusChangeListener(
        (v, focus) -> {
          if (!focus)
            ScopeApp.prefs()
                .edit()
                .putString("recordingLabel", recordingName.getText().toString().trim())
                .apply();
        });
    hero.addView(recordingName);
    record =
        button(
            CaptureService.active() ? "Stop & save" : "● Record selected",
            ACCENT,
            () -> {
              if (CaptureService.active()) CaptureService.instance.stopSession();
              else startRecord(selected.toArray(new String[0]));
            });
    hero.addView(record);
    pause =
        button(
            "Pause",
            CARD,
            () -> {
              if (CaptureService.active()) CaptureService.instance.togglePause();
            });
    two(
        hero,
        pause,
        button(
            "Bookmark",
            CARD,
            () -> {
              if (CaptureService.active())
                prompt("Bookmark label", "", s -> CaptureService.instance.mark(s));
              else toast("Start a recording to add a bookmark");
            }));
    body.addView(hero);
    LinearLayout auto = card();
    auto.addView(text("Automatic calls", 17, INK));
    auto.addView(
        text(
            CaptureService.armed
                ? "Armed · waiting for phone or app calls"
                : "Arm while this app is open to record calls in the background.",
            14,
            MUTED));
    auto.addView(
        button(
            CaptureService.armed ? "Disarm automatic calls" : "Arm automatic calls",
            CARD,
            () -> setup(CaptureService.armed ? "DISARM_AUTO" : "ARM_AUTO")));
    auto.addView(
        text(
            "Wi-Fi and app calls try VoIP playback + mic; carrier capture is attempted alongside"
                + " them. Audio state detection varies by calling app.",
            13,
            MUTED));
    body.addView(auto);
    LinearLayout preset = card();
    preset.addView(text("Quick selections", 17, INK));
    two(
        preset,
        button("Voice memo", CARD, () -> selectPreset("mic")),
        button("Wi-Fi / app call", CARD, () -> selectPreset("voice_playback", "mic")));
    two(
        preset,
        button("Carrier call", CARD, () -> selectPreset("voice_call", "mic", "voice_playback")),
        button("Media + mic", CARD, () -> selectPreset("media", "mic")));
    two(
        preset,
        button("Save preset", CARD, () -> prompt("Preset name", "My setup", this::savePreset)),
        button("Load preset", CARD, this::loadPreset));
    body.addView(preset);
    section("SELECTED ROUTES");
    LinearLayout list = card();
    for (String id : new ArrayList<>(selected)) {
      Source source;
      try {
        source = Source.get(id);
      } catch (Exception ignored) {
        continue;
      }
      Row row = new Row();
      rows.put(id, row);
      LinearLayout line = horizontal();
      CheckBox check = new CheckBox(this);
      check.setChecked(true);
      check.setContentDescription("Select " + source.title);
      check.setOnCheckedChangeListener(
          (v, on) -> {
            if (on) selected.add(id);
            else selected.remove(id);
            saveSelection();
          });
      line.addView(check, new LinearLayout.LayoutParams(dp(44), dp(48)));
      TextView label = text(source.title, 15, INK);
      line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
      list.addView(line);
      row.state = text("Ready · " + Formats.label(Formats.current(id)), 13, MUTED);
      list.addView(row.state);
      row.wave = new WaveformView(this, ACCENT);
      list.addView(row.wave, new LinearLayout.LayoutParams(-1, dp(40)));
      row.stats = text(source.detail, 13, MUTED);
      list.addView(row.stats);
    }
    list.addView(button("Choose sources", CARD, this::chooseSources));
    list.addView(
        button(
            "Compare live sources",
            CARD,
            () -> {
              tab = 5;
              render();
            }));
    body.addView(list);
    if (!CaptureService.exportStatus.isEmpty())
      body.addView(text(CaptureService.exportStatus, 14, ACCENT));
  }

  private void selectPreset(String... ids) {
    selected.clear();
    selected.addAll(Arrays.asList(ids));
    saveSelection();
    render();
  }

  private void chooseSources() {
    List<Source> available = Source.available();
    String[] names = available.stream().map(s -> s.title).toArray(String[]::new);
    boolean[] checked = new boolean[names.length];
    Set<String> next = new LinkedHashSet<>(selected);
    for (int i = 0; i < names.length; i++) checked[i] = next.contains(available.get(i).id);
    new AlertDialog.Builder(this)
        .setTitle("Recording sources")
        .setMultiChoiceItems(
            names,
            checked,
            (d, i, on) -> {
              String id = available.get(i).id;
              if (on) next.add(id);
              else next.remove(id);
            })
        .setPositiveButton(
            "Apply",
            (d, w) -> {
              selected.clear();
              selected.addAll(next);
              saveSelection();
              render();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void recordOne(String id) {
    if (CaptureService.active()) {
      CaptureService.instance.startTrack(id);
    } else startRecord(new String[] {id});
  }

  private void arm(Source s) {
    if (ScopeApp.bridge == null) {
      toast("Connect the privileged helper in Settings");
      return;
    }
    if (CaptureService.active()) {
      toast("Arm policies before recording; source settings are frozen during a session");
      return;
    }
    ScopeApp.IO.execute(
        () -> {
          try {
            String result =
                ScopeApp.bridge.arm(
                    s.id,
                    ScopeApp.prefs().getInt("rate", 48000),
                    ScopeApp.prefs().getInt("channels", 1),
                    ScopeApp.prefs().getInt("uidFilter", -1));
            ScopeApp.log(result.startsWith("FAILED") ? "ERROR" : "INFO", result);
            handler.post(() -> toast(result));
          } catch (Throwable e) {
            ScopeApp.log("ERROR", ShellBridge.root(e));
          }
        });
  }

  private void saveSelection() {
    ScopeApp.prefs().edit().putStringSet("selected", new LinkedHashSet<>(selected)).apply();
  }

  private void startRecord(String[] sources) {
    startRecord(sources, false);
  }

  private void startRecord(String[] sources, boolean all) {
    pendingRecordAll = all;
    if (recordingName != null && tab == 0)
      ScopeApp.prefs()
          .edit()
          .putString("recordingLabel", recordingName.getText().toString().trim())
          .apply();
    if (sources.length == 0) {
      Notices.error(
          "No sources selected", "Choose at least one source before recording.", "sources");
      showText(
          "Choose a source", "Select at least one source with a live signal before recording.");
      return;
    }
    if (CaptureService.stopping && CaptureService.instance != null) {
      toast("Finalizing previous session");
      return;
    }
    pendingSources = sources;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 21);
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 22);
      return;
    }
    boolean needsProjection =
        ScopeApp.bridge == null
            && Arrays.stream(sources)
                .anyMatch(
                    s -> {
                      Source x = Source.get(s);
                      return x.playback() && (x.usage == 0 || x.usage == 1 || x.usage == 14);
                    });
    if (needsProjection) {
      startActivityForResult(
          getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 31);
      return;
    }
    launchCapture(null);
  }

  private void launchCapture(Intent data) {
    launching = true;
    monitors.disable();
    ScopeApp.IO.execute(
        () -> {
          monitors.stop();
          handler.post(() -> launchCaptureReady(data));
        });
  }

  private void launchCaptureReady(Intent data) {
    Intent intent =
        new Intent(this, CaptureService.class)
            .setAction("RECORD")
            .putExtra("sources", pendingSources)
            .putExtra("recordAll", pendingRecordAll)
            .putExtra("autoLabel", ScopeApp.prefs().getString("recordingLabel", "").isBlank())
            .putExtra(
                "label",
                !ScopeApp.prefs().getString("recordingLabel", "").isBlank()
                    ? ScopeApp.prefs().getString("recordingLabel", "")
                    : Arrays.asList(pendingSources).contains("voice_playback")
                        ? "Wi-Fi or app call"
                        : Arrays.asList(pendingSources).contains("voice_call")
                            ? "Call"
                            : pendingSources.length == 1 && pendingSources[0].equals("mic")
                                ? "Voice memo"
                                : "Recording");
    if (data != null) intent.putExtra("projection", data);
    startForegroundService(intent);
    handler.postDelayed(
        () -> {
          launching = false;
          if (tab == 0 || tab == 5) render();
        },
        500);
  }

  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    ScopeApp.app.ensureCallObserver();
    if (r == 36) {
      render();
      toast("Naming access updated. Unavailable call details will be omitted.");
      return;
    }
    if (r == 35) {
      render();
      if (g.length == 0 || g[0] != PackageManager.PERMISSION_GRANTED)
        Notices.error(
            "Bluetooth access not enabled",
            "Phone microphone sources still work. Allow Nearby devices in Android app permissions"
                + " to show connected headset microphone sources.",
            "settings");
      return;
    }
    if (r == 26 || r == 27) {
      if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) setup(setupAction);
      else
        showText(
            "Permission needed",
            r == 26
                ? "Wireless pairing needs an interactive notification so you can stay in Android"
                    + " Settings. Enable AudioScope notifications and retry."
                : "Microphone access is needed to arm background call capture.");
      return;
    }
    if (r == 24) {
      if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
        monitors.enable();
        monitors.refresh();
      } else toast("Microphone permission is required for live monitoring");
    } else if (r == 23) {
      if (CaptureService.armed) {
        startForegroundService(new Intent(this, CaptureService.class).setAction("ARM_AUTO"));
      }
      toast(
          g.length > 0 && g[0] == 0
              ? "Phone-call detection enabled"
              : "VoIP audio-state detection remains available");
    } else if (r == 21) {
      if (g.length > 0 && g[0] == 0) startRecord(pendingSources);
      else toast("Microphone permission is required for the recording foreground service");
    } else if (r == 22) {
      boolean projection =
          ScopeApp.bridge == null
              && Arrays.stream(pendingSources)
                  .anyMatch(
                      s ->
                          Source.get(s).playback()
                              && (Source.get(s).usage == 0
                                  || Source.get(s).usage == 1
                                  || Source.get(s).usage == 14));
      if (projection)
        startActivityForResult(
            getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 31);
      else launchCapture(null);
    }
  }

  protected void onActivityResult(int r, int result, Intent data) {
    super.onActivityResult(r, result, data);
    if (r == 40 && result == RESULT_OK && data != null && data.getData() != null) {
      try {
        StorageFolders.remember(data.getData(), data.getFlags());
        Notices.event(
            "Save folder changed",
            "New recordings will be copied to "
                + StorageFolders.label()
                + ". Local audio is indexed for Files / Recent after saving.",
            "settings");
        render();
      } catch (Exception e) {
        Notices.error(
            "Save folder could not be selected",
            "The previous save location is unchanged. Pick a writable folder.\n\n"
                + ShellBridge.root(e),
            "settings");
      }
    }
    if (r == 31) {
      if (result == RESULT_OK && data != null) launchCapture(data);
      else toast("Playback consent was declined");
    }
  }

  private final Runnable update =
      new Runnable() {
        public void run() {
          if (destroyed || !foreground) return;
          setIfChanged(engine, "Helper: " + ScopeApp.backend + " · tap to set up");
          if (tab == 0) {
            setIfChanged(time, CaptureService.formatTime(CaptureService.elapsedMs()));
            setIfChanged(
                record,
                CaptureService.active()
                    ? "Stop & save"
                    : CaptureService.stopping ? "Saving audio…" : "● Record selected");
            record.setEnabled(!CaptureService.stopping);
            setIfChanged(pause, CaptureService.paused ? "Resume" : "Pause");
            pause.setEnabled(CaptureService.active());
            setIfChanged(
                sessionState,
                CaptureService.active()
                    ? (CaptureService.paused ? "Paused" : "Recording")
                        + " · "
                        + CaptureService.tracks.values().stream()
                            .filter(t -> t.running && t.frames > 0)
                            .count()
                        + " routes receiving audio"
                    : CaptureService.stopping
                        ? "Finalizing audio"
                        : selected.size()
                            + " routes selected"
                            + (CaptureService.armed ? " · automatic calls armed" : ""));
            for (Map.Entry<String, Row> e : rows.entrySet()) {
              CaptureService.Track t = CaptureService.tracks.get(e.getKey());
              Row row = e.getValue();
              row.wave.recording(
                  CaptureService.active() && !CaptureService.paused && t != null && t.running);
              row.wave.update(CaptureService.active() ? t : null);
              if (t != null) {
                setIfChanged(
                    row.state,
                    t.error.isEmpty()
                        ? t.state + " · " + Formats.label(t.codec)
                        : CaptureProblem.of(t.error).title);
                row.state.setTextColor(t.error.isEmpty() ? ACCENT : RED);
                setIfChanged(
                    row.stats,
                    t.error.isEmpty()
                        ? String.format(
                            Locale.US,
                            "%.0f dBFS · %,d frames · %d drops",
                            t.db,
                            t.frames,
                            t.dropped)
                        : CaptureProblem.of(t.error).explanation + " Tap for repair steps.");
                row.stats.setOnClickListener(
                    v -> {
                      if (!t.error.isEmpty()) {
                        Notices.problem(t.source, t.error, true);
                        showText(t.source.title, CaptureProblem.of(t.error).details(t.error));
                      }
                    });
              }
            }
          }
          if (tab == 5 && sourcePanel != null) {
            sourcePanel.update();
            if (System.currentTimeMillis() - lastMonitor > 1500) {
              lastMonitor = System.currentTimeMillis();
              if (!CaptureService.active()
                  && !CaptureService.stopping
                  && !launching
                  && !CaptureService.preparingAuto()) monitors.enable();
              monitors.refresh();
            }
          }
          if (tab == 1 && libraryPanel != null) libraryPanel.update();
          if (tab == 3 && logView != null && System.currentTimeMillis() - lastMonitor > 1500) {
            lastMonitor = System.currentTimeMillis();
            setIfChanged(
                logView, ScopeApp.logText(logFilter == null ? "" : logFilter.getText().toString()));
          }
          handler.postDelayed(this, 250);
        }
      };

  private void sessions() {
    libraryPanel =
        new LibraryPanel(
            this,
            new LibraryPanel.Actions() {
              public void share(File f) {
                MainActivity.this.share(f);
              }

              public void browse(File f) {
                MainActivity.this.browse(f);
              }

              public void details(String title, String detail) {
                showText(title, detail);
              }
            });
    body.addView(libraryPanel.view());
  }

  private void browse(File folder) {
    File[] files = folder.listFiles(File::isFile);
    if (files == null) return;
    Arrays.sort(files, Comparator.comparing(File::getName));
    String[] labels = new String[files.length];
    for (int i = 0; i < files.length; i++)
      labels[i] = files[i].getName() + "  (" + files[i].length() / 1024 + " KiB)";
    new AlertDialog.Builder(this)
        .setTitle(folder.getName())
        .setItems(
            labels,
            (d, w) -> {
              File f = files[w];
              if (f.getName().endsWith(".wav")
                  || f.getName().endsWith(".m4a")
                  || f.getName().endsWith(".webm"))
                new AlertDialog.Builder(this)
                    .setTitle(f.getName())
                    .setItems(
                        new String[] {"Play in AudioScope", "Share file"},
                        (a, b) -> {
                          if (b == 0) play(f);
                          else share(f);
                        })
                    .show();
              else if (f.getName().endsWith(".json") || f.getName().endsWith(".log"))
                try {
                  showText(f.getName(), Exports.read(f));
                } catch (Exception e) {
                  toast(e.toString());
                }
              else share(f);
            })
        .show();
  }

  private void play(File f) {
    PlaybackService.play(this, f);
  }

  private void share(File f) {
    Intent i =
        new Intent(Intent.ACTION_SEND)
            .setType(getContentResolver().getType(ShareProvider.uri(f)))
            .putExtra(Intent.EXTRA_STREAM, ShareProvider.uri(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    i.setClipData(ClipData.newRawUri("AudioScope export", ShareProvider.uri(f)));
    startActivity(Intent.createChooser(i, "Share " + f.getName()));
  }

  private void routing() {
    body.addView(title("Output routing"));
    body.addView(
        text(
            "Original WAVs always stay separate. Create additional outputs from the same capture.",
            13,
            MUTED));
    body.addView(
        text(
            "Changes apply to the next session. Gain and mute affect exported mixes; originals"
                + " retain captured PCM.",
            12,
            ACCENT));
    LinearLayout outputs = card();
    toggle(outputs, "Stereo pair WAV", "L / R assignments below", "stereo", false);
    toggle(outputs, "Normalized mono mix", "Weighted sum with headroom", "mix", false);
    toggle(outputs, "True multitrack MKA", "One named PCM audio track per source", "mka", false);
    body.addView(outputs);
    LinearLayout pair = card();
    pair.addView(text("STEREO ASSIGNMENT", 11, MUTED));
    sourceSpinner(pair, "Left channel", "routeLeft", "mic");
    sourceSpinner(pair, "Right channel", "routeRight", "voice_playback");
    pair.addView(
        button(
            "Swap left / right",
            CARD,
            () -> {
              if (!settingsEditable()) return;
              String l = ScopeApp.prefs().getString("routeLeft", "mic"),
                  r = ScopeApp.prefs().getString("routeRight", "voice_playback");
              ScopeApp.prefs().edit().putString("routeLeft", r).putString("routeRight", l).apply();
              render();
            }));
    body.addView(pair);
    section("MIX GAIN / MUTE");
    for (Source s : Source.ALL) {
      LinearLayout c = card();
      c.addView(text(s.title, 14, INK));
      TextView value =
          text("Gain " + ScopeApp.prefs().getFloat("gain_" + s.id, 1) + "×", 11, MUTED);
      c.addView(value);
      SeekBar slider = new SeekBar(this);
      slider.setMax(200);
      slider.setProgress(Math.round(ScopeApp.prefs().getFloat("gain_" + s.id, 1) * 100));
      slider.setEnabled(!CaptureService.active());
      slider.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {}

            public void onStopTrackingTouch(SeekBar b) {}

            public void onProgressChanged(SeekBar b, int n, boolean user) {
              if (user) {
                ScopeApp.prefs().edit().putFloat("gain_" + s.id, n / 100f).apply();
                value.setText("Gain " + n / 100f + "×");
              }
            }
          });
      c.addView(slider);
      toggle(c, "Mute in exports", "Keep original source recording", "mute_" + s.id, false);
      body.addView(c);
    }
    body.addView(
        text(
            "Stereo / mono exports align source start timestamps and estimate clock drift. MKA"
                + " preserves each source's native sample clock and initial offset. Raw streams and"
                + " per-chunk timing remain available for precise analysis.",
            12,
            MUTED));
  }

  private void lab() {
    body.addView(title("Tools"));
    body.addView(
        button(
            "Output routing & mix controls",
            CARD,
            () -> {
              tab = 2;
              render();
            }));
    body.addView(button("Notification history", CARD, this::notificationHistory));
    body.addView(text("Measure the route. Inspect the permissions. Keep the evidence.", 13, MUTED));
    LinearLayout tests = card();
    tests.addView(text("RECORDING TESTS", 11, MUTED));
    tests.addView(
        button(
            "5-second probe • selected sources",
            ACCENT,
            () -> {
              if (CaptureService.active()) {
                toast("Stop the active session first");
                return;
              }
              startRecord(selected.toArray(new String[0]));
              waitThenStop(5);
            }));
    tests.addView(button("Sequential source sweep • selected", CARD, this::sweep));
    tests.addView(button("Run offline pipeline self-test", CARD, this::selfTest));
    tests.addView(button("Repair interrupted WAV headers", CARD, this::repair));
    body.addView(tests);
    LinearLayout inspector = card();
    inspector.addView(text("LIVE AUDIO INSPECTOR", 11, MUTED));
    two(
        inspector,
        button("Inspect device / shell", CARD, this::inspect),
        button("Export diagnostics", CARD, this::diagnosticExport));
    inspectView =
        text(
            "Tap Inspect to collect current mode, device routes, capture configurations, shell"
                + " permissions, and AudioService state.",
            12,
            MUTED);
    inspectView.setTypeface(Typeface.MONOSPACE);
    inspectView.setTextIsSelectable(true);
    inspector.addView(inspectView);
    body.addView(inspector);
    LinearLayout logs = card();
    logs.addView(text("EVENT LOG", 11, MUTED));
    logFilter = input("Filter: source, error, mode…", "", false);
    logs.addView(logFilter);
    logView = text(ScopeApp.logText(""), 10, MUTED);
    logView.setTypeface(Typeface.MONOSPACE);
    logView.setTextIsSelectable(true);
    logs.addView(logView);
    body.addView(logs);
  }

  private void waitThenStop(int seconds) {
    handler.postDelayed(
        new Runnable() {
          int attempts;

          public void run() {
            if (CaptureService.active()) {
              handler.postDelayed(
                  () -> {
                    if (CaptureService.active()) CaptureService.instance.stopSession();
                  },
                  seconds * 1000L);
            } else if (attempts++ < 120) handler.postDelayed(this, 500);
          }
        },
        500);
  }

  private void sweep() {
    if (CaptureService.active()) {
      toast("Stop the active session first");
      return;
    }
    if (selected.isEmpty()) {
      toast("Select sources in Capture");
      return;
    }
    String[] ids = selected.toArray(new String[0]);
    startRecord(new String[] {ids[0]});
    handler.postDelayed(
        new Runnable() {
          int attempts;

          public void run() {
            if (CaptureService.active()) {
              ScopeApp.IO.execute(
                  () -> {
                    for (int i = 0; i < ids.length; i++) {
                      if (!CaptureService.active()) break;
                      if (i > 0) CaptureService.instance.startTrack(ids[i]);
                      ScopeApp.log("INFO", "Sweep " + (i + 1) + "/" + ids.length + ": " + ids[i]);
                      try {
                        Thread.sleep(5000);
                      } catch (InterruptedException e) {
                        break;
                      }
                      CaptureService.Track t = CaptureService.tracks.get(ids[i]);
                      if (t != null) {
                        t.stop();
                        try {
                          t.done.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception ignored) {
                        }
                      }
                    }
                    if (CaptureService.active()) CaptureService.instance.stopSession();
                  });
            } else if (attempts++ < 120) handler.postDelayed(this, 500);
          }
        },
        500);
  }

  private void inspect() {
    ScopeApp.IO.execute(
        () -> {
          String report = deviceReport();
          try {
            if (ScopeApp.bridge != null)
              report += "\n\nSHELL INSPECTION\n" + ScopeApp.bridge.inspect();
            else report += "\nShell helper not connected.";
          } catch (Throwable e) {
            report += "\n" + ShellBridge.root(e);
          }
          final String result = report;
          handler.post(
              () -> {
                if (inspectView != null) inspectView.setText(result);
              });
        });
  }

  private String deviceReport() {
    AudioManager a = getSystemService(AudioManager.class);
    StringBuilder b =
        new StringBuilder(
            "AudioScope "
                + BuildConfig.VERSION_NAME
                + "\n"
                + Build.MANUFACTURER
                + " "
                + Build.MODEL
                + "\nAndroid "
                + Build.VERSION.RELEASE
                + " / SDK "
                + Build.VERSION.SDK_INT
                + "\nMode: "
                + a.getMode()
                + " (0 normal, 2 call, 3 communication)\nBackend: "
                + ScopeApp.backend
                + "\n");
    for (AudioDeviceInfo d :
        a.getDevices(AudioManager.GET_DEVICES_INPUTS | AudioManager.GET_DEVICES_OUTPUTS))
      b.append(d.isSource() ? "INPUT " : "OUTPUT ")
          .append(d.getProductName())
          .append(" • type ")
          .append(d.getType())
          .append(" • rates ")
          .append(Arrays.toString(d.getSampleRates()))
          .append('\n');
    for (AudioRecordingConfiguration r : a.getActiveRecordingConfigurations())
      b.append("RECORD source ")
          .append(r.getClientAudioSource())
          .append(" • silenced ")
          .append(r.isClientSilenced())
          .append(" • ")
          .append(r.getFormat())
          .append('\n');
    for (AudioPlaybackConfiguration p : a.getActivePlaybackConfigurations())
      b.append("PLAY ").append(p.getAudioAttributes()).append('\n');
    if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
        == PackageManager.PERMISSION_GRANTED)
      try {
        b.append("Managed Telecom call: ")
            .append(getSystemService(TelecomManager.class).isInManagedCall())
            .append('\n');
      } catch (Exception e) {
        b.append("Telecom: ").append(e.getMessage());
      }
    else b.append("Telecom state unavailable; phone-state permission is optional.\n");
    return b.toString();
  }

  private void diagnosticExport() {
    ScopeApp.IO.execute(
        () -> {
          try {
            File f =
                new File(
                    ScopeApp.app.getExternalFilesDir(null),
                    "diagnostics-" + System.currentTimeMillis() + ".txt");
            String report =
                deviceReport()
                    + "\n"
                    + (ScopeApp.bridge == null ? "Shell disconnected" : ScopeApp.bridge.inspect())
                    + "\n\nEVENTS\n"
                    + ScopeApp.logText("");
            try (FileWriter w = new FileWriter(f)) {
              w.write(report);
            }
            handler.post(() -> share(f));
          } catch (Throwable e) {
            ScopeApp.log("ERROR", e.toString());
          }
        });
  }

  private void repair() {
    if (CaptureService.active()) {
      toast("Stop recording before repairing files");
      return;
    }
    ScopeApp.IO.execute(
        () -> {
          int count = 0;
          File[] dirs = ScopeApp.sessions().listFiles(File::isDirectory);
          if (dirs != null)
            for (File d : dirs) {
              File[] fs = d.listFiles((p, n) -> n.endsWith(".wav"));
              if (fs != null)
                for (File f : fs)
                  try {
                    WavFile.repair(f);
                    count++;
                  } catch (Exception e) {
                    ScopeApp.log("ERROR", "Repair " + f.getName() + ": " + e);
                  }
            }
          ScopeApp.log("INFO", "WAV header repair finished • " + count + " files");
        });
  }

  private void selfTest() {
    ScopeApp.IO.execute(
        () -> {
          try {
            File d = new File(ScopeApp.sessions(), "self-test-" + System.currentTimeMillis());
            d.mkdirs();
            File one = new File(d, "440Hz.wav"), two = new File(d, "880Hz.wav");
            tone(one, 440);
            tone(two, 880);
            List<PcmRouter.Input> in =
                Arrays.asList(
                    new PcmRouter.Input("a", one, 0, 1000000000, 1),
                    new PcmRouter.Input("b", two, 100000000, 1000000000, 1));
            try {
              PcmRouter.export(new File(d, "stereo.wav"), in, 48000, "a", "b", false);
              PcmRouter.export(new File(d, "mix.wav"), in, 48000, "", "", true);
            } finally {
              for (PcmRouter.Input i : in) i.close();
            }
            MkaWriter.export(
                new File(d, "multitrack.mka"),
                Arrays.asList(
                    new MkaWriter.Track("440 Hz test tone", one, 48000, 1, 0),
                    new MkaWriter.Track("880 Hz delayed test tone", two, 48000, 1, 100000000)));
            try (WavFile.Reader r = new WavFile.Reader(new File(d, "stereo.wav"))) {
              if (r.channels != 2 || r.frames != 52800)
                throw new IOException("Timeline self-test failed");
            }
            Exports.encode(one, "AAC", 128000);
            Exports.encode(one, "Opus", 128000);
            for (String filename : new String[] {"440Hz.m4a", "440Hz.webm"}) {
              MediaExtractor extractor = new MediaExtractor();
              try {
                extractor.setDataSource(new File(d, filename).getPath());
                if (extractor.getTrackCount() != 1)
                  throw new IOException("Encoded test track missing");
              } finally {
                extractor.release();
              }
            }
            try (FileWriter w = new FileWriter(new File(d, "TEST-RESULT.txt"))) {
              w.write(
                  "PASS: PCM16 WAV, two-source stereo, 100 ms alignment, normalized mix, two-track"
                      + " Matroska, AAC and Opus containers. These are generated test tones, not"
                      + " microphone recordings.\n");
            }
            ScopeApp.log(
                "INFO",
                "SELF-TEST PASS • WAV / stereo alignment / mix / MKA / AAC / Opus • "
                    + d.getName());
            Notices.event(
                "Offline self-test passed",
                "WAV, aligned stereo, mix, MKA, AAC and Opus passed. Open Sessions to hear the"
                    + " generated test tones.",
                "library");
          } catch (Throwable e) {
            ScopeApp.log("ERROR", "SELF-TEST FAILED: " + ShellBridge.root(e));
            Notices.error(
                "Offline self-test failed",
                "Open Tools and inspect the log.\n\n" + ShellBridge.root(e),
                "record");
          }
        });
  }

  private void tone(File file, double frequency) throws IOException {
    try (WavFile wav = new WavFile(file, 48000, 1)) {
      byte[] b = new byte[96000];
      for (int i = 0; i < 48000; i++) {
        short v = (short) (Math.sin(2 * Math.PI * frequency * i / 48000) * 8000);
        b[i * 2] = (byte) v;
        b[i * 2 + 1] = (byte) (v >>> 8);
      }
      wav.write(b, b.length);
    }
  }

  private void jumpSetting(String heading) {
    for (int i = 0; i < body.getChildCount(); i++) {
      View child = body.getChildAt(i);
      if (child instanceof TextView && heading.contentEquals(((TextView) child).getText())) {
        ((ScrollView) body.getParent()).smoothScrollTo(0, child.getTop());
        return;
      }
    }
  }

  private void settings() {
    body.addView(title("Settings"));
    body.addView(
        text("Appearance, recording defaults, connections, and notifications.", 14, MUTED));
    two(
        body,
        button("Save folder", CARD, () -> jumpSetting("SAVE LOCATION")),
        button("Bluetooth", CARD, () -> jumpSetting("BLUETOOTH & MICROPHONES")));
    section("APPEARANCE");
    LinearLayout appearance = card();
    appearance.addView(text("Dark Material You", 18, INK));
    appearance.addView(text("Choose an accent, including Android’s wallpaper palette.", 14, MUTED));
    String current = ScopeApp.prefs().getString("accent", "Purple");
    int colorIndex = Arrays.asList(ThemePalette.NAMES).indexOf(current);
    Spinner colors = spinner(ThemePalette.NAMES, Math.max(0, colorIndex));
    colors.setOnItemSelectedListener(
        listener(
            index -> {
              String chosen = ThemePalette.NAMES[index];
              if (!chosen.equals(ScopeApp.prefs().getString("accent", "Purple"))) {
                ScopeApp.prefs().edit().putString("accent", chosen).apply();
                handler.post(this::render);
              }
            }));
    appearance.addView(colors);
    appearance.addView(text("App text size", 14, INK));
    String[] sizes = {"Small · 85%", "Standard · 100%", "Large · 115%"};
    float[] scales = {.85f, 1f, 1.15f};
    float scale = ScopeApp.prefs().getFloat("uiTextScale", 1f);
    int sizeIndex = scale < .95f ? 0 : scale > 1.05f ? 2 : 1;
    Spinner textSize = spinner(sizes, sizeIndex);
    textSize.setOnItemSelectedListener(
        listener(
            i -> {
              if (scales[i] != ScopeApp.prefs().getFloat("uiTextScale", 1f)) {
                ScopeApp.prefs().edit().putFloat("uiTextScale", scales[i]).apply();
                handler.post(this::render);
              }
            }));
    appearance.addView(textSize);
    toggle(
        appearance,
        "Smooth interface animations",
        "Short transitions and button feedback; Android's reduced-motion setting is respected.",
        "animations",
        true);
    toggle(
        appearance,
        "Smooth live waveforms",
        "Interpolate real measured peaks between audio updates. The saved audio is unchanged.",
        "smoothWaveforms",
        true);

    body.addView(appearance);
    section("AUTOMATIC FILE NAMING");
    LinearLayout naming = card();
    toggle(
        naming,
        "Name recordings from call details",
        "Use available caller, app, and direction; missing information is omitted. Optional access"
            + " below stays on this phone.",
        "autoNaming",
        true);
    naming.addView(
        text(
            "Phone names: "
                + (CallContext.allowed(Manifest.permission.READ_CALL_LOG)
                    ? "call log allowed"
                    : "call-log access off")
                + " · "
                + (CallContext.allowed(Manifest.permission.READ_CONTACTS)
                    ? "contacts allowed"
                    : "contacts off"),
            13,
            MUTED));
    naming.addView(
        button(
            "Allow phone caller names & direction",
            CARD,
            () ->
                requestPermissions(
                    new String[] {
                      Manifest.permission.READ_CALL_LOG,
                      Manifest.permission.READ_CONTACTS,
                      Manifest.permission.READ_PHONE_STATE
                    },
                    36)));
    naming.addView(
        text(
            "VoIP caller names: "
                + (CallContext.notificationAccess()
                    ? "notification access allowed"
                    : "notification access off")
                + ". Only ongoing call notifications are used. An app that hides caller or"
                + " direction keeps those fields empty.",
            13,
            MUTED));
    naming.addView(
        button(
            "Set up VoIP call naming",
            CARD,
            () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
    naming.addView(
        text(
            "Example: date_WhatsApp_in_Alex_VoIP playback_0.m4a. Direction appears only when"
                + " exposed by Android.",
            13,
            MUTED));
    naming.addView(
        button(
            "Edit filename template",
            CARD,
            () -> {
              EditText template =
                  input(
                      "{date}_{app}_{direction}_{contact}_{source}",
                      ScopeApp.prefs()
                          .getString(
                              "namingTemplate", "{date}_{app}_{direction}_{contact}_{source}"),
                      false);
              new AlertDialog.Builder(this)
                  .setTitle("Filename template")
                  .setMessage(
                      "Fields: {date}, {app}, {direction}, {contact}, {number}, {label}, {source}."
                          + " Empty fields disappear. Date and a track index keep names unique.")
                  .setView(template)
                  .setPositiveButton(
                      "Save",
                      (d, w) -> {
                        String value = template.getText().toString().trim();
                        if (!value.isEmpty())
                          ScopeApp.prefs().edit().putString("namingTemplate", value).apply();
                      })
                  .setNegativeButton("Cancel", null)
                  .show();
            }));
    body.addView(naming);
    section("RECORDING DEFAULTS");
    LinearLayout capture = card();
    capture.addView(text("Default audio format", 17, INK));
    capture.addView(
        text(
            "Changing this updates every source’s format selector. You can then override individual"
                + " sources.",
            14,
            MUTED));
    Spinner format =
        spinner(Formats.LABELS, Formats.index(ScopeApp.prefs().getString("codec", "WAV")));
    format.setEnabled(!CaptureService.active());
    format.setOnItemSelectedListener(
        listener(
            i -> {
              String value = Formats.VALUES[i];
              if (!value.equals(ScopeApp.prefs().getString("codec", "WAV"))) {
                Formats.setDefault(value);
                Notices.event(
                    "Default format changed",
                    Formats.label(value) + " now applies to every source for new recordings.",
                    "settings");
                toast("All source formats updated to " + Formats.label(value));
              }
            }));
    capture.addView(format);
    capture.addView(
        button(
            "Apply default to every source",
            CARD,
            () -> {
              if (settingsEditable()) {
                Formats.setDefault(ScopeApp.prefs().getString("codec", "WAV"));
                toast("Every source now uses the default format");
              }
            }));
    capture.addView(
        text(
            "M4A saves space. WAV is uncompressed. Opus uses WebM. PCM is headerless; private WAV"
                + " originals are kept for recovery and waveform playback.",
            13,
            MUTED));
    toggle(
        capture,
        "Save copies visible in Files",
        "Finished audio appears in your selected folder and Files / Recent. Default raw PCM goes in"
            + " Downloads/AudioScope.",
        "publicFiles",
        true);
    choice(
        capture,
        "Sample rate",
        new String[] {"16,000 Hz", "24,000 Hz", "44,100 Hz", "48,000 Hz"},
        "rate",
        new int[] {16000, 24000, 44100, 48000},
        48000);
    choice(
        capture,
        "Capture channels",
        new String[] {"Mono", "Stereo"},
        "channels",
        new int[] {1, 2},
        1);
    choice(
        capture,
        "Encoded bitrate",
        new String[] {"64 kbps", "128 kbps", "192 kbps", "256 kbps"},
        "bitrate",
        new int[] {64000, 128000, 192000, 256000},
        128000);
    numberSetting(capture, "Session limit in minutes · 0 = unlimited", "maxMinutes", 0, 0, 720);
    toggle(
        capture,
        "Keep CPU awake while recording",
        "Helps background capture continue while the screen is off.",
        "wakelock",
        true);
    body.addView(capture);
    section("SAVE LOCATION");
    LinearLayout storage = card();
    storage.addView(text("Save folder · " + StorageFolders.label(), 16, INK));
    storage.addView(
        text(
            "Choose a local folder or SD card for finished audio. Files / Recent indexing is"
                + " requested after saving. Cloud folders use their provider's Recent list."
                + " Originals remain safe in Sessions.",
            14,
            MUTED));
    storage.addView(
        button(
            "Choose save folder",
            CARD,
            () -> {
              if (!settingsEditable()) return;
              Intent picker =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                      .addFlags(
                          Intent.FLAG_GRANT_READ_URI_PERMISSION
                              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                              | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
              String savedTree = ScopeApp.prefs().getString("saveTree", "");
              if (!savedTree.isEmpty())
                picker.putExtra(
                    android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(savedTree));
              startActivityForResult(picker, 40);
            }));
    storage.addView(
        button(
            "Use default Recordings/AudioScope",
            CARD,
            () -> {
              if (settingsEditable()) {
                StorageFolders.reset();
                render();
              }
            }));
    toggle(
        storage,
        "Copy session metadata",
        "Write a JSON sidecar in custom folders with source names, format, timing, bookmarks,"
            + " routing and errors.",
        "publicMetadata",
        true);
    body.addView(storage);
    section("BLUETOOTH & MICROPHONES");
    LinearLayout bt = card();
    bt.addView(text("Ordinary microphones use the phone", 16, INK));
    bt.addView(
        text(
            "Phone input is explicitly selected and the actual route is checked. Headset sources"
                + " appear only while connected and never monitor automatically. Bluetooth mic use"
                + " may switch CMF earbuds to call audio and interrupt YouTube or music.",
            14,
            MUTED));
    bt.addView(text(BluetoothRouting.description(), 14, ACCENT));
    bt.addView(
        button(
            "Allow Nearby devices",
            CARD,
            () -> requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, 35)));
    bt.addView(
        button(
            "Open Android Bluetooth settings",
            CARD,
            () -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
    List<AudioDeviceInfo> inputs = BluetoothRouting.connected();
    if (!inputs.isEmpty()) {
      String[] devices = new String[inputs.size()];
      int selectedInput = 0;
      for (int i = 0; i < inputs.size(); i++) {
        devices[i] = inputs.get(i).getProductName().toString();
        if (inputs.get(i).getId() == ScopeApp.prefs().getInt("bluetoothInput", -1)
            || !inputs.get(i).getAddress().isEmpty()
                && inputs
                    .get(i)
                    .getAddress()
                    .equals(ScopeApp.prefs().getString("bluetoothInputAddress", "")))
          selectedInput = i;
      }
      bt.addView(text("Preferred headset microphone", 14, INK));
      Spinner devicesPicker = spinner(devices, selectedInput);
      devicesPicker.setEnabled(!CaptureService.active() && !BluetoothRouting.busy());
      devicesPicker.setOnItemSelectedListener(
          listener(
              i ->
                  ScopeApp.prefs()
                      .edit()
                      .putInt("bluetoothInput", inputs.get(i).getId())
                      .putString("bluetoothInputAddress", inputs.get(i).getAddress())
                      .apply()));
      bt.addView(devicesPicker);
    }
    toggle(
        bt,
        "Monitor phone microphones",
        "Turn off phone mic previews if your phone still changes media routing. Recording buttons"
            + " continue to work.",
        "phoneMicPreview",
        true);
    toggle(
        bt,
        "Prepare headset call audio",
        "Only explicit Bluetooth source actions request communication audio. Turn off to use a"
            + " route already established by a call app.",
        "bluetoothCommunication",
        true);
    choice(
        bt,
        "Bluetooth mic sample rate · Mono",
        new String[] {"16,000 Hz speech", "24,000 Hz", "48,000 Hz"},
        "bluetoothRate",
        new int[] {16000, 24000, 48000},
        16000);
    bt.addView(
        button(
            "Release AudioScope's idle Bluetooth route",
            CARD,
            () -> {
              if (BluetoothRouting.busy()) {
                showText(
                    "Bluetooth source still active",
                    "Stop Bluetooth recording and monitoring before releasing the route.");
                return;
              }
              try {
                BluetoothRouting.resetIdleRoute();
                Notices.event(
                    "Idle headset route released",
                    "AudioScope released its communication route. If media remains silent, pause"
                        + " and resume playback or reconnect the headset.",
                    "settings");
              } catch (Exception e) {
                Notices.error("Headset route needs attention", ShellBridge.root(e), "settings");
              }
            }));
    body.addView(bt);
    section("CALL AUTOMATION");
    LinearLayout automation = card();
    automation.addView(text("Automatic phone and app calls", 17, INK));
    automation.addView(
        text(
            "Arm from this screen or Record while AudioScope is open. The persistent notification"
                + " lets you disarm. Re-arm after reboot or if Android stops the app.",
            14,
            MUTED));
    automation.addView(
        button(
            CaptureService.armed ? "Disarm automatic calls" : "Arm automatic calls",
            ACCENT,
            () -> setup(CaptureService.armed ? "DISARM_AUTO" : "ARM_AUTO")));
    automation.addView(
        button(
            "Allow phone-call detection",
            CARD,
            () -> requestPermissions(new String[] {Manifest.permission.READ_PHONE_STATE}, 23)));
    automation.addView(
        text(
            "Phone-state permission improves carrier detection. VoIP / Wi-Fi calls also use"
                + " communication audio state and record VoIP playback + mic rather than assuming a"
                + " carrier route works.",
            13,
            MUTED));
    body.addView(automation);
    section("OFFLINE CAPTURE HELPER");
    LinearLayout adb = card();
    adb.addView(text("Embedded ADB", 18, INK));
    adb.addView(
        text(
            "1. Open Wireless debugging below and enable it.\n"
                + "2. Choose Pair device with pairing code.\n"
                + "3. Stay in Settings. Pull down notifications, tap Enter code, and send the six"
                + " digits.\n"
                + "AudioScope discovers both ports and starts the helper automatically.",
            14,
            MUTED));
    adb.addView(button("Pair in Wireless debugging", ACCENT, () -> setup("PAIR")));
    adb.addView(button("Connect paired phone", CARD, () -> setup("CONNECT")));
    adb.addView(
        text(
            "Once connected, capture continues offline without Wi-Fi. Android changes the"
                + " connection port; Connect paired phone discovers it again.",
            13,
            MUTED));
    body.addView(adb);
    LinearLayout helper = card();
    helper.addView(text("Shevery / Shizuku", 18, INK));
    helper.addView(
        text(
            "Start the manager’s service first. Connect requests its Binder and then asks you to"
                + " authorize AudioScope.",
            14,
            MUTED));
    helper.addView(
        button(
            "Connect Shevery / Shizuku",
            ACCENT,
            () -> {
              if (settingsEditable()) {
                monitors.disable();
                ScopeApp.app.connectShizuku();
              }
            }));
    helper.addView(
        button(
            "Open helper manager",
            CARD,
            () -> {
              Intent i = getPackageManager().getLaunchIntentForPackage("com.hamondev.shevery");
              if (i == null)
                i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
              if (i != null) startActivity(i);
              else
                showText(
                    "Helper manager not installed",
                    "Install and start Shevery or Shizuku, or use Embedded ADB above.");
            }));
    body.addView(helper);
    section("NOTIFICATIONS");
    LinearLayout notifications = card();
    notifications.addView(
        button(
            "Enable / manage Android notifications",
            CARD,
            () -> {
              if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                  != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 25);
              else
                startActivity(
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }));
    toggle(
        notifications,
        "Recording and setup updates",
        "Started, paused, resumed, bookmarks, saved files, helper status, and automation. Recording"
            + " controls and repair notifications have their own channels.",
        "eventNotifications",
        true);
    notifications.addView(button("Notification history", CARD, this::notificationHistory));
    notifications.addView(
        button(
            "Send a test notification",
            CARD,
            () ->
                Notices.event(
                    "AudioScope notifications are working",
                    "Tap Details to open this message. Recording notifications include pause,"
                        + " bookmark, and stop controls.",
                    "settings")));
    body.addView(notifications);
    section("ADVANCED");
    LinearLayout advanced = card();
    LinearLayout content = column();
    content.setVisibility(View.GONE);
    advanced.addView(
        button(
            "Show / hide advanced setup",
            CARD,
            () ->
                content.setVisibility(
                    content.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
    content.addView(text("Manual ADB ports · use if discovery is unavailable", 16, INK));
    EditText pairPort = input("Pairing port", "", true),
        code = input("Six-digit pairing code", "", true),
        connectPort = input("Connection port", ScopeApp.prefs().getString("adbPort", ""), true);
    content.addView(pairPort);
    content.addView(code);
    content.addView(
        button(
            "Pair using manual port",
            CARD,
            () -> {
              try {
                EmbeddedAdb.pairDevice(
                    Integer.parseInt(pairPort.getText().toString()), code.getText().toString());
                code.setText("");
              } catch (Exception e) {
                toast("Enter Android’s current pairing port");
              }
            }));
    content.addView(connectPort);
    content.addView(
        button(
            "Connect using manual port",
            CARD,
            () -> {
              if (!settingsEditable()) return;
              try {
                EmbeddedAdb.launch(Integer.parseInt(connectPort.getText().toString()));
              } catch (Exception e) {
                toast("Enter Android’s current connection port");
              }
            }));
    content.addView(
        button("Open Wireless debugging", CARD, () -> PairingService.openWireless(this)));
    content.addView(
        button(
            "Arm Wi-Fi / VoIP playback before a call",
            CARD,
            () -> arm(Source.get("voice_playback"))));
    content.addView(
        button(
            "Disarm playback policies",
            CARD,
            () -> {
              if (settingsEditable())
                ScopeApp.IO.execute(
                    () -> {
                      try {
                        if (ScopeApp.bridge != null) ScopeApp.bridge.disarm();
                        Notices.event(
                            "Playback policies released",
                            "Routes will be registered again when needed.",
                            "settings");
                      } catch (Exception e) {
                        ScopeApp.log("ERROR", e.toString());
                      }
                    });
            }));
    content.addView(
        button(
            "Stop shell helper",
            CARD,
            () -> {
              if (settingsEditable())
                ScopeApp.IO.execute(
                    () -> {
                      try {
                        if (ScopeApp.bridge != null) ScopeApp.bridge.shutdown();
                      } catch (Exception ignored) {
                      }
                      ScopeApp.bridge = null;
                      ScopeApp.backend = "Not connected";
                    });
            }));
    toggle(content, "Save raw PCM too", "Headerless PCM16 with timing sidecars.", "raw", false);
    numberSetting(content, "Playback app UID · −1 = all apps", "uidFilter", -1, -1, 999999);
    numberSetting(content, "Silence threshold · dBFS", "silenceDb", -60, -120, -10);
    numberSetting(content, "Silence grace · seconds", "silenceSeconds", 5, 1, 60);
    toggle(
        content,
        "Signal-aware carrier fallback",
        "If carrier capture stays silent, also try separated call tracks and VoIP playback + mic.",
        "fallback",
        false);
    content.addView(
        button(
            "Output routing & mix controls",
            CARD,
            () -> {
              tab = 2;
              render();
            }));
    content.addView(
        button(
            "Enable off-Wi-Fi helper restart",
            CARD,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("ADB TCP restart")
                    .setMessage(
                        "This keeps an authorized ADB listener on port 5555 until reboot and may"
                            + " stop Shizuku. Disable it below when no longer needed.")
                    .setPositiveButton("Enable", (d, w) -> EmbeddedAdb.enableOfflineRestart())
                    .setNegativeButton("Cancel", null)
                    .show()));
    two(
        content,
        button("Restart port 5555", CARD, () -> EmbeddedAdb.launch(5555)),
        button("Disable listener", CARD, EmbeddedAdb::disableOfflineRestart));
    advanced.addView(content);
    body.addView(advanced);
    section("ABOUT");
    LinearLayout about = card();
    about.addView(text("AudioScope " + BuildConfig.VERSION_NAME, 17, INK));
    about.addView(
        text(
            "Local audio capture and processing. No account, analytics, uploads, or cloud"
                + " processing. GPLv3-or-later with upstream Section 7 terms; see the source"
                + " repository for attribution and license.",
            14,
            MUTED));
    about.addView(
        button(
            "AudioScope source",
            CARD,
            () -> openUrl("https://github.com/ibrahim91015/AudioScope")));
    about.addView(
        button(
            "CallVault reference", CARD, () -> openUrl("https://github.com/madkongo/CallVault")));
    about.addView(button("Shevery", CARD, () -> openUrl("https://github.com/HmnDev-Tech/shevery")));
    body.addView(about);
  }

  private void setup(String action) {
    if (!settingsEditable()) return;
    setupAction = action;
    if (!action.equals("DISARM_AUTO")
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 26);
      return;
    }
    if (action.equals("ARM_AUTO")
        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 27);
      return;
    }
    if (action.equals("PAIR") || action.equals("CONNECT")) {
      startForegroundService(
          new Intent(this, PairingService.class)
              .setAction(action.equals("PAIR") ? "START" : "CONNECT"));
      PairingService.openWireless(this);
    } else {
      startForegroundService(new Intent(this, CaptureService.class).setAction(action));
      handler.postDelayed(this::render, 300);
    }
  }

  private void notificationHistory() {
    try {
      JSONArray history = new JSONArray(ScopeApp.prefs().getString("noticeHistory", "[]"));
      if (history.length() == 0) {
        showText(
            "Notification history",
            "Recording, setup, saved-file updates, and repair messages will appear here, including"
                + " messages when Android notifications are disabled.");
        return;
      }
      String[] names = new String[history.length()];
      for (int i = 0; i < history.length(); i++) {
        JSONObject n = history.getJSONObject(i);
        names[i] =
            new java.text.SimpleDateFormat("MMM d · HH:mm", Locale.US)
                    .format(new Date(n.getLong("time")))
                + "\n"
                + n.getString("title");
      }
      new AlertDialog.Builder(this)
          .setTitle("Notification history")
          .setItems(
              names,
              (d, w) -> {
                try {
                  JSONObject n = history.getJSONObject(w);
                  showText(n.getString("title"), n.getString("detail"));
                } catch (Exception ignored) {
                }
              })
          .setNegativeButton("Close", null)
          .show();
    } catch (Exception e) {
      toast(e.toString());
    }
  }

  private boolean settingsEditable() {
    if (CaptureService.active() || CaptureService.stopping) {
      toast("Stop and finalize the session before changing setup or routing");
      return false;
    }
    return true;
  }

  private EditText input(String hint, String value, boolean numeric) {
    EditText e = new EditText(this);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setTextSize(Ui.sp(14));
    e.setSingleLine(true);
    e.setHint(hint);
    e.setText(value);
    e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    e.setInputType(
        numeric
            ? android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            : android.text.InputType.TYPE_CLASS_TEXT);
    return e;
  }

  private Spinner spinner(String[] labels, int selected) {
    return Ui.spinner(this, labels, selected);
  }

  private void toggle(LinearLayout c, String label, String detail, String key, boolean fallback) {
    com.google.android.material.materialswitch.MaterialSwitch s =
        new com.google.android.material.materialswitch.MaterialSwitch(this);
    s.setText(label);
    s.setTextColor(INK);
    s.setTextSize(Ui.sp(14));
    s.setPadding(0, dp(8), 0, dp(6));
    s.setChecked(ScopeApp.prefs().getBoolean(key, fallback));
    s.setEnabled(!CaptureService.active());
    s.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    s.setOnCheckedChangeListener((v, b) -> ScopeApp.prefs().edit().putBoolean(key, b).apply());
    c.addView(s);
    c.addView(text(detail, 11, MUTED));
  }

  private void choice(
      LinearLayout c, String label, String[] labels, String key, int[] values, int fallback) {
    c.addView(text(label, 12, MUTED));
    int value = ScopeApp.prefs().getInt(key, fallback), index = 0;
    for (int i = 0; i < values.length; i++) if (values[i] == value) index = i;
    Spinner s = spinner(labels, index);
    s.setEnabled(!CaptureService.active());
    s.setOnItemSelectedListener(
        listener(
            position -> {
              int old = ScopeApp.prefs().getInt(key, fallback);
              ScopeApp.prefs().edit().putInt(key, values[position]).apply();
              if (old != values[position]) disarmAfterFormatChange();
            }));
    c.addView(s);
  }

  private void stringChoice(
      LinearLayout c, String label, String[] labels, String key, String fallback) {
    c.addView(text(label, 12, MUTED));
    String value = ScopeApp.prefs().getString(key, fallback);
    int index = 0;
    for (int i = 0; i < labels.length; i++) if (labels[i].equals(value)) index = i;
    Spinner s = spinner(labels, index);
    s.setEnabled(!CaptureService.active());
    s.setOnItemSelectedListener(
        listener(position -> ScopeApp.prefs().edit().putString(key, labels[position]).apply()));
    c.addView(s);
  }

  private AdapterView.OnItemSelectedListener listener(java.util.function.IntConsumer action) {
    return new AdapterView.OnItemSelectedListener() {
      public void onNothingSelected(AdapterView<?> a) {}

      public void onItemSelected(AdapterView<?> a, View v, int p, long id) {
        action.accept(p);
      }
    };
  }

  private void sourceSpinner(LinearLayout c, String label, String key, String fallback) {
    c.addView(text(label, 12, MUTED));
    List<Source> available = Source.available();
    String[] names = available.stream().map(s -> s.title).toArray(String[]::new);
    int index = 0;
    for (int i = 0; i < available.size(); i++)
      if (available.get(i).id.equals(ScopeApp.prefs().getString(key, fallback))) index = i;
    Spinner s = spinner(names, index);
    s.setEnabled(!CaptureService.active());
    s.setOnItemSelectedListener(
        listener(p -> ScopeApp.prefs().edit().putString(key, available.get(p).id).apply()));
    c.addView(s);
  }

  private void numberSetting(
      LinearLayout c, String label, String key, int fallback, int min, int max) {
    c.addView(text(label, 12, MUTED));
    EditText e = input(label, String.valueOf(ScopeApp.prefs().getInt(key, fallback)), true);
    e.setEnabled(!CaptureService.active());
    e.setOnFocusChangeListener(
        (v, focus) -> {
          if (!focus) {
            try {
              int n = Integer.parseInt(e.getText().toString());
              if (n < min || n > max) throw new NumberFormatException();
              ScopeApp.prefs().edit().putInt(key, n).apply();
              if (key.equals("uidFilter")) disarmAfterFormatChange();
            } catch (Exception ex) {
              e.setText(String.valueOf(ScopeApp.prefs().getInt(key, fallback)));
              toast("Use a value from " + min + " to " + max);
            }
          }
        });
    c.addView(e);
  }

  private void disarmAfterFormatChange() {
    ScopeApp.IO.execute(
        () -> {
          try {
            if (ScopeApp.bridge != null) ScopeApp.bridge.disarm();
          } catch (Exception ignored) {
          }
        });
  }

  private void prompt(String title, String initial, java.util.function.Consumer<String> action) {
    EditText e = input(title, initial, false);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(e)
        .setPositiveButton(
            "Save",
            (d, w) -> {
              String s = e.getText().toString().trim();
              if (!s.isEmpty()) action.accept(s);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void savePreset(String name) {
    if (!settingsEditable()) return;
    try {
      JSONObject j = new JSONObject();
      j.put("sources", new JSONArray(selected));
      JSONObject settings = new JSONObject();
      for (Map.Entry<String, ?> e : ScopeApp.prefs().getAll().entrySet()) {
        String k = e.getKey();
        if (k.equals("rate")
            || k.equals("channels")
            || k.equals("uidFilter")
            || k.equals("codec")
            || k.equals("bitrate")
            || k.equals("raw")
            || k.equals("stereo")
            || k.equals("mix")
            || k.equals("mka")
            || k.startsWith("route")
            || k.startsWith("gain_")
            || k.startsWith("mute_")
            || k.startsWith("format_")) settings.put(k, e.getValue());
      }
      j.put("settings", settings);
      JSONObject all = new JSONObject(ScopeApp.prefs().getString("presets", "{}"));
      all.put(name, j);
      ScopeApp.prefs().edit().putString("presets", all.toString()).apply();
      toast("Saved " + name);
    } catch (Exception e) {
      toast(e.toString());
    }
  }

  private void loadPreset() {
    if (!settingsEditable()) return;
    try {
      JSONObject all = new JSONObject(ScopeApp.prefs().getString("presets", "{}"));
      List<String> names = new ArrayList<>();
      all.keys().forEachRemaining(names::add);
      if (names.isEmpty()) {
        toast("Save a preset first");
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("Load capture preset")
          .setItems(
              names.toArray(new String[0]),
              (d, w) -> {
                try {
                  JSONObject j = all.getJSONObject(names.get(w));
                  selected.clear();
                  JSONArray a = j.getJSONArray("sources");
                  for (int i = 0; i < a.length(); i++) selected.add(a.getString(i));
                  saveSelection();
                  JSONObject prefs = j.getJSONObject("settings");
                  SharedPreferences.Editor edit = ScopeApp.prefs().edit();
                  Iterator<String> keys = prefs.keys();
                  while (keys.hasNext()) {
                    String key = keys.next();
                    Object v = prefs.get(key);
                    if (v instanceof Boolean) edit.putBoolean(key, (Boolean) v);
                    else if (v instanceof Integer) edit.putInt(key, (Integer) v);
                    else if (v instanceof Number) edit.putFloat(key, ((Number) v).floatValue());
                    else edit.putString(key, v.toString());
                  }
                  edit.apply();
                  disarmAfterFormatChange();
                  render();
                } catch (Exception e) {
                  toast(e.toString());
                }
              })
          .show();
    } catch (Exception e) {
      toast(e.toString());
    }
  }

  private void showText(String title, String content) {
    ScrollView s = new ScrollView(this);
    TextView t = text(content, 14, INK);
    t.setPadding(dp(16), dp(12), dp(16), dp(12));
    t.setTextIsSelectable(true);
    s.addView(t);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(s)
        .setPositiveButton("Close", null)
        .show();
  }

  private void setIfChanged(TextView view, CharSequence value) {
    if (!view.getText().toString().contentEquals(value)) view.setText(value);
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private void openUrl(String url) {
    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
  }
}
