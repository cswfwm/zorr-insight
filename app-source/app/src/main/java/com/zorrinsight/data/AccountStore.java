package com.zorrinsight.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地多账号保存。
 *
 * zorr.pro 的身份就是玩家昵称（页面只有一个昵称输入框，没有密码），
 * 所以一条账号记录 = 昵称 + 服务器 + 备注，用于自动填充并进入游戏。
 */
public final class AccountStore {

    public static final class Account {
        public String nickname = "";
        public String server = "us";
        public String note = "";

        public Account() {}

        public Account(String nickname, String server, String note) {
            this.nickname = nickname;
            this.server = server;
            this.note = note;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("nickname", nickname);
                o.put("server", server);
                o.put("note", note);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Account from(JSONObject o) {
            Account a = new Account();
            a.nickname = o.optString("nickname", "");
            a.server = o.optString("server", "us");
            a.note = o.optString("note", "");
            return a;
        }
    }

    private static final String KEY_LIST = "accounts";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_AUTO = "autoLogin";

    private final SharedPreferences sp;

    public AccountStore(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("accounts", Context.MODE_PRIVATE);
    }

    public List<Account> all() {
        List<Account> out = new ArrayList<>();
        String raw = sp.getString(KEY_LIST, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Account.from(o));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void persist(List<Account> list) {
        JSONArray arr = new JSONArray();
        for (Account a : list) arr.put(a.toJson());
        sp.edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public void add(Account a) {
        if (a == null || a.nickname.trim().isEmpty()) return;
        List<Account> list = all();
        String name = a.nickname.trim();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).nickname.equalsIgnoreCase(name)) {
                list.set(i, a);
                persist(list);
                return;
            }
        }
        list.add(a);
        persist(list);
    }

    public void remove(String nickname) {
        List<Account> list = all();
        list.removeIf(a -> a.nickname.equals(nickname));
        persist(list);
        if (nickname.equals(activeNickname())) {
            sp.edit().remove(KEY_ACTIVE).apply();
        }
    }

    public void setActive(String nickname) {
        sp.edit().putString(KEY_ACTIVE, nickname).apply();
    }

    public String activeNickname() {
        return sp.getString(KEY_ACTIVE, "");
    }

    public Account active() {
        String n = activeNickname();
        if (n.isEmpty()) return null;
        for (Account a : all()) {
            if (a.nickname.equals(n)) return a;
        }
        return null;
    }

    public boolean autoLogin() {
        return sp.getBoolean(KEY_AUTO, true);
    }

    public void setAutoLogin(boolean on) {
        sp.edit().putBoolean(KEY_AUTO, on).apply();
    }
}
