package com.zorrinsight.net;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenAI 兼容接口（/chat/completions）。支持自建中转站：baseUrl 可带或不带 /v1，
 * 也接受完整的 .../chat/completions 地址。
 */
public final class AiClient {

    public interface Cb {
        void onOk(String text);
        void onErr(String message);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private final SharedPreferences prefs;

    public AiClient(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences("ai", Context.MODE_PRIVATE);
    }

    public String baseUrl() { return prefs.getString("baseUrl", "https://api.openai.com/v1").trim(); }
    public String apiKey()  { return prefs.getString("apiKey", "").trim(); }
    public String model()   { return prefs.getString("model", "gpt-4o-mini").trim(); }

    /**
     * 视觉模型名。留空表示与对话模型相同。
     * 看图读字通常需要更强的模型，单独配置可以只给识别用好的、日常用便宜的。
     */
    public String visionModel() {
        String v = prefs.getString("visionModel", "").trim();
        return v.isEmpty() ? model() : v;
    }

    public void saveVisionModel(String name) {
        prefs.edit().putString("visionModel", name == null ? "" : name.trim()).apply();
    }

    /** 视觉接口的 baseUrl；留空沿用对话接口的。 */
    public String visionBaseUrl() {
        String v = prefs.getString("visionBaseUrl", "").trim();
        return v.isEmpty() ? baseUrl() : v;
    }

    public void saveVisionBaseUrl(String url) {
        prefs.edit().putString("visionBaseUrl", url == null ? "" : url.trim()).apply();
    }

    /** 视觉接口的 key；留空沿用对话接口的。 */
    public String visionApiKey() {
        String v = prefs.getString("visionApiKey", "").trim();
        return v.isEmpty() ? apiKey() : v;
    }

    public void saveVisionApiKey(String key) {
        prefs.edit().putString("visionApiKey", key == null ? "" : key.trim()).apply();
    }

    public void save(String baseUrl, String apiKey, String model) {
        prefs.edit()
                .putString("baseUrl", baseUrl.trim())
                .putString("apiKey", apiKey.trim())
                .putString("model", model.trim())
                .apply();
    }

    public boolean configured() {
        return !apiKey().isEmpty() && !baseUrl().isEmpty() && !model().isEmpty();
    }

    private String endpoint() {
        String b = baseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        if (b.endsWith("/v1")) return b + "/chat/completions";
        return b + "/v1/chat/completions";
    }

    public void chat(String system, String user, int maxTokens, boolean jsonMode, Cb cb) {
        POOL.execute(() -> {
            HttpURLConnection c = null;
            try {
                JSONArray messages = new JSONArray();
                if (system != null && !system.isEmpty()) {
                    messages.put(new JSONObject().put("role", "system").put("content", system));
                }
                messages.put(new JSONObject().put("role", "user").put("content", user));

                JSONObject body = new JSONObject()
                        .put("model", model())
                        .put("messages", messages)
                        .put("temperature", 0.2)
                        .put("max_tokens", maxTokens);
                if (jsonMode) {
                    body.put("response_format", new JSONObject().put("type", "json_object"));
                }

                c = (HttpURLConnection) new URL(endpoint()).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(20000);
                c.setReadTimeout(90000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + apiKey());
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
                String content = choices.getJSONObject(0)
                        .optJSONObject("message").optString("content", "");
                if (content.trim().isEmpty()) {
                    cb.onErr("模型返回空内容");
                    return;
                }
                cb.onOk(content.trim());
            } catch (Exception e) {
                cb.onErr(e.getClass().getSimpleName() + "：" + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        });
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
        return t.length() > 220 ? t.substring(0, 220) + "…" : t;
    }
}
