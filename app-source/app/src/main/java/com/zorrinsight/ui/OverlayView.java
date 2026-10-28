package com.zorrinsight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** 叠加在游戏画面右上角的白色数值层：滑板转速、移速、帧率、延迟。 */
public class OverlayView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String spin = "--";
    private String move = "--";
    private String fps = "--";
    private String ping = "--";

    public OverlayView(Context c, AttributeSet a) {
        super(c, a);
        float dp = getResources().getDisplayMetrics().density;
        p.setColor(Color.parseColor("#2A2D31"));
        p.setTextSize(11f * dp);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        shadow.setColor(0x00000000);
        shadow.setTextSize(11f * dp);
        shadow.setTypeface(p.getTypeface());
        // 叠在 WebView 之上，必须完全透明且不拦截触摸，否则游戏点不动
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    public void update(float spinDegPerSec, float movePxPerSec, float fpsVal, int pingMs) {
        spin = Float.isNaN(spinDegPerSec) ? "--" : String.format("%.0f°/s", spinDegPerSec);
        move = Float.isNaN(movePxPerSec) ? "--" : String.format("%.0fpx/s", movePxPerSec);
        fps = fpsVal <= 0 ? "--" : (fpsVal < 10 ? String.format("%.1f", fpsVal)
                : String.format("%.0f", fpsVal));
        ping = pingMs <= 0 ? "--" : pingMs + "ms";
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float dp = getResources().getDisplayMetrics().density;
        // 横排一行，放在游戏区之外的底栏，不遮挡游戏画面
        String text = "转速 " + spin + "   移速 " + move + "   采样 " + fps + "fps   延迟 " + ping;
        float y = getHeight() / 2f + 4 * dp;
        c.drawText(text, 1, y + 1, shadow);
        c.drawText(text, 0, y, p);
    }
}
