package com.tvbrowser.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 覆盖在网页上方的鼠标指针，由遥控器方向键控制。 */
public class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private float x, y;
    private boolean pressed;

    public CursorView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        fill.setColor(0xFFFFFFFF);
        stroke.setColor(0xFF000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * d);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        ring.setColor(0x88FF6A00);

        // 箭头形状，尖端在 (0,0)
        float s = 1.4f * d;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17 * s);
        arrow.lineTo(4.5f * s, 13 * s);
        arrow.lineTo(7.5f * s, 20 * s);
        arrow.lineTo(10.5f * s, 18.8f * s);
        arrow.lineTo(7.5f * s, 12 * s);
        arrow.lineTo(13 * s, 12 * s);
        arrow.close();
    }

    public float getCursorX() { return x; }
    public float getCursorY() { return y; }

    public void setPosition(float nx, float ny) {
        x = Math.max(0, Math.min(nx, getWidth() - 1));
        y = Math.max(0, Math.min(ny, getHeight() - 1));
        invalidate();
    }

    public void setPressed2(boolean p) {
        pressed = p;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw == 0) setPosition(w / 2f, h / 2f);
        else setPosition(x, y);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pressed) canvas.drawCircle(x, y, 18 * getResources().getDisplayMetrics().density, ring);
        canvas.save();
        canvas.translate(x, y);
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, stroke);
        canvas.restore();
    }
}
