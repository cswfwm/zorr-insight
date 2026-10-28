package com.zorrinsight.data;

import android.content.Context;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 只读加载 wiki 掉落数据（assets/zorr-drops-slim.json 与 zorr-wiki-drops.json）。 */
public final class WikiData {

    private static WikiData instance;
    private final JSONObject full;
    private final JSONObject zhMobs;
    private final JSONObject zhPetals;
    private final JSONObject zhRegions;

    public static final String[] RARITIES = {
            "common", "unusual", "rare", "epic", "legendary",
            "mythic", "ultra", "super", "omega", "unique"
    };
    public static final String[] RARITY_CN = {
            "普通", "罕见", "稀有", "史诗", "传说", "神话", "至尊", "超级", "Ω", "独有"
    };

    private WikiData(JSONObject full, JSONObject zh) {
        this.full = full;
        this.zhMobs = zh.optJSONObject("mobs");
        this.zhPetals = zh.optJSONObject("petals");
        this.zhRegions = zh.optJSONObject("regions");
    }

    public static synchronized WikiData get(Context ctx) {
        if (instance == null) {
            JSONObject obj, zh;
            try {
                obj = new JSONObject(slurp(ctx, "zorr-wiki-drops.json"));
            } catch (Exception e) {
                obj = new JSONObject();
            }
            try {
                zh = new JSONObject(slurp(ctx, "zorr-names-zh.json"));
            } catch (Exception e) {
                zh = new JSONObject();
            }
            instance = new WikiData(obj, zh);
        }
        return instance;
    }

    private static String slurp(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1 << 16, in.available()));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}";
        }
    }

    public JSONObject raw() {
        return full;
    }

    public JSONObject mobs() {
        return full.optJSONObject("mobs");
    }

    public JSONObject mob(String key) {
        JSONObject m = mobs();
        return m == null ? null : m.optJSONObject(key);
    }

    /** 按名称或 id 模糊查找怪物键名。 */
    public String findMobKey(String query) {
        JSONObject m = mobs();
        if (m == null || query == null) return null;
        String q = query.trim().toLowerCase().replace(" ", "").replace("_", "");
        if (q.isEmpty()) return null;
        if (m.has(q)) return q;
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys);
        for (String k : keys) {
            if (k.replace("_", "").equals(q)) return k;
            String label = m.optJSONObject(k) == null ? "" : m.optJSONObject(k).optString("name", "");
            if (label.replace(" ", "").toLowerCase().equals(q)) return k;
        }
        for (String k : keys) {
            String label = m.optJSONObject(k) == null ? "" : m.optJSONObject(k).optString("name", "");
            if (k.contains(q) || label.toLowerCase().contains(q)) return k;
        }
        return null;
    }

    /** 怪物显示名：优先用游戏官方中文名。 */
    public String displayName(String key) {
        if (zhMobs != null) {
            String n = zhMobs.optString(key, "");
            if (n.isEmpty()) n = zhMobs.optString(normalizeKey(key), "");
            if (!n.isEmpty()) return n;
        }
        JSONObject o = mob(key);
        return o == null ? key : o.optString("name", key);
    }

    /** 花瓣显示名：优先用官方中文名。 */
    public String petalName(String key) {
        if (zhPetals != null && key != null) {
            String n = zhPetals.optString(normalizeKey(key), "");
            if (!n.isEmpty()) return n;
        }
        return key == null ? "" : key;
    }

    /** 语言表键为小写下划线：Baby Ant → baby_ant；(Petal) 这类后缀要剥掉。 */
    private static String normalizeKey(String name) {
        String s = name.toLowerCase().trim();
        s = s.replaceAll("\\s*\\((petal|mob|skin)\\)\\s*$", "");
        // 语言表里撇号被去掉了：Devil's Plague → devils_plague
        return s.replace(' ', '_').replace('-', '_').replace("'", "").replace("\u2019", "");
    }

    /** 区域/生物群系显示名。 */
    public String regionName(String key) {
        if (key == null) return "";
        // 画面上的区域名是 Garden/Desert/...，语言表的键是 plains/desert/...
        String mapped = key.toLowerCase();
        if (mapped.equals("garden")) mapped = "plains";
        if (zhRegions != null) {
            String n = zhRegions.optString(mapped, "");
            if (!n.isEmpty()) return n;
            n = zhRegions.optString(key.toLowerCase(), "");
            if (!n.isEmpty()) return n;
        }
        return key;
    }

    /** 指定稀有度的基础属性；不存在时回退到 common。 */
    public JSONObject baseStats(String key, String rarity) {
        JSONObject o = mob(key);
        if (o == null) return null;
        JSONObject stats = o.optJSONObject("base_stats");
        if (stats == null) return null;
        JSONObject s = stats.optJSONObject(rarity);
        return s != null ? s : stats.optJSONObject("common");
    }

    /** 掉落列表：每条含 petal、按怪物稀有度给出概率。 */
    public List<Drop> drops(String key, String mobRarity) {
        List<Drop> out = new ArrayList<>();
        JSONObject o = mob(key);
        if (o == null) return out;
        JSONObject ds = o.optJSONObject("drops");
        if (ds == null) return out;
        for (java.util.Iterator<String> it = ds.keys(); it.hasNext(); ) {
            String petalKey = it.next();
            JSONObject petal = ds.optJSONObject(petalKey);
            if (petal == null) continue;
            JSONObject byRar = petal.optJSONObject("by_mob_rarity");
            if (byRar == null) continue;
            JSONObject hit = byRar.optJSONObject(mobRarity);
            if (hit == null) continue;
            out.add(new Drop(
                    petal.optString("petal", petalKey),
                    hit.optString("petal_rarity", ""),
                    hit.optDouble("chance", 0),
                    hit.optInt("qty", 1)));
        }
        out.sort((a, b) -> Double.compare(b.chance, a.chance));
        return out;
    }

    public static final class Drop {
        public final String petal;
        public final String petalRarity;
        public final double chance;
        public final int qty;

        Drop(String petal, String petalRarity, double chance, int qty) {
            this.petal = petal;
            this.petalRarity = petalRarity;
            this.chance = chance;
            this.qty = qty;
        }
    }

    public static String cnRarity(String r) {
        for (int i = 0; i < RARITIES.length; i++) {
            if (RARITIES[i].equals(r)) return RARITY_CN[i];
        }
        return r;
    }
}
