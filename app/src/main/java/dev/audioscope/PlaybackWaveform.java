package dev.audioscope;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.*;
import java.io.*;
import java.util.function.DoubleConsumer;

/** Actual PCM peaks, cached locally. Touch position maps directly to playback time. */
public final class PlaybackWaveform extends View {
  private static final java.util.concurrent.ExecutorService WORKERS =
      java.util.concurrent.Executors.newFixedThreadPool(2);
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private float[] peaks;
  private float progress, targetProgress;
  private android.animation.ValueAnimator progressAnimator;
  private final DoubleConsumer seek;
  private boolean disposed;

  public PlaybackWaveform(Context c, File wav, DoubleConsumer seek) {
    super(c);
    this.seek = seek;
    setFocusable(true);
    setContentDescription("Recording waveform. Tap or drag to seek.");
    WORKERS.execute(
        () -> {
          try {
            float[] values = peaks(wav);
            post(
                () -> {
                  if (!disposed) {
                    peaks = values;
                    invalidate();
                  }
                });
          } catch (Exception e) {
            ScopeApp.log("WARN", "Waveform: " + e);
          }
        });
  }

  private static float[] peaks(File wav) throws IOException {
    int bins = 360;
    File cache = new File(wav.getParent(), wav.getName() + ".peaks");
    try (DataInputStream in = new DataInputStream(new FileInputStream(cache))) {
      if (in.readLong() == wav.length() && in.readLong() == wav.lastModified()) {
        float[] result = new float[bins];
        for (int i = 0; i < bins; i++) result[i] = in.readFloat();
        return result;
      }
    } catch (IOException ignored) {
    }
    float[] result = new float[bins];
    try (WavFile.Reader info = new WavFile.Reader(wav);
        DataInputStream in =
            new DataInputStream(new BufferedInputStream(new FileInputStream(wav), 65536))) {
      byte[] header = new byte[44];
      in.readFully(header);
      long samples = info.frames * info.channels;
      for (long i = 0; i < samples; i++) {
        short v = (short) (in.readUnsignedByte() | (in.readByte() << 8));
        int index = (int) Math.min(bins - 1, i * bins / Math.max(1, samples));
        result[index] = Math.max(result[index], Math.abs((int) v) / 32768f);
      }
    }
    float max = .015f;
    for (float p : result) max = Math.max(max, p);
    for (int i = 0; i < bins; i++) result[i] /= max;
    try (DataOutputStream out = new DataOutputStream(new FileOutputStream(cache))) {
      out.writeLong(wav.length());
      out.writeLong(wav.lastModified());
      for (float p : result) out.writeFloat(p);
    }
    return result;
  }

  public void progress(float p) {
    float next = Math.max(0, Math.min(1, p));
    if (next == targetProgress) return;
    targetProgress = next;
    if (progressAnimator != null) progressAnimator.cancel();
    if (!Ui.motion()) {
      progress = next;
      invalidate();
      return;
    }
    if (next == progress) return;
    progressAnimator = android.animation.ValueAnimator.ofFloat(progress, next);
    progressAnimator.setDuration(Math.abs(next - progress) > .1f ? 120 : 220);
    progressAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
    progressAnimator.addUpdateListener(
        a -> {
          progress = (Float) a.getAnimatedValue();
          invalidate();
        });
    progressAnimator.start();
  }

  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int accent = ThemePalette.accent(getContext());
    float mid = getHeight() / 2f;
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeWidth(Ui.dp(getContext(), 2));
    int bars = Math.max(1, getWidth() / Ui.dp(getContext(), 5));
    for (int i = 0; i < bars; i++) {
      float x = (i + .5f) * getWidth() / bars;
      float amplitude =
          peaks == null ? 0 : peaks[Math.min(peaks.length - 1, i * peaks.length / bars)];
      float h =
          Math.max(Ui.dp(getContext(), 1), amplitude * (getHeight() / 2f - Ui.dp(getContext(), 5)));
      paint.setColor(x / getWidth() <= progress ? accent : 0xff6c5b80);
      canvas.drawLine(x, mid - h, x, mid + h, paint);
    }
    paint.setColor(Ui.INK);
    paint.setStrokeWidth(Ui.dp(getContext(), 1));
    canvas.drawLine(
        progress * getWidth(),
        Ui.dp(getContext(), 3),
        progress * getWidth(),
        getHeight() - Ui.dp(getContext(), 3),
        paint);
  }

  public boolean onTouchEvent(MotionEvent e) {
    if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
      getParent().requestDisallowInterceptTouchEvent(true);
      seek.accept(Math.max(0, Math.min(1, e.getX() / getWidth())));
      return true;
    }
    if (e.getAction() == MotionEvent.ACTION_UP) {
      performClick();
      getParent().requestDisallowInterceptTouchEvent(false);
      return true;
    }
    return super.onTouchEvent(e);
  }

  public boolean performClick() {
    super.performClick();
    return true;
  }

  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo n) {
    super.onInitializeAccessibilityNodeInfo(n);
    n.setClassName("android.widget.SeekBar");
    n.setRangeInfo(
        AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, 100, progress * 100));
    n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
  }

  public boolean performAccessibilityAction(int action, Bundle args) {
    if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
      seek.accept(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) / 100);
      return true;
    }
    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
      seek.accept(
          Math.max(
              0,
              Math.min(
                  1,
                  progress
                      + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? .05 : -.05))));
      return true;
    }
    return super.performAccessibilityAction(action, args);
  }

  protected void onDetachedFromWindow() {
    disposed = true;
    if (progressAnimator != null) progressAnimator.cancel();
    super.onDetachedFromWindow();
  }
}
