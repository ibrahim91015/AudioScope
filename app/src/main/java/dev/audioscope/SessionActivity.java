package dev.audioscope;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;

/**
 * Dedicated recording detail screen, with system-bar/cutout padding and persistent media controls.
 */
public final class SessionActivity extends androidx.appcompat.app.AppCompatActivity {
  private LibraryPanel panel;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable update =
      new Runnable() {
        public void run() {
          if (panel != null) panel.update();
          handler.postDelayed(this, 200);
        }
      };

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    File folder =
        new File(
            ScopeApp.sessions(),
            getIntent().getStringExtra("session") == null
                ? ""
                : getIntent().getStringExtra("session"));
    try {
      if (!folder.isDirectory()
          || !folder
              .getCanonicalFile()
              .getParentFile()
              .equals(ScopeApp.sessions().getCanonicalFile())) {
        finish();
        return;
      }
    } catch (IOException e) {
      finish();
      return;
    }
    panel =
        new LibraryPanel(
            this,
            new LibraryPanel.Actions() {
              public void share(File f) {
                shareFile(f);
              }

              public void details(String title, String detail) {
                new AlertDialog.Builder(SessionActivity.this)
                    .setTitle(title)
                    .setMessage(detail)
                    .setPositiveButton("Close", null)
                    .show();
              }

              public void browse(File directory) {
                File[] files = directory.listFiles(File::isFile);
                if (files == null) return;
                java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
                String[] names = new String[files.length];
                for (int i = 0; i < files.length; i++)
                  names[i] = files[i].getName() + " · " + files[i].length() / 1024 + " KB";
                new AlertDialog.Builder(SessionActivity.this)
                    .setTitle("Files · tap to share")
                    .setItems(names, (d, i) -> shareFile(files[i]))
                    .setNegativeButton("Close", null)
                    .show();
              }
            },
            folder);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setBackgroundColor(Ui.BG);
    scroll.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 20));
    scroll.addView(panel.view());
    setContentView(scroll);
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        scroll,
        (v, insets) -> {
          androidx.core.graphics.Insets safe =
              insets.getInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars()
                      | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
          v.setPadding(
              safe.left + Ui.dp(this, 16),
              safe.top + Ui.dp(this, 12),
              safe.right + Ui.dp(this, 16),
              safe.bottom + Ui.dp(this, 20));
          return insets;
        });
    Ui.enter(panel.view());
  }

  private void shareFile(File f) {
    Intent i =
        new Intent(Intent.ACTION_SEND)
            .setType(
                f.getName().endsWith(".zip")
                    ? "application/zip"
                    : f.getName().endsWith(".json") ? "application/json" : "audio/*")
            .putExtra(Intent.EXTRA_STREAM, ShareProvider.uri(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivity(Intent.createChooser(i, "Share recording"));
  }

  public void onResume() {
    super.onResume();
    handler.post(update);
  }

  public void onPause() {
    handler.removeCallbacks(update);
    super.onPause();
  }
}
