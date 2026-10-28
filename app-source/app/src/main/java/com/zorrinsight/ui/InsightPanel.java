package com.zorrinsight.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zorrinsight.data.WikiData;
import com.zorrinsight.neural.AutoTrainer;
import com.zorrinsight.probe.DetectionSet;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 解析面板。
 *
 * 左栏：画面里检测到的单位（自动列出）+ 手动搜索图鉴。
 * 右栏：选中单位的血量、护甲、掉落概率。
 */
public class InsightPanel extends LinearLayout {

    public interface ContextProvider {
        String[] get();               // [region, extra]
        DetectionSet detections();    // 当前画面的检测结果
        /** 当前画面（可能为 null）。用于把画面交给模型读出单位名称。 */
        android.graphics.Bitmap frame();
    }

    private static final String[] RAR_COLOR = {
            "#7EEF6D", "#FFE65D", "#4D52E3", "#861FDE", "#DE1F1F",
            "#1FDBDE", "#FF2B75", "#2BFFA3", "#FFD700", "#C0C4C8"
    };

    private static final String[] HUE_NAME = {"红", "黄", "绿", "青", "蓝", "品红"};

    private final WikiData wiki;
    private final ContextProvider ctx;

    private final LinearLayout detectList;
    private final LinearLayout mobList;
    private final ScrollView detail;
    private final LinearLayout detailBody;

    private Runnable dismiss;
    private String selectedRarity = "common";
    private String selectedMob;

    public InsightPanel(Context c, ContextProvider ctx) {
        super(c);
        this.wiki = WikiData.get(c);
        this.ctx = ctx;

        setOrientation(VERTICAL);
        setBackgroundColor(Color.WHITE);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(14), dp(16), dp(6));

        TextView title = new TextView(c);
        title.setText("解析");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#1B1B1B"));
        head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));

        TextView recognize = new TextView(c);
        recognize.setText("识别名称");
        recognize.setTextSize(14);
        recognize.setTextColor(Color.parseColor("#1EA761"));
        recognize.setPadding(0, 0, dp(14), 0);
        recognize.setOnClickListener(v -> recognizeNames());
        head.addView(recognize);

        TextView refresh = new TextView(c);
        refresh.setText("刷新");
        refresh.setTextSize(14);
        refresh.setTextColor(Color.parseColor("#1EA761"));
        refresh.setPadding(0, 0, dp(14), 0);
        refresh.setOnClickListener(v -> refreshAll());
        head.addView(refresh);

        TextView close = new TextView(c);
        close.setText("关闭");
        close.setTextSize(14);
        close.setTextColor(Color.parseColor("#1EA761"));
        close.setOnClickListener(v -> {
            if (dismiss != null) dismiss.run();
        });
        head.addView(close);
        addView(head);

        TextView ctxView = new TextView(c);
        String[] info = ctx == null ? null : ctx.get();
        ctxView.setText(info != null && info[1] != null ? info[1] : "");
        ctxView.setTextSize(12);
        ctxView.setTextColor(Color.parseColor("#7A7F85"));
        ctxView.setPadding(dp(16), 0, dp(16), dp(8));
        addView(ctxView);

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(HORIZONTAL);
        body.setPadding(dp(12), 0, dp(12), dp(8));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, 0);
        bp.weight = 1;
        addView(body, bp);

        // 左栏：检测结果 + 图鉴
        ScrollView leftScroll = new ScrollView(c);
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(VERTICAL);
        leftScroll.addView(left);
        body.addView(leftScroll, new LinearLayout.LayoutParams(0, MATCH, 1));

        TextView detTitle = new TextView(c);
        detTitle.setText("画面中的单位");
        detTitle.setTextSize(11);
        detTitle.setTextColor(Color.parseColor("#9AA0A6"));
        detTitle.setPadding(dp(6), 0, 0, dp(4));
        left.addView(detTitle);

        detectList = new LinearLayout(c);
        detectList.setOrientation(VERTICAL);
        left.addView(detectList);

        TextView dexTitle = new TextView(c);
        dexTitle.setText("图鉴");
        dexTitle.setTextSize(11);
        dexTitle.setTextColor(Color.parseColor("#9AA0A6"));
        dexTitle.setPadding(dp(6), dp(14), 0, dp(4));
        left.addView(dexTitle);

        mobList = new LinearLayout(c);
        mobList.setOrientation(VERTICAL);
        left.addView(mobList);

        // 右栏：详情
        detail = new ScrollView(c);
        detail.setPadding(dp(12), 0, 0, 0);
        detailBody = new LinearLayout(c);
        detailBody.setOrientation(VERTICAL);
        detail.addView(detailBody);
        body.addView(detail, new LinearLayout.LayoutParams(0, MATCH, 1.25f));

        refreshAll();
        showEmpty();
    }

    public void setDismiss(Runnable r) { this.dismiss = r; }

    /** 面板打开或点刷新时：重新拉一遍检测结果。 */
    public void refreshAll() {
        fillDetections();
        fillDex("");
    }


    /**
     * 把画面交给视觉模型，读出画面上可见的敌方单位名称。
     *
     * 名称是游戏画在画面上的，读它属于「看图认字」，不触碰游戏内部数据。
     * 识别结果写入「画面中的单位」列表，点条目即可查它的属性与掉落。
     */
    private void recognizeNames() {
        android.graphics.Bitmap f = ctx == null ? null : ctx.frame();
        if (f == null || f.isRecycled()) {
            android.widget.Toast.makeText(getContext(), "还没有采集到画面",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        com.zorrinsight.net.VisionClient vc =
                new com.zorrinsight.net.VisionClient(getContext());
        if (!vc.configured()) {
            android.widget.Toast.makeText(getContext(), "请先在设置里配置 AI 接口",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.Toast.makeText(getContext(), "正在识别画面…",
                android.widget.Toast.LENGTH_SHORT).show();

        // 放大后再送，小字在缩放后容易糊
        android.graphics.Bitmap big = android.graphics.Bitmap.createScaledBitmap(
                f, f.getWidth() * 2, f.getHeight() * 2, true);
        byte[] png = com.zorrinsight.net.VisionClient.toPng(big);
        if (big != f) big.recycle();

        vc.readText(png, 600, new com.zorrinsight.net.VisionClient.Cb() {
            @Override
            public void onOk(String json) {
                final java.util.List<String> names = new java.util.ArrayList<>();
                final org.json.JSONObject[] statsHolder = {null};
                try {
                    String t = json.trim();
                    int a = t.indexOf('{'), b = t.lastIndexOf('}');
                    if (a >= 0 && b > a) t = t.substring(a, b + 1);
                    org.json.JSONObject o = new org.json.JSONObject(t);
                    statsHolder[0] = o.optJSONObject("stats");
                    org.json.JSONArray arr = o.optJSONArray("units");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject u = arr.optJSONObject(i);
                            if (u == null) continue;
                            String n = u.optString("name", "").trim();
                            if (!n.isEmpty()) names.add(n);
                        }
                    }
                } catch (Exception ignored) {
                }
                ((android.app.Activity) getContext()).runOnUiThread(() -> {
                    showNames(names);
                    showStats(statsHolder[0]);
                });
            }

            @Override
            public void onErr(String message) {
                ((android.app.Activity) getContext()).runOnUiThread(() ->
                        android.widget.Toast.makeText(getContext(), "识别失败：" + message,
                                android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 识别结果里的统计数字，显示在面板顶部。 */
    private void showStats(org.json.JSONObject stats) {
        if (stats == null) return;
        String dps = stats.optString("dps", "");
        String kills = stats.optString("kills", "");
        String dmg = stats.optString("damage", "");
        String time = stats.optString("time", "");
        if (dps.isEmpty() && kills.isEmpty() && dmg.isEmpty() && time.isEmpty()) return;

        StringBuilder sb = new StringBuilder("画面统计：");
        if (!dps.isEmpty()) sb.append("DPS ").append(dps).append("  ");
        if (dmg.isEmpty() && !kills.isEmpty()) sb.append("击杀 ").append(kills).append("  ");
        if (time.isEmpty() && !dmg.isEmpty()) sb.append("伤害 ").append(dmg);
        if (!time.isEmpty()) sb.append("存活 ").append(time);

        TextView t = new TextView(getContext());
        t.setText(sb.toString().trim());
        t.setTextSize(12);
        t.setTextColor(Color.parseColor("#1EA761"));
        t.setPadding(dp(6), dp(4), dp(6), dp(6));
        detailBody.addView(t, 0);
    }

    /** 把识别出的名称列在左栏，点一条查图鉴。 */
    private void showNames(java.util.List<String> names) {
        detectList.removeAllViews();
        if (names.isEmpty()) {
            TextView t = new TextView(getContext());
            t.setText("没识别到单位名称。\n请确认游戏里已打开名称显示，且画面上有敌方单位。");
            t.setTextSize(12);
            t.setTextColor(Color.parseColor("#9AA0A6"));
            t.setPadding(dp(6), dp(4), dp(6), dp(4));
            t.setLineSpacing(dp(3), 1f);
            detectList.addView(t);
            return;
        }
        for (String n : names) {
            final String key = wiki.findMobKey(n);
            TextView row = new TextView(getContext());
            row.setText("· " + n + (key == null ? "（图鉴无此条目）" : ""));
            row.setTextSize(13);
            row.setTextColor(Color.parseColor(key == null ? "#9AA0A6" : "#2A2D31"));
            row.setPadding(dp(6), dp(8), dp(6), dp(8));
            row.setOnClickListener(v -> {
                if (key == null) return;
                selectedMob = key;
                fillDex("");
                showMob(key);
            });
            detectList.addView(row);
        }
    }

    private void fillDetections() {
        detectList.removeAllViews();
        DetectionSet ds = ctx == null ? null : ctx.detections();
        if (ds == null || ds.items.isEmpty()) {
            TextView t = new TextView(getContext());
            t.setText("没有检测到单位。\n画面里出现怪物后点「刷新」。");
            t.setTextSize(12);
            t.setTextColor(Color.parseColor("#9AA0A6"));
            t.setPadding(dp(6), dp(4), dp(6), dp(4));
            t.setLineSpacing(dp(3), 1f);
            detectList.addView(t);
            return;
        }
        for (DetectionSet.Item item : ds.items) {
            TextView row = new TextView(getContext());
            row.setText("· " + item.guess);
            row.setTextSize(13);
            row.setTextColor(Color.parseColor("#2A2D31"));
            row.setPadding(dp(6), dp(8), dp(6), dp(8));
            row.setBackground(round("#FFFFFF", "#FFFFFF", 8));
            // 检测到的是颜色与血量，具体是哪只怪需要对照图鉴确认：
            // 点一下会打开图鉴并给出该色系可能的单位
            row.setOnClickListener(v -> openForDetection(item));
            detectList.addView(row);
        }
    }

    /** 由检测结果跳到图鉴：按色系找候选单位。 */
    private void openForDetection(DetectionSet.Item item) {
        String hue = HUE_NAME[Math.max(0, Math.min(5, item.blob.hueBucket))];
        List<String> candidates = new ArrayList<>();
        JSONObject mobs = wiki.mobs();
        if (mobs != null) {
            for (Iterator<String> it = mobs.keys(); it.hasNext(); ) {
                String k = it.next();
                String n = wiki.displayName(k).toLowerCase();
                if (hueMatches(hue, n)) candidates.add(k);
            }
        }
        Collections.sort(candidates, (a, b) -> wiki.displayName(a).compareToIgnoreCase(wiki.displayName(b)));

        if (candidates.isEmpty()) {
            fillDex("");
            return;
        }
        selectedMob = candidates.get(0);
        fillDex("");
        showMob(selectedMob);

        TextView note = new TextView(getContext());
        note.setText("该单位血条约 " + item.blob.fillRatio + "%（" + hue + "色系）");
        note.setTextSize(11);
        note.setTextColor(Color.parseColor("#9AA0A6"));
        detailBody.addView(note, 0);
    }

    private static boolean hueMatches(String hue, String name) {
        switch (hue) {
            case "红": return name.contains("ladybug") || name.contains("red");
            case "黄": return name.contains("bee") || name.contains("wasp") || name.contains("ant")
                    || name.contains("honey") || name.contains("sun");
            case "绿": return name.contains("beetle") || name.contains("leaf") || name.contains("cactus")
                    || name.contains("worm") || name.contains("mantis");
            case "青": return name.contains("moth") || name.contains("jelly") || name.contains("bubble");
            case "蓝": return name.contains("spider") || name.contains("water") || name.contains("crab");
            case "品红": return name.contains("hornet") || name.contains("scorpion") || name.contains("centipede");
            default: return false;
        }
    }

    private void fillDex(String query) {
        mobList.removeAllViews();
        JSONObject mobs = wiki.mobs();
        if (mobs == null) return;

        String q = query == null ? "" : query.trim().toLowerCase();
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = mobs.keys(); it.hasNext(); ) {
            String k = it.next();
            String n = wiki.displayName(k);
            if (q.isEmpty() || k.contains(q) || n.toLowerCase().contains(q)) keys.add(k);
        }
        Collections.sort(keys, (a, b) ->
                java.text.Collator.getInstance(java.util.Locale.CHINA)
                        .compare(wiki.displayName(a), wiki.displayName(b)));
        if (keys.size() > 60) keys = keys.subList(0, 60);

        for (String k : keys) {
            TextView row = new TextView(getContext());
            row.setText(wiki.displayName(k));
            row.setTextSize(13);
            row.setPadding(dp(6), dp(7), dp(6), dp(7));
            row.setTextColor(Color.parseColor(k.equals(selectedMob) ? "#1EA761" : "#4A4F55"));
            row.setBackground(round(k.equals(selectedMob) ? "#EAF7F0" : "#FFFFFF", "#FFFFFF", 8));
            row.setOnClickListener(v -> {
                selectedMob = k;
                fillDex("");
                showMob(k);
            });
            mobList.addView(row);
        }
    }

    private void showEmpty() {
        detailBody.removeAllViews();
        TextView t = new TextView(getContext());
        t.setText("点左侧「画面中的单位」里的任意一条，或从图鉴里选一个单位，查看血量、护甲与掉落概率。");
        t.setTextSize(13);
        t.setTextColor(Color.parseColor("#7A7F85"));
        t.setLineSpacing(dp(4), 1f);
        detailBody.addView(t);
    }

    private void showMob(String key) {
        detailBody.removeAllViews();
        JSONObject mob = wiki.mob(key);
        if (mob == null) return;

        TextView name = new TextView(getContext());
        name.setText(wiki.displayName(key));
        name.setTextSize(17);
        name.setTextColor(Color.parseColor("#1B1B1B"));
        detailBody.addView(name);

        org.json.JSONArray biomes = mob.optJSONArray("biomes");
        if (biomes != null && biomes.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < biomes.length(); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(wiki.regionName(biomes.optString(i)));
            }
            TextView b = new TextView(getContext());
            b.setText(sb.toString());
            b.setTextSize(11);
            b.setTextColor(Color.parseColor("#9AA0A6"));
            b.setPadding(0, dp(2), 0, dp(10));
            detailBody.addView(b);
        }

        HorizontalScrollView hs = new HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(getContext());
        chips.setOrientation(HORIZONTAL);
        hs.addView(chips);
        for (int i = 0; i < WikiData.RARITIES.length; i++) {
            final String rar = WikiData.RARITIES[i];
            TextView chip = new TextView(getContext());
            chip.setText(WikiData.RARITY_CN[i]);
            chip.setTextSize(12);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            boolean on = rar.equals(selectedRarity);
            chip.setTextColor(Color.parseColor(on ? "#FFFFFF" : "#5F6368"));
            chip.setBackground(round(on ? "#1EA761" : "#F2F3F5", "#FFFFFF", 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.setMargins(0, 0, dp(6), 0);
            chip.setOnClickListener(v -> {
                selectedRarity = rar;
                showMob(key);
            });
            chips.addView(chip, lp);
        }
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(MATCH, WRAP);
        hp.setMargins(0, 0, 0, dp(10));
        detailBody.addView(hs, hp);

        JSONObject st = wiki.baseStats(key, selectedRarity);
        detailBody.addView(sectionTitle("属性 · " + WikiData.cnRarity(selectedRarity)));
        if (st == null) {
            detailBody.addView(line("该稀有度没有记录", "#9AA0A6"));
        } else {
            detailBody.addView(statRow("血量", fmt(st.optDouble("health", Double.NaN))));
            detailBody.addView(statRow("伤害", fmt(st.optDouble("damage", Double.NaN))));
            detailBody.addView(statRow("护甲", fmt(st.optDouble("armor", Double.NaN))));
            detailBody.addView(statRow("体型", fmt(st.optDouble("size", Double.NaN))));
            detailBody.addView(statRow("经验", fmt(st.optDouble("base xp", Double.NaN))));
            detailBody.addView(statRow("质量", fmt(st.optDouble("mass", Double.NaN))));
        }

        detailBody.addView(sectionTitle("掉落 · 该稀有度"));
        List<WikiData.Drop> drops = wiki.drops(key, selectedRarity);
        if (drops.isEmpty()) {
            detailBody.addView(line("该稀有度没有掉落记录。", "#9AA0A6"));
        } else {
            for (WikiData.Drop d : drops) {
                detailBody.addView(dropRow(d));
            }
        }

        if (st != null) {
            double thp = st.optDouble("health", Double.NaN);
            double tdmg = st.optDouble("damage", Double.NaN);
            if (!Double.isNaN(thp) && !Double.isNaN(tdmg)) {
                detailBody.addView(sectionTitle("威胁参考"));
                detailBody.addView(line("血量×伤害：" + fmt(thp * tdmg), "#2A2D31"));
                detailBody.addView(line("定位：" + rankHint(thp, tdmg), "#7A7F85"));
            }
        }
        detail.scrollTo(0, 0);
    }

    private View dropRow(WikiData.Drop d) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        View dot = new View(getContext());
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        int idx = indexOfRarity(d.petalRarity);
        g.setColor(Color.parseColor(idx >= 0 && idx < RAR_COLOR.length ? RAR_COLOR[idx] : "#DADCE0"));
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dlp.setMargins(0, 0, dp(8), 0);
        row.addView(dot, dlp);

        TextView n = new TextView(getContext());
        n.setText(wiki.petalName(normalizePetalKey(d.petal)) + " ×" + d.qty);
        n.setTextSize(13);
        n.setTextColor(Color.parseColor("#2A2D31"));
        row.addView(n, new LinearLayout.LayoutParams(0, WRAP, 1));

        TextView p = new TextView(getContext());
        p.setText(trimPct(d.chance) + "%");
        p.setTextSize(13);
        p.setTextColor(Color.parseColor("#1EA761"));
        row.addView(p);
        return row;
    }

    /** 与 zh 表的键对齐：去掉空格与大小写差异。 */
    private static String normalizePetalKey(String name) {
        if (name == null) return "";
        return name.toLowerCase().replace(" ", "").replace("_", "").replace("-", "");
    }

    private static int indexOfRarity(String r) {
        for (int i = 0; i < WikiData.RARITIES.length; i++) {
            if (WikiData.RARITIES[i].equals(r)) return i;
        }
        return -1;
    }

    private String rankHint(double hp, double dmg) {
        if (hp >= 10000) return "高血量，优先集火";
        if (dmg >= 80) return "高伤害，避免贴身";
        if (hp >= 2000) return "中等血量，可稳定输出";
        return "常规单位";
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(Color.parseColor("#9AA0A6"));
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private View statRow(String k, String v) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView key = new TextView(getContext());
        key.setText(k);
        key.setTextSize(13);
        key.setTextColor(Color.parseColor("#7A7F85"));
        row.addView(key, new LinearLayout.LayoutParams(0, WRAP, 1));

        TextView val = new TextView(getContext());
        val.setText(v);
        val.setTextSize(13);
        val.setTextColor(Color.parseColor("#1B1B1B"));
        row.addView(val);
        return row;
    }

    private TextView line(String s, String color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(Color.parseColor(color));
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "—";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return trimPct(v);
    }

    private static String trimPct(double v) {
        String s = String.format("%.2f", v);
        if (s.endsWith("00")) s = s.substring(0, s.length() - 2);
        if (s.endsWith("0") && s.contains(".")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private GradientDrawable round(String fill, String stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(fill));
        g.setCornerRadius(dp(radiusDp));
        if (!fill.equals(stroke)) g.setStroke(dp(1), Color.parseColor(stroke));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
}
