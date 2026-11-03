package com.rampey.materialtv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;

/** Lightweight glass lighting for Android 9: translucent tint, edge and a static reflection. */
final class GlassDrawable extends GradientDrawable {
    private final Paint reflection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF();
    private final float radius;
    private final int edgeWidth;
    private int ink;
    private int surface;
    private boolean styled;

    GlassDrawable(float radius, int edgeWidth) {
        this.radius = radius;
        this.edgeWidth = edgeWidth;
        setCornerRadius(radius);
    }

    void setGlassColors(int surface, int highlight) {
        if (styled && this.surface == surface && ink == highlight) return;
        boolean reflectionChanged = !styled || ink != highlight;
        styled = true;
        this.surface = surface;
        setColor(alpha(surface, 0xB8));
        setStroke(edgeWidth, alpha(highlight, 0x60));
        ink = highlight;
        if (reflectionChanged) updateReflection();
    }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        area.set(bounds);
        updateReflection();
    }

    private void updateReflection() {
        if (area.height() <= 0) return;
        reflection.setShader(new LinearGradient(0, area.top, 0, area.bottom,
                new int[]{alpha(ink, 0x26), alpha(ink, 0), alpha(ink, 0x0C)},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        super.draw(canvas);
        canvas.drawRoundRect(area, radius, radius, reflection);
    }

    private static int alpha(int color, int opacity) {
        return (color & 0x00FFFFFF) | (opacity << 24);
    }
}
