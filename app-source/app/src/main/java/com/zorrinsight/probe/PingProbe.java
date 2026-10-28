package com.zorrinsight.probe;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 网络延迟探针。
 *
 * 游戏走 wss://s-... 的长连接，读不到 RTT，这里改为定期对同一台服务器
 * 建一条 TCP 连接并测握手耗时，作为网络延迟的近似值。
 * 只发起连接后立即关闭，不发送任何数据。
 */
public final class PingProbe {

    private final ScheduledExecutorService pool =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ping-probe");
                t.setDaemon(true);
                return t;
            });

    private volatile int lastMs = -1;
    private volatile long lastAt;

    public int lastMs() { return lastMs; }
    public long lastAt() { return lastAt; }

    public void start(String host, int port, long everyMs) {
        pool.scheduleWithFixedDelay(() -> {
            long t0 = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 3000);
                lastMs = (int) ((System.nanoTime() - t0) / 1_000_000L);
                lastAt = System.currentTimeMillis();
            } catch (Exception e) {
                lastMs = -1;
                lastAt = System.currentTimeMillis();
            }
        }, 0, everyMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        pool.shutdownNow();
    }
}
