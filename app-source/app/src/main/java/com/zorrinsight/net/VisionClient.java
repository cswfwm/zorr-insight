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
                "请找出画面里所有属于「敌方单位」的文字标签，通常是单位名称。",
                "同时把画面上的统计数字（DPS、击杀数、总伤害、存活时间）一并读出。",
                "每个单位请给出：名称、稀有度（血条下方那行英文，如 Common/Unusual/Rare/Epic）、",
                "血量百分比、以及它在画面中的相对坐标 x,y（0~1）。",
                "只输出 JSON，格式为：",
                "{\"units\":[{\"name\":\"岩石\",\"rarity\":\"Unusual\",\"hp\":\"72%\",\"x\":0.55,\"y\":0.48}],",
                "\"stats\":{\"dps\":\"\",\"kills\":\"\",\"damage\":\"\",\"time\":\"\"}}",
                "x、y 取 0~1 的相对坐标，表示该单位在画面中的大致位置。",
                "看不清就留空数组，不要编造名称。");
        String user = "读出画面里的敌方单位名称。";
        chatWithImage(png, system, user, maxTokens, cb);
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
