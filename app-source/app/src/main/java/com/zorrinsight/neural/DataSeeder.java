package com.zorrinsight.neural;

import com.zorrinsight.net.AiClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 用大模型生成前期训练样本。
 *
 * 做法：把当前画面的网格颜色特征（数值）交给模型，让它判断画面属于哪个区域。
 * 模型不需要看图，只需要读一组可解释的颜色统计量——这组量与训练时用的
 * 特征完全一致，因此标注与特征之间不存在表示不匹配。
 */
public final class DataSeeder {

    public interface Progress {
        void onStatus(String text);
        void onDone(int added, String message);
    }

    private static final String SYSTEM = String.join("\n",
            "你是 zorr.pro 的画面分析助手。我会给你一幅游戏画面的 6x6 网格颜色特征。",
            "每个格子有 8 个数：前 6 个把色相分成 0-5 六个桶（红/黄/绿/青/蓝/品红）的占比，",
            "第 7 个是饱和度均值，第 8 个是明度均值。最后 4 个数是整幅画面的统计量。",
            "请判断画面最可能处于哪个区域：Garden、Desert、Ocean、Jungle、Hel 或 Unknown。",
            "依据：Garden 草绿高饱和；Desert 黄褐、明度高、饱和中等；Ocean 偏蓝青、整体偏暗；",
            "Jungle 深绿、明度低；Hel 灰白或暗红、饱和度低。",
            "只输出 JSON：{\"region\":\"Garden\",\"confidence\":0.0-1.0,\"reason\":\"不超过20字\"}");

    private DataSeeder() {}

    /**
     * 基于一组特征向模型请求标注，成功后加入训练池。
     *
     * @param variations 同一特征加上小幅扰动后的多个样本，用于扩大样本量
     */
    public static void seed(AiClient ai, AutoTrainer trainer, float[] features,
                            int variations, Progress cb) {
        if (features == null) {
            cb.onDone(0, "还没有采集到画面特征");
            return;
        }
        String payload = describe(features);
        cb.onStatus("正在请求模型标注…");

        ai.chat(SYSTEM, payload, 200, true, new AiClient.Cb() {
            @Override
            public void onOk(String text) {
                int label = -1;
                String reason = "";
                try {
                    String t = text.trim();
                    int a = t.indexOf('{'), b = t.lastIndexOf('}');
                    if (a >= 0 && b > a) t = t.substring(a, b + 1);
                    JSONObject o = new JSONObject(t);
                    String region = o.optString("region", "Unknown");
                    reason = o.optString("reason", "");
                    label = labelIndex(region);
                } catch (Exception ignored) {
                }
                if (label < 0 || label == AutoTrainer.REGION_LABELS.length - 1) {
                    cb.onDone(0, "模型未能给出确定区域" + (reason.isEmpty() ? "" : "：" + reason));
                    return;
                }
                int added = 0;
                java.util.Random rnd = new java.util.Random(System.nanoTime());
                for (int i = 0; i < Math.max(1, variations); i++) {
                    float[] x = i == 0 ? features.clone() : jitter(features, rnd);
                    trainer.regionPool().add(x, label, "ai");
                    added++;
                }
                cb.onDone(added, "已标注为 "
                        + AutoTrainer.REGION_LABELS[label]
                        + "，新增 " + added + " 条样本"
                        + (reason.isEmpty() ? "" : "（" + reason + "）"));
            }

            @Override
            public void onErr(String message) {
                cb.onDone(0, "请求失败：" + message);
            }
        });
    }

    private static String describe(float[] f) {
        int grid = com.zorrinsight.probe.FrameFeatures.GRID;
        int per = com.zorrinsight.probe.FrameFeatures.PER_CELL;
        StringBuilder sb = new StringBuilder();
        sb.append("特征向量（").append(f.length).append(" 维）：\n");
        sb.append("网格 ");
        for (int gy = 0; gy < grid; gy++) {
            sb.append("行").append(gy + 1).append(": ");
            for (int gx = 0; gx < grid; gx++) {
                int base = (gy * grid + gx) * per;
                sb.append('[');
                for (int k = 0; k < 6; k++) {
                    sb.append(String.format("%.2f", f[base + k]));
                    if (k < 5) sb.append(',');
                }
                sb.append("|S").append(String.format("%.2f", f[base + 6]));
                sb.append("|V").append(String.format("%.2f", f[base + 7]));
                sb.append("] ");
            }
            sb.append('\n');
        }
        int tail = grid * grid * per;
        sb.append("全局: 饱和 ").append(String.format("%.2f", f[tail]))
          .append("，明度 ").append(String.format("%.2f", f[tail + 1]));
        return sb.toString();
    }

    private static float[] jitter(float[] src, java.util.Random rnd) {
        float[] out = new float[src.length];
        for (int i = 0; i < src.length; i++) {
            float n = (float) (rnd.nextGaussian() * 0.02);
            out[i] = Math.max(0f, src[i] + n);
        }
        return out;
    }

    public static int labelIndex(String region) {
        for (int i = 0; i < AutoTrainer.REGION_LABELS.length; i++) {
            if (AutoTrainer.REGION_LABELS[i].equalsIgnoreCase(region)) return i;
        }
        return AutoTrainer.REGION_LABELS.length - 1;
    }
}
