package com.zorrinsight.neural;

import android.graphics.Bitmap;

import android.graphics.Color;

import com.zorrinsight.probe.FrameFeatures;
import com.zorrinsight.probe.MobDetector;
import com.zorrinsight.probe.RegionProbe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自动训练：持续从画面抽样本，训练三个任务。
 *
 * 1. 区域（Region）：小地图主色 → Garden/Desert/Ocean/Jungle/Hel
 * 2. 地图（Map）：整幅画面的 292 维颜色分布，用区域标签顺带训练（判断玩家在不在同一张图）
 * 3. 单位（Mob）：血条上方单位本体的颜色分布 → 色系类别
 *
 * 后两个任务的标签都来自画面自身可读的线索，不需要人工逐张标注：
 * 区域来自小地图，单位色系来自血条位置采样。这是这套「自监督」设计的核心。
 */
public final class AutoTrainer {

    public static final String[] REGION_LABELS = {
            "Garden", "Desert", "Ocean", "Jungle", "Hel", "Unknown"
    };

    public static final String[] MOB_LABELS = {
            "红系", "黄系", "绿系", "青系", "蓝系", "品红系", "灰白系", "Unknown"
    };

    /** 单位特征：色相 6 桶 + 饱和 + 明度 + 血条填充率 + 宽度归一 */
    public static final int MOB_DIM = 10;

    public static final class Sample {
        public final float[] x;
        public final int y;
        public final String src;

        Sample(float[] x, int y, String src) {
            this.x = x;
            this.y = y;
            this.src = src;
        }
    }

    private final Pool region = new Pool();
    private final Pool map = new Pool();
    private final Pool mob = new Pool();

    private final Net netRegion;
    private final Net netMap;
    private final Net netMob;

    private volatile boolean running;
    private volatile long lastTick;
    private volatile int lastDetected;
    private volatile String lastRegionName = "—";
    private volatile float lastRegionConf;

    public AutoTrainer() {
        netRegion = new Net(FrameFeatures.DIM, 24, REGION_LABELS.length, 11L);
        netMap = new Net(FrameFeatures.DIM, 32, REGION_LABELS.length, 22L);
        netMob = new Net(MOB_DIM, 20, MOB_LABELS.length, 33L);
    }

    public Net regionNet() { return netRegion; }
    public Net mapNet() { return netMap; }
    public Net mobNet() { return netMob; }

    public Pool regionPool() { return region; }
    public Pool mapPool() { return map; }
    public Pool mobPool() { return mob; }

    public boolean running() { return running; }
    public long lastTick() { return lastTick; }
    public int lastDetected() { return lastDetected; }
    public String lastRegionName() { return lastRegionName; }
    public float lastRegionConf() { return lastRegionConf; }

    /**
     * 每帧调用：读小地图判区域，顺带采一条地图样本和若干单位样本。
     * 返回这一帧检测到的单位数量。
     */
    public int observe(Bitmap frame) {
        if (frame == null) return 0;
        lastTick = System.currentTimeMillis();

        RegionProbe.Region r = RegionProbe.read(frame);
        lastRegionName = r.name;
        lastRegionConf = r.confidence;

        float[] feat = FrameFeatures.from(frame);
        int regionLbl = indexOf(REGION_LABELS, r.name);
        if (regionLbl >= 0 && regionLbl != REGION_LABELS.length - 1) {
            // 只有明确的区域才收样本，Unknown 不进池
            if (r.confidence >= 0.35f) {
                region.add(new Sample(feat.clone(), regionLbl, "self"));
                map.add(new Sample(feat.clone(), regionLbl, "self"));
            }
        }

        List<MobDetector.Blob> blobs = MobDetector.detect(frame);
        lastDetected = blobs.size();
        for (MobDetector.Blob b : blobs) {
            mob.add(new Sample(mobFeatures(frame, b), mobLabel(b), "self"));
        }
        return blobs.size();
    }

    /**
     * 单位特征向量。
     *
     * 用像素数组一次取完，避免逐点 getPixel；
     * 色相桶按「参与统计的像素数」归一，再加饱和度/明度的均值与方差，
     * 让不同单位之间真正有区分度——之前只用均值，很多单位特征几乎相同。
     */
    public static float[] mobFeatures(Bitmap frame, MobDetector.Blob b) {
        float[] f = new float[MOB_DIM];
        int left = Math.max(0, b.bodyLeft), top = Math.max(0, b.bodyTop);
        int right = Math.min(frame.getWidth() - 1, b.bodyRight);
        int bottom = Math.min(frame.getHeight() - 1, b.bodyBottom);
        if (right <= left || bottom <= top) return f;

        int w = frame.getWidth();
        int[] px = new int[w * (bottom - top + 1)];
        frame.getPixels(px, 0, w, left, top, right - left + 1, bottom - top + 1);
        int rw = right - left + 1, rh = bottom - top + 1;

        float[] hsv = new float[3];
        int n = 0;
        float satSum = 0, satSq = 0, valSum = 0, valSq = 0;

        for (int y = 0; y < rh; y += 2) {
            for (int x = 0; x < rw; x += 2) {
                int c = px[y * rw + x];
                Color.RGBToHSV((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
                int bucket = (int) (hsv[0] / 60f);
                if (bucket > 5) bucket = 5;
                f[bucket] += 1f;
                satSum += hsv[1];
                satSq += hsv[1] * hsv[1];
                valSum += hsv[2];
                valSq += hsv[2] * hsv[2];
                n++;
            }
        }
        if (n > 0) {
            for (int i = 0; i < 6; i++) f[i] /= n;
            float ms = satSum / n, mv = valSum / n;
            f[6] = ms;
            f[7] = mv;
            // 第 8、9 位留给血量与等级带，方差的区分力放到颜色桶里已够
            float varS = Math.max(0, satSq / n - ms * ms);
            float varV = Math.max(0, valSq / n - mv * mv);
            for (int i = 0; i < 6; i++) {
                f[i] = f[i] * (0.7f + 0.3f * varS) + varV * 0.1f;
            }
        }
        f[8] = b.fillRatio / 100f;
        f[9] = b.tierBlocks / 9f;
        return f;
    }

    private static int mobLabel(MobDetector.Blob b) {
        // 血条几乎空且色相不明显，当作灰白系
        if (b.fillRatio >= 0 && b.hueBucket < 0) return 6;
        if (b.fillRatio < 12) return 6;
        return Math.max(0, Math.min(5, b.hueBucket));
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return arr.length - 1;
    }

    /**
     * 后台自动训练。
     *
     * 三路各自独立：样本不够就跳过，没有新增样本就不重复训练，
     * 避免在没有新信息时把 CPU 空转掉（这也是之前卡顿的一部分来源）。
     */
    public void startTraining(long intervalMs) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            int lastRegion = -1, lastMap = -1, lastMob = -1;
            while (running) {
                try {
                    int rs = region.size(), ms = map.size(), bs = mob.size();
                    if (rs != lastRegion) {
                        region.train(netRegion, 4, 0.04f);
                        lastRegion = rs;
                    }
                    if (ms != lastMap) {
                        map.train(netMap, 3, 0.03f);
                        lastMap = ms;
                    }
                    if (bs != lastMob) {
                        mob.train(netMob, 5, 0.05f);
                        lastMob = bs;
                    }
                    trainedRounds++;
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                }
            }
        }, "auto-trainer");
        t.setDaemon(true);
        t.start();
    }

    private volatile int trainedRounds;

    public int trainedRounds() { return trainedRounds; }

    /** 三路样本与准确率的整体摘要，用于界面展示。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("区域 ").append(region.size())
          .append(" · 地图 ").append(map.size())
          .append(" · 单位 ").append(mob.size());
        if (region.size() >= 12) {
            sb.append("｜验证准确率 ");
            sb.append(String.format("%.0f%%", region.lastAcc() * 100));
        }
        return sb.toString();
    }

    public void stopTraining() {
        running = false;
    }

    /** 样本池 + 自己的一次训练循环。 */
    public static final class Pool {
        private final List<Sample> items = Collections.synchronizedList(new ArrayList<>());
        private final ArrayDeque<Float> lossHist = new ArrayDeque<>();
        private final ArrayDeque<Float> accHist = new ArrayDeque<>();
        private static final int MAX_SAMPLES = 4000;
        private static final int HIST = 90;
        private volatile float lastLoss = Float.NaN;
        private volatile float lastAcc = Float.NaN;
        private volatile int epochs;

        public void add(float[] x, int y, String src) {
            add(new Sample(x, y, src));
        }

        void add(Sample s) {
            items.add(s);
            if (items.size() > MAX_SAMPLES) items.remove(0);
        }

        public int size() { return items.size(); }
        public int epochs() { return epochs; }
        public float lastLoss() { return lastLoss; }
        public float lastAcc() { return lastAcc; }

        public float[] lossSeries() { return arr(lossHist); }
        public float[] accSeries() { return arr(accHist); }

        private static float[] arr(ArrayDeque<Float> q) {
            float[] o = new float[q.size()];
            int i = 0;
            for (Float v : q) o[i++] = v;
            return o;
        }

        /**
         * 训练若干轮。
         *
         * 训练集与验证集分开：验证集不参与更新，准确率才是可信的。
         * 学习率随损失下降自动衰减，连续多轮验证准确率没有提升就提前收工，
         * 避免在已经收敛的样本上反复空转吃 CPU。
         */
        void train(Net net, int rounds, float baseLr) {
            List<Sample> all;
            synchronized (items) {
                if (items.size() < 12) return;
                all = new ArrayList<>(items);
            }
            Collections.shuffle(all);
            int valCount = Math.max(2, all.size() / 5);
            List<Sample> val = new ArrayList<>(all.subList(0, valCount));
            List<Sample> train = new ArrayList<>(all.subList(valCount, all.size()));
            if (train.isEmpty()) return;

            float bestAcc = -1;
            int stale = 0;
            float lr = baseLr;

            for (int e = 0; e < rounds; e++) {
                Collections.shuffle(train);
                float sum = 0;
                final int batch = 8;
                int n = 0;
                for (Sample smp : train) {
                    if (smp.x.length != net.in) continue;
                    sum += net.trainOne(smp.x, smp.y, lr, 1e-4f);
                    if (++n % batch == 0) net.step(lr, 1e-4f, batch);
                }
                if (n == 0) return;
                if (n % batch != 0) net.step(lr, 1e-4f, n % batch);

                int ok = 0;
                for (Sample smp : val) {
                    if (smp.x.length == net.in && net.predict(smp.x) == smp.y) ok++;
                }
                float acc = (float) ok / val.size();
                lastLoss = sum / n;
                lastAcc = acc;
                epochs++;

                synchronized (lossHist) {
                    lossHist.add(lastLoss);
                    accHist.add(acc);
                    while (lossHist.size() > HIST) lossHist.removeFirst();
                    while (accHist.size() > HIST) accHist.removeFirst();
                }

                if (acc > bestAcc + 1e-4f) {
                    bestAcc = acc;
                    stale = 0;
                } else if (++stale >= 3) {
                    // 连续 3 轮没进步：降学习率，再不行就停
                    lr *= 0.5f;
                    stale = 0;
                    if (lr < baseLr * 0.1f) break;
                }
            }
        }
    }
}
