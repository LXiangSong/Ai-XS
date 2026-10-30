package app.aixs.mobile;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;

/**
 * Ai-XS design system: one palette, one type scale, one spacing rhythm.
 * Screens compose these primitives instead of styling widgets inline, so the
 * whole app keeps the same voice in light and dark themes.
 */
final class Ui {
  // ---- color roles -------------------------------------------------------
  static int bg, surface, card, cardHigh, variant, text, muted, faint, line, lineSoft,
      accent, onAccent, accentSoft, onAccentSoft, mint, danger, dangerSoft, warn;
  static boolean light = true;

  static void init(Context c) {
    bg = c.getColor(R.color.xs_bg);
    surface = c.getColor(R.color.xs_surface);
    card = c.getColor(R.color.xs_surface_container);
    cardHigh = c.getColor(R.color.xs_surface_container_high);
    variant = c.getColor(R.color.xs_surface_variant);
    text = c.getColor(R.color.xs_on_surface);
    muted = c.getColor(R.color.xs_on_surface_variant);
    faint = c.getColor(R.color.xs_on_surface_faint);
    line = c.getColor(R.color.xs_outline);
    lineSoft = c.getColor(R.color.xs_outline_variant);
    accent = c.getColor(R.color.xs_primary);
    onAccent = c.getColor(R.color.xs_on_primary);
    accentSoft = c.getColor(R.color.xs_primary_container);
    onAccentSoft = c.getColor(R.color.xs_on_primary_container);
    mint = c.getColor(R.color.xs_secondary);
    danger = c.getColor(R.color.xs_error);
    dangerSoft = c.getColor(R.color.xs_error_container);
    warn = c.getColor(R.color.xs_warning);
    light = (c.getResources().getConfiguration().uiMode & 0x30) != 0x20;
  }

  // ---- metrics -----------------------------------------------------------
  static int dp(Context c, int value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }
  static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
  static int alpha(int color, float ratio) {
    return ColorUtils.setAlphaComponent(color, Math.round(255 * Math.min(1f, Math.max(0f, ratio))));
  }
  static int blend(int base, int over, float ratio) { return ColorUtils.blendARGB(base, over, ratio); }

  // ---- type --------------------------------------------------------------
  static Typeface medium(Context c) { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }
  static Typeface bold(Context c) { return Typeface.create("sans-serif", Typeface.BOLD); }
  static Typeface mono(Context c) { return Typeface.create("monospace", Typeface.NORMAL); }

  // ---- text factories ----------------------------------------------------
  static TextView text(Context c, CharSequence value, float size, int color, Typeface face) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    if (face != null) t.setTypeface(face);
    t.setIncludeFontPadding(false);
    t.setGravity(Gravity.CENTER_VERTICAL);
    return t;
  }
  static TextView display(Context c, String value) {
    TextView t = text(c, value, 25, text, medium(c));
    t.setLetterSpacing(-0.02f);
    t.setLineSpacing(dp(c, 2), 1f);
    return t;
  }
  static TextView title(Context c, String value) { return text(c, value, 17, text, medium(c)); }
  static TextView subtitle(Context c, String value) {
    TextView t = text(c, value, 14.5f, text, null);
    t.setLineSpacing(dp(c, 5), 1f);
    return t;
  }
  static TextView body(Context c, String value) {
    TextView t = text(c, value, 14.5f, text, null);
    t.setLineSpacing(dp(c, 5), 1f);
    t.setTextIsSelectable(true);
    return t;
  }
  static TextView meta(Context c, String value) { return text(c, value, 12, muted, null); }
  /** Compact status capsule; padding keeps the label clear of the rounded edges. */
  static TextView pill(Context c, CharSequence value, int fg, int bg) {
    TextView t = text(c, value, 10.5f, fg, medium(c));
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5));
    t.setBackground(round(bg, 999f));
    return t;
  }
  static TextView pill(Context c, CharSequence value) { return pill(c, value, muted, cardHigh); }
  static TextView sectionLabel(Context c, String value) {
    TextView t = text(c, value, 11, faint, medium(c));
    t.setLetterSpacing(0.09f);
    t.setAllCaps(true);
    return t;
  }

  // ---- boxes -------------------------------------------------------------
  static LinearLayout col(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }
  static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }
  static LinearLayout.LayoutParams w(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
  /** 呼吸式明暗，视图离开窗口时自动取消。 */
  static void pulse(final View view) {
    final ObjectAnimator animator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.45f);
    animator.setDuration(820);
    animator.setRepeatMode(ValueAnimator.REVERSE);
    animator.setRepeatCount(ValueAnimator.INFINITE);
    view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
      @Override public void onViewAttachedToWindow(View v) { animator.start(); }
      @Override public void onViewDetachedFromWindow(View v) { animator.cancel(); }
    });
    animator.start();
  }

  /** 在标签后循环追加「·」「··」「···」，表示仍在进行。 */
  static void ellipsis(final TextView view, final String base) {
    final Handler handler = new Handler(Looper.getMainLooper());
    final int[] step = {0};
    final Runnable tick = new Runnable() {
      @Override public void run() {
        step[0] = (step[0] + 1) % 4;
        StringBuilder label = new StringBuilder(base);
        for (int i = 0; i < step[0]; i++) label.append('·');
        view.setText(label.toString());
        handler.postDelayed(this, 420);
      }
    };
    view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
      @Override public void onViewAttachedToWindow(View v) { handler.post(tick); }
      @Override public void onViewDetachedFromWindow(View v) { handler.removeCallbacks(tick); }
    });
    handler.post(tick);
  }

  static LinearLayout.LayoutParams fill() { return new LinearLayout.LayoutParams(-1, -2); }
  static LinearLayout.LayoutParams fill(int height) { return new LinearLayout.LayoutParams(-1, height); }
  static LinearLayout.LayoutParams weigh(float weight) {
    return new LinearLayout.LayoutParams(0, -2, weight);
  }
  static LinearLayout.LayoutParams weighHeight(float weight) {
    return new LinearLayout.LayoutParams(0, 0, weight);
  }
  static void pad(View v, int l, int t, int r, int b) {
    v.setPadding(dp(v.getContext(), l), dp(v.getContext(), t), dp(v.getContext(), r), dp(v.getContext(), b));
  }
  static void margin(View v, int l, int t, int r, int b) {
    ViewGroup.LayoutParams raw = v.getLayoutParams();
    LinearLayout.LayoutParams lp =
        raw instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) raw : fill();
    lp.setMargins(dp(v.getContext(), l), dp(v.getContext(), t), dp(v.getContext(), r), dp(v.getContext(), b));
    v.setLayoutParams(lp);
  }
  static void gap(LinearLayout parent, int height) {
    View v = new View(parent.getContext());
    parent.addView(v, w(1, dp(parent.getContext(), height)));
  }

  // ---- shapes ------------------------------------------------------------
  static GradientDrawable round(int color, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(radius);
    return d;
  }
  static GradientDrawable round(Context c, int color, float radius, float strokeWidth, int strokeColor) {
    GradientDrawable d = round(color, dp(c, radius));
    if (strokeWidth > 0) d.setStroke(Math.max(1, dp(c, strokeWidth)), strokeColor);
    return d;
  }
  static GradientDrawable corners(int color, float tl, float tr, float br, float bl) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadii(new float[] {tl, tl, tr, tr, br, br, bl, bl});
    return d;
  }
  static GradientDrawable corners(Context c, int color, float tl, float tr, float br, float bl, int stroke, int strokeColor) {
    GradientDrawable d = corners(color, dp(c, tl), dp(c, tr), dp(c, br), dp(c, bl));
    if (stroke > 0) d.setStroke(Math.max(1, dp(c, stroke)), strokeColor);
    return d;
  }
  static void ripple(View v, int color) {
    float ratio = light ? 0.10f : 0.15f;
    v.setBackground(new RippleDrawable(ColorStateList.valueOf(alpha(color, ratio)), v.getBackground(), null));
  }
  /** Ripple clipped to the same rounded shape, so a press never bleeds past the card. */
  static void rippleRounded(View v, int radiusDp, int color) {
    Drawable background = v.getBackground();
    GradientDrawable mask = round(Color.WHITE, dp(v.getContext(), radiusDp));
    float ratio = light ? 0.10f : 0.15f;
    v.setBackground(new RippleDrawable(ColorStateList.valueOf(alpha(color, ratio)), background, mask));
  }
  static void touchable(View v) {
    v.setClickable(true);
    v.setFocusable(true);
  }

  // ---- primitives --------------------------------------------------------
  /** Compact icon-only control with a 44dp touch target and a soft round ripple. */
  static LinearLayout iconButton(Context c, int icon, String description, Runnable click) {
    LinearLayout box = new LinearLayout(c);
    box.setGravity(Gravity.CENTER);
    box.setContentDescription(description);
    box.setBackground(round(Color.TRANSPARENT, dp(c, 14)));
    rippleRounded(box, 14, muted);
    ImageView image = new ImageView(c);
    image.setImageResource(icon);
    image.setColorFilter(muted);
    box.addView(image, w(dp(c, 22), dp(c, 22)));
    touchable(box);
    box.setOnClickListener(v -> click.run());
    box.setLayoutParams(w(dp(c, 44), dp(c, 44)));
    return box;
  }

  /** Small rounded label used for agent / model / effort selectors. */
  static LinearLayout pill(Context c, int icon, String value, boolean accented, Runnable click) {
    LinearLayout p = row(c);
    p.setGravity(Gravity.CENTER);
    int fill = accented ? accentSoft : cardHigh;
    p.setBackground(round(fill, dp(c, 999)));
    pad(p, 10, 0, 12, 0);
    p.setMinimumHeight(dp(c, 30));
    if (icon != 0) {
      ImageView image = new ImageView(c);
      image.setImageResource(icon);
      image.setColorFilter(accented ? onAccentSoft : muted);
      p.addView(image, w(dp(c, 14), dp(c, 14)));
      ((LinearLayout.LayoutParams) image.getLayoutParams()).setMarginEnd(dp(c, 6));
    }
    TextView label = text(c, value, 12, accented ? onAccentSoft : muted, medium(c));
    label.setSingleLine(true);
    label.setEllipsize(TextUtils.TruncateAt.END);
    p.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
    if (click != null) {
      touchable(p);
      rippleRounded(p, 999, accent);
      p.setOnClickListener(v -> click.run());
    }
    return p;
  }

  static View divider(Context c, int inset) {
    View v = new View(c);
    v.setBackgroundColor(lineSoft);
    LinearLayout.LayoutParams lp = fill(Math.max(1, dp(c, 0.6f)));
    lp.setMarginStart(dp(c, inset));
    v.setLayoutParams(lp);
    return v;
  }

  /** Card that groups consecutive rows into one calm surface. */
  static LinearLayout group(LinearLayout parent) {
    Context c = parent.getContext();
    LinearLayout g = col(c);
    g.setBackground(round(c, card, 20, 0.8f, lineSoft));
    g.setClipToOutline(true);
    LinearLayout.LayoutParams lp = fill();
    lp.bottomMargin = dp(c, 14);
    parent.addView(g, lp);
    return g;
  }

  /** One tappable row inside a group: icon tile, title, detail, chevron. */
  static LinearLayout rowItem(LinearLayout group, int icon, String heading, String detail, Runnable click) {
    Context c = group.getContext();
    if (group.getChildCount() > 0) group.addView(divider(c, 16));
    LinearLayout r = row(c);
    pad(r, 14, 13, 12, 13);
    r.setMinimumHeight(dp(c, 58));
    if (icon != 0) {
      LinearLayout tile = new LinearLayout(c);
      tile.setGravity(Gravity.CENTER);
      tile.setBackground(round(alpha(accent, light ? 0.10f : 0.16f), dp(c, 11)));
      ImageView image = new ImageView(c);
      image.setImageResource(icon);
      image.setColorFilter(accent);
      tile.addView(image, w(dp(c, 18), dp(c, 18)));
      r.addView(tile, w(dp(c, 34), dp(c, 34)));
    }
    LinearLayout labels = col(c);
    r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    margin(labels, icon != 0 ? 12 : 2, 0, 8, 0);
    TextView name = text(c, heading, 15, text, medium(c));
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(name);
    if (detail != null && !detail.isEmpty()) {
      TextView note = text(c, detail, 12.5f, muted, null);
      note.setMaxLines(2);
      note.setEllipsize(TextUtils.TruncateAt.END);
      note.setLineSpacing(dp(c, 2), 1f);
      LinearLayout.LayoutParams lp = fill();
      lp.topMargin = dp(c, 3);
      labels.addView(note, lp);
    }
    if (click != null) {
      ImageView arrow = new ImageView(c);
      arrow.setImageResource(R.drawable.ic_chevron);
      arrow.setColorFilter(faint);
      arrow.setAlpha(0.75f);
      r.addView(arrow, w(dp(c, 18), dp(c, 18)));
      touchable(r);
      rippleRounded(r, 14, text);
      r.setOnClickListener(v -> click.run());
    }
    group.addView(r);
    return r;
  }

  /** Muted explanatory line that sits between groups. */
  static TextView note(LinearLayout parent, String value) {
    Context c = parent.getContext();
    TextView t = text(c, value, 12.5f, muted, null);
    t.setLineSpacing(dp(c, 3), 1f);
    LinearLayout.LayoutParams lp = fill();
    lp.setMargins(dp(c, 4), 0, dp(c, 4), dp(c, 10));
    parent.addView(t, lp);
    return t;
  }

  static void empty(LinearLayout parent, int icon, String heading, String caption) {
    Context c = parent.getContext();
    LinearLayout box = col(c);
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    pad(box, 16, 40, 16, 16);
    if (icon != 0) {
      LinearLayout badge = new LinearLayout(c);
      badge.setGravity(Gravity.CENTER);
      badge.setBackground(round(alpha(accent, light ? 0.10f : 0.18f), dp(c, 18)));
      ImageView image = new ImageView(c);
      image.setImageResource(icon);
      image.setColorFilter(accent);
      badge.addView(image, w(dp(c, 22), dp(c, 22)));
      LinearLayout.LayoutParams lp = w(dp(c, 52), dp(c, 52));
      lp.bottomMargin = dp(c, 16);
      box.addView(badge, lp);
    }
    TextView title = text(c, heading, 17, text, medium(c));
    title.setGravity(Gravity.CENTER);
    box.addView(title);
    if (caption != null && !caption.isEmpty()) {
      TextView sub = text(c, caption, 13.5f, muted, null);
      sub.setGravity(Gravity.CENTER);
      sub.setLineSpacing(dp(c, 4), 1f);
      LinearLayout.LayoutParams lp = fill();
      lp.topMargin = dp(c, 8);
      lp.leftMargin = dp(c, 12);
      lp.rightMargin = dp(c, 12);
      box.addView(sub, lp);
    }
    parent.addView(box, fill());
  }

  // ---- motion ------------------------------------------------------------
  static void enter(View view, int index) {
    Context c = view.getContext();
    view.setAlpha(0f);
    view.setTranslationY(dp(c, 6));
    view.animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(Math.min(index, 6) * 22L)
        .setDuration(190L)
        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
        .start();
  }
}
