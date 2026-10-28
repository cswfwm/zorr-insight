package com.zorrinsight;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.zorrinsight.data.WikiData;
import com.zorrinsight.net.VisionClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 视觉预览：把模型看到的那一帧画出来，用稀有度配色框住敌方单位，
 * 右侧按稀有度从高到低列出清单，并附血量、攻击、防御与掉落花瓣。
 */
public class VisionPreviewActivity extends AppCompatActivity {

    public static Bitmap sharedFrame;
    public static com.zorrinsight.probe.DetectionSet sharedDetections;

    private ImageView frameView;
    private TextView infoView;
    private LinearLayout listBox;
    private LinearLayout filterRow;

    private final List<UnitOverlay.Unit> units = new ArrayList<>();
    /** 只显示稀有度不低于该索引的单位；-1 表示全部显示。 */
    private int minRarity = -1;
    private UnitOverlay.Unit selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        // 顶部：标题 + 识别按钮
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("视觉标注");
        title.setTextSize(16);
        title.setTextColor(Color.parseColor("#1B1B1B"));
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView regionBtn = new TextView(this);
        regionBtn.setText("识别区域");
        regionBtn.setTextSize(13);
        regionBtn.setTextColor(Color.parseColor("#1EA761"));
        regionBtn.setPadding(dp(8), dp(4), dp(6), dp(4));
        regionBtn.setOnClickListener(v -> recognizeRegion());
        head.addView(regionBtn);

        TextView recognize = new TextView(this);
        recognize.setText("识别单位");
        recognize.setTextSize(13);
        recognize.setTextColor(Color.parseColor("#1EA761"));
        recognize.setPadding(dp(10), dp(4), dp(6), dp(4));
        recognize.setOnClickListener(v -> recognizeUnits());
        head.addView(recognize);
        root.addView(head);

        // 稀有度筛选（按钮由 recolorFilter 统一重建，避免两处逻辑重复）
        filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(0, 0, 0, dp(4));
        root.addView(filterRow);
        recolorFilter();

        infoView = new TextView(this);
        infoView.setTextSize(11);
        infoView.setTextColor(Color.parseColor("#7A7F85"));
        infoView.setPadding(0, dp(4), 0, dp(6));
        root.addView(infoView);

        // 中部：画面 + 右侧列表
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        bp.weight = 1;
        root.addView(body, bp);

        frameView = new ImageView(this);
        frameView.setAdjustViewBounds(true);
        frameView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frameView.setBackgroundColor(Color.parseColor("#F5F6F7"));
        body.addView(frameView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.7f));

        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setPadding(dp(8), 0, 0, 0);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        rightScroll.addView(listBox);
        body.addView(rightScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);
        renderFrame();
        buildList();
    }

    /** 当前筛选下应当显示的单位。 */
    private List<UnitOverlay.Unit> visibleUnits() {
        if (minRarity < 0) return units;
        List<UnitOverlay.Unit> out = new ArrayList<>();
        for (UnitOverlay.Unit u : units) {
            if (u.rarityIndex() >= minRarity) out.add(u);
        }
        return out;
    }

    /** 重建筛选按钮，刷新选中态。 */
    private void recolorFilter() {
        if (filterRow == null) return;
        filterRow.removeAllViews();
        TextView fl = new TextView(this);
        fl.setText("只看：");
        fl.setTextSize(10);
        fl.setTextColor(Color.parseColor("#7A7F85"));
        filterRow.addView(fl);
        for (int i = -1; i < UnitOverlay.RARITY_KEYS.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(i < 0 ? "全部" : UnitOverlay.RARITY_CN[i]);
            chip.setTextSize(10);
            chip.setPadding(dp(7), dp(3), dp(7), dp(3));
            GradientDrawable cg = new GradientDrawable();
            boolean on = i == minRarity;
            cg.setColor(Color.parseColor(on ? "#1EA761" : "#F2F3F5"));
            cg.setCornerRadius(dp(10));
            chip.setBackground(cg);
            chip.setTextColor(Color.parseColor(on ? "#FFFFFF" : "#5F6368"));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, dp(4), 0);
            chip.setLayoutParams(clp);
            chip.setOnClickListener(v -> {
                minRarity = idx;
                recolorFilter();
                renderFrame();
                buildList();
            });
            filterRow.addView(chip);
        }
    }

    /** 把标注画到图上。 */
    private void renderFrame() {
        Bitmap src = sharedFrame;
        if (src == null || src.isRecycled()) {
            infoView.setText("还没有采集到画面。回到主界面进入游戏后再打开。");
            return;
        }
        Bitmap marked = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(marked);
        float scale = 1f;   // 标注直接画在原始帧坐标系上
        List<UnitOverlay.Unit> shown = visibleUnits();
        UnitOverlay.draw(c, shown, marked.getWidth(), marked.getHeight(), scale);
        frameView.setImageBitmap(marked);

        StringBuilder sb = new StringBuilder();
        sb.append("画面 ").append(src.getWidth()).append("×").append(src.getHeight());
        sb.append("　标注 ").append(visibleUnits().size()).append(" 个");
        int[] st = com.zorrinsight.probe.MobDetector.lastStats;
        if (st != null && st.length >= 3 && st[0] > 0) {
            sb.append("\n血条筛选：几何 ").append(st[0])
              .append(" → 尺寸 ").append(st[1])
              .append(" → 通过 ").append(st[2]);
        }
        if (minRarity >= 0) sb.append("（已筛选）");
        if (units.isEmpty()) sb.append("　（点「识别单位」开始）");
        infoView.setText(sb.toString());
    }

    /**
     * 识别小地图下方的区域名。
     *
     * 像素法靠字符块数判断，Hel（3 个字）和 Jungle（6 个字）在字形粘连时容易混，
     * 所以把该区域裁出来交给模型直接读字。
     */
    private void recognizeRegion() {
        Bitmap src = sharedFrame;
        if (src == null || src.isRecycled()) {
            Toast.makeText(this, "还没有采集到画面", Toast.LENGTH_SHORT).show();
            return;
        }
        VisionClient vc = new VisionClient(this);
        if (!vc.configured()) {
            Toast.makeText(this, "请先在设置里配置 AI 接口", Toast.LENGTH_SHORT).show();
            return;
        }

        // 裁右上角小地图一带（含下方区域名），放大后送模型
        int x0 = (int) (src.getWidth() * 0.78f);
        int y0 = (int) (src.getHeight() * 0.08f);
        int x1 = src.getWidth();
        int y1 = (int) (src.getHeight() * 0.40f);
        x1 = Math.min(x1, src.getWidth());
        y1 = Math.min(y1, src.getHeight());
        if (x1 - x0 < 20 || y1 - y0 < 20) {
            Toast.makeText(this, "画面太小，无法裁剪小地图区域", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap crop = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0);
        Bitmap big = Bitmap.createScaledBitmap(crop,
                crop.getWidth() * 3, crop.getHeight() * 3, true);
        crop.recycle();
        byte[] png = VisionClient.toPng(big);
        big.recycle();

        // 像素法初判作为提示，让模型对照
        com.zorrinsight.probe.RegionProbe.Region rough =
                com.zorrinsight.probe.RegionProbe.read(src);
        Toast.makeText(this, "正在识别区域…", Toast.LENGTH_SHORT).show();

        vc.readRegion(png, rough.name, new VisionClient.Cb() {
            @Override
            public void onOk(String text) {
                String raw = text.trim().replace("\"", "").replace("`", "").trim();
                runOnUiThread(() -> {
                    if (raw.equalsIgnoreCase("UNKNOWN") || raw.isEmpty()) {
                        infoView.setText("区域：未能读出（像素法初判 "
                                + rough.name + "，置信度 "
                                + Math.round(rough.confidence * 100) + "%）");
                        return;
                    }
                    String cn = com.zorrinsight.data.WikiData.get(
                            VisionPreviewActivity.this).regionName(raw);
                    infoView.setText("区域：" + raw + (cn.equalsIgnoreCase(raw) ? "" : "（" + cn + "）"));
                });
            }

            @Override
            public void onErr(String message) {
                runOnUiThread(() -> infoView.setText("区域识别失败：" + message));
            }
        });
    }

    /** 调视觉模型识别画面里的单位。 */
    private void recognizeUnits() {
        Bitmap src = sharedFrame;
        if (src == null || src.isRecycled()) {
            Toast.makeText(this, "还没有采集到画面", Toast.LENGTH_SHORT).show();
            return;
        }
        VisionClient vc = new VisionClient(this);
        if (!vc.configured()) {
            Toast.makeText(this, "请先在设置里配置 AI 接口", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在识别…", Toast.LENGTH_SHORT).show();

        // 原图已经足够清晰，不再放大（放大只会让体积暴涨、模型端再被压缩）。
        // 若原图本身很宽，长边限制在 1600 以内，避免上传过慢。
        Bitmap send = src;
        int maxEdge = Math.max(src.getWidth(), src.getHeight());
        if (maxEdge > 1600) {
            float k = 1600f / maxEdge;
            send = Bitmap.createScaledBitmap(src,
                    Math.round(src.getWidth() * k), Math.round(src.getHeight() * k), true);
        }
        byte[] png = VisionClient.toPng(send);
        if (send != src && send != null) send.recycle();

        vc.readText(png, 900, new VisionClient.Cb() {
            @Override
            public void onOk(String json) {
                List<UnitOverlay.Unit> parsed = parse(json);
                runOnUiThread(() -> {
                    units.clear();
                    units.addAll(parsed);
                    UnitOverlay.sortByRarity(units);
                    renderFrame();
                    buildList();
                });
            }

            @Override
            public void onErr(String message) {
                runOnUiThread(() -> Toast.makeText(VisionPreviewActivity.this,
                        "识别失败：" + message, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 解析模型返回的 JSON，并按图鉴补全攻防数值。 */
    private List<UnitOverlay.Unit> parse(String json) {
        List<UnitOverlay.Unit> out = new ArrayList<>();
        WikiData wiki = WikiData.get(this);
        try {
            String t = json.trim();
            int a = t.indexOf('{'), b = t.lastIndexOf('}');
            if (a >= 0 && b > a) t = t.substring(a, b + 1);
            JSONObject o = new JSONObject(t);
            JSONArray arr = o.optJSONArray("units");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                UnitOverlay.Unit u = new UnitOverlay.Unit();
                u.name = e.optString("name", "").trim();
                u.rarity = e.optString("rarity", "").trim();
                u.hp = e.optString("hp", "").trim();
                u.nx = (float) e.optDouble("x", 0.5);
                u.ny = (float) e.optDouble("y", 0.5);

                // 用图鉴补攻防：按名称查怪物，再按稀有度取对应档位
                String key = wiki.findMobKey(u.name);
                if (key != null) {
                    String rk = rarityKey(u.rarity);
                    JSONObject st = wiki.baseStats(key, rk);
                    if (st != null) {
                        u.atk = st.optDouble("damage", Double.NaN);
                        u.def = st.optDouble("armor", Double.NaN);
                    }
                    // 识别出的名字统一换成图鉴里的官方名
                    u.name = wiki.displayName(key);
                }
                out.add(u);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String rarityKey(String r) {
        if (r == null) return "common";
        String s = r.trim().toLowerCase();
        for (int i = 0; i < UnitOverlay.RARITY_KEYS.length; i++) {
            if (UnitOverlay.RARITY_KEYS[i].toLowerCase().equals(s)) {
                return UnitOverlay.RARITY_KEYS[i].toLowerCase();
            }
        }
        return "common";
    }

    /** 右侧清单：稀有度从高到低。 */
    private void buildList() {
        listBox.removeAllViews();
        if (units.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("点「识别单位」后，这里按稀有度从高到低列出画面里的敌方单位。");
            t.setTextSize(11);
            t.setTextColor(Color.parseColor("#9AA0A6"));
            t.setLineSpacing(dp(3), 1f);
            listBox.addView(t);
            return;
        }
        for (UnitOverlay.Unit u : visibleUnits()) {
            listBox.addView(listRow(u));
        }
    }

    private LinearLayout listRow(UnitOverlay.Unit u) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FAFBFC"));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), UnitOverlay.colorFor(u));
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(lp);

        TextView name = new TextView(this);
        if (u.name.isEmpty()) {
            name.setText("未读到名称");
        } else if (u.atk != u.atk && u.def != u.def) {
            // 名字查不到图鉴，说明可能是模型猜的或写错了，不能当成确定结论展示
            name.setText(u.name + "（图鉴未收录）");
        } else {
            name.setText(u.name);
        }
        name.setTextSize(13);
        name.setTextColor(UnitOverlay.colorFor(u));
        row.addView(name);

        TextView meta = new TextView(this);
        StringBuilder sb = new StringBuilder(UnitOverlay.rarityCn(u));
        if (!u.hp.isEmpty()) sb.append(" · 血量 ").append(u.hp);
        row.addView(meta);
        meta.setText(sb.toString());
        meta.setTextSize(10);
        meta.setTextColor(Color.parseColor("#7A7F85"));

        TextView combat = new TextView(this);
        StringBuilder c = new StringBuilder();
        c.append("攻 ").append(u.atk == u.atk ? trim(u.atk) : "—");
        c.append("　防 ").append(u.def == u.def ? trim(u.def) : "—");
        combat.setText(c.toString());
        combat.setTextSize(10);
        combat.setTextColor(Color.parseColor("#4A4F55"));
        row.addView(combat);

        row.setOnClickListener(v -> {
            selected = u;
            Toast.makeText(this, u.name + " · " + UnitOverlay.rarityCn(u), Toast.LENGTH_SHORT).show();
        });
        return row;
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format("%.1f", v);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
