package com.zorrinsight;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.zorrinsight.data.PetalNames;
import com.zorrinsight.data.WikiData;

/** 背包：展示从页面只读采集到的槽位。 */
public class BagActivity extends AppCompatActivity {

    private static final String[] RAR_COLOR = {
            "#7EEF6D", "#FFE65D", "#4D52E3", "#861FDE", "#DE1F1F",
            "#1FDBDE", "#FF2B75", "#2BFFA3", "#FFD700", "#C0C4C8"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("背包");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#1B1B1B"));
        root.addView(title);

        String name = getIntent().getStringExtra("name");
        String xp = getIntent().getStringExtra("xp");
        int[] rarities = getIntent().getIntArrayExtra("petals");
        int[] ids = getIntent().getIntArrayExtra("ids");
        int[] sx = getIntent().getIntArrayExtra("sx");
        int[] sy = getIntent().getIntArrayExtra("sy");
        int[] sw = getIntent().getIntArrayExtra("sw");
        int[] sh = getIntent().getIntArrayExtra("sh");
        String framePath = getIntent().getStringExtra("framePath");
        int vw = getIntent().getIntExtra("vw", 0);
        int webW = getIntent().getIntExtra("webW", 0);
        PetalNames names = new PetalNames(this);

        android.graphics.Bitmap frame = null;
        if (framePath != null) {
            frame = android.graphics.BitmapFactory.decodeFile(framePath);
        }

        TextView sub = new TextView(this);
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isEmpty()) sb.append("玩家：").append(name);
        if (xp != null && !xp.isEmpty()) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("进度：").append(xp);
        }
        if (sb.length() == 0) sb.append("未读到玩家信息");
        sub.setText(sb.toString());
        sub.setTextSize(13);
        sub.setTextColor(Color.parseColor("#7A7F85"));
        sub.setPadding(0, dp(8), 0, dp(6));
        root.addView(sub);

        String diag = getIntent().getStringExtra("diag");
        if (diag != null && !diag.isEmpty()) {
            TextView dg = new TextView(this);
            dg.setText(diag);
            dg.setTextSize(11);
            dg.setTextColor(Color.parseColor("#AAB0B6"));
            dg.setLineSpacing(dp(2), 1f);
            dg.setPadding(0, 0, 0, dp(12));
            root.addView(dg);
        }

        TextView refresh = new TextView(this);
        refresh.setText("重新读取");
        refresh.setTextSize(14);
        refresh.setGravity(Gravity.CENTER);
        refresh.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1EA761"));
        bg.setCornerRadius(dp(12));
        refresh.setBackground(bg);
        refresh.setPadding(0, dp(11), 0, dp(11));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, 0, 0, dp(14));
        refresh.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });
        root.addView(refresh, rp);

        if (rarities == null || rarities.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("没有读到花瓣槽位。\n\n可能原因：\n"
                    + "· 还停在登录/选服界面，没有进入游戏\n"
                    + "· 刚进入游戏，槽位尚未渲染\n\n"
                    + "建议：回到游戏，按 X 键打开背包面板后再点「重新读取」，\n"
                    + "那时页面上是完整的花瓣清单，读到的内容最全。");
            empty.setTextSize(14);
            empty.setTextColor(Color.parseColor("#5F6368"));
            empty.setLineSpacing(dp(5), 1f);
            root.addView(empty);
        } else {
            TextView head = new TextView(this);
            head.setText("槽位（" + rarities.length + " 个）");
            head.setTextSize(13);
            head.setTextColor(Color.parseColor("#7A7F85"));
            head.setPadding(0, 0, 0, dp(6));
            root.addView(head);

            // 说明：槽位图标是 canvas 绘制的，页面里没有可读的花瓣名文本
            TextView note = new TextView(this);
            note.setText("花瓣名无法从页面直接读取（图标是画在 canvas 上的），这里按「花瓣编号 + 稀有度」列出。");
            note.setTextSize(11);
            note.setTextColor(Color.parseColor("#9AA0A6"));
            note.setLineSpacing(dp(2), 1f);
            note.setPadding(0, 0, 0, dp(10));
            root.addView(note);

            String tip = getIntent().getStringExtra("tooltip");
            TextView tipView = new TextView(this);
            if (tip != null && !tip.isEmpty()) {
                tipView.setText("悬停标题：" + tip);
                tipView.setTextColor(Color.parseColor("#1EA761"));
            } else {
                tipView.setText("悬停标题：未读到（把鼠标停在花瓣图标上，再点「重新读取」）");
                tipView.setTextColor(Color.parseColor("#AAB0B6"));
            }
            tipView.setTextSize(11);
            tipView.setLineSpacing(dp(2), 1f);
            tipView.setPadding(0, 0, 0, dp(12));
            root.addView(tipView);

            for (int i = 0; i < rarities.length; i++) {
                int pid = ids != null && i < ids.length ? ids[i] : -1;
                android.graphics.Bitmap icon = null;
                if (frame != null && sx != null && i < sx.length) {
                    icon = cropIcon(frame, sx[i], sy[i], sw[i], sh[i], vw, webW);
                }
                root.addView(slotRow(i + 1, pid, rarities[i], names, icon));
            }
        }

        setContentView(scroll);
        if (frame != null && !frame.isRecycled()) frame.recycle();
    }

    /**
     * 从整帧画面里裁出槽位所在的小块。
     *
     * 槽位坐标来自页面 DOM 的 getBoundingClientRect（CSS 像素），
     * 而画面是设备像素，两者差一个缩放比，这里按比例换算。
     */
    private android.graphics.Bitmap cropIcon(android.graphics.Bitmap frame,
                                             int x, int y, int w, int h,
                                             int viewportWidth, int webWidth) {
        if (frame == null || x < 0 || y < 0 || w <= 0 || h <= 0) return null;
        // 画面是 web 高度的 1/3 采样；DOM 坐标是 CSS 像素。
        // scale = (画面像素 / CSS 像素) = (web 高度/3) / 视口高度，
        // 横纵比一致，用宽度算：webWidth/3 / viewportWidth
        float scale;
        if (viewportWidth > 0 && webWidth > 0) {
            scale = (webWidth / 3f) / viewportWidth;
        } else {
            scale = 1f;
        }
        int px = Math.round(x * scale), py = Math.round(y * scale);
        int pw = Math.round(w * scale), ph = Math.round(h * scale);
        px = Math.max(0, Math.min(px, frame.getWidth() - 1));
        py = Math.max(0, Math.min(py, frame.getHeight() - 1));
        pw = Math.max(1, Math.min(pw, frame.getWidth() - px));
        ph = Math.max(1, Math.min(ph, frame.getHeight() - py));
        try {
            return android.graphics.Bitmap.createBitmap(frame, px, py, pw, ph);
        } catch (Exception e) {
            return null;
        }
    }

    private LinearLayout slotRow(int index, int petalId, int rarity,
                                 PetalNames names, android.graphics.Bitmap icon) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView num = new TextView(this);
        num.setText(String.valueOf(index));
        num.setTextSize(12);
        num.setTextColor(Color.parseColor("#AAB0B6"));
        num.setWidth(dp(28));
        row.addView(num);

        if (icon != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(icon);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(34), dp(34));
            ilp.setMargins(0, 0, dp(8), 0);
            row.addView(iv, ilp);
        }

        TextView dot = new TextView(this);
        int size = dp(22);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(rarity >= 0 && rarity < RAR_COLOR.length
                ? Color.parseColor(RAR_COLOR[rarity])
                : Color.parseColor("#E3E5E8"));
        dot.setBackground(g);
        dot.setWidth(size);
        dot.setHeight(size);
        row.addView(dot);

        TextView label = new TextView(this);
        StringBuilder t = new StringBuilder("   ");
        String known = names.get(petalId);
        if (known != null) {
            t.append(known).append("   ");
        }
        t.append(rarity < 0 ? "未知稀有度"
                : rarity < WikiData.RARITY_CN.length
                ? WikiData.RARITY_CN[rarity] : "稀有度 " + rarity);
        if (petalId >= 0 && known == null) t.append("   #").append(petalId);
        label.setText(t.toString());
        label.setTextSize(14);
        label.setTextColor(Color.parseColor("#1B1B1B"));
        row.addView(label);

        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
