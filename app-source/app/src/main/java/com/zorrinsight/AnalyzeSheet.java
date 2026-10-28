package com.zorrinsight;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.zorrinsight.ui.InsightPanel;

/** 从底部弹出的解析面板：画面检测到的单位 + 图鉴查询。 */
public final class AnalyzeSheet {

    public static InsightPanel show(Activity act, InsightPanel.ContextProvider ctx) {
        Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.25f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        InsightPanel panel = new InsightPanel(act, ctx);
        d.setContentView(panel);
        d.show();

        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.88f));
            w.setGravity(Gravity.BOTTOM);
        }
        panel.setDismiss(d::dismiss);
        return panel;
    }
}
