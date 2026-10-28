package com.zorrinsight.probe;

import android.graphics.Bitmap;

/**
 * 从连续两帧画面估计运动量。
 *
 * 转速：花瓣绕着玩家转，把玩家中心外一圈环形区域按角度分成 36 份，
 *       统计每份里「彩色像素」的比例，得到一条角度剖面；两帧剖面做循环相关，
 *       峰值位置就是转过的角度。
 * 移速：取中心外一圈区域做块匹配，找两帧之间最吻合的平移量，换算成每秒像素。
 *
 * 两者都是画面估算，不是游戏内部数值。
 */
public final class MotionProbe {

    public static final class Motion {
        public float degPerSec = Float.NaN;
        public float pxPerSec = Float.NaN;
    }

    private static final int BINS = 36;

    /** 计算两帧之间的转动与位移。reason 为空表示成功。 */
    public static Motion estimate(Bitmap prev, Bitmap cur, long dtMs) {
        Motion m = new Motion();
        if (prev == null || cur == null || dtMs <= 0) return m;
        int w = Math.min(prev.getWidth(), cur.getWidth());
        int h = Math.min(prev.getHeight(), cur.getHeight());
        if (w < 40 || h < 40) return m;

        int cx = w / 2, cy = h / 2;
        int rMax = Math.min(w, h) / 2;

        int n = w * h;
        int[] anaA = analyze(prev);
        int[] anaB = analyze(cur);

        float[] a = ringProfile(anaA, n, w, h, cx, cy, rMax);
        float[] b = ringProfile(anaB, n, w, h, cx, cy, rMax);
        if (a != null && b != null) {
            int shift = bestShift(a, b);
            if (shift != Integer.MIN_VALUE) {
                float deg = shift * (360f / BINS);
                m.degPerSec = deg * 1000f / dtMs;
            }
        }

        float[] d = flow(anaA, anaB, w, h, cx, cy, rMax);
        if (d != null) {
            float px = (float) Math.hypot(d[0], d[1]);
            m.pxPerSec = px * 1000f / dtMs;
        }
        return m;
    }

    /**
     * 一次性取像素，同时算出亮度数组和「高饱和彩色」掩码。
     *
     * 转速靠环带上的彩色分布做互相关；如果只用亮度判据，草绿地面几乎全亮，
     * 剖面会退化成平滑曲线，转角永远算不出来，所以必须保留彩色判定。
     */
    private static int[] analyze(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        // 结果布局：前半是亮度，后半是彩色标记（1 表示高饱和彩色）
        int[] out = new int[px.length * 2];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
            out[i] = (r * 77 + g * 150 + bl * 29) >> 8;
            int mx = Math.max(r, Math.max(g, bl));
            int mn = Math.min(r, Math.min(g, bl));
            out[px.length + i] = (mx - mn > 60 && mx > 120) ? 1 : 0;
        }
        return out;
    }

    /** 环形区的角度剖面：每份里的彩色像素占比。 */
    private static float[] ringProfile(int[] ana, int n, int w, int h,
                                       int cx, int cy, int rMax) {
        float r0 = rMax * 0.30f, r1 = rMax * 0.78f;
        int r0i = (int) r0, r1i = (int) r1;
        if (r1i <= r0i + 4) return null;
        float[] sum = new float[BINS];
        int[] cnt = new int[BINS];
        for (int dy = -r1i; dy <= r1i; dy++) {
            int y = cy + dy;
            if (y < 0 || y >= h) continue;
            for (int dx = -r1i; dx <= r1i; dx++) {
                int x = cx + dx;
                if (x < 0 || x >= w) continue;
                int d2 = dx * dx + dy * dy;
                if (d2 < r0i * r0i || d2 > r1i * r1i) continue;
                int bin = (int) ((Math.atan2(dy, dx) + Math.PI) / (2 * Math.PI) * BINS) % BINS;
                cnt[bin]++;
                if (ana[n + y * w + x] == 1) sum[bin]++;
            }
        }
        float[] prof = new float[BINS];
        for (int i = 0; i < BINS; i++) {
            prof[i] = cnt[i] == 0 ? 0 : sum[i] / cnt[i];
        }
        return prof;
    }

    /** 循环相关找最佳旋转量（以 bin 为单位，取绝对值最小的等价解）。 */
    private static int bestShift(float[] a, float[] b) {
        float mean = 0;
        for (float v : a) mean += v;
        mean /= a.length;
        if (mean < 0.008f) return Integer.MIN_VALUE;   // 环上几乎没有彩色，判断不了

        int best = 0;
        float bestScore = -Float.MAX_VALUE;
        for (int s = 0; s < BINS; s++) {
            float dot = 0;
            for (int i = 0; i < BINS; i++) {
                dot += a[i] * b[(i + s) % BINS];
            }
            if (dot > bestScore) {
                bestScore = dot;
                best = s;
            }
        }
        if (best > BINS / 2) best -= BINS;
        return best;
    }

    /** 中心外的一圈做块匹配，返回 {dx, dy}（像素）。 */
    private static float[] flow(int[] anaA, int[] anaB, int w, int h,
                                int cx, int cy, int rMax) {
        int inner = (int) (rMax * 0.45f);
        int outer = (int) (rMax * 0.95f);
        final int step = 5;
        final int range = 10;

        float bestScore = Float.MAX_VALUE;
        float[] best = null;
        for (int dy = -range; dy <= range; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                float sad = 0;
                int n = 0;
                for (int y = cy - outer; y <= cy + outer; y += step) {
                    if (y < 0 || y >= h) continue;
                    for (int x = cx - outer; x <= cx + outer; x += step) {
                        if (x < 0 || x >= w) continue;
                        int ddx = x - cx, ddy = y - cy;
                        int d2 = ddx * ddx + ddy * ddy;
                        if (d2 < inner * inner || d2 > outer * outer) continue;
                        int x2 = x + dx, y2 = y + dy;
                        if (x2 < 0 || y2 < 0 || x2 >= w || y2 >= h) continue;
                        sad += Math.abs(anaA[y * w + x] - anaB[y2 * w + x2]);
                        n++;
                    }
                }
                if (n < 40) continue;
                float score = sad / n;
                if (score < bestScore) {
                    bestScore = score;
                    best = new float[]{dx, dy};
                }
            }
        }
        return best;
    }

    private MotionProbe() {}
}
