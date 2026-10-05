package dev.audioscope;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.*;
import com.google.android.material.button.MaterialButton;

public final class Ui {
  public static final int BG = 0xff141019,
      CARD = 0xff251e30,
      INK = 0xfff1eaf9,
      MUTED = 0xffc1b5cd,
      RED = 0xffff8da0;

  public static boolean motion() {
    return ScopeApp.prefs().getBoolean("animations", true)
        && android.animation.ValueAnimator.areAnimatorsEnabled();
  }

  public static void enter(android.view.View view) {
    if (!motion()) return;
    view.setAlpha(0);
    view.setTranslationY(dp(view.getContext(), 6));
    view.animate()
        .alpha(1)
        .translationY(0)
        .setDuration(160)
        .setInterpolator(new android.view.animation.DecelerateInterpolator())
        .start();
  }

  public static void tint(TextView view, int color) {
    Object current = view.getTag(R.id.tint_target);
    if (current instanceof Integer && (Integer) current == color) return;
    view.setTag(R.id.tint_target, color);
    android.animation.ValueAnimator prior =
        (android.animation.ValueAnimator) view.getTag(R.id.tint_animator);
    if (prior != null) prior.cancel();
    if (!motion()) {
      view.setTextColor(color);
      return;
    }
    android.animation.ValueAnimator animator =
        android.animation.ValueAnimator.ofArgb(view.getCurrentTextColor(), color);
    animator.setDuration(160);
    animator.addUpdateListener(a -> view.setTextColor((Integer) a.getAnimatedValue()));
    view.setTag(R.id.tint_animator, animator);
    animator.start();
  }

  public static float sp(float size) {
    return size * ScopeApp.prefs().getFloat("uiTextScale", 1f);
  }

  public static int dp(Context c, float n) {
    return Math.round(c.getResources().getDisplayMetrics().density * n);
  }

  public static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public static GradientDrawable background(Context c, int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  public static TextView text(Context c, String value, int size, int color) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextColor(color);
    t.setTextSize(sp(size));
    t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(c, 1), 1.08f);
    t.setPadding(0, dp(c, 3), 0, dp(c, 3));
    return t;
  }

  public static LinearLayout card(Context c) {
    LinearLayout l = column(c);
    l.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
    l.setBackground(background(c, CARD, 22));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(c, 10);
    l.setLayoutParams(p);
    return l;
  }

  public static MaterialButton button(Context c, String title, boolean primary, Runnable action) {
    MaterialButton b = new MaterialButton(c);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextSize(sp(14));
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {MUTED, primary ? BG : INK}));
    b.setBackgroundTintList(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {0xff312738, primary ? ThemePalette.accent(c) : 0xff453654}));
    b.setCornerRadius(dp(c, 22));
    b.setInsetTop(0);
    b.setInsetBottom(0);
    b.setMinHeight(dp(c, 44));
    b.setMinimumHeight(dp(c, 44));
    b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
    b.setOnClickListener(v -> action.run());
    b.setOnTouchListener(
        (v, event) -> {
          if (motion()) {
            boolean down = event.getAction() == android.view.MotionEvent.ACTION_DOWN;
            if (down
                || event.getAction() == android.view.MotionEvent.ACTION_UP
                || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
              v.animate()
                  .scaleX(down ? .96f : 1f)
                  .scaleY(down ? .96f : 1f)
                  .setDuration(down ? 80 : 140)
                  .start();
          }
          return false;
        });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(c, 8);
    b.setLayoutParams(p);
    return b;
  }

  public static MaterialButton icon(Context c, String symbol, String description, Runnable action) {
    MaterialButton b = button(c, symbol, false, action);
    b.setContentDescription(description);
    b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(0, 0, 0, 0);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setCornerRadius(dp(c, 14));
    return b;
  }

  public static Spinner spinner(Context c, String[] labels, int selected) {
    Spinner s = new Spinner(c);
    ArrayAdapter<String> a =
        new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, labels) {
          public android.view.View getView(
              int i, android.view.View v, android.view.ViewGroup parent) {
            TextView t = (TextView) super.getView(i, v, parent);
            t.setTextColor(INK);
            t.setTextSize(sp(13));
            t.setSingleLine(true);
            return t;
          }

          public android.view.View getDropDownView(
              int i, android.view.View v, android.view.ViewGroup p) {
            TextView t = (TextView) super.getDropDownView(i, v, p);
            t.setTextColor(INK);
            t.setTextSize(sp(14));
            t.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
            return t;
          }
        };
    a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    s.setAdapter(a);
    s.setSelection(selected);
    s.setMinimumHeight(dp(c, 44));
    return s;
  }
}
