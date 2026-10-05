package dev.audioscope;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public class WaveformView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int color;
  private CaptureService.Track track;

  public WaveformView(Context c, int color) {
    super(c);
    this.color = color;
    setContentDescription("Live horizontal audio waveform; waiting for capture");
  }

  private int lastPosition = -1;
  private final float[] displayed = new float[160];
  private float smoothPosition;
  private boolean recording;

  public void recording(boolean value) {
    if (recording != value) {
      recording = value;
      invalidate();
    }
  }

  public void update(CaptureService.Track t) {
    if (track != t || t != null && t.histPos != lastPosition) {
      if (track != t) {
        java.util.Arrays.fill(displayed, 0);
        smoothPosition = 0;
      }
      track = t;
      lastPosition = t == null ? -1 : t.histPos;
      invalidate();
    }
  }

  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth(), h = getHeight(), middle = h / 2;
    paint.setColor(0xff2b3840);
    paint.setStrokeWidth(1);
    for (int i = 1; i <= 4; i++) canvas.drawLine(w * i / 5, 5, w * i / 5, h - 5, paint);
    canvas.drawLine(0, middle, w, middle, paint);
    paint.setColor(recording ? color : 0xff938d9c);
    paint.setStrokeWidth(Math.max(2, w / 160 * .55f));
    paint.setStrokeCap(Paint.Cap.ROUND);
    if (track == null) return;
    int pos = track.histPos, n = track.history.length;
    boolean smooth = Ui.motion() && ScopeApp.prefs().getBoolean("smoothWaveforms", true);
    if (!smooth || smoothPosition == 0 || pos < smoothPosition || pos - smoothPosition > 30)
      smoothPosition = pos;
    else smoothPosition += (pos - smoothPosition) * .24f;
    int base = (int) smoothPosition;
    float fraction = smoothPosition - base;
    for (int i = 0; i < n; i++) {
      int age = n - 1 - i;
      if (pos <= age) continue;
      float a = track.history[Math.floorMod(base - n + i, n)];
      float b = track.history[Math.floorMod(base - n + i + 1, n)];
      float target = a + (b - a) * fraction;
      displayed[i] += (target - displayed[i]) * (smooth ? .3f : 1f);
      float amplitude = displayed[i];
      float length = (float) Math.pow(Math.min(1, amplitude), .55) * h * .43f;
      float x = w * i / n;
      canvas.drawLine(x, middle - Math.max(1, length), x, middle + Math.max(1, length), paint);
    }
    if (smooth && track.running && isShown()) postInvalidateOnAnimation();
  }
}
