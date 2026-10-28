package com.zorrinsight.probe;

/**
 * 自动登录脚本。
 *
 * 只做一件事：把已保存的昵称写入页面的昵称输入框，并触发 input 事件让页面自己
 * 感知到变化。不点击 Play、不提交、不选择服务器——进游戏仍由用户自己操作。
 */
public final class LoginScript {

    public static String fillNickname(String nickname) {
        String safe = nickname == null ? "" : nickname
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "")
                .replace("\r", "");
        return String.join("\n",
                "(function(){",
                "  var el=document.querySelector('.nickname-input');",
                "  if(!el){ return JSON.stringify({ok:false,why:'no-input'}); }",
                "  if(el.value && el.value.trim().length>0 && el.value!=='" + safe + "'){",
                "    return JSON.stringify({ok:false,why:'already-has-name',value:el.value});",
                "  }",
                "  el.value='" + safe + "';",
                "  el.dispatchEvent(new Event('input',{bubbles:true}));",
                "  el.dispatchEvent(new Event('change',{bubbles:true}));",
                "  return JSON.stringify({ok:true});",
                "})();");
    }

    /** 读出页面上当前填写的昵称，用于「保存当前账号」。 */
    public static final String READ_NICKNAME = String.join("\n",
            "(function(){",
            "  var el=document.querySelector('.nickname-input');",
            "  return el && el.value ? el.value.trim() : '';",
            "})();");
}
