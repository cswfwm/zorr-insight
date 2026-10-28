package com.zorrinsight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** 训练曲线：损失下降（灰）与准确率上升（绿）叠加在同一坐标系。 */
public class SparkView extends View {

    private float[] loss = new float[0];
    private float[] acc = new float[0];
    private final Paint pLoss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAcc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public SparkView(Context c, AttributeSet a) {
        super(c, a);
        pLoss.setStyle(Paint.Style.STROKE);
        pLoss.setStrokeWidth(1.6f * getResources().getDisplayMetrics().density);
        pLoss.setColor(Color.parseColor("#B9BEC4"));
        pAcc.setStyle(Paint.Style.STROKE);
        pAcc.setStrokeWidth(1.6f * getResources().getDisplayMetrics().density);
        pAcc.setColor(Color.parseColor("#1EA761"));
        pBase.setStyle(Paint.Style.STROKE);
        pBase.setStrokeWidth(1f);
        pBase.setColor(Color.parseColor("#F0F1F3"));
        pText.setColor(Color.parseColor("#AAB0B6"));
        pText.setTextSize(9f * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setData(float[] loss, float[] acc) {
        this.loss = loss == null ? new float[0] : loss;
        this.acc = acc == null ? new float[0] : acc;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pad = 4f * getResources().getDisplayMetrics().density;
        for (int i = 1; i < 4; i++) {
            float y = pad + (h - 2 * pad) * i / 4f;
            c.drawLine(pad, y, w - pad, y, pBase);
        }

        if (loss.length > 1) drawSeries(c, loss, true, w, h, pad, pLoss);
        if (acc.length > 1) drawSeries(c, acc, false, w, h, pad, pAcc);

        if (loss.length <= 1) {
            c.drawText("训练开始后显示曲线", pad + 2, h / 2f + 3, pText);
        }
    }

    private void drawSeries(Canvas c, float[] data, boolean normalizeMax,
                            int w, int h, float pad, Paint p) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : data) {
            if (Float.isNaN(v)) continue;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (max - min < 1e-6f) {
            max = min + 1;
        }
        if (!normalizeMax) {
            // 准确率固定在 0..1，便于横向比较
            min = 0;
            max = 1;
        }
        path.reset();
        float span = Math.max(1, data.length - 1);
        float usableW = w - 2 * pad;
        float usableH = h - 2 * pad;
        boolean started = false;
        for (int i = 0; i < data.length; i++) {
            float v = data[i];
            if (Float.isNaN(v)) continue;
            float nx = pad + usableW * i / span;
            float ny = pad + usableH * (1f - (v - min) / (max - min));
            if (!started) {
                path.moveTo(nx, ny);
                started = true;
            } else {
                path.lineTo(nx, ny);
            }
        }
        c.drawPath(path, p);
    }
}
