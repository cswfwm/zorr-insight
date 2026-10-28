package com.zorrinsight;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.zorrinsight.data.AccountStore;
import com.zorrinsight.data.PetalNames;
import com.zorrinsight.data.WikiData;
import com.zorrinsight.neural.AutoTrainer;
import com.zorrinsight.neural.DataSeeder;
import com.zorrinsight.net.AiClient;
import com.zorrinsight.probe.DetectionSet;
import com.zorrinsight.probe.LoginScript;
import com.zorrinsight.probe.MobDetector;
import com.zorrinsight.probe.MotionProbe;
import com.zorrinsight.probe.PingProbe;
import com.zorrinsight.probe.Probe;
import com.zorrinsight.ui.NetView;
import com.zorrinsight.ui.OverlayView;
import com.zorrinsight.ui.SparkView;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String GAME_URL = "https://zorr.pro/";

    private WebView web;
    private TextView tvLoading, tvRegion, tvModel, tvDetect;
    private SparkView spark;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AutoTrainer trainer = new AutoTrainer();
    private AiClient ai;
    private AccountStore accounts;
    private PetalNames petalNames;
    private boolean autoFilled;
    private boolean railsOpen;

    private volatile Probe.Snapshot lastSnapshot = new Probe.Snapshot();
    private volatile String lastRawProbe = "";
    private volatile DetectionSet lastDetections = new DetectionSet();
    private volatile Bitmap lastFrame;
    /** 送去检测的那一帧（与模型输入完全一致），用于视觉预览。 */
    private volatile Bitmap lastDetectFrame;
    private Bitmap prevFrame;
    private long prevFrameAt;
    private final MotionProbe.Motion motion = new MotionProbe.Motion();
    private final PingProbe ping = new PingProbe();
    private OverlayView overlay;
    private NetView netView;
    private long lastFpsAt;
    private int frameCount;
    private float fps;
    private boolean running = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        tvLoading = findViewById(R.id.tvLoading);
        tvRegion = findViewById(R.id.tvRegion);
        tvModel = findViewById(R.id.tvModel);
        tvDetect = findViewById(R.id.tvDetect);
        spark = findViewById(R.id.spark);
        netView = findViewById(R.id.netView);
        netView.bind(trainer.regionNet(), com.zorrinsight.neural.AutoTrainer.REGION_LABELS);
        overlay = findViewById(R.id.overlay);
        ai = new AiClient(this);
        accounts = new AccountStore(this);
        petalNames = new PetalNames(this);

        findViewById(R.id.btnBag).setOnClickListener(v -> openBag());
        findViewById(R.id.btnRails).setOnClickListener(v -> toggleRails());
        findViewById(R.id.leftRail).setOnClickListener(v -> openBag());
        findViewById(R.id.rightRail).setOnClickListener(v -> openAnalyze());
        findViewById(R.id.btnAnalyze).setOnClickListener(v -> openAnalyze());
        findViewById(R.id.btnVision).setOnClickListener(v -> openVision());
        setupBottomSheet();
        findViewById(R.id.btnAccounts).setOnClickListener(v -> openAccounts());
        findViewById(R.id.btnAccounts).setOnLongClickListener(v -> {
            saveCurrentNickname();
            return true;
        });
        findViewById(R.id.btnAI).setOnClickListener(v -> seedFromAi());
        findViewById(R.id.btnTrain).setOnClickListener(v -> toggleAutoTrain());
        findViewById(R.id.tvTitle).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        setupWeb();
        trainer.startTraining(1200);
        ping.start("zorr.pro", 443, 4000);
        ui.postDelayed(this::tick, 1500);
        ui.postDelayed(this::motionTick, 800);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setVerticalScrollBarEnabled(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                tvLoading.setVisibility(View.GONE);
                tryAutoLogin();
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.loadUrl(GAME_URL);
    }

    /**
     * 异步抓一帧当前画面。
     *
     * WebView 是硬件合成的，View.draw() 往往只能拿到空白或陈旧内容，
     * 所以优先用 PixelCopy 直接从窗口 Surface 取像素（回调异步返回）；
     * 失败时退回 View.draw。结果缩放 1/3，够检测用也省内存。
     */
    private interface FrameCb {
        void onFrame(Bitmap bmp);
    }

    private void captureAsync(FrameCb cb) {
        if (web == null) {
            cb.onFrame(null);
            return;
        }
        int w = web.getWidth();
        int h = web.getHeight();
        if (w <= 0 || h <= 0) {
            cb.onFrame(null);
            return;
        }

        // web 在窗口中的实际位置：PixelCopy 截的是整个窗口，必须按这个偏移裁剪，
        // 否则会把状态栏/顶栏一起缩放进画面，检测全错
        int[] loc = new int[2];
        web.getLocationInWindow(loc);
        final int wx = loc[0], wy = loc[1];

        final int outW = Math.max(1, w / 3);
        final int outH = Math.max(1, h / 3);
        final Bitmap scaled = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                // Window 重载只接受整窗 Bitmap，没有 Rect 版本；
                // 先整窗截图，再按 web 的位置裁剪
                android.view.Window dw = getWindow();
                android.graphics.Rect frameRect = new android.graphics.Rect();
                dw.getDecorView().getWindowVisibleDisplayFrame(frameRect);
                int winW = dw.getDecorView().getWidth();
                int winH = dw.getDecorView().getHeight();
                if (winW <= 0 || winH <= 0) {
                    fallbackCapture(cb, scaled);
                    return;
                }
                final Bitmap full = Bitmap.createBitmap(winW, winH, Bitmap.Config.ARGB_8888);
                android.view.PixelCopy.request(dw, full, result -> {
                    try {
                        if (result != android.view.PixelCopy.SUCCESS) {
                            cb.onFrame(null);
                            return;
                        }
                        Canvas c = new Canvas(scaled);
                        android.graphics.Rect src = new android.graphics.Rect(
                                wx, wy, wx + w, wy + h);
                        android.graphics.Rect dst = new android.graphics.Rect(
                                0, 0, outW, outH);
                        c.drawBitmap(full, src, dst, null);
                        cb.onFrame(scaled);
                    } catch (Throwable t) {
                        cb.onFrame(null);
                    } finally {
                        if (!full.isRecycled()) full.recycle();
                    }
                }, ui);
                return;
            } catch (Throwable ignored) {
            }
        }
        fallbackCapture(cb, scaled);
    }

    private void fallbackCapture(FrameCb cb, Bitmap scaled) {
        try {
            Canvas c = new Canvas(scaled);
            c.scale(1f / 3f, 1f / 3f);
            web.draw(c);
            cb.onFrame(scaled);
        } catch (Throwable t) {
            cb.onFrame(null);
        }
    }

    /**
     * 高频运动采样：运动估计要求两帧间隔短（游戏内位移不能跨太多像素），
     * 2 秒一次的采样跨度太大，块匹配直接失配，所以单独用 200ms 跑一条通道。
     */
    private void motionTick() {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (prevFrameAt != 0 && now - prevFrameAt < 120) {
            ui.postDelayed(this::motionTick, 120);
            return;
        }
        captureAsync(frame -> {
            if (frame == null) {
                ui.postDelayed(this::motionTick, 200);
                return;
            }
            long dt = prevFrameAt == 0 ? 0 : now - prevFrameAt;
            if (prevFrame != null && !prevFrame.isRecycled() && dt >= 100) {
                MotionProbe.Motion m = MotionProbe.estimate(prevFrame, frame, dt);
                if (!Float.isNaN(m.degPerSec)) motion.degPerSec = m.degPerSec;
                if (!Float.isNaN(m.pxPerSec)) motion.pxPerSec = m.pxPerSec;
            }
            if (prevFrame != null && !prevFrame.isRecycled()) prevFrame.recycle();
            prevFrame = frame;
            prevFrameAt = now;

            frameCount++;
            if (lastFpsAt == 0) lastFpsAt = now;
            if (now - lastFpsAt >= 1000) {
                fps = frameCount * 1000f / (now - lastFpsAt);
                frameCount = 0;
                lastFpsAt = now;
            }
            if (overlay != null) {
                overlay.update(motion.degPerSec, motion.pxPerSec, fps, ping.lastMs());
            }
            ui.postDelayed(this::motionTick, 200);
        });
    }

    /** 周期性：读 DOM 文本 + 抓画面 → 检测单位 → 喂训练器。 */
    private void tick() {
        if (!running) return;

        web.evaluateJavascript(Probe.READ_SCRIPT, value -> {
            lastRawProbe = value == null ? "" : value;
            lastSnapshot = Probe.Snapshot.parse(value);
            rememberTooltip(lastSnapshot);
        });

        captureAsync(frame -> {
            if (frame != null) {
                Bitmap old = lastFrame;
                lastFrame = frame;
                if (old != null && old != frame && !old.isRecycled()) old.recycle();

                // 检测与训练都要遍历整幅画面，放主线程会明显卡顿，挪到后台。
                // 用副本：主线程下一轮会 recycle lastFrame，后台不能直接引用它。
                final Bitmap work = frame.copy(Bitmap.Config.ARGB_8888, false);
                new Thread(() -> {
                    try {
                        List<MobDetector.Blob> blobs = MobDetector.detect(work);
                        lastDetections = DetectionSet.of(blobs);
                        // 保留模型实际使用的那一帧，供「视觉」预览对照。
                        // 每 2 秒覆盖一次旧图，不累积，避免占内存。
                        Bitmap preview = work.copy(Bitmap.Config.ARGB_8888, false);
                        Bitmap oldPreview = lastDetectFrame;
                        lastDetectFrame = preview;
                        if (oldPreview != null && !oldPreview.isRecycled() && oldPreview != preview) {
                            oldPreview.recycle();
                        }
                        trainer.observe(work);
                        // 用当前帧特征驱动一次前向，让结构图显示真实激活
                        float[] feat = com.zorrinsight.probe.FrameFeatures.from(work);
                        boolean trained = trainer.regionPool().size() >= 12;
                        if (trained) trainer.regionNet().forward(feat);
                        ui.post(() -> {
                            if (netView != null) netView.setInput(feat, trained);
                        });
                    } catch (Throwable ignored) {
                    } finally {
                        if (work != null && !work.isRecycled()) work.recycle();
                    }
                }, "detect").start();
            }
            updateHud();
            ui.postDelayed(this::tick, 2000);
        });
    }

    private void updateHud() {
        Probe.Snapshot s = lastSnapshot;
        AutoTrainer t = trainer;

        String region = t.lastRegionName();
        String regionCn;
        if (region == null || region.equals("—") || region.equals("Unknown")) {
            regionCn = "待采样";
        } else {
            // 游戏里区域名是英文，这里给出中文并在括号里保留原文便于对照
            regionCn = WikiData.get(this).regionName(region.toLowerCase());
            if (regionCn.equalsIgnoreCase(region)) regionCn = region + "（未译）";
        }
        String conf = t.lastRegionConf() > 0 ? String.format(" · %.0f%%", t.lastRegionConf() * 100) : "";
        tvRegion.setText("区域：" + regionCn + conf);

        tvDetect.setText("画面单位 " + lastDetections.items.size() + " 个"
                + (lastDetections.items.isEmpty() ? "" : "（点解析查看）"));

        StringBuilder sb = new StringBuilder();
        sb.append(t.summary());
        sb.append("｜第 ").append(t.regionPool().epochs()).append(" 轮");
        sb.append(t.running() ? "（自动训练中）" : "（已暂停）");
        if (s != null && !s.xp.isEmpty()) sb.append("｜进度 ").append(s.xp);
        tvModel.setText(sb.toString());

        spark.setData(t.regionPool().lossSeries(), t.regionPool().accSeries());

        TextView left = findViewById(R.id.leftRail);
        if (left != null && left.getVisibility() == View.VISIBLE && s != null) {
            StringBuilder bag = new StringBuilder("背包\n");
            int shown = Math.min(10, s.petalRarities.length);
            if (shown == 0) {
                bag.append("—");
            } else {
                for (int i = 0; i < shown; i++) {
                    bag.append(i + 1).append(' ');
                    int r = s.petalRarities[i];
                    bag.append(r < 0 ? "?" : WikiData.RARITY_CN[
                            Math.min(r, WikiData.RARITY_CN.length - 1)]);
                    if (i < shown - 1) bag.append('\n');
                }
            }
            left.setText(bag.toString());
        }
    }

    /** 打开视觉预览：展示模型实际看到的那一帧与检测框。 */
    private void openVision() {
        VisionPreviewActivity.sharedFrame = lastDetectFrame;
        VisionPreviewActivity.sharedDetections = lastDetections;
        startActivity(new Intent(this, VisionPreviewActivity.class));
    }

    /** 底部抽屉：默认收起只占一行，上滑或点击把手展开训练面板。 */
    private void setupBottomSheet() {
        View handle = findViewById(R.id.sheetHandle);
        View body = findViewById(R.id.sheetBody);
        TextView arrow = findViewById(R.id.tvSheetArrow);

        // 子 View 会拦截触摸，全部设为不可点击，保证手势落在把手上
        handle.setClickable(true);
        View region = findViewById(R.id.tvRegion);
        View arrowView = findViewById(R.id.tvSheetArrow);
        if (region != null) region.setClickable(false);
        if (arrowView != null) arrowView.setClickable(false);

        handle.setOnClickListener(v -> setSheetOpen(body.getVisibility() != View.VISIBLE));

        // 上滑展开、下滑收起
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startY = e.getY();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        float dy = startY - e.getY();
                        if (dy > 18) setSheetOpen(true);
                        else if (dy < -18) setSheetOpen(false);
                        else v.performClick();
                        return true;
                }
                return false;
            }
        });
        if (arrow != null) arrow.setText("上滑 ▲");
    }

    private boolean sheetOpen;

    private void setSheetOpen(boolean open) {
        sheetOpen = open;
        View body = findViewById(R.id.sheetBody);
        TextView arrow = findViewById(R.id.tvSheetArrow);
        if (body != null) body.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.setText(open ? "下滑 ▼" : "上滑 ▲");
    }

    private void toggleAutoTrain() {
        if (trainer.running()) {
            trainer.stopTraining();
            ((TextView) findViewById(R.id.btnTrain)).setText("训练");
            Toast.makeText(this, "已暂停自动训练", Toast.LENGTH_SHORT).show();
        } else {
            trainer.startTraining(1200);
            ((TextView) findViewById(R.id.btnTrain)).setText("停训");
            Toast.makeText(this, "自动训练中：边玩边学", Toast.LENGTH_SHORT).show();
        }
    }

    /** 顶栏「背包」用于展开/收起两侧栏。 */
    private void toggleRails() {
        railsOpen = !railsOpen;
        int vis = railsOpen ? View.VISIBLE : View.GONE;
        findViewById(R.id.leftRail).setVisibility(vis);
        findViewById(R.id.rightRail).setVisibility(vis);
        ((TextView) findViewById(R.id.btnRails)).setText(railsOpen ? "收起" : "侧栏");

        View game = findViewById(R.id.gameArea);
        android.widget.LinearLayout.LayoutParams gp =
                (android.widget.LinearLayout.LayoutParams) game.getLayoutParams();
        gp.weight = railsOpen ? 3 : 1;
        game.setLayoutParams(gp);

        View left = findViewById(R.id.leftRail);
        android.widget.LinearLayout.LayoutParams lp =
                (android.widget.LinearLayout.LayoutParams) left.getLayoutParams();
        lp.weight = 1;
        left.setLayoutParams(lp);

        View right = findViewById(R.id.rightRail);
        right.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, railsOpen ? 1 : 0));
    }

    /** 把页面上当前填写的昵称存进账号列表。 */
    private void saveCurrentNickname() {
        web.evaluateJavascript(LoginScript.READ_NICKNAME, value -> {
            String name = value == null ? "" : value.replace("\"", "").trim();
            if (name.isEmpty() || "null".equals(name)) {
                Toast.makeText(this, "页面上还没有填写昵称", Toast.LENGTH_SHORT).show();
                return;
            }
            accounts.add(new AccountStore.Account(name, "us", ""));
            accounts.setActive(name);
            Toast.makeText(this, "已保存账号：" + name, Toast.LENGTH_SHORT).show();
        });
    }

    /** 打开页面后，把已保存的昵称填进登录框。只填不提交。 */
    private void tryAutoLogin() {
        if (!accounts.autoLogin()) return;
        AccountStore.Account a = accounts.active();
        if (a == null || a.nickname.isEmpty()) return;
        if (autoFilled) return;
        autoFilled = true;
        web.evaluateJavascript(LoginScript.fillNickname(a.nickname), value -> {
            if (value != null && value.contains("\"ok\":true")) {
                Toast.makeText(this, "已填入昵称：" + a.nickname, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openAccounts() {
        AccountsSheet.show(this, accounts, a -> {
            autoFilled = false;
            Toast.makeText(this, "已切换为 " + a.nickname, Toast.LENGTH_SHORT).show();
            tryAutoLogin();
        });
    }

    /**
     * 打开背包。先在后台采集一次最新数据，再跳转；采集失败或页面未就绪时
     * 直接用上一次的结果，避免点了没反应。
     */
    private void openBag() {
        final boolean[] launched = {false};
        Runnable launch = () -> {
            if (launched[0] || isFinishing() || isDestroyed()) return;
            launched[0] = true;
            try {
                Probe.Snapshot s = lastSnapshot != null ? lastSnapshot : new Probe.Snapshot();
                Intent i = new Intent(MainActivity.this, BagActivity.class);
                i.putExtra("petals", s.petalRarities);
                i.putExtra("ids", s.petalIds);
            int n = s.slots == null ? 0 : s.slots.length;
            int[] sx = new int[n], sy = new int[n], sw = new int[n], sh = new int[n];
            for (int k = 0; k < n; k++) {
                Probe.Slot sl = s.slots[k];
                sx[k] = sl.x; sy[k] = sl.y; sw[k] = sl.w; sh[k] = sl.h;
            }
            i.putExtra("sx", sx);
            i.putExtra("sy", sy);
            i.putExtra("sw", sw);
            i.putExtra("sh", sh);
            i.putExtra("vw", s.viewportWidth);
            i.putExtra("webW", web == null ? 0 : web.getWidth());
                i.putExtra("xp", s.xp);
                i.putExtra("name", s.name);
                i.putExtra("diag", diagText(s));
                i.putExtra("tooltip", s.tooltip);
                // 带上整帧画面，背包页按槽位坐标裁出花瓣图标
                Bitmap f = lastFrame;
                if (f != null && !f.isRecycled()) {
                    String path = cacheFrame(f);
                    if (path != null) i.putExtra("framePath", path);
                }
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "打开背包失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        };

        if (web == null) {
            launch.run();
            return;
        }
        try {
            web.evaluateJavascript(Probe.READ_SCRIPT, value -> {
                try {
                    lastRawProbe = value == null ? "" : value;
                    Probe.Snapshot parsed = Probe.Snapshot.parse(value);
                    if (parsed != null) lastSnapshot = parsed;
                } catch (Exception ignored) {
                }
                launch.run();
            });
            // 采集回调偶尔不返回，兜底 1.2 秒后仍然打开
            ui.postDelayed(launch, 1200);
        } catch (Exception e) {
            launch.run();
        }
    }

    /** 把画面存到缓存目录，供背包页裁剪花瓣图标。 */
    private String cacheFrame(Bitmap bmp) {
        try {
            java.io.File f = new java.io.File(getCacheDir(), "bag_frame.png");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 悬停时游戏会弹出带花瓣名的 tooltip。若此刻画面上只有一个带编号的槽位，
     * 就把该编号与这个名字关联起来，之后同一编号可直接显示名称。
     * 槽位不唯一时不记录，避免记错。
     */
    private void rememberTooltip(Probe.Snapshot s) {
        if (s == null || s.tooltip.isEmpty()) return;
        int onlyId = -1;
        int distinct = 0;
        if (s.slots != null) {
            for (Probe.Slot sl : s.slots) {
                if (sl.petalId < 0) continue;
                if (onlyId < 0) {
                    onlyId = sl.petalId;
                    distinct = 1;
                } else if (sl.petalId != onlyId) {
                    distinct++;
            }
            }
        }
        if (distinct == 1 && onlyId >= 0) {
            petalNames.put(onlyId, s.tooltip);
        }
    }

    /** 采集诊断：把原始长度和关键计数带进背包页，便于判断哪里没取到。 */
    private String diagText(Probe.Snapshot s) {
        StringBuilder d = new StringBuilder();
        d.append("原始返回 ").append(lastRawProbe.length()).append(" 字符");
        d.append("；槽位 ").append(s.slots.length);
        if (!s.scope.isEmpty()) d.append("（选择器 ").append(s.scope).append("）");
        if (!s.tooltip.isEmpty()) d.append("；悬停标题 ").append(s.tooltip);
        d.append("；稀有度 ").append(s.petalRarities.length);
        d.append("；聊天 ").append(s.chat.length);
        if (!s.xp.isEmpty()) d.append("；进度 ").append(s.xp);
        if (lastRawProbe.length() > 0 && lastRawProbe.length() < 200) {
            d.append("\n原始内容：").append(lastRawProbe);
        }
        return d.toString();
    }

    private void openAnalyze() {
        AnalyzeSheet.show(this, new com.zorrinsight.ui.InsightPanel.ContextProvider() {
            @Override
            public String[] get() {
                String region = trainer.lastRegionName();
                StringBuilder sb = new StringBuilder();
                sb.append("区域：").append(region);
                Probe.Snapshot s = lastSnapshot;
                if (s.petalRarities.length > 0) {
                    sb.append("；槽位：");
                    for (int i = 0; i < Math.min(10, s.petalRarities.length); i++) {
                        sb.append(s.petalRarities[i] < 0 ? "?" : String.valueOf(s.petalRarities[i]));
                        if (i < Math.min(10, s.petalRarities.length) - 1) sb.append(',');
                    }
                }
                return new String[]{region, sb.toString()};
            }

            @Override
            public DetectionSet detections() {
                return lastDetections;
            }

            @Override
            public Bitmap frame() {
                return lastFrame;
            }
        });
    }

    /** 用 AI 为当前画面生成标注样本。 */
    private void seedFromAi() {
        if (!ai.configured()) {
            Toast.makeText(this, "请先在设置里配置 AI 接口", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        Bitmap f = lastFrame;
        if (f == null) {
            Toast.makeText(this, "还没有采集到画面", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在请求模型标注…", Toast.LENGTH_SHORT).show();
        float[] feat = com.zorrinsight.probe.FrameFeatures.from(f);
        DataSeeder.seed(ai, trainer, feat, 6, new DataSeeder.Progress() {
            @Override
            public void onStatus(String text) {}

            @Override
            public void onDone(int added, String message) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    updateHud();
                });
            }
        });
    }

    public AutoTrainer trainerRef() { return trainer; }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        if (!trainer.running()) trainer.startTraining(1200);
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(this::tick, 300);
        ui.postDelayed(this::motionTick, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        running = false;
        trainer.stopTraining();
        ping.stop();
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            web.setWebViewClient(null);
            web.destroy();
        }
        if (lastFrame != null && !lastFrame.isRecycled()) lastFrame.recycle();
        if (prevFrame != null && !prevFrame.isRecycled()) prevFrame.recycle();
        if (lastDetectFrame != null && !lastDetectFrame.isRecycled()) lastDetectFrame.recycle();
        super.onDestroy();
    }
}
