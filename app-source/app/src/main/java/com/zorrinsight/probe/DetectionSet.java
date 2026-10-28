package com.zorrinsight.probe;

import java.util.ArrayList;
import java.util.List;

/** 一组检测到的单位，附带用于列表展示的简短描述。 */
public final class DetectionSet {

    public static final class Item {
        public final MobDetector.Blob blob;
        public final String guess;
        public final int index;

        Item(MobDetector.Blob blob, String guess, int index) {
            this.blob = blob;
            this.guess = guess;
            this.index = index;
        }
    }

    public final List<Item> items = new ArrayList<>();

    public static DetectionSet of(List<MobDetector.Blob> blobs) {
        DetectionSet s = new DetectionSet();
        int i = 0;
        for (MobDetector.Blob b : blobs) {
            s.items.add(new Item(b, describe(b), i++));
        }
        return s;
    }

    private static String describe(MobDetector.Blob b) {
        String[] hue = {"红", "黄", "绿", "青", "蓝", "品红"};
        String color = b.hueBucket >= 0 && b.hueBucket < hue.length ? hue[b.hueBucket] : "灰";
        String tier = tierName(b.tierBlocks);
        return color + "系 · " + tier + " · 血条 " + b.fillRatio + "%";
    }

    /** 由血条下方文字的字符块数估计等级带。 */
    public static String tierName(int blocks) {
        if (blocks >= 8) return "Legendary+";
        if (blocks == 7) return "Unusual";
        if (blocks == 6) return "Common";
        if (blocks == 5) return "Mythic";
        if (blocks == 4) return "Rare/Epic";
        return "未知等级";
    }
}
