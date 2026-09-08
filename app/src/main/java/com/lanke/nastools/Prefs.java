package com.lanke.nastools;

import android.content.Context;
import android.content.SharedPreferences;

/** 连接设置的读写（SSH 直连，HTTP 模式已在 v1.2.4 移除） */
public class Prefs {
    private static final String NAME = "nastools";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** NAS 地址：即设置页的 SSH 主机（同一台 NAS，只填一次） */
    public static String host(Context c) {
        String h = sp(c).getString("ssh_host", "");
        return h.isEmpty() ? "192.168.1.11" : h;
    }

    // ---------------- SSH 直连 ----------------

    public static String sshHost(Context c) {
        return sp(c).getString("ssh_host", "");
    }

    public static int sshPort(Context c) {
        int p = sp(c).getInt("ssh_port", 0);
        return p > 0 ? p : 22;
    }

    public static String sshUser(Context c) {
        return sp(c).getString("ssh_user", "");
    }

    public static void saveSsh(Context c, String host, int port, String user) {
        sp(c).edit()
                .putString("ssh_host", host.trim())
                .putInt("ssh_port", port)
                .putString("ssh_user", user.trim())
                .apply();
    }

    /** 远端 python3 的绝对路径，首次探测后记住，省得每次 command -v */
    public static String python(Context c) {
        String p = sp(c).getString("python", "");
        return p.isEmpty() ? "python3" : p;
    }

    public static void setPython(Context c, String p) {
        sp(c).edit().putString("python", p).apply();
    }

    /** 两个项目的目录（自动探测结果），空串表示每次重新探测 */
    public static String calHome(Context c) {
        return sp(c).getString("cal_home", "");
    }

    public static String projHome(Context c) {
        return sp(c).getString("proj_home", "");
    }

    public static void setHomes(Context c, String cal, String proj) {
        sp(c).edit().putString("cal_home", cal).putString("proj_home", proj).apply();
    }

    /** 已探测到的候选目录，供设置页手动挑选 */
    public static String calCandidates(Context c) {
        return sp(c).getString("cal_cands", "");
    }

    public static String projCandidates(Context c) {
        return sp(c).getString("proj_cands", "");
    }

    public static void setCandidates(Context c, String cal, String proj) {
        sp(c).edit().putString("cal_cands", cal).putString("proj_cands", proj).apply();
    }
}
