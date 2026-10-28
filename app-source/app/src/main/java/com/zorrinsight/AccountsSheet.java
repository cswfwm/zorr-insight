package com.zorrinsight;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.zorrinsight.data.AccountStore;

import java.util.List;

/** 多账号管理：新增、切换、删除、自动登录开关。 */
public final class AccountsSheet {

    public interface OnPick {
        void onPick(AccountStore.Account account);
    }

    public static void show(Activity act, AccountStore store, OnPick onPick) {
        Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.25f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(act, "#FFFFFF", 18));
        int pad = dp(act, 18);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act);
        title.setText("账号");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#1B1B1B"));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(act);
        close.setText("关闭");
        close.setTextSize(14);
        close.setTextColor(Color.parseColor("#1EA761"));
        close.setOnClickListener(v -> d.dismiss());
        head.addView(close);
        root.addView(head);

        TextView note = new TextView(act);
        note.setText("zorr.pro 用昵称识别身份，没有密码。保存后会记住昵称，进入页面时自动填入。");
        note.setTextSize(11);
        note.setTextColor(Color.parseColor("#7A7F85"));
        note.setPadding(0, dp(act, 6), 0, dp(act, 12));
        note.setLineSpacing(dp(act, 3), 1f);
        root.addView(note);

        ScrollView scroll = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        lp.weight = 1;
        root.addView(scroll, lp);

        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            List<AccountStore.Account> all = store.all();
            String active = store.activeNickname();
            if (all.isEmpty()) {
                TextView empty = new TextView(act);
                empty.setText("还没有保存的账号。");
                empty.setTextSize(13);
                empty.setTextColor(Color.parseColor("#9AA0A6"));
                empty.setPadding(0, dp(act, 10), 0, dp(act, 10));
                list.addView(empty);
            }
            for (AccountStore.Account a : all) {
                list.addView(row(act, store, a, a.nickname.equals(active), d, refresh, onPick));
            }
        };
        refresh[0].run();

        // 新增账号
        EditText input = new EditText(act);
        input.setHint("输入玩家昵称");
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(act.getDrawable(R.drawable.bg_card));
        input.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.setMargins(0, dp(act, 10), 0, dp(act, 8));
        root.addView(input, ip);

        TextView add = new TextView(act);
        add.setText("保存这个昵称");
        add.setTextSize(14);
        add.setGravity(Gravity.CENTER);
        add.setTextColor(Color.WHITE);
        add.setBackground(round(act, "#1EA761", 12));
        add.setPadding(0, dp(act, 11), 0, dp(act, 11));
        add.setOnClickListener(v -> {
            String n = input.getText().toString().trim();
            if (n.isEmpty()) return;
            store.add(new AccountStore.Account(n, "us", ""));
            input.setText("");
            refresh[0].run();
        });
        root.addView(add);

        // 自动登录开关
        LinearLayout autoRow = new LinearLayout(act);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(0, dp(act, 12), 0, 0);
        TextView autoLabel = new TextView(act);
        autoLabel.setText("打开时自动填入昵称");
        autoLabel.setTextSize(13);
        autoLabel.setTextColor(Color.parseColor("#2A2D31"));
        autoRow.addView(autoLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(act);
        sw.setChecked(store.autoLogin());
        sw.setOnCheckedChangeListener((b, on) -> store.setAutoLogin(on));
        autoRow.addView(sw);
        root.addView(autoRow);

        d.setContentView(root);
        d.show();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.78f));
            w.setGravity(Gravity.BOTTOM);
        }
    }

    private static LinearLayout row(Activity act, AccountStore store, AccountStore.Account a,
                                    boolean active, Dialog d, Runnable[] refresh, OnPick onPick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 12), dp(act, 10), dp(act, 10), dp(act, 10));
        row.setBackground(round(act, active ? "#EAF7F0" : "#F7F8F9", 12));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, 0, 0, dp(act, 8));
        row.setLayoutParams(rp);

        TextView name = new TextView(act);
        name.setText(a.nickname + (active ? "  · 当前" : ""));
        name.setTextSize(14);
        name.setTextColor(Color.parseColor(active ? "#1EA761" : "#2A2D31"));
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView use = new TextView(act);
        use.setText("使用");
        use.setTextSize(13);
        use.setTextColor(Color.parseColor("#1EA761"));
        use.setPadding(dp(act, 10), 0, dp(act, 10), 0);
        use.setOnClickListener(v -> {
            store.setActive(a.nickname);
            if (onPick != null) onPick.onPick(a);
            d.dismiss();
        });
        row.addView(use);

        TextView del = new TextView(act);
        del.setText("删除");
        del.setTextSize(13);
        del.setTextColor(Color.parseColor("#D93025"));
        del.setOnClickListener(v -> {
            store.remove(a.nickname);
            refresh[0].run();
        });
        row.addView(del);
        return row;
    }

    private static GradientDrawable round(Activity act, String fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(fill));
        g.setCornerRadius(dp(act, radiusDp));
        return g;
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
