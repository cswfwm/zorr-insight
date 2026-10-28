package com.zorrinsight.net;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 版本检查：查询 GitHub 最新 Release，与本机版本比较。
 *
 * 只读一个公开接口，不上传任何信息。
 */
public final class UpdateChecker {

    private static final String API =
            "https://api.github.com/repos/cswfwm/zorr-insight/releases/latest";

    public interface Cb {
        /** @param newer 是否有更新；@param latest 最新版本号；@param url 下载页 */
        void onResult(boolean newer, String latest, String url, String note);
    }

    private UpdateChecker() {}

    public static void check(Context ctx, Cb cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) {
                    cb.onResult(false, "", "", "查询失败");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject o = new JSONObject(sb.toString());
                String tag = o.optString("tag_name", "").trim();      // 形如 v12.1
                String url = o.optString("html_url", "");
                String latest = tag.replaceFirst("^[vV]", "");

                String current = currentVersion(ctx);
                boolean newer = compare(latest, current) > 0;
                cb.onResult(newer, latest, url, "");
            } catch (Exception e) {
                cb.onResult(false, "", "", e.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    public static String currentVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 逐段比较版本号，返回 >0 表示 a 更新。 */
    public static int compare(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? num(pa[i]) : 0;
            int y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
