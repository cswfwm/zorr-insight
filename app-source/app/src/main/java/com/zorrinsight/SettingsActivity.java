package com.zorrinsight;

import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.zorrinsight.net.AiClient;

/** OpenAI 格式接口配置：baseUrl / apiKey / model，带连通性测试。 */
public class SettingsActivity extends AppCompatActivity {

    private AiClient ai;
    private EditText etBase, etKey, etModel;
    private EditText etVisionBase, etVisionKey, etVisionModel;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ai = new AiClient(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AI 接口");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#1B1B1B"));
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("兼容 OpenAI 格式的接口，可直接填中转站地址。用于生成区域标注样本，加速前期训练。");
        note.setTextSize(12);
        note.setTextColor(Color.parseColor("#7A7F85"));
        note.setPadding(0, dp(6), 0, dp(16));
        note.setLineSpacing(dp(3), 1f);
        root.addView(note);

        etBase = field(root, "baseUrl", ai.baseUrl(), InputType.TYPE_TEXT_VARIATION_URI);
        etKey = field(root, "apiKey", ai.apiKey(), InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etModel = field(root, "model", ai.model(), InputType.TYPE_CLASS_TEXT);

        Button save = new Button(this);
        save.setText("保存");
        save.setAllCaps(false);
        save.setOnClickListener(v -> {
            ai.save(etBase.getText().toString(), etKey.getText().toString(), etModel.getText().toString());
            ai.saveVisionModel(etVisionModel.getText().toString());
            ai.saveVisionBaseUrl(etVisionBase.getText().toString());
            ai.saveVisionApiKey(etVisionKey.getText().toString());
            tvStatus.setText("已保存");
            tvStatus.setTextColor(Color.parseColor("#1EA761"));
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        sp.setMargins(0, dp(10), 0, dp(8));
        root.addView(save, sp);

        Button test = new Button(this);
        test.setText("测试连通");
        test.setAllCaps(false);
        test.setOnClickListener(v -> {
            ai.save(etBase.getText().toString(), etKey.getText().toString(), etModel.getText().toString());
            tvStatus.setText("正在请求…");
            tvStatus.setTextColor(Color.parseColor("#7A7F85"));
            ai.chat("你是一个连通性测试助手。", "只回复两个字：正常。", 16, false,
                    new AiClient.Cb() {
                        @Override
                        public void onOk(String text) {
                            runOnUiThread(() -> {
                                tvStatus.setText("连通正常：" + text.trim());
                                tvStatus.setTextColor(Color.parseColor("#1EA761"));
                            });
                        }

                        @Override
                        public void onErr(String message) {
                            runOnUiThread(() -> {
                                tvStatus.setText("失败：" + message);
                                tvStatus.setTextColor(Color.parseColor("#D93025"));
                            });
                        }
                    });
        });
        root.addView(test, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        // ── 视觉模型（识别画面文字用） ──
        TextView visionTitle = new TextView(this);
        visionTitle.setText("视觉模型");
        visionTitle.setTextSize(16);
        visionTitle.setTextColor(Color.parseColor("#1B1B1B"));
        visionTitle.setPadding(0, dp(22), 0, 0);
        root.addView(visionTitle);

        TextView visionNote = new TextView(this);
        visionNote.setText("识别画面上单位名称、稀有度用。留空则沿用上面的接口与模型。"
                + "读小字建议用带强 OCR 能力的模型。");
        visionNote.setTextSize(11);
        visionNote.setTextColor(Color.parseColor("#7A7F85"));
        visionNote.setPadding(0, dp(6), 0, dp(8));
        visionNote.setLineSpacing(dp(3), 1f);
        root.addView(visionNote);

        // 快捷填充：点一下带出地址与模型名，key 仍需自己填
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, 0, 0, dp(6));
        addPreset(presets, "智谱 GLM-4.6V-Flash（免费）",
                "https://open.bigmodel.cn/api/paas/v4", "glm-4.6v-flash");
        addPreset(presets, "通义 Qwen3-VL",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-vl-plus");
        root.addView(presets);

        etVisionBase = field(root, "视觉 baseUrl（可留空）", ai.visionBaseUrl(),
                InputType.TYPE_TEXT_VARIATION_URI);
        etVisionKey = field(root, "视觉 apiKey（可留空）", ai.visionApiKey(),
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etVisionModel = field(root, "视觉模型名（可留空）", ai.visionModel(),
                InputType.TYPE_CLASS_TEXT);

        Button testVision = new Button(this);
        testVision.setText("测试视觉");
        testVision.setAllCaps(false);
        testVision.setOnClickListener(v -> {
            ai.save(etBase.getText().toString(), etKey.getText().toString(), etModel.getText().toString());
            ai.saveVisionModel(etVisionModel.getText().toString());
            ai.saveVisionBaseUrl(etVisionBase.getText().toString());
            ai.saveVisionApiKey(etVisionKey.getText().toString());
            tvStatus.setText("正在测试视觉…");
            tvStatus.setTextColor(Color.parseColor("#7A7F85"));

            // 造一张带文字的图，让模型读，验证它真的能看图
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                    320, 90, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            c.drawColor(Color.WHITE);
            android.graphics.Paint pt = new android.graphics.Paint();
            pt.setColor(Color.BLACK);
            pt.setTextSize(34);
            c.drawText("Unusual", 20, 56, pt);
            byte[] png = com.zorrinsight.net.VisionClient.toPng(b);
            b.recycle();

            new com.zorrinsight.net.VisionClient(this).readText(png, 120,
                    new com.zorrinsight.net.VisionClient.Cb() {
                        @Override
                        public void onOk(String json) {
                            runOnUiThread(() -> {
                                boolean hit = json.toLowerCase().contains("unusual");
                                tvStatus.setText(hit ? "视觉正常（读到了 Unusual）"
                                        : "视觉可用，但没读准：\n" + json.trim());
                                tvStatus.setTextColor(Color.parseColor(hit ? "#1EA761" : "#B26A00"));
                            });
                        }

                        @Override
                        public void onErr(String message) {
                            runOnUiThread(() -> {
                                tvStatus.setText("视觉失败：" + message);
                                tvStatus.setTextColor(Color.parseColor("#D93025"));
                            });
                        }
                    });
        });
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        vp.setMargins(0, dp(10), 0, 0);
        root.addView(testVision, vp);

        tvStatus = new TextView(this);
        tvStatus.setText(ai.configured() ? "已配置" : "未配置");
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(Color.parseColor("#7A7F85"));
        tvStatus.setPadding(0, dp(12), 0, 0);
        root.addView(tvStatus);

        setContentView(scroll);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle("设置");
    }

    /** 快捷填充按钮：带出地址与模型名。 */
    private void addPreset(LinearLayout parent, String label, String baseUrl, String model) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(10);
        chip.setPadding(dp(8), dp(5), dp(8), dp(5));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(Color.parseColor("#F2F3F5"));
        g.setCornerRadius(dp(12));
        chip.setBackground(g);
        chip.setTextColor(Color.parseColor("#2A2D31"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            if (etVisionBase != null) etVisionBase.setText(baseUrl);
            if (etVisionModel != null) etVisionModel.setText(model);
            tvStatus.setText("已填入 " + model + "，请补上对应的 apiKey");
            tvStatus.setTextColor(Color.parseColor("#1EA761"));
        });
        parent.addView(chip);
    }

    private EditText field(LinearLayout root, String label, String value, int inputType) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(Color.parseColor("#7A7F85"));
        l.setPadding(0, dp(10), 0, dp(4));
        root.addView(l);

        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackground(getDrawable(R.drawable.bg_card));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(e);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
