package com.lanke.nastools;

import android.content.Context;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;

import android.util.Base64;

/**
 * SSH 直连群晖的传输层。
 *
 * 工作方式：
 *   1. 首次使用时把 assets 里的 nas_api.py 传到 NAS 的 /tmp/nas_tools/
 *      （优先走 SFTP；群晖默认没开 sftp 子系统时会退回 exec 管道：base64 后经 python3 写盘）
 *      走 /tmp 是为了不在 NAS 上留常驻文件，重启自动消失，下次用再传。
 *   2. 之后每次操作就是一条命令：
 *        sudo -S -p '' python3 /tmp/nas_tools/nas_api.py --json '{...}'
 *      密码从 stdin 喂给 sudo，脚本结果以单行 JSON 返回。
 *
 * 会话在本进程内复用，切到后台不会断；设置改动或连接报错后会自动重建。
 */
public class Ssh {

    /** 远端脚本暂存目录。用 /tmp：不留常驻文件，且所有用户可写，无需 sudo 建目录 */
    private static final String REMOTE_DIR = "/tmp/nas_tools";
    private static final String REMOTE_PY = REMOTE_DIR + "/nas_api.py";
    /** 脚本输出里只有这一行是结果，sudo 的提示语等噪声靠它过滤掉 */
    private static final String MARK = "__NAS_JSON__ ";

    private static final Object LOCK = new Object();

    private static Session session;
    private static String sessionKey;
    private static String uploadedMd5;
    private static byte[] assetBytes;

    // ---------------------------------------------------------------- 对外

    /** 执行一次 CLI 调用。req 形如 {"cmd":"status"} */
    public static Api.R call(Context c, JSONObject req, int timeoutMs) {
        synchronized (LOCK) {
            Api.R r = new Api.R();
            try {
                Session s = connect(c);
                ensureScript(c, s);
                String py = Prefs.python(c);
                String cal = Prefs.calHome(c);
                String proj = Prefs.projHome(c);

                StringBuilder cmd = new StringBuilder();
                cmd.append("sudo -S -p '' ").append(py).append(" ")
                        .append(REMOTE_PY).append(" --json ").append(q(req.toString()));
                if (!cal.isEmpty()) cmd.append(" --cal-home ").append(q(cal));
                if (!proj.isEmpty()) cmd.append(" --proj-home ").append(q(proj));

                String pwd = SecurePrefs.getPassword(c);
                Res res = exec(s, cmd.toString(), pwd, timeoutMs);
                return parse(res, r);
            } catch (Exception e) {
                r.ok = false;
                r.error = nice(e);
                return r;
            }
        }
    }

    /** 环境探测：找目录 / 看权限 / 看 python 版本。成功时把结果写进 Prefs */
    public static Api.R probe(Context c, boolean redetect) {
        synchronized (LOCK) {
            Api.R r = new Api.R();
            try {
                Session s = connect(c);
                ensureScript(c, s);

                String py = Prefs.python(c);
                String cal = redetect ? "" : Prefs.calHome(c);
                String proj = redetect ? "" : Prefs.projHome(c);

                StringBuilder cmd = new StringBuilder();
                cmd.append("sudo -S -p '' ").append(py).append(" ")
                        .append(REMOTE_PY)
                        .append(" --json ").append(q("{\"cmd\":\"probe\"}"));
                if (!cal.isEmpty()) cmd.append(" --cal-home ").append(q(cal));
                if (!proj.isEmpty()) cmd.append(" --proj-home ").append(q(proj));

                String pwd = SecurePrefs.getPassword(c);
                Res res = exec(s, cmd.toString(), pwd, 60000);
                Api.R out = parse(res, r);
                if (out.ok && out.json != null) {
                    Prefs.setHomes(c, out.json.optString("cal_home", ""),
                            out.json.optString("proj_home", ""));
                    Prefs.setCandidates(c, out.json.optString("candidates_cal", ""),
                            out.json.optString("candidates_proj", ""));
                }
                return out;
            } catch (Exception e) {
                r.ok = false;
                r.error = nice(e);
                return r;
            }
        }
    }

    /** 只连一次 SSH 跑条 whoami，用于设置页「测试连接」 */
    public static Api.R test(Context c) {
        synchronized (LOCK) {
            Api.R r = new Api.R();
            try {
                Session s = connect(c);
                String pwd = SecurePrefs.getPassword(c);
                Res res = exec(s, "sudo -S -p '' id -u", pwd, 25000);
                String u = res.out.trim().split("\\s+")[0];
                r.ok = "0".equals(u);
                r.error = r.ok ? "" : "sudo 提权失败（输出：" + res.out.trim()
                        + (res.err.trim().isEmpty() ? "" : " / " + res.err.trim()) + "）";
                return r;
            } catch (Exception e) {
                r.ok = false;
                r.error = nice(e);
                return r;
            }
        }
    }

    /** 设置变更后调用，下次会重新建会话 */
    public static void reset() {
        synchronized (LOCK) {
            close();
            uploadedMd5 = null;
        }
    }

    // ---------------------------------------------------------------- 连接

    private static Session connect(Context c) throws Exception {
        String host = Prefs.sshHost(c);
        String user = Prefs.sshUser(c);
        int port = Prefs.sshPort(c);
        String pwd = SecurePrefs.getPassword(c);

        if (host.isEmpty()) throw new IllegalStateException("还没填 NAS 地址");
        if (user.isEmpty()) throw new IllegalStateException("还没填 SSH 用户名");
        if (pwd.isEmpty()) throw new IllegalStateException("还没填 SSH 密码");

        String key = host + ":" + port + ":" + user;
        if (session != null && session.isConnected() && key.equals(sessionKey)) return session;
        close();

        JSch jsch = new JSch();
        Session s = jsch.getSession(user, host, port);
        s.setPassword(pwd);
        Properties cfg = new Properties();
        // 家庭局域网场景：首次连接自动接受主机密钥，但下面会把指纹存下来，
        // 之后指纹变了就报错，防止中间人。
        cfg.put("StrictHostKeyChecking", "no");
        cfg.put("PreferredAuthentications", "password,keyboard-interactive");
        cfg.put("MaxAuthTries", "3");
        s.setConfig(cfg);
        s.setTimeout(20000);
        s.connect(25000);

        checkFingerprint(c, s);

        session = s;
        sessionKey = key;
        uploadedMd5 = null;

        // 记住 python3 的绝对路径，省得每次 command -v
        if (Prefs.python(c).equals("python3")) {
            try {
                Res res = exec(s, "command -v python3", pwd, 20000);
                String p = res.out.trim();
                if (p.startsWith("/")) Prefs.setPython(c, p);
            } catch (Exception ignore) {
            }
        }
        return s;
    }

    private static void checkFingerprint(Context c, Session s) throws Exception {
        HostKey hk = s.getHostKey();
        if (hk == null || hk.getKey() == null) return;
        String fp = sha256hex(hk.getKey());
        if (fp == null) return;
        String key = "fp_" + Prefs.sshHost(c) + "_" + Prefs.sshPort(c);
        String old = c.getSharedPreferences("nastools", Context.MODE_PRIVATE)
                .getString(key, "");
        if (old.isEmpty()) {
            c.getSharedPreferences("nastools", Context.MODE_PRIVATE).edit()
                    .putString(key, fp).apply();
        } else if (!old.equals(fp)) {
            close();
            throw new IllegalStateException(
                    "NAS 主机密钥变了（原来 " + old.substring(0, 16) + "…，现在 "
                            + fp.substring(0, 16) + "…）。\n"
                            + "可能换过 NAS 或有人在冒充它。确认没问题就去设置页清掉记录重连。");
        }
    }

    private static void close() {
        if (session != null) {
            try {
                session.disconnect();
            } catch (Exception ignore) {
            }
        }
        session = null;
        sessionKey = null;
    }

    // ---------------------------------------------------------------- 脚本同步

    private static byte[] asset(Context c) throws Exception {
        if (assetBytes != null) return assetBytes;
        InputStream is = c.getAssets().open("nas_api.py");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        assetBytes = bos.toByteArray();
        return assetBytes;
    }

    private static String md5(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 远端文件 md5，文件不存在返回空串 */
    private static String remoteMd5(Session s, String pwd) throws Exception {
        String one = "import hashlib,os,sys;p=sys.argv[1]"
                + ";print(hashlib.md5(open(p,'rb').read()).hexdigest()"
                + " if os.path.isfile(p) else '')";
        Res res = exec(s, "python3 -c " + q(one) + " " + REMOTE_PY, pwd, 25000);
        return res.out.trim();
    }

    private static void ensureScript(Context c, Session s) throws Exception {
        byte[] bytes = asset(c);
        String local = md5(bytes);
        if (local.equals(uploadedMd5)) return;

        String pwd = SecurePrefs.getPassword(c);
        String remote = remoteMd5(s, pwd);
        if (local.equals(remote)) {
            uploadedMd5 = local;
            return;
        }

        // 确保远端目录存在（用 sudo 建，避免 /tmp 里残留的 root 目录导致普通用户写不进去）
        exec(s, "sudo -S -p '' mkdir -p " + REMOTE_DIR, pwd, 20000);

        // 优先走 SFTP；群晖默认没开 sftp 子系统时 openChannel("sftp") 会抛
        // “failed to send channel request”，此时自动退回 exec 管道上传。
        boolean ok = false;
        Exception sftpErr = null;
        try {
            uploadSftp(s, bytes);
            ok = true;
        } catch (Exception e) {
            sftpErr = e;
        }
        if (!ok) {
            try {
                uploadExec(s, bytes, pwd);
                ok = true;
            } catch (Exception e) {
                if (sftpErr != null) {
                    throw new Exception("脚本上传失败（SFTP 与 exec 两种方式都失败）。\n"
                            + "SFTP：" + nice(sftpErr) + "\n"
                            + "exec：" + e.getMessage());
                }
                throw e;
            }
        }

        String after = remoteMd5(s, pwd);
        if (!local.equals(after)) {
            throw new Exception("脚本上传到 NAS 后校验不一致（本地 " + local
                    + " / 远端 " + after + "），可能 /tmp 不可写或 sudo 掉了权限");
        }
        uploadedMd5 = local;
    }

    /** 首选上传方式：标准 SFTP 子系统 */
    private static void uploadSftp(Session s, byte[] bytes) throws Exception {
        ChannelSftp ch = (ChannelSftp) s.openChannel("sftp");
        try {
            ch.connect(20000);
            ch.put(new ByteArrayInputStream(bytes), REMOTE_PY);
        } finally {
            try {
                ch.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 退回上传方式：不走 SFTP，把脚本 base64 后经 exec 管道写进 NAS。
     * 群晖默认没 sftp 子系统时靠它（诊断页能跑通说明 exec 通道本就可用）。
     * stdin 先喂 sudo 密码，再喂 base64 内容；sudo -S 读完密码后把剩余 stdin 交给 python3。
     */
    private static void uploadExec(Session s, byte[] bytes, String pwd) throws Exception {
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        ChannelExec ch = (ChannelExec) s.openChannel("exec");
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        InputStream in = ch.getInputStream();
        OutputStream os = ch.getOutputStream();
        ch.setErrStream(se);
        ch.setCommand("sudo -S -p '' python3 -c \"import sys,base64;"
                + "open(sys.argv[1],'wb').write(base64.b64decode(sys.stdin.read()))\" " + REMOTE_PY);
        ch.connect(25000);
        try {
            os.write((pwd + "\n").getBytes(StandardCharsets.UTF_8));
            os.write((b64 + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception ignore) {
        }
        try {
            os.close();
        } catch (Exception ignore) {
        }
        byte[] buf = new byte[8192];
        long deadline = System.currentTimeMillis() + 30000 + 25000;
        try {
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, in.available()));
                    if (n < 0) break;
                    so.write(buf, 0, n);
                }
                if (ch.isClosed()) {
                    if (in.available() > 0) continue;
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new java.io.IOException("上传脚本超时");
                }
                Thread.sleep(60);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
            ch.disconnect();
        }
        String err = new String(se.toByteArray(), StandardCharsets.UTF_8).trim();
        if (!err.isEmpty()) {
            throw new Exception("exec 上传脚本时远端报错：" + err);
        }
    }

    // ---------------------------------------------------------------- 执行

    private static class Res {
        String out = "";
        String err = "";
        int exit = -1;
    }

    private static Res exec(Session s, String cmd, String pwd, int timeoutMs) throws Exception {
        Res r = new Res();
        ChannelExec ch = (ChannelExec) s.openChannel("exec");
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        InputStream in = ch.getInputStream();
        OutputStream os = ch.getOutputStream();
        ch.setErrStream(se);
        ch.setCommand(cmd);
        ch.connect(25000);
        try {
            // sudo -S 从 stdin 读密码；写完就关，脚本本身不读 stdin
            os.write((pwd + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception ignore) {
        }
        try {
            os.close();
        } catch (Exception ignore) {
        }

        byte[] buf = new byte[8192];
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 5000) + 25000;
        try {
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, in.available()));
                    if (n < 0) break;
                    so.write(buf, 0, n);
                }
                if (ch.isClosed()) {
                    if (in.available() > 0) continue;
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new java.io.IOException("命令执行超时（"
                            + (timeoutMs / 1000) + " 秒）");
                }
                Thread.sleep(60);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
            ch.disconnect();
        }
        r.out = new String(so.toByteArray(), StandardCharsets.UTF_8);
        r.err = new String(se.toByteArray(), StandardCharsets.UTF_8);
        r.exit = ch.getExitStatus();
        return r;
    }

    // ---------------------------------------------------------------- 解析

    private static Api.R parse(Res res, Api.R r) {
        String line = null;
        for (String l : res.out.split("\n")) {
            if (l.startsWith(MARK)) line = l.substring(MARK.length()).trim();
        }
        if (line == null) {
            r.ok = false;
            String e = res.err.trim();
            r.error = e.isEmpty() ? res.out.trim() : e;
            if (r.error.isEmpty()) {
                r.error = "没拿到脚本输出（sudo 可能失败了）";
            } else {
                String low = r.error.toLowerCase();
                if (low.contains("sorry, try again") || low.contains("permission denied")
                        || low.contains("incorrect password")) {
                    r.error = "密码不对，或者账号没加入 administrators 组。\n（原始输出：" + r.error + "）";
                } else if (low.contains("sudo: no tty present")
                        || low.contains("must have a tty")) {
                    r.error = "这台 NAS 的 sudo 要求交互式终端。\n"
                            + "SSH 登录群晖后执行 sudo synouser --help 看是否也报这个错。";
                } else if (low.contains("command not found") && low.contains("python3")) {
                    r.error = "NAS 上没有 python3。群晖 DSM 7 自带，若被精简过需先装 Python 3 套件。";
                }
            }
            return r;
        }
        try {
            r.json = new JSONObject(line);
            r.ok = r.json.optBoolean("ok", false);
            if (!r.ok) {
                r.error = r.json.optString("error", "未知错误");
                if (r.error.isEmpty()) r.error = "未知错误";
            }
        } catch (Exception e) {
            r.ok = false;
            r.error = "结果不是合法 JSON：" + line;
        }
        return r;
    }

    // ---------------------------------------------------------------- 工具

    /** shell 单引号包裹，内部的 ' 转义成 '\'' —— 避免中文备注里的引号把命令截断 */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 主机密钥指纹：先 base64 解码再算 SHA-256；解码失败则退而求其次按字符串算（仍能稳定检测密钥变化） */
    private static String sha256hex(String key) {
        if (key == null) return null;
        byte[] raw;
        try {
            raw = Base64.decode(key, Base64.DEFAULT);
        } catch (Exception e) {
            raw = key.getBytes(StandardCharsets.UTF_8);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw);
            StringBuilder sb = new StringBuilder("SHA256:");
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String nice(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        String low = m.toLowerCase();
        if (low.contains("auth fail") || low.contains("auth cancel")) {
            return "SSH 登录失败：用户名或密码不对。\n"
                    + "注意群晖要用 administrators 组的账号，root 不能直连。";
        }
        if (low.contains("unknownhost") || low.contains("unable to resolve")) {
            return "找不到这台 NAS，确认 IP 对不对、手机和它在同一个 WiFi。";
        }
        if (low.contains("timeout") || low.contains("timed out")) {
            return "连接超时。确认群晖已开启 SSH（控制面板 → 终端机和 SNMP），"
                    + "且端口填的是 SSH 端口（你的是 6060，不是 22）。";
        }
        if (low.contains("connection refused")) {
            return "端口不通。群晖的 SSH 端口不是 22 的话要改填实际端口。";
        }
        if (low.contains("reject(hostkey") || low.contains("hostkey")) {
            return m;
        }
        return e.getClass().getSimpleName() + ": " + m;
    }

    // ---------------------------------------------------------------- 诊断

    /** 7 步 SSH 连通性诊断，返回完整文本（设置页用弹窗展示）。不影响主连接的会话缓存。 */
    public static String diagnose(Context c) {
        StringBuilder log = new StringBuilder();
        String host = Prefs.sshHost(c);
        String user = Prefs.sshUser(c);
        int port = Prefs.sshPort(c);
        String pwd = SecurePrefs.getPassword(c);
        log.append("目标 ").append(user).append("@").append(host).append(":").append(port).append("\n\n");
        if (host.isEmpty() || user.isEmpty() || pwd.isEmpty()) {
            log.append("[NG] 请先在上面填好地址、用户名、密码，再点诊断。");
            return log.toString();
        }
        Session session = null;
        try {
            // ---- [1] 本地环境 ----
            log.append("── [1/7] 本地库环境\n");
            try {
                log.append("  JSch 版本：").append(JSch.VERSION).append("\n");
            } catch (Throwable t) {
                log.append("  JSch 版本：读取失败 ").append(t).append("\n");
            }
            try {
                Class.forName("org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters");
                log.append("  BouncyCastle：[OK] 已随包打进 APK\n");
            } catch (Throwable t) {
                log.append("  BouncyCastle：[NG] 缺失\n");
            }
            String[] keys = {"xdh", "kex.curve25519-sha256", "signature.ssh-ed25519",
                    "signature.ssh-ed448", "keypairgen.eddsa", "keypairgen_fromprivate.eddsa"};
            for (String k : keys) {
                String v = safeConfig(k);
                if (v != null) log.append("  配置 ").append(k).append(" = ").append(v).append("\n");
            }
            log.append("  （含 .bc. 的实现才能在 Android 上用；含 .jce. 的需要 Java 11/15+）\n\n");

            // ---- [2~4] TCP / 握手 / 认证 ----
            JSch jsch = new JSch();
            Properties cfg = new Properties();
            cfg.put("StrictHostKeyChecking", "no");
            cfg.put("PreferredAuthentications", "password,keyboard-interactive");
            cfg.put("MaxAuthTries", "3");
            session = jsch.getSession(user, host, port);
            session.setPassword(pwd);
            session.setConfig(cfg);
            session.setTimeout(20000);

            log.append("── [2/7] TCP 连通\n");
            long t0 = System.currentTimeMillis();
            session.connect(15000);
            log.append("  [OK] 端口可达，耗时 ").append(System.currentTimeMillis() - t0).append(" ms\n\n");

            log.append("── [3/7] SSH 握手\n  [OK] 握手成功\n");
            try {
                log.append("  服务端指纹：").append(session.getHostKey().getFingerPrint(jsch)).append("\n");
                log.append("  密钥类型：").append(session.getHostKey().getType()).append("\n");
            } catch (Throwable ignore) {
            }
            log.append("\n── [4/7] 身份认证\n  [OK] 密码认证通过\n\n");

            // ---- [5] 基本信息 ----
            log.append("── [5/7] 远端身份与系统\n");
            DiagRes r = dexec(session, "id", 15000);
            log.append("  id → ").append(r.one()).append("\n");
            r = dexec(session, "whoami", 10000);
            log.append("  whoami → ").append(r.one()).append("\n");
            r = dexec(session, "uname -a", 10000);
            log.append("  uname → ").append(r.one()).append("\n");
            r = dexec(session, "cat /etc.defaults/VERSION 2>/dev/null | head -6", 10000);
            log.append("  系统版本 → ").append(r.one().isEmpty() ? "(读不到，可能不是群晖)" : r.one().replace("\n", " ")).append("\n\n");

            // ---- [6] 提权 ----
            log.append("── [6/7] sudo 非交互提权（这一步决定 App 能不能真的改配置）\n");
            boolean sudoOk = false;
            r = dexec(session, "sudo -n true", 12000);
            if (r.code == 0) {
                log.append("  sudo 免密：[OK] 无需密码即可提权\n");
                sudoOk = true;
            } else {
                log.append("  sudo 免密：[NG] 需要密码（正常，群晖默认如此）→ 改用 -S 从 stdin 喂密码\n");
                r = dexecSudo(session, "id -u", pwd, 15000);
                if ("0".equals(r.one().trim())) {
                    log.append("  [OK] 提权成功，uid=0（root）\n");
                    sudoOk = true;
                } else {
                    log.append("  [NG] 提权失败，退出码 ").append(r.code).append("，输出：").append(r.one()).append("\n");
                }
            }

            // ---- [7] 环境依赖 ----
            log.append("\n── [7/7] 方案所需环境\n");
            if (sudoOk) {
                r = dexecSudo(session, "command -v python3 && python3 -V 2>&1", pwd, 15000);
                log.append("  python3 → ").append(r.one().isEmpty() ? "[NG] 不存在（方案依赖 python3）" : r.one().replace("\n", " ")).append("\n");
                r = dexecSudo(session, "ls -d /volume1/docker /volume1/homes /volume2/docker 2>/dev/null", pwd, 15000);
                log.append("  常见卷 → ").append(r.one().isEmpty() ? "(无)" : r.one().replace("\n", " ")).append("\n");
                r = dexecSudo(session, "ls -d /volume1/docker/child_calendar /volume1/docker/projector_block 2>/dev/null", pwd, 15000);
                log.append("  项目目录 → ").append(r.one().isEmpty() ? "(没找到)" : r.one().replace("\n", " ")).append("\n");
                r = dexecSudo(session, "iptables -L -n 2>&1 | head -3", pwd, 15000);
                log.append("  iptables → ").append(r.code == 0 ? "[OK] 可执行" : "[NG] " + r.one()).append("\n");
            } else {
                log.append("  跳过（需要先解决提权）\n");
            }

            log.append("\n── 结论\n  ");
            if (sudoOk) {
                log.append("SSH 通道可用，非交互提权成功 → 可以做「App 内置 SSH，按需执行脚本」的方案。");
            } else {
                log.append("卡在提权，请看 [6/7] 的错误输出。");
            }
        } catch (com.jcraft.jsch.JSchException e) {
            String m = String.valueOf(e.getMessage());
            log.append("  [NG] ").append(m).append("\n");
            if (m.contains("Auth fail")) {
                log.append("  用户名或密码不对。注意群晖 SSH 只允许 administrators 组的账号登录。\n");
            } else if (m.contains("timeout") || m.contains("timed out")) {
                log.append("  连不上主机，检查地址与端口，以及手机是否在同一局域网。\n");
            }
        } catch (Exception e) {
            log.append("  [NG] ").append(e).append("\n");
        } finally {
            if (session != null) {
                try {
                    session.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
        return log.toString();
    }

    private static class DiagRes {
        int code = -1;
        String out = "";
        String err = "";

        String one() {
            return (out == null ? "" : out).trim();
        }
    }

    private static DiagRes dexec(Session s, String cmd, int timeoutMs) {
        return drun(s, cmd, null, timeoutMs);
    }

    private static DiagRes dexecSudo(Session s, String cmd, String pwd, int timeoutMs) {
        String wrapped = "sudo -S -p '' sh -c '" + cmd.replace("'", "'\\''") + "'";
        return drun(s, wrapped, pwd, timeoutMs);
    }

    private static DiagRes drun(Session s, String cmd, String pwd, int timeoutMs) {
        DiagRes r = new DiagRes();
        ChannelExec ch = null;
        try {
            ch = (ChannelExec) s.openChannel("exec");
            ch.setCommand(cmd);
            ByteArrayOutputStream eo = new ByteArrayOutputStream();
            ch.setErrStream(eo);
            InputStream in = ch.getInputStream();
            ch.connect(timeoutMs);
            if (pwd != null) {
                OutputStream os = ch.getOutputStream();
                os.write((pwd + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, in.available()));
                    if (n < 0) break;
                    bo.write(buf, 0, n);
                } else {
                    if (ch.isClosed()) {
                        if (in.available() > 0) continue;
                        break;
                    }
                    Thread.sleep(60);
                }
            }
            r.out = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            r.err = new String(eo.toByteArray(), StandardCharsets.UTF_8);
            r.code = ch.getExitStatus();
        } catch (Exception e) {
            r.err = String.valueOf(e.getMessage());
            r.code = -999;
        } finally {
            if (ch != null) {
                try {
                    ch.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
        return r;
    }

    private static String safeConfig(String key) {
        try {
            return JSch.getConfig(key);
        } catch (Throwable t) {
            return null;
        }
    }
}
