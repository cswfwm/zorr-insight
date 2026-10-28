package com.zorrinsight.probe;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 把游戏画面降维成固定长度特征向量：把画面分成 6x6 网格，每格取平均色
 * （HSV 的色调分布 + 明度/饱和度均值），再加上整幅画面的统计量。
 * 区域差异主要体现为地面底色与纹理，这个特征足以区分 Garden/Desert/Ocean/Jungle/Hel。
 */
public final class FrameFeatures {

    public static final int GRID = 6;
    /** 每格：hue6(6 个色调桶) + sat + val = 8 维；另加 4 维全局统计 */
    public static final int PER_CELL = 8;
    public static final int DIM = GRID * GRID * PER_CELL + 4;

    private FrameFeatures() {}

    public static float[] from(Bitmap bmp) {
        float[] f = new float[DIM];
        if (bmp == null) return f;

        int w = bmp.getWidth();
        int h = bmp.getHeight();
        // 只取中央区域，避开四周 UI（按钮、聊天、槽位）
        int x0 = (int) (w * 0.18), x1 = (int) (w * 0.82);
        int y0 = (int) (h * 0.16), y1 = (int) (h * 0.84);
        int cw = Math.max(1, (x1 - x0) / GRID);
        int ch = Math.max(1, (y1 - y0) / GRID);

        float[] hsv = new float[3];
        float satSum = 0, valSum = 0;
        int cells = 0;

        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                float[] cell = new float[PER_CELL];
                int count = 0;
                // 每格采样 10x10，避免逐像素拖慢
                for (int sy = 0; sy < 10; sy++) {
                    for (int sx = 0; sx < 10; sx++) {
                        int px = x0 + gx * cw + (sx * cw) / 10;
                        int py = y0 + gy * ch + (sy * ch) / 10;
                        if (px < 0 || py < 0 || px >= w || py >= h) continue;
                        int c = bmp.getPixel(px, py);
                        Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv);
                        int bucket = (int) (hsv[0] / 60f);
                        if (bucket > 5) bucket = 5;
                        cell[bucket] += 1f;
                        cell[6] += hsv[1];
                        cell[7] += hsv[2];
                        satSum += hsv[1];
                        valSum += hsv[2];
                        count++;
                    }
                }
                if (count > 0) {
                    for (int i = 0; i < PER_CELL; i++) cell[i] /= count;
                }
                int base = (gy * GRID + gx) * PER_CELL;
                System.arraycopy(cell, 0, f, base, PER_CELL);
                cells++;
            }
        }

        int tail = GRID * GRID * PER_CELL;
        float n = Math.max(1, cells * 100f);
        f[tail] = satSum / n;
        f[tail + 1] = valSum / n;
        f[tail + 2] = satSum / n * (valSum / n);
        f[tail + 3] = 1f;   // 偏置位，保持维度固定
        return f;
    }

    /** 粗分区域：仅用于没有模型时的兜底展示，不参与训练。 */
    public static String roughRegion(float[] f) {
        int tail = GRID * GRID * PER_CELL;
        float sat = f[tail], val = f[tail + 1];
        if (val > 0.75f && sat < 0.35f) return "Hel / Void（灰白）";
        if (sat > 0.55f && val > 0.55f) return "Garden（高饱和草绿）";
        if (sat > 0.3f && val > 0.7f) return "Desert（明亮沙色）";
        if (val < 0.45f) return "Ocean（偏暗）";
        return "Jungle（深绿）";
    }
}
