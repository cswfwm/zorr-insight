package com.zorrinsight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.zorrinsight.neural.Net;

/**
 * 神经网络结构图：输入层 → 隐层 → 输出层。
 *
 * 输入层有 292 维，不可能逐个画；隐层 24 个、输出 6 个可以照实画。
 * 画法：输入层按维度分组画成 12 条色带，隐层 24 个圆点，输出层 6 个圆点加标签。
 * 连线颜色表示权重正负，透明度表示权重绝对值，圆点亮度表示当前激活。
 */
public class NetView extends View {

    private Net net;
    private float[] input;
    private String[] labels;
    private boolean trained;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public NetView(Context c, AttributeSet a) {
        super(c, a);
        text.setColor(Color.parseColor("#7A7F85"));
        text.setTextSize(9f * getResources().getDisplayMetrics().scaledDensity);
        p.setStrokeWidth(0.6f * getResources().getDisplayMetrics().density);
    }

    public void bind(Net net, String[] outputLabels) {
        this.net = net;
        this.labels = outputLabels;
        invalidate();
    }

    /** 每次采集到新特征时调用，用于显示当前激活。 */
    public void setInput(float[] x, boolean trained) {
        this.input = x;
        this.trained = trained;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (net == null) {
            c.drawText("模型尚未初始化", 8, h / 2f, text);
            return;
        }

        float pad = 6f * getResources().getDisplayMetrics().density;
        // 输出层右侧要留出标签宽度，否则文字会被裁掉
        float labelW = text.measureText("独有 0.00") + 10;
        float leftX = pad + 10;
        float midX = w * 0.44f;
        float rightX = w - pad - labelW;

        // 输入层：按维度分成 12 组色带
        int groups = 12;
        float inTop = pad + 4, inBottom = h - pad - 4;
        float gh = (inBottom - inTop) / groups;
        float[] probs = trained ? net.lastProbs() : null;
        float[] hid = trained ? net.hiddenActivations() : null;
        float[] mags = net.hiddenMagnitudes();

        p.setStyle(Paint.Style.FILL);
        for (int g = 0; g < groups; g++) {
            float y = inTop + g * gh;
            // 用输入特征的对应片段决定色带亮度
            float v = 0;
            if (input != null && input.length > 0) {
                int step = Math.max(1, input.length / groups);
                for (int k = 0; k < step && g * step + k < input.length; k++) {
                    v += input[g * step + k];
                }
                v /= step;
            }
            int alpha = 40 + (int) (Math.min(1f, Math.abs(v)) * 170);
            p.setColor(Color.argb(alpha, 30, 167, 97));
            c.drawRoundRect(leftX - 5, y, leftX + 5, y + Math.max(1, gh - 2), 2, 2, p);
        }
        c.drawText("输入 " + net.in, leftX - 12, h - pad + 2, text);

        // 中线位置
        float midTop = pad + 4, midBottom = h - pad - 4;
        float midStep = (midBottom - midTop) / Math.max(1, net.hidden - 1);
        // 24 个点在有限高度里会挤在一起，半径跟着间距缩放
        float dotR = Math.max(1.2f, Math.min(3.2f, midStep * 0.34f));

        // 隐层圆点 + 连接线
        for (int i = 0; i < net.hidden; i++) {
            float y = midTop + i * midStep;
            float mag = mags[i];
            float act = hid == null ? 0 : Math.min(1f, Math.abs(hid[i]) / 3f);

            // 输入层到隐层：按权重符号着色
            p.setColor(Color.argb((int) (18 + mag * 90), 90, 120, 160));
            c.drawLine(leftX + 6, inTop + (i % groups) * gh + gh / 2, midX - 4, y, p);
            // 隐层到输出层
            p.setColor(Color.argb((int) (14 + mag * 60), 30, 167, 97));
            c.drawLine(midX + 4, y, rightX - 4, inTop + (i % Math.max(1, net.out)) * 4 + 4, p);

            p.setStyle(Paint.Style.FILL);
            int a = (int) (70 + act * 180);
            p.setColor(Color.argb(a, 30, 167, 97));
            float r = dotR * (1f + act * 0.5f);
            c.drawCircle(midX, y, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Color.parseColor("#DDE1E4"));
            c.drawCircle(midX, y, r, p);
            p.setStyle(Paint.Style.FILL);
        }

        // 输出层
        float outTop = pad + 6, outBottom = h - pad - 6;
        float outStep = net.out > 1 ? (outBottom - outTop) / (net.out - 1) : 0;
        for (int i = 0; i < net.out; i++) {
            float y = outTop + i * outStep;
            float pr = probs == null ? 0 : probs[i];
            p.setColor(Color.argb((int) (60 + pr * 195), 78, 90, 110));
            c.drawCircle(rightX, y, 2.4f + pr * 3f, p);

            if (labels != null && i < labels.length) {
                String name = labels[i];
                text.setColor(pr > 0.5f ? Color.parseColor("#1EA167") : Color.parseColor("#9AA0A6"));
                c.drawText(pr > 0.01f ? String.format("%s %.2f", name, pr) : name,
                        rightX + 7, y + 3, text);
            }
        }
        text.setColor(Color.parseColor("#7A7F85"));

        // 右上角标注结构
        String dim = net.in + " → " + net.hidden + " → " + net.out;
        c.drawText(dim, w - text.measureText(dim) - pad, pad + 9, text);
    }
}
