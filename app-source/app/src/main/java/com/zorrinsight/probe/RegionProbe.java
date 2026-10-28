package com.zorrinsight.probe;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 按小地图判断所在区域与等级带。
 *
 * 小地图在右上角，是黑底方块：白色表示已探索区域，左下有玩家点；
 * 方块下方是区域名（Garden / Desert / Ocean / Jungle / Hel），上方是等级带。
 *
 * 这两行字都是 canvas 绘制的，读不到 DOM，只能读像素。判据用三个可解释的量：
 *   字符块数、整体宽高比、亮像素密度
 * 比字形模板匹配稳，因为它不依赖字体渲染的细微差异。
 */
public final class RegionProbe {

    public static final class Region {
        public final String name;
        public final float confidence;
        public final String tier;
        public final boolean found;

        Region(String name, float confidence, String tier, boolean found) {
            this.name = name;
            this.confidence = confidence;
            this.tier = tier;
            this.found = found;
        }
    }

    /** 各区域名在 13px 字高下的参考值：字符块数、宽高比上限附近。 */
    // 字符块数按实际单词字母数估：Garden 6、Desert 6、Ocean 5、Jungle 6、Hel 3。
    // 粘连会让块数偏少，所以这里给的是「预期下限」，判据也允许一定误差。
    // 但 Hel(3) 与 Jungle(6) 差距太小容易混，实际以视觉模型读字为准，这里只做兜底。
    private static final String[] NAMES = {"Garden", "Desert", "Ocean", "Jungle", "Hel"};
    private static final int[] CHAR_BLOCKS = {6, 6, 5, 6, 3};
    private static final float[] ASPECT = {4.6f, 4.5f, 3.6f, 4.6f, 2.2f};

    public static Region read(Bitmap bmp) {
        if (bmp == null) return new Region("Unknown", 0, "—", false);

        int[] box = findMinimap(bmp);
        if (box == null) return new Region("Unknown", 0, "—", false);

        int mx0 = box[0], my0 = box[1], mx1 = box[2], my1 = box[3];
        TextShape label = readText(bmp, mx0, mx1, my1 + 1, Math.min(bmp.getHeight() - 1,
                my1 + (my1 - my0) / 3 + 6));
        TextShape tier = readText(bmp, mx0, mx1, Math.max(0, my0 - (my1 - my0) / 4 - 4), my0 - 1);

        String name = "Unknown";
        float conf = 0;
        if (label != null) {
            for (int i = 0; i < NAMES.length; i++) {
                float s = score(label, CHAR_BLOCKS[i], ASPECT[i]);
                if (s > conf) {
                    conf = s;
                    name = NAMES[i];
                }
            }
            // 置信度不够就返回 Unknown，交给上层用视觉模型复核，
            // 不要硬猜——之前 Hel 和 Jungle 就是这样被认混的
            if (conf < 0.80f) {
                name = "Unknown";
            }
        }
        return new Region(name, conf, tierName(tier), true);
    }

    /** 在右上角找黑底正方形，返回 {x0,y0,x1,y1}。 */
    private static int[] findMinimap(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        // 实测：小地图在画面右侧约 x 82%~100%、y 12%~32% 处，
        // 搜索范围过宽会把左侧统计文字的黑色描边也算进来
        int sx = (int) (w * 0.80f), ex = w - 1;
        int sy = (int) (h * 0.08f), ey = (int) (h * 0.36f);
        if (ex - sx < 24 || ey - sy < 24) return null;

        // 以列方向的连续黑段确定上下边，再取这些列的公共左右边
        int bestX = -1, bestLen = 0;
        for (int x = ex; x > sx; x -= 2) {
            int run = 0, best = 0, bestStart = -1, start = -1;
            for (int y = sy; y < ey; y++) {
                if (isBlack(bmp.getPixel(x, y))) {
                    if (run == 0) start = y;
                    run++;
                    if (run > best) {
                        best = run;
                        bestStart = start;
                    }
                } else {
                    run = 0;
                }
            }
            if (best > bestLen) {
                bestLen = best;
                bestX = x;
                if (bestLen > (ey - sy) * 0.6f) break;
            }
        }
        if (bestX < 0 || bestLen < 40) return null;

        // 该列的连续段就是小地图的上下边
        int y0 = -1, y1 = -1, run = 0, start = -1, bestRun = 0;
        for (int y = sy; y < ey; y++) {
            if (isBlack(bmp.getPixel(bestX, y))) {
                if (run == 0) start = y;
                run++;
                if (run > bestRun) {
                    bestRun = run;
                    y0 = start;
                    y1 = y;
                }
            } else run = 0;
        }
        if (y0 < 0) return null;

        // 左右边：从靠近顶部开始往下找第一行「干净」的黑段
        // （小地图内部有白色探索区，靠中间的行会被切断）
        int left = -1, right = -1;
        for (int probe = 2; probe <= Math.min(14, (y1 - y0) / 2); probe++) {
            int rl = -1, rr = -1;
            for (int x = sx; x <= ex; x++) {
                if (isBlack(bmp.getPixel(x, y0 + probe))) {
                    if (rl < 0) rl = x;
                    rr = x;
                }
            }
            if (rl >= 0 && rr - rl >= 40) {
                left = rl;
                right = rr;
                break;
            }
        }
        if (right < 0) return null;

        // 小地图接近正方形，偏差太大说明找错了
        int bw = right - left, bh = y1 - y0;
        float ar = (float) bw / Math.max(1, bh);
        if (ar < 0.7f || ar > 1.4f) return null;
        return new int[]{left, y0, right, y1};
    }

    /** 文字的形状描述：亮像素的字符块数、宽高比、密度。 */
    private static final class TextShape {
        int blocks;
        float aspect;
        float density;
    }

    private static TextShape readText(Bitmap bmp, int x0, int x1, int y0, int y1) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        y0 = Math.max(0, y0);
        y1 = Math.min(h - 1, y1);
        x0 = Math.max(0, x0);
        x1 = Math.min(w - 1, x1);
        if (y1 <= y0 + 2 || x1 <= x0 + 6) return null;

        int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
        int lit = 0, total = 0;
        int[] colLit = new int[x1 - x0 + 1];

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int c = bmp.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                total++;
                if (r > 205 && g > 205 && b > 205) {
                    lit++;
                    colLit[x - x0]++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        // 文字要高亮但不至于占满整条带
        if (lit < 25 || maxX <= minX || maxY < minY) return null;
        float density = (float) lit / Math.max(1, total);
        if (density > 0.5f) return null;   // 大片白色区域，不是文字

        int blocks = 0;
        boolean inBlock = false;
        for (int v : colLit) {
            if (v > 0 && !inBlock) {
                blocks++;
                inBlock = true;
            } else if (v == 0) {
                inBlock = false;
            }
        }
        if (blocks < 1 || blocks > 12) return null;

        TextShape ts = new TextShape();
        ts.blocks = blocks;
        ts.aspect = (float) (maxX - minX + 1) / Math.max(1, maxY - minY + 1);
        ts.density = density;
        return ts;
    }

    private static float score(TextShape t, int blocks, float aspect) {
        float blockScore = 1f - Math.min(1f, Math.abs(t.blocks - blocks) / 4f);
        float aspectScore = 1f - Math.min(1f, Math.abs(t.aspect - aspect) / 3f);
        return 0.5f * blockScore + 0.5f * aspectScore;
    }

    private static String tierName(TextShape t) {
        if (t == null) return "—";
        // 等级带是单词：Common 6、Unusual 7、Rare 4、Epic 4、Legendary 9
        if (t.blocks >= 8) return "Legendary+";
        if (t.blocks >= 6) return "Unusual";
        if (t.blocks == 5) return "Mythic/Ultra";
        if (t.blocks == 4) return "Rare/Epic";
        return "Common";
    }

    private static boolean isBlack(int c) {
        return Color.red(c) < 40 && Color.green(c) < 40 && Color.blue(c) < 40;
    }

    private RegionProbe() {}
}
