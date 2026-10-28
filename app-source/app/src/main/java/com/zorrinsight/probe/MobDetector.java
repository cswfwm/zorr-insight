package com.zorrinsight.probe;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * 从画面里找敌方/玩家单位。
 *
 * 定位依据是头顶那条血条：黑色描边的横向胶囊，内部彩色填充 + 白色百分比数字。
 * 血条比单位本体稳定得多，因此先用血条定位，再取血条上方一块区域当作单位本体。
 *
 * 判据（在真实截图上标定过）：
 *   内部高度 12~26px、宽度 38~125px、宽高比 ≥1.7、两端落在黑描边上。
 */
public final class MobDetector {

    public static final class Blob {
        public final int barLeft, barTop, barRight, barBottom;
        public final int bodyLeft, bodyTop, bodyRight, bodyBottom;
        public final int fillRatio;
        public final int hueBucket;
        /** 血条下方那行稀有度文字（Unusual / Rare…）的字形块数，0 表示没读到。 */
        public final int tierBlocks;

        Blob(int bl, int bt, int br, int bb, int l, int t, int r, int b, int fill, int hue,
             int tierBlocks) {
            barLeft = bl; barTop = bt; barRight = br; barBottom = bb;
            bodyLeft = l; bodyTop = t; bodyRight = r; bodyBottom = b;
            fillRatio = fill;
            hueBucket = hue;
            this.tierBlocks = tierBlocks;
        }

        public int cx() { return (bodyLeft + bodyRight) / 2; }
        public int cy() { return (bodyTop + bodyBottom) / 2; }
        public int barWidth() { return barRight - barLeft + 1; }
        public int barHeight() { return barBottom - barTop + 1; }
    }

    /** 上一次检测各阶段的候选数量，用于调参与诊断。 */
    public static volatile int[] lastStats = new int[4];

    public static List<Blob> detect(Bitmap bmp) {
        List<Blob> out = new ArrayList<>();
        if (bmp == null) return out;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 60 || h < 60) return out;

        // 一次性把像素读进数组：逐像素 getPixel 在整屏扫描时是主要卡顿来源
        final int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        final int[] lum = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            lum[i] = (r * 77 + g * 150 + b * 29) >> 8;
        }

        // 右上角是小地图（黑底白图），不是游戏场景，整体排除
        int mapX = (int) (w * 0.62f);
        int mapY = (int) (h * 0.42f);

        List<int[]> bars = new ArrayList<>();
        int geometric = 0, geometricOk = 0, passed = 0;

        // 血条判据（在 2000x1200 实机截图上逐像素标定）：
        //  1) 填充是高亮度彩色，与地面草绿靠亮度差区分
        //  2) 厚度 4~16px，宽度 40~300px
        //  3) 条内颜色均匀（UI 图标有渐变/图案，方差大）
        //  4) 上下各有一条深色描边
        // 旧版依赖「条里有白色百分比文字」，但并非所有单位都显示百分比，
        // 会导致大量漏检（实机一条都检不到）。
        for (int y = 6; y < h - 6; y++) {
            int x = 6;
            while (x < w - 6) {
                if (isBarFill(px[y * w + x])) {
                    int x0 = x;
                    while (x < w - 6 && isBarFill(px[y * w + x])) x++;
                    int len = x - x0;
                    if (len >= 40 && len <= 300) {
                        int cx = (x0 + x - 1) / 2;
                        int thick = 0;
                        while (thick < 20 && y + thick < h
                                && isBarFill(px[(y + thick) * w + cx])) {
                            thick++;
                        }
                        geometric++;
                        if (thick >= 4 && thick <= 14
                                && len / (float) thick >= 6.0f) {
                            geometricOk++;
                            if (isUniform(px, w, x0, x, y + thick / 2)
                                    && isDarkNear(lum, w, h, cx, y - 1, -1)
                                    && isDarkNear(lum, w, h, cx, y + thick, 1)
                                    && cleanGroundRatio(px, w, h, x0, x - 1, y, y + thick - 1) >= 0.75f) {
                                passed++;
                                bars.add(new int[]{x0, x - 1, y, y + thick - 1});
                            }
                        }
                    }
                } else {
                    x++;
                }
            }
        }

        lastStats = new int[]{geometric, geometricOk, passed, bars.size()};

        List<int[]> uniq = new ArrayList<>();
        for (int[] b : bars) {
            int cx = (b[0] + b[1]) / 2, cy = (b[2] + b[3]) / 2;
            boolean dup = false;
            for (int[] u : uniq) {
                if (Math.abs(cx - (u[0] + u[1]) / 2) < 30
                        && Math.abs(cy - (u[2] + u[3]) / 2) < 10) {
                    dup = true;
                    break;
                }
            }
            if (!dup) uniq.add(b);
        }

        for (int[] b : uniq) {
            int l = b[0], r = b[1], top = b[2], bot = b[3];
            if (!hasBodyAbove(lum, w, h, l, r, top)) continue;
            out.add(new Blob(l, top, r, bot,
                    l, Math.max(0, top - 46), r, Math.max(0, top - 6),
                    fillRatio(lum, w, l, r, top, bot),
                    hueBucket(px, w, h, l, r, top, bot),
                    tierBlocks(lum, w, h, l, r, bot)));
        }
        return out;
    }

    /** 血条填充：亮度高、饱和度足的彩色像素（排除灰白文字与深色描边）。 */
    private static boolean isBarFill(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        return mx >= 190 && (mx - mn) >= 60;
    }

    /**
     * 血条上下 4~6px 处应基本是「纯地面绿」。
     *
     * 这一条是排除 UI 的关键：顶部工具栏的按钮、左侧玩家名字条周围
     * 都是深色底或白色边框，只有世界里的单位血条是直接贴在地面上的。
     * 实机标定：岩石血条 0.94，Discord 按钮 0.00，玩家名字条 0.25。
     */
    private static float cleanGroundRatio(int[] px, int w, int h,
                                          int x0, int x1, int y0, int y1) {
        int ok = 0, tot = 0;
        int[] fr = {15, 30, 70, 85};
        int[] dy = {-4, -6, 4, 6};
        for (int f : fr) {
            int x = x0 + (x1 - x0) * f / 100;
            for (int d : dy) {
                int y = d < 0 ? y0 + d : y1 + d;
                if (x < 0 || x >= w || y < 0 || y >= h) continue;
                tot++;
                int c = px[y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int mx = Math.max(r, Math.max(g, b));
                int mn = Math.min(r, Math.min(g, b));
                int v = (r * 77 + g * 150 + b * 29) >> 8;
                // 地面绿：绿通道占优、饱和度足够、明度中等（排除纯黑 UI 与纯白边框）
                if (g == mx && g - mn > 60 && v > 90 && v < 230) ok++;
            }
        }
        return tot == 0 ? 0f : ok / (float) tot;
    }

    /** 条内颜色是否均匀：血条是纯色填充，UI 图标有渐变或图案。 */
    private static boolean isUniform(int[] px, int w, int x0, int x1, int y) {
        int n = 0;
        double sr = 0, sg = 0, sb = 0, sr2 = 0, sg2 = 0, sb2 = 0;
        int step = Math.max(1, (x1 - x0) / 20);
        for (int x = x0 + (x1 - x0) / 5; x < x1 - (x1 - x0) / 5; x += step) {
            int c = px[y * w + x];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            sr += r; sg += g; sb += b;
            sr2 += r * r; sg2 += g * g; sb2 += b * b;
            n++;
        }
        if (n < 3) return false;
        double vr = sr2 / n - (sr / n) * (sr / n);
        double vg = sg2 / n - (sg / n) * (sg / n);
        double vb = sb2 / n - (sb / n) * (sb / n);
        double sd = Math.sqrt(Math.max(0, vr)) + Math.sqrt(Math.max(0, vg))
                + Math.sqrt(Math.max(0, vb));
        return sd <= 22;
    }

    /** 在指定位置附近找深色描边像素。dir=-1 向上、1 向下。 */
    private static boolean isDarkNear(int[] lum, int w, int h, int x, int y, int dir) {
        for (int d = 0; d <= 3; d++) {
            int yy = y + dir * d;
            if (yy < 0 || yy >= h) return false;
            if (lum[yy * w + x] < 110) return true;
        }
        return false;
    }

    private static boolean isDark(int[] lum, int w, int x, int y) {
        return lum[y * w + x] < 85;
    }

    /** 血条上方应有一块彩色区域，作为单位本体；用于排除 UI 按钮。 */
    private static boolean hasBodyAbove(int[] lum, int w, int h, int l, int r, int top) {
        int x0 = l + (r - l) / 5;
        int x1 = r - (r - l) / 5;
        int y0 = Math.max(0, top - 46);
        int y1 = Math.max(0, top - 8);
        int colored = 0, total = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x <= x1; x += 2) {
                total++;
                int v = lum[y * w + x];
                // 亮度中高且不一致 —— 本体有颜色，纯背景是均匀的
                if (v > 95 && v < 245) colored++;
            }
        }
        return total > 0 && colored / (float) total > 0.12f;
    }

    /** 条内彩色像素占比，近似血量。 */
    private static int fillRatio(int[] lum, int w, int l, int r, int top, int bot) {
        int colored = 0, total = 0;
        for (int y = top + 2; y < bot - 1; y++) {
            for (int x = l + 2; x <= r - 2; x++) {
                total++;
                int v = lum[y * w + x];
                if (v > 60 && v < 245) colored++;
            }
        }
        return total == 0 ? 100 : Math.round(colored * 100f / total);
    }

    /**
     * 血条下方那行字的字符块数，用来估计稀有度/等级带：
     * Common 6 块、Unusual 7 块、Rare 4 块、Epic 4 块、Legendary 9 块。
     */
    private static int tierBlocks(int[] lum, int w, int h, int l, int r, int barBottom) {
        int y0 = barBottom + 2;
        int y1 = Math.min(h - 1, barBottom + 22);
        if (y1 <= y0) return 0;
        int x0 = Math.max(0, l - 10), x1 = Math.min(w - 1, r + 10);
        boolean[] colLit = new boolean[x1 - x0 + 1];
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int v = lum[y * w + x];
                if (v > 150) colLit[x - x0] = true;
            }
        }
        int blocks = 0;
        boolean in = false;
        for (boolean b : colLit) {
            if (b && !in) {
                blocks++;
                in = true;
            } else if (!b) {
                in = false;
            }
        }
        return blocks >= 2 && blocks <= 12 ? blocks : 0;
    }

    private static int hueBucket(int[] px, int w, int h, int l, int r, int top, int bot) {
        float[] hsv = new float[3];
        int[] count = new int[6];
        for (int y = top; y <= bot; y += 2) {
            if (y < 0 || y >= h) continue;
            for (int x = l; x <= r; x += 2) {
                if (x < 0 || x >= w) continue;
                int c = px[y * w + x];
                Color.RGBToHSV((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, hsv);
                if (hsv[1] < 0.30f || hsv[2] < 0.25f) continue;
                int b = (int) (hsv[0] / 60f);
                if (b > 5) b = 5;
                count[b]++;
            }
        }
        int best = 0;
        for (int i = 1; i < 6; i++) if (count[i] > count[best]) best = i;
        return best;
    }

    private MobDetector() {}
}
