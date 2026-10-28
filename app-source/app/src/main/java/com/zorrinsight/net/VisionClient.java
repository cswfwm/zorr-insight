package com.zorrinsight.net;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视觉识别：把画面裁片发给支持图像输入的 OpenAI 兼容接口，让它读出图上的文字。
 *
 * 用户把游戏内的名称显示打开后，单位名会画在画面上；这里只做「看图读字」，
 * 不接触游戏内部数据，也不向页面注入任何东西。
 */
public final class VisionClient {

    public interface Cb {
        void onOk(String json);
        void onErr(String message);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private final AiClient ai;

    public VisionClient(Context ctx) {
        ai = new AiClient(ctx);
    }

    public boolean configured() {
        return ai.configured();
    }

    /** 让模型读出图中所有可见的文字（单位名、百分比等）。 */
    public void readText(byte[] png, int maxTokens, Cb cb) {
        String system = String.join("\n",
                "你会收到一张 zorr.pro 游戏画面截图。",
                "任务：找出画面里所有敌方单位，读出它们头顶或附近的文字。",
                "",
                "每个单位需要给出：",
                "1. name：画面上显示的单位名称文字（照抄看到的字，不要推测）",
                "2. rarity：血条下方那行稀有度英文（Common / Unusual / Rare / Epic / Legendary / Mythic 等）",
                "3. hp：血条里的血量百分比",
                "4. x、y：该单位在画面中的相对坐标，取值 0~1",
                "",
                "同时读出画面上的统计数字：DPS、击杀数、总伤害、存活时间。",
                "",
                "严格按此格式输出 JSON（尖括号内容替换成你实际看到的，不要保留尖括号）：",
                "{\"units\":[{\"name\":\"<照抄的名称>\",\"rarity\":\"<稀有度>\",\"hp\":\"<百分比>\",\"x\":<0到1>,\"y\":<0到1>}],",
                "\"stats\":{\"dps\":\"\",\"kills\":\"\",\"damage\":\"\",\"time\":\"\"}}",
                "",
                "注意：看不清的文字留空字符串，不要猜测，不要用示例里的内容填充。",
                "画面里没有敌方单位时输出 {\"units\":[],\"stats\":{}}。");
        
String user = "读出画面里的敌方单位名称。";
        chatWithImage(png, system, user, maxTokens, cb);
    }

    /**
     * 只读小地图下方的区域名。
     *
     * 区域名是简短英文单词（Garden / Desert / Ocean / Jungle / Hel 等），
     * 靠字符块数去猜容易把 Hel 和 Jungle 认混，交给模型直接读字更可靠。
     */
    public void readRegion(byte[] png, String hint, Cb cb) {
        String system = String.join("\n",
                "这张图是游戏画面右上角的小地图区域。",
                "小地图方块的下方有一行区域名（英文），可能是：",
                "Plains / Desert / Ocean / Jungle / Hel / Arctic / Sewers / Void / Bio / Garden 之一。",
                "请只输出那行的原文，不要解释，不要加引号。",
                "看不到就输出 UNKNOWN。");
        String user = hint == null || hint.isEmpty()
                ? "读出小地图下方的区域名。" : "像素法初判为 " + hint + "，请确认实际文字。";
        chatWithImage(png, system, user, 32, cb);
    }

    private void chatWithImage(byte[] png, String system, String user, int maxTokens, Cb cb) {
        POOL.execute(() -> {
            HttpURLConnection c = null;
            try {
                String b64 = Base64.getEncoder().encodeToString(png);

                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "text").put("text", user));
                content.put(new JSONObject()
                        .put("type", "image_url")
                        .put("image_url", new JSONObject()
                                .put("url", "data:image/png;base64," + b64)));

                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", system));
                messages.put(new JSONObject().put("role", "user").put("content", content));

                JSONObject body = new JSONObject()
                        .put("model", ai.visionModel())
                        .put("messages", messages)
                        .put("temperature", 0.1)
                        .put("max_tokens", maxTokens);

                c = (HttpURLConnection) new URL(endpoint()).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(20000);
                c.setReadTimeout(120000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + ai.visionApiKey());
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = c.getResponseCode();
                String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code >= 400) {
                    cb.onErr("HTTP " + code + "：" + shortText(text));
                    return;
                }
                JSONObject json = new JSONObject(text);
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    cb.onErr("响应中没有 choices");
                    return;
                }
                String content2 = choices.getJSONObject(0)
                        .optJSONObject("message").optString("content", "");
                if (content2.trim().isEmpty()) {
                    cb.onErr("模型返回空内容");
                    return;
                }
                cb.onOk(content2.trim());
            } catch (Exception e) {
                cb.onErr(e.getClass().getSimpleName() + "：" + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private String endpoint() {
        String b = ai.visionBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        if (b.endsWith("/v1")) return b + "/chat/completions";
        return b + "/v1/chat/completions";
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String shortText(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /** 把裁剪后的画面编码成 PNG。 */
    public static byte[] toPng(android.graphics.Bitmap bmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }
}
