package com.zorrinsight.probe;

import org.json.JSONObject;

/**
 * WebView 中只读采集：只查询页面已经渲染出来的元素，不修改任何游戏状态，
 * 也不发送输入事件。
 */
public final class Probe {

    /**
     * 返回 JSON：
     * {slots:[{rar,id,count}], petals:[...], chat:[...], xp:"", name:"", hud:"", dead:bool}
     */
    public static final String READ_SCRIPT = String.join("\n",
            "(function(){",
            "  function txt(el){ return el ? (el.innerText||el.textContent||'').trim() : ''; }",
            "  function all(sel){ var out=[]; try{ document.querySelectorAll(sel).forEach(function(e){",
            "      var t=txt(e); if(t) out.push(t); }); }catch(e){} return out; }",
            "  var res={};",
            // 槽位：.petal 且带 rarity-bg-N；类名里还有 petal-{id}-{rarity}
            "  var slots=[];",
            // 背包打开时（X 键）优先读背包：那里是完整的花瓣清单，带数量
            "  var inv = document.querySelector('.inventory-petals');",
            "  var scope;",
            "  var els;",
            "  if (inv && inv.offsetParent !== null) {",
            "    scope = '.inventory-petals .petal';",
            "    els = inv.querySelectorAll(':scope > .petal');",
            "  }",
            "  if (!els || els.length === 0) {",
            "    scope = '.hud .petal-rows .petal, .hud .petal';",
            "    els = document.querySelectorAll(scope);",
            "  }",
            "  if (!els || els.length === 0) {",
            "    scope = '.petal';",
            "    els = document.querySelectorAll(scope);",
            "  }",
            "  try{",
            "    els.forEach(function(p){",
            "      var cls = p.className || '';",
            "      // 背包里的元素不一定带 rarity-bg-N，但 class 里都会有 petal-{id}-{rarity}",
            "      if (cls.indexOf('rarity-bg-') < 0 && cls.indexOf('petal-') < 0) return;",
            // 类名形如：petal icon petal-{索引}-{稀有度} rarity-bg-{稀有度}
            // 第二个数字才是稀有度，第一个是花瓣索引
            "      var toks = cls.split(' ');",
            "      var pid = -1, rar = -1;",
            "      for (var ti = 0; ti < toks.length; ti++) {",
            "        var tk = toks[ti];",
            "        if (tk.indexOf('petal-') === 0 && tk !== 'petal-rows') {",
            "          var parts = tk.split('-');",
            "          if (parts.length >= 3) {",
            "            pid = parseInt(parts[1], 10); rar = parseInt(parts[2], 10);",
            "          }",
            "        }",
            "        if (rar < 0 && tk.indexOf('rarity-bg-') === 0) {",
            "          rar = parseInt(tk.slice(10), 10);",
            "        }",
            "      }",
            "      var cnt = p.querySelector('.petal-count');",
            "      var c = cnt ? txt(cnt).replace(/[^0-9]/g,'') : '';",
            "      var b = p.getBoundingClientRect();",
            "      if (b.width < 8 || b.height < 8) return;",
            "      slots.push({ rar: rar, id: pid, count: c ? parseInt(c,10) : 0,",
            "                   x: Math.round(b.left), y: Math.round(b.top),",
            "                   w: Math.round(b.width), h: Math.round(b.height) });",
            "    });",
            "  }catch(e){}",
            "  res.slots = slots;",
            "  res.scope = scope;",
            // 主界面下方的槽位行（petal-rows / petals），也单独取一份
            "  var rows=[];",
            "  try{ document.querySelectorAll('.hud .petal-rows .petal').forEach(function(p){",
            "      var cls=p.className||''; var r=-1, pid=-1;",
            "      var tk=cls.split(' ');",
            "      for(var j=0;j<tk.length;j++){",
            "        var t=tk[j];",
            "        if(t.indexOf('petal-')===0 && t!=='petal-rows'){",
            "          var ps=t.split('-');",
            "          if(ps.length>=3){ pid=parseInt(ps[1],10); r=parseInt(ps[2],10); }",
            "        }",
            "        if(r<0 && t.indexOf('rarity-bg-')===0){ r=parseInt(t.slice(10),10); }",
            "      }",
            "      rows.push({ rar: r, id: pid, count: 0 }); }); }catch(e){}",
            "  res.petals = rows;",
            "  res.chat = all('.chat-content .chat-message, .chat-content > div, .chats > div').slice(-40);",
            "  var xp=document.querySelector('.xp-progress .bar-text')||document.querySelector('.xp-progress');",
            "  res.xp = txt(xp);",
            "  var nm=document.querySelector('.nickname-input');",
            "  res.name = nm && nm.value ? nm.value : '';",
            "  res.hud = txt(document.querySelector('.hud'));",
            "  var d=document.querySelector('.death');",
            "  res.dead = !!(d && d.offsetParent !== null);",
            "  res.vw = window.innerWidth || 0;",
            // 已存在的 tooltip 标题（把鼠标悬停在花瓣上时游戏自己弹出来的）
            "  var tt = document.querySelector('.tooltip .tooltip-title');",
            "  res.tooltip = tt ? txt(tt) : '';",
            "  return JSON.stringify(res);",
            "})();");

    public static final class Slot {
        public int rarity = -1;
        public int petalId = -1;
        public int count;
        /** 该槽位在页面中的位置与大小（CSS 像素），用于从画面上裁下花瓣图。 */
        public int x, y, w, h;
    }

    public static final class Snapshot {
        public String[] chat = new String[0];
        public Slot[] slots = new Slot[0];
        public int[] petalRarities = new int[0];
        public int[] petalIds = new int[0];
        public String xp = "";
        public String name = "";
        public String hud = "";
        public String scope = "";
        /** 页面 CSS 视口宽度，用于把 DOM 坐标换算成画面像素。 */
        public int viewportWidth;
        public String tooltip = "";
        public boolean dead;

        public static Snapshot parse(String json) {
            Snapshot s = new Snapshot();
            if (json == null) return s;
            String raw = json;
            try {
                if (raw.startsWith("\"")) raw = new org.json.JSONTokener(raw).nextValue().toString();
            } catch (Exception ignored) {
            }
            try {
                JSONObject o = new JSONObject(raw);

                org.json.JSONArray slots = o.optJSONArray("slots");
                if (slots != null && slots.length() > 0) {
                    s.slots = new Slot[slots.length()];
                    for (int i = 0; i < slots.length(); i++) {
                        JSONObject e = slots.optJSONObject(i);
                        Slot sl = new Slot();
                        if (e != null) {
                            sl.rarity = e.optInt("rar", -1);
                            sl.petalId = e.optInt("id", -1);
                            sl.count = e.optInt("count", 0);
                            sl.x = e.optInt("x", -1);
                            sl.y = e.optInt("y", -1);
                            sl.w = e.optInt("w", 0);
                            sl.h = e.optInt("h", 0);
                        }
                        s.slots[i] = sl;
                    }
                }

                // 稀有度、花瓣索引、坐标必须来自同一来源，否则三者索引会错位
                // （曾出现稀有度取自 petals、坐标取自 slots，导致背包图文全对不上）
                java.util.List<Integer> rar = new java.util.ArrayList<>();
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                if (s.slots.length > 0) {
                    for (Slot sl : s.slots) {
                        rar.add(sl.rarity);
                        ids.add(sl.petalId);
                    }
                } else {
                    org.json.JSONArray petals = o.optJSONArray("petals");
                    if (petals != null) {
                        for (int i = 0; i < petals.length(); i++) {
                            JSONObject p = petals.optJSONObject(i);
                            if (p != null) {
                                rar.add(p.optInt("rar", -1));
                                ids.add(p.optInt("id", -1));
                            }
                        }
                    }
                }
                s.petalRarities = new int[rar.size()];
                for (int i = 0; i < rar.size(); i++) s.petalRarities[i] = rar.get(i);
                s.petalIds = new int[ids.size()];
                for (int i = 0; i < ids.size(); i++) s.petalIds[i] = ids.get(i);

                org.json.JSONArray chat = o.optJSONArray("chat");
                if (chat != null) {
                    s.chat = new String[chat.length()];
                    for (int i = 0; i < chat.length(); i++) s.chat[i] = chat.optString(i, "");
                }
                s.xp = o.optString("xp", "");
                s.name = o.optString("name", "");
                s.hud = o.optString("hud", "");
                s.scope = o.optString("scope", "");
                s.viewportWidth = o.optInt("vw", 0);
                s.tooltip = o.optString("tooltip", "");
                s.dead = o.optBoolean("dead", false);
            } catch (Exception ignored) {
            }
            return s;
        }
    }
}
