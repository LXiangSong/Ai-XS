package app.aixs.mobile;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Small context-window ring: quiet until the window starts filling up. */
final class ContextRing extends View {
  private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF bounds = new RectF();
  private final float density;
  private float progress;
  private int tint = Ui.accent;

  ContextRing(Context context) {
    super(context);
    density = context.getResources().getDisplayMetrics().density;
    track.setStyle(Paint.Style.STROKE);
    track.setStrokeWidth(2f * density);
    track.setColor(Ui.lineSoft);
    arc.setStyle(Paint.Style.STROKE);
    arc.setStrokeWidth(2f * density);
    arc.setStrokeCap(Paint.Cap.ROUND);
    arc.setColor(tint);
  }

  void setProgress(float value, int color) {
    progress = Math.max(0f, Math.min(1f, value));
    tint = color;
    arc.setColor(color);
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float stroke = 2f * density;
    float inset = stroke / 2f + density;
    bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
    canvas.drawArc(bounds, 0f, 360f, false, track);
    if (progress > 0f) canvas.drawArc(bounds, -90f, 360f * progress, false, arc);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int size = (int) (18 * density + 0.5f);
    setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
  }
}
