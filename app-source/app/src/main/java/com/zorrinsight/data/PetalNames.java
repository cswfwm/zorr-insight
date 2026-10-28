package com.zorrinsight.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 花瓣编号 → 名称的持久映射。
 *
 * 页面上槽位图标是 canvas 画的，DOM 里没有名称文本；但把鼠标停在花瓣上时，
 * 游戏自己会弹出带名称的 tooltip。用户悬停一次后，这里记下「当时唯一的那个槽位
 * 编号」与该名称的对应，以后再看到同一编号就直接显示名字。
 */
public final class PetalNames {

    private final SharedPreferences sp;

    public PetalNames(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("petalNames", Context.MODE_PRIVATE);
    }

    public String get(int petalId) {
        if (petalId < 0) return null;
        String v = sp.getString("p" + petalId, null);
        return v == null || v.isEmpty() ? null : v;
    }

    public void put(int petalId, String name) {
        if (petalId < 0 || name == null || name.trim().isEmpty()) return;
        sp.edit().putString("p" + petalId, name.trim()).apply();
    }

    public int count() {
        return sp.getAll().size();
    }
}
