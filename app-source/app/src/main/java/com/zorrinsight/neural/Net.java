package com.zorrinsight.neural;

import java.util.Random;

/**
 * 单隐层 MLP，softmax 分类。用于把「画面颜色特征」映射到「区域/生态」标签。
 * 训练在设备端进行，样本可来自 AI 生成的标注，也可来自人工确认。
 */
public final class Net {

    public final int in, hidden, out;
    private final float[][] w1;   // hidden x in
    private final float[] b1;     // hidden
    private final float[][] w2;   // out x hidden
    private final float[] b2;     // out

    private final float[][] gw1;
    private final float[] gb1;
    private final float[][] gw2;
    private final float[] gb2;

    private final float[] h;      // hidden 激活
    private final float[] logits;
    private final float[] probs;

    public Net(int in, int hidden, int out, long seed) {
        this.in = in;
        this.hidden = hidden;
        this.out = out;
        Random rnd = new Random(seed);
        w1 = new float[hidden][in];
        b1 = new float[hidden];
        w2 = new float[out][hidden];
        b2 = new float[out];
        gw1 = new float[hidden][in];
        gb1 = new float[hidden];
        gw2 = new float[out][hidden];
        gb2 = new float[out];
        h = new float[hidden];
        logits = new float[out];
        probs = new float[out];
        float s1 = (float) Math.sqrt(2.0 / in);
        float s2 = (float) Math.sqrt(2.0 / hidden);
        for (int i = 0; i < hidden; i++) {
            for (int j = 0; j < in; j++) w1[i][j] = (float) (rnd.nextGaussian() * s1);
        }
        for (int i = 0; i < out; i++) {
            for (int j = 0; j < hidden; j++) w2[i][j] = (float) (rnd.nextGaussian() * s2);
        }
    }

    public synchronized float[] forward(float[] x) {
        for (int i = 0; i < hidden; i++) {
            float sum = b1[i];
            float[] wi = w1[i];
            for (int j = 0; j < in; j++) sum += wi[j] * x[j];
            h[i] = sum > 0 ? sum : 0;      // ReLU
        }
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < out; i++) {
            float sum = b2[i];
            float[] wi = w2[i];
            for (int j = 0; j < hidden; j++) sum += wi[j] * h[j];
            logits[i] = sum;
            if (sum > max) max = sum;
        }
        float tot = 0;
        for (int i = 0; i < out; i++) {
            probs[i] = (float) Math.exp(logits[i] - max);
            tot += probs[i];
        }
        for (int i = 0; i < out; i++) probs[i] /= tot;
        return probs;
    }

    public synchronized int predict(float[] x) {
        float[] p = forward(x);
        int best = 0;
        for (int i = 1; i < out; i++) if (p[i] > p[best]) best = i;
        return best;
    }

    /** 单样本反向传播，返回交叉熵损失。 */
    public synchronized float trainOne(float[] x, int label, float lr, float l2) {
        float[] p = forward(x);
        float loss = (float) -Math.log(Math.max(p[label], 1e-7f));

        // 输出层梯度 dL/dlogit = p - y
        for (int i = 0; i < out; i++) {
            float d = p[i] - (i == label ? 1f : 0f);
            float[] gi = gw2[i];
            float[] wi = w2[i];
            for (int j = 0; j < hidden; j++) gi[j] += d * h[j];
            gb2[i] += d;
        }
        // 隐层梯度
        for (int j = 0; j < hidden; j++) {
            if (h[j] <= 0) continue;
            float d = 0;
            for (int i = 0; i < out; i++) {
                d += (p[i] - (i == label ? 1f : 0f)) * w2[i][j];
            }
            float[] gj = gw1[j];
            for (int k = 0; k < in; k++) gj[k] += d * x[k];
            gb1[j] += d;
        }
        return loss;
    }

    /** 把累积梯度应用一步，然后清零。 */
    public synchronized void step(float lr, float l2, int batch) {
        float scale = lr / Math.max(1, batch);
        for (int i = 0; i < hidden; i++) {
            float[] wi = w1[i];
            float[] gi = gw1[i];
            for (int j = 0; j < in; j++) {
                wi[j] -= scale * (gi[j] + l2 * wi[j]);
                gi[j] = 0;
            }
            b1[i] -= scale * gb1[i];
            gb1[i] = 0;
        }
        for (int i = 0; i < out; i++) {
            float[] wi = w2[i];
            float[] gi = gw2[i];
            for (int j = 0; j < hidden; j++) {
                wi[j] -= scale * (gi[j] + l2 * wi[j]);
                gi[j] = 0;
            }
            b2[i] -= scale * gb2[i];
            gb2[i] = 0;
        }
    }

    public float[][] weights1() { return w1; }
    public float[][] weights2() { return w2; }

    /** 最近一次 forward 的隐层激活（ReLU 输出），供结构可视化。 */
    public synchronized float[] hiddenActivations() { return h.clone(); }

    /** 最近一次 forward 的输出概率。 */
    public synchronized float[] lastProbs() { return probs.clone(); }

    /** 隐层每个神经元的权重绝对值均值，用来表示连接强度。 */
    public float[] hiddenMagnitudes() {
        float[] m = new float[hidden];
        for (int i = 0; i < hidden; i++) {
            float sum = 0;
            for (int j = 0; j < in; j++) sum += Math.abs(w1[i][j]);
            m[i] = sum / Math.max(1, in);
        }
        return m;
    }

    /** 保存为简单文本，便于导出到其它工具分析。 */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(in).append(' ').append(hidden).append(' ').append(out).append('\n');
        for (float[] row : w1) { for (float v : row) sb.append(v).append(' '); sb.append('\n'); }
        for (float v : b1) sb.append(v).append(' ');
        sb.append('\n');
        for (float[] row : w2) { for (float v : row) sb.append(v).append(' '); sb.append('\n'); }
        for (float v : b2) sb.append(v).append(' ');
        sb.append('\n');
        return sb.toString();
    }
}
