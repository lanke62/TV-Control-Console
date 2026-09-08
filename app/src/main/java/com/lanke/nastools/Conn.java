package com.lanke.nastools;

import android.content.Context;

import org.json.JSONObject;

import java.util.Map;

/**
 * 统一入口：把业务请求翻译成 nas_api.py 的 CLI 命令并走 SSH 执行。
 * （旧版还有一条 HTTP 常驻服务的路，v1.2.4 起整体移除，App 只走 SSH 直连。）
 */
public class Conn {

    public static Api.R get(Context c, String path, Map<String, String> q, int timeoutMs) {
        if (c == null) return err("页面已销毁");
        try {
            JSONObject req = new JSONObject();
            switch (path) {
                case "/api/status":
                    req.put("cmd", "status");
                    break;
                case "/api/calendar":
                    req.put("cmd", "calendar");
                    req.put("ym", q == null ? "" : nz(q.get("ym")));
                    break;
                case "/api/overrides":
                    req.put("cmd", "overrides");
                    req.put("op", "list");
                    break;
                case "/api/logs":
                    req.put("cmd", "logs");
                    req.put("n", q == null ? 80 : Integer.parseInt(nz2(q.get("n"), "80")));
                    break;
                case "/api/health":
                    // 设置页「测试连接」走真正的提权测试
                    return Ssh.test(c);
                default:
                    return err("不支持的接口：" + path);
            }
            return Ssh.call(c, req, timeoutMs);
        } catch (Exception e) {
            return err(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static Api.R post(Context c, String path, JSONObject body, int timeoutMs) {
        if (c == null) return err("页面已销毁");
        try {
            JSONObject req = new JSONObject();
            switch (path) {
                case "/api/config":
                    req.put("cmd", "config");
                    req.put("scope", body == null ? "" : body.optString("scope", ""));
                    req.put("set", body == null ? new JSONObject() : body.optJSONObject("set"));
                    req.put("apply", body == null || body.optBoolean("apply", true));
                    break;
                case "/api/action":
                    req.put("cmd", "action");
                    req.put("action", body == null ? "" : body.optString("action", ""));
                    break;
                case "/api/overrides":
                    req.put("cmd", "overrides");
                    if (body != null) {
                        req.put("op", body.optString("op", "list"));
                        req.put("date", body.optString("date", ""));
                        req.put("flag", body.optString("flag", ""));
                        req.put("note", body.optString("note", ""));
                    } else {
                        req.put("op", "list");
                    }
                    break;
                default:
                    return err("不支持的接口：" + path);
            }
            return Ssh.call(c, req, timeoutMs);
        } catch (Exception e) {
            return err(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String nz2(String s, String d) {
        return (s == null || s.isEmpty()) ? d : s;
    }

    private static Api.R err(String m) {
        Api.R r = new Api.R();
        r.ok = false;
        r.error = m;
        return r;
    }
}
