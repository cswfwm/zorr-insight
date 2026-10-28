package com.zorrinsight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * 单位标注覆盖层：在游戏画面上框出敌方单位，按稀有度着色，并在框下方写名称等信息。
 *
 * 颜色按稀有度取游戏官方配色（Common 绿 → Omega 金），比统一红框更容易区分。
 * 标注只画在预览页里，不覆盖实际游戏，避免遮挡。
 */
public final class UnitOverlay {

    /** 稀有度顺序，与游戏一致：common 到 omega。 */
    public static final String[] RARITY_KEYS = {
            "Common", "Unusual", "Rare", "Epic", "Legendary",
            "Mythic", "Ultra", "Super", "Omega"
    };
    /** 官方稀有度配色，取自游戏自身的 rarity-bg 色板。 */
    public static final String[] RARITY_COLORS = {
            "#7EEF6D", "#FFE65D", "#4D52E3", "#861FDE", "#DE1F1F",
            "#1FDBDE", "#FF2B75", "#2BFFA3", "#FFD700"
    };
    public static final String[] RARITY_CN = {
            "普通", "罕见", "稀有", "史诗", "传说", "神话", "至尊", "超级", "Ω"
    };

    public static final class Unit {
        public String name = "";
        public String rarity = "";
        public String hp = "";
        public double atk = Double.NaN;
        public double def = Double.NaN;
        public float nx = 0.5f, ny = 0.5f;   // 画面相对坐标
        public int barLeft = -1, barTop = -1, barRight = -1, barBottom = -1;

        public int rarityIndex() {
            for (int i = 0; i < RARITY_KEYS.length; i++) {
                if (RARITY_KEYS[i].equalsIgnoreCase(rarity)) return i;
            }
            return -1;
        }
    }

    public static int colorFor(Unit u) {
        int idx = u.rarityIndex();
        if (idx >= 0 && idx < RARITY_COLORS.length) return Color.parseColor(RARITY_COLORS[idx]);
        return Color.parseColor("#FF2B75");
    }

    public static String rarityCn(Unit u) {
        int idx = u.rarityIndex();
        return idx >= 0 ? RARITY_CN[idx] : (u.rarity.isEmpty() ? "未知" : u.rarity);
    }

    /** 稀有度高→低，同级按血量百分比高→低。 */
    public static void sortByRarity(List<Unit> units) {
        units.sort((a, b) -> {
            int ra = a.rarityIndex(), rb = b.rarityIndex();
            if (ra != rb) return Integer.compare(rb, ra);
            return Double.compare(hpValue(b), hpValue(a));
        });
    }

    public static double hpValue(Unit u) {
        if (u.hp == null) return 0;
        String d = u.hp.replaceAll("[^0-9.]", "");
        try {
            return d.isEmpty() ? 0 : Double.parseDouble(d);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 把标注画到画布上。scale 是绘制尺寸与原始帧尺寸之比。 */
    public static void draw(Canvas c, List<Unit> units, int frameW, int frameH, float scale) {
        if (units == null || units.isEmpty()) return;
        float density = 1f;
        Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(Math.max(2f, 2.2f * scale));

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);

        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        txt.setTextSize(Math.max(10f, 11f * scale));

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setStyle(Paint.Style.FILL);

        int idx = 1;
        for (Unit u : units) {
            float cx = u.nx * frameW * scale;
            float cy = u.ny * frameH * scale;
            int color = colorFor(u);
            box.setColor(color);

            // 单位框：以坐标为中心的一个方框，尺寸参考血条宽度
            float bw;
            if (u.barLeft >= 0) {
                bw = (u.barRight - u.barLeft + 1) * scale * 1.05f;
            } else {
                bw = 46 * scale;
            }
            bw = Math.max(bw, 26 * scale);
            float bh = bw * 0.9f;
            RectF r = new RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
            c.drawRoundRect(r, 4 * scale, 4 * scale, box);

            // 标签：名称 · 稀有度 · 攻/防
            StringBuilder sb = new StringBuilder();
            sb.append(u.name.isEmpty() ? "单位" : u.name);
            sb.append(" · ").append(rarityCn(u));
            if (u.atk == u.atk) sb.append(" · 攻").append(trim(u.atk));
            if (u.def == u.def) sb.append(" · 防").append(trim(u.def));

            String label = sb.toString();
            float tw = txt.measureText(label);
            float th = txt.getTextSize();
            float lx = cx - tw / 2;
            float ly = r.bottom + th + 4 * scale;

            bg.setColor(0xCC000000);
            c.drawRoundRect(new RectF(lx - 3 * scale, ly - th,
                    lx + tw + 3 * scale, ly + 3 * scale), 3 * scale, 3 * scale, bg);
            txt.setColor(color);
            c.drawText(label, lx, ly, txt);
            idx++;
        }
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format("%.1f", v);
    }

    private UnitOverlay() {}
}
