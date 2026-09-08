package com.lanke.nastools;

import org.json.JSONObject;

/**
 * 请求结果载体 + 超时常量。
 * （v1.2.4 前 App 直连 NAS HTTP 服务，这里曾是完整 HTTP 客户端；
 *   HTTP 模式移除后只剩结果类型，通信统一走 Conn → Ssh。）
 */
public class Api {

    public static final int T_NORMAL = 20000;
    public static final int T_LONG = 120000;
    public static final int T_BUILD = 300000;

    public static class R {
        public boolean ok;
        public int code;
        public String error;
        public JSONObject json;

        public String msg() {
            return (error == null || error.isEmpty()) ? "未知错误" : error;
        }

        public String out() {
            if (json == null) return "";
            String s = json.optString("output", "");
            if (s.isEmpty()) s = json.optString("apply_output", "");
            if (s.isEmpty()) s = json.optString("build_output", "");
            return s;
        }
    }
}
