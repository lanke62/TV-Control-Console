#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
nas_api.py —— 「家庭联网控制」NAS 端执行脚本

用途
    给安卓 App 提供命令行接口（App 经 SSH 调用，单行 JSON 收发）：
      * 查看校历信息 / 设备清单 / 每台设备当前状态
      * 增删改被控设备（MAC 为身份，不依赖固定 IP）
      * 每台设备一套开放时段，每个时段独立控制「外网 / 内网」放行
      * 立即重建防火墙规则（也可由 DSM 任务计划定时调用）
      * 查看日志

设计约束（重要）
    1. 只用 Python 标准库，群晖自带 python3 即可运行，无需 pip 安装任何东西。
    2. 兼容 Python 3.8（群晖 DSM 7.2 自带 python3 3.8.x）。
    3. 改配置文件时：先备份成 .bak，再按 KEY= 逐行精确替换，绝不整体重写（保留注释）。
       设备清单是 JSON，用「写临时文件 + 原子替换」。
    4. 写文件统一 UTF-8 + LF（Windows 上传的 CRLF 会让 sh 脚本失效）。
    5. 所有会改系统状态的命令走同一把全局锁。
    6. 规则一律「全量重建」：先清掉本脚本创建的所有 HNC_* 链，再按设备清单重建。
       这样删设备不会留残留规则。

部署位置： /volume1/docker/nas_tools/nas_api.py
配置目录： /volume1/docker/nas_tools/conf/   （devices.json / net.conf）
日志：     /volume1/docker/nas_tools/log/netctl.log
校历项目： /volume1/docker/child_calendar    （只读，用来判定今天上学 / 放假）

定时任务（DSM 任务计划，root，开机 + 每 10 分钟）：
    /bin/python3 /volume1/docker/nas_tools/nas_api.py --apply
"""

import os
import re
import sys
import json
import time
import shlex
import threading
import subprocess

VERSION = "2.0.0"

# ---------------------------------------------------------------- 路径

DEFAULT_HOME = "/volume1/docker/nas_tools"
DEFAULT_CAL_HOME = "/volume1/docker/child_calendar"

HOME = DEFAULT_HOME
CAL_HOME = DEFAULT_CAL_HOME

SELF_CONF_DIR = ""
SELF_DEVICES = ""
SELF_NET = ""
SELF_LOG = ""

CAL_BIN = ""
CAL_CONF = ""
CAL_DATA = ""
CAL_LOG = ""
CAL_TSV = ""
CAL_OVERRIDES = ""

CHAIN_PREFIX = "HNC_"
ICMP_OK = True

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
YM_RE = re.compile(r"^\d{4}-\d{2}$")
HM_RE = re.compile(r"^\d{2}:\d{2}$")
MAC_RE = re.compile(r"^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

CMD_LOCK = threading.RLock()


def set_homes(home=None, cal=None):
    """重设工具目录与校历目录，并刷新所有派生路径。

    命令行模式下由 App 传入实际路径（不同 NAS 上目录名可能不一样），
    所以这些不能写死成常量。
    """
    global HOME, CAL_HOME
    global SELF_CONF_DIR, SELF_DEVICES, SELF_NET, SELF_LOG
    global CAL_BIN, CAL_CONF, CAL_DATA, CAL_LOG, CAL_TSV, CAL_OVERRIDES

    if home:
        HOME = home.rstrip("/")
    if cal:
        CAL_HOME = cal.rstrip("/")

    SELF_CONF_DIR = os.path.join(HOME, "conf")
    SELF_DEVICES = os.path.join(SELF_CONF_DIR, "devices.json")
    SELF_NET = os.path.join(SELF_CONF_DIR, "net.conf")
    SELF_LOG = os.path.join(HOME, "log", "netctl.log")

    CAL_BIN = os.path.join(CAL_HOME, "bin")
    CAL_CONF = os.path.join(CAL_HOME, "conf", "calendar.conf")
    CAL_DATA = os.path.join(CAL_HOME, "data")
    CAL_LOG = os.path.join(CAL_HOME, "log")
    CAL_TSV = os.path.join(CAL_DATA, "school_calendar.txt")
    CAL_OVERRIDES = os.path.join(CAL_HOME, "conf", "overrides.txt")


set_homes(DEFAULT_HOME, DEFAULT_CAL_HOME)


def detect_cal_home():
    """按特征文件自动找校历项目目录（不写死目录名）。"""
    cands = []
    for root in ("/volume1", "/volume2"):
        if not os.path.isdir(root):
            continue
        rc, out, _e = run(["find", root, "-maxdepth", "5",
                           "-name", "calendar.conf", "-path", "*/conf/*"], timeout=25)
        if rc == 0:
            for ln in out.splitlines():
                ln = ln.strip()
                if ln:
                    cands.append(os.path.dirname(os.path.dirname(ln)))
        if cands:
            break
    # 兜底：老路径
    if not cands and os.path.isdir(DEFAULT_CAL_HOME):
        cands.append(DEFAULT_CAL_HOME)
    return (cands[0] if cands else ""), cands


# ---------------------------------------------------------------- 基础工具

def dec(b):
    if isinstance(b, bytes):
        return b.decode("utf-8", "replace")
    return b or ""


def run(cmd, timeout=120, cwd=None):
    """跑一条命令，返回 (rc, stdout, stderr)。不抛异常。"""
    try:
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=cwd)
        try:
            out, err = p.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            p.kill()
            out, err = p.communicate()
            return 124, dec(out), dec(err) + "\n(超时)"
        return p.returncode, dec(out), dec(err)
    except Exception as e:
        return 127, "", "%s: %s" % (type(e).__name__, e)


def tail(path, n):
    out = []
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                out.append(line.rstrip("\n"))
    except Exception:
        return []
    return out[-max(1, n):]


def find_bin(name):
    for p in ("/bin/" + name, "/usr/bin/" + name, "/sbin/" + name, "/usr/sbin/" + name):
        if os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    rc, out, _e = run(["command", "-v", name])
    if rc == 0 and out.strip():
        return out.strip().splitlines()[0]
    return ""


def valid_hm(s):
    if not HM_RE.match(s or ""):
        return False
    h, m = int(s[:2]), int(s[3:])
    return 0 <= h <= 23 and 0 <= m <= 59


def norm_mac(s):
    """把 MAC 归一化成 aa:bb:cc:dd:ee:ff；非法返回空串。"""
    t = (s or "").strip().lower().replace("-", ":").replace(".", "")
    if re.match(r"^[0-9a-f]{12}$", t):
        t = ":".join(t[i:i + 2] for i in range(0, 12, 2))
    return t if MAC_RE.match(t) else ""


# ---------------------------------------------------------------- 配置读写

def split_value(rest):
    """把 'KEY=value  # 注释' 里的 value 抠出来，去引号。"""
    v = rest.strip()
    if v.startswith('"'):
        i = v.find('"', 1)
        if i > 0:
            return v[1:i]
        return v[1:]
    if v.startswith("'"):
        i = v.find("'", 1)
        if i > 0:
            return v[1:i]
        return v[1:]
    # 无引号：取到空白或 # 之前
    v = re.split(r"\s+#|\s+", v, 1)[0]
    return v


def read_conf(path):
    """读 KEY=VALUE 形式的 conf，返回 dict（忽略注释行）。"""
    kv = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.rstrip("\r\n")
                s = line.strip()
                if not s or s.startswith("#") or "=" not in s:
                    if s.startswith("export ") and "=" in s:
                        pass
                    else:
                        continue
                if s.startswith("export "):
                    s = s[7:]
                k, rest = s.split("=", 1)
                kv[k.strip()] = split_value(rest)
    except Exception:
        pass
    return kv


def write_conf(path, kv):
    """按 KEY= 逐行精确替换；保留注释与结构；改前备份 .bak；统一 UTF-8 + LF。

    返回实际改动的 KEY 列表。
    """
    old_lines = []
    if os.path.isfile(path):
        try:
            with open(path, "rb") as f:
                raw = f.read()
            try:
                os.replace(path, path + ".bak")
            except Exception:
                with open(path + ".bak", "wb") as b:
                    b.write(raw)
            old_lines = raw.decode("utf-8", "replace").replace("\r\n", "\n").split("\n")
        except Exception:
            old_lines = []
    else:
        d = os.path.dirname(path)
        if d and not os.path.isdir(d):
            os.makedirs(d)

    done = set()
    out = []
    for line in old_lines:
        s = line.strip()
        target = s[7:] if s.startswith("export ") else s
        if "=" in target and not target.startswith("#"):
            k = target.split("=", 1)[0].strip()
            if k in kv:
                out.append("%s=%s" % (k, fmt_kv(kv[k])))
                done.add(k)
                continue
        out.append(line)

    rest = [k for k in kv.keys() if k not in done]
    if rest and out and out[-1].strip() != "":
        out.append("")
    for k in rest:
        out.append("%s=%s" % (k, fmt_kv(kv[k])))

    text = "\n".join(out)
    if not text.endswith("\n"):
        text += "\n"
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    return list(kv.keys())


def fmt_kv(v):
    s = str(v)
    if re.search(r"[\s#;'\"]", s) or s == "":
        return '"' + s.replace('"', '\\"') + '"'
    return s


def logline(msg):
    """往工具日志追加一行；失败不抛。"""
    try:
        d = os.path.dirname(SELF_LOG)
        if d and not os.path.isdir(d):
            os.makedirs(d)
        with open(SELF_LOG, "a", encoding="utf-8", newline="\n") as f:
            f.write("%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
    except Exception:
        pass


# ---------------------------------------------------------------- 网络全局参数

def load_net():
    kv = read_conf(SELF_NET)
    return {
        "wan_if": kv.get("WAN_IF", "ovs_eth0") or "ovs_eth0",
        "lan_net": kv.get("LAN_NET", "192.168.1.0/24"),
        "nas_ip": kv.get("NAS_IP", ""),
    }


def save_net(kv):
    v = {}
    for k in ("WAN_IF", "LAN_NET", "NAS_IP"):
        if k in kv:
            v[k] = str(kv[k]).strip()
    if "LAN_NET" in v and v["LAN_NET"]:
        if not re.match(r"^\d{1,3}(\.\d{1,3}){3}/\d{1,2}$", v["LAN_NET"]):
            raise ValueError("LAN_NET 应形如 192.168.1.0/24")
    if "WAN_IF" in v and v["WAN_IF"] and not re.match(r"^[A-Za-z0-9_.:-]+$", v["WAN_IF"]):
        raise ValueError("WAN_IF 只能含字母数字与 _ . : -")
    return write_conf(SELF_NET, v)


# ---------------------------------------------------------------- 设备存储

def default_windows(which):
    """新设备的默认时段：沿用旧版默认值，避免新设备一上来全封。"""
    if which == "school":
        return [{"s": "08:00", "e": "12:00", "wan": 1, "lan": 1},
                {"s": "14:00", "e": "16:30", "wan": 1, "lan": 1}]
    return [{"s": "18:00", "e": "21:00", "wan": 1, "lan": 1}]


def device_template(did, name, mac, note=""):
    return {
        "id": did,
        "name": name,
        "mac": mac,
        "note": note,
        "enabled": 1,
        "mode": "auto",
        "school": default_windows("school"),
        "holiday": default_windows("holiday"),
    }


def norm_windows(raw, field):
    """校验并归一化时段数组。每项 {s,e,wan,lan}。"""
    out = []
    if raw is None:
        return out
    if not isinstance(raw, list):
        raise ValueError("%s 必须是数组" % field)
    if len(raw) > 12:
        raise ValueError("%s 最多 12 段" % field)
    for i, w in enumerate(raw):
        if not isinstance(w, dict):
            raise ValueError("%s 第 %d 段格式不对" % (field, i + 1))
        s = str(w.get("s", "")).strip()
        e = str(w.get("e", "")).strip()
        if not valid_hm(s) or not valid_hm(e):
            raise ValueError("%s 第 %d 段时间格式应为 HH:MM" % (field, i + 1))
        if s == e:
            raise ValueError("%s 第 %d 段起止时间相同" % (field, i + 1))
        a = hhmm_to_min(s)
        b = hhmm_to_min(e)
        dur = (b - a) if b > a else (1440 - a + b)
        if dur > 12 * 60:
            raise ValueError("%s 第 %d 段超过 12 小时（是不是起止填反了）" % (field, i + 1))
        out.append({
            "s": s, "e": e,
            "wan": 1 if str(w.get("wan", 1)) in ("1", "true", "True") else 0,
            "lan": 1 if str(w.get("lan", 1)) in ("1", "true", "True") else 0,
        })
    return out


def load_devices():
    """读设备清单。文件不存在就返回空清单（不自动写盘）。"""
    doc = {"version": 1, "devices": []}
    if not os.path.isfile(SELF_DEVICES):
        return doc
    try:
        with open(SELF_DEVICES, "r", encoding="utf-8", errors="replace") as f:
            raw = json.load(f)
    except Exception:
        # 坏了就备份一份，返回空清单，别让 App 直接崩
        try:
            os.replace(SELF_DEVICES, SELF_DEVICES + ".broken")
        except Exception:
            pass
        return doc
    if isinstance(raw, list):
        raw = {"version": 1, "devices": raw}
    if not isinstance(raw, dict):
        return doc
    lst = raw.get("devices")
    if not isinstance(lst, list):
        lst = []
    out = []
    for d in lst:
        if not isinstance(d, dict):
            continue
        mac = norm_mac(d.get("mac", ""))
        if not mac:
            continue
        did = str(d.get("id", "")).strip() or ("d%d" % (len(out) + 1))
        it = {
            "id": did,
            "name": str(d.get("name", "")).strip() or mac,
            "mac": mac,
            "note": str(d.get("note", "")).strip(),
            "enabled": 1 if str(d.get("enabled", 1)) in ("1", "true", "True") else 0,
            "mode": str(d.get("mode", "auto")),
        }
        if it["mode"] not in ("auto", "open", "block"):
            it["mode"] = "auto"
        try:
            it["school"] = norm_windows(d.get("school"), "上学日时段")
            it["holiday"] = norm_windows(d.get("holiday"), "放假日时段")
        except ValueError:
            it["school"] = default_windows("school")
            it["holiday"] = default_windows("holiday")
        out.append(it)
    doc["devices"] = out
    return doc


def save_devices(doc):
    """原子写 devices.json（写临时文件再 rename），并留一份 .bak。"""
    d = os.path.dirname(SELF_DEVICES)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    text = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    tmp = SELF_DEVICES + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    if os.path.isfile(SELF_DEVICES):
        try:
            with open(SELF_DEVICES, "rb") as f:
                old = f.read()
            with open(SELF_DEVICES + ".bak", "wb") as f:
                f.write(old)
        except Exception:
            pass
    os.replace(tmp, SELF_DEVICES)


def next_id(doc):
    n = 1
    used = set()
    for d in doc["devices"]:
        used.add(d.get("id", ""))
    while ("d%d" % n) in used:
        n += 1
    return "d%d" % n


def find_device(doc, did):
    for d in doc["devices"]:
        if d.get("id") == did:
            return d
    return None


# ---------------------------------------------------------------- 校历（只读）

def calendar_info():
    info = {
        "exists": False, "days": 0, "school_days": 0, "holiday_days": 0,
        "built_at": "", "range_start": "", "range_end": "",
    }
    if os.path.isfile(CAL_TSV):
        try:
            n = 0
            s = 0
            with open(CAL_TSV, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    parts = line.rstrip("\n").split("\t")
                    if len(parts) >= 2 and DATE_RE.match(parts[0]):
                        n += 1
                        if parts[1] == "1":
                            s += 1
            info["exists"] = True
            info["days"] = n
            info["school_days"] = s
            info["holiday_days"] = n - s
            info["built_at"] = time.strftime(
                "%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(CAL_TSV)))
        except Exception:
            pass
    return info


def day_type(date=None):
    """判定某天是上学日还是放假日：school / holiday / unknown。

    直接读 TSV（快、不依赖 sh 脚本），读不到再退回 is_school_day.sh。
    """
    d = date or time.strftime("%Y-%m-%d")
    if os.path.isfile(CAL_TSV):
        try:
            with open(CAL_TSV, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    parts = line.rstrip("\n").split("\t")
                    if len(parts) >= 2 and parts[0] == d:
                        return "school" if parts[1] == "1" else "holiday"
        except Exception:
            pass
    script = os.path.join(CAL_BIN, "is_school_day.sh")
    if os.path.isfile(script):
        rc, out, _e = run(["sh", script, d], timeout=20)
        t = (out or "").strip().splitlines()
        if t:
            v = t[0].strip()
            if v in ("school", "holiday"):
                return v
    return "unknown"


def month_days(ym):
    if not YM_RE.match(ym or ""):
        raise ValueError("月份格式应为 YYYY-MM")
    rc, out, err = run(["sh", os.path.join(CAL_BIN, "is_school_day.sh"), ym], timeout=40)
    days = []
    for line in out.splitlines():
        parts = re.split(r"\s+", line.strip(), 3)
        if len(parts) < 2 or not DATE_RE.match(parts[0]):
            continue
        days.append({
            "date": parts[0],
            "school": parts[1].startswith("上学"),
            "cat": parts[2] if len(parts) > 2 else "",
            "label": parts[3] if len(parts) > 3 else "",
        })
    return {"ok": True, "ym": ym, "days": days, "error": "" if days else (err or "").strip()}


def read_overrides():
    items = []
    if not os.path.isfile(CAL_OVERRIDES):
        return items
    try:
        with open(CAL_OVERRIDES, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith("#"):
                    continue
                p = s.split("|")
                if len(p) >= 2 and DATE_RE.match(p[0]):
                    items.append({"date": p[0], "flag": p[1],
                                  "note": p[2] if len(p) > 2 else ""})
    except Exception:
        pass
    return items


# ---------------------------------------------------------------- iptables 引擎

KERNELTZ_CACHE = {}


def ipt(binpath, args, timeout=20):
    return run([binpath, "-w", "5"] + list(args), timeout)


def has_kerneltz(binpath):
    """xt_time 是否支持 --kerneltz（按本机时区解释时段）。"""
    if binpath in KERNELTZ_CACHE:
        return KERNELTZ_CACHE[binpath]
    rc, out, err = run([binpath, "-m", "time", "--help"], timeout=10)
    txt = (out or "") + (err or "")
    ok = "--kerneltz" in txt
    KERNELTZ_CACHE[binpath] = ok
    return ok


def tz_offset_minutes():
    """本机时区相对 UTC 的偏移（分钟，东八区 = +480）。"""
    try:
        off = -time.timezone
        if time.daylight and time.localtime().tm_isdst:
            off = -time.altzone
        return int(off // 60)
    except Exception:
        return 0


def hhmm_to_min(s):
    return int(s[:2]) * 60 + int(s[3:])


def min_to_hhmm(m):
    m = m % (24 * 60)
    return "%02d:%02d" % (m // 60, m % 60)


def time_args(binpath, start, end):
    """生成 -m time 的参数；不支持 --kerneltz 就把时间换算成 UTC。"""
    if has_kerneltz(binpath):
        return ["-m", "time", "--kerneltz",
                "--timestart", start, "--timestop", end]
    off = tz_offset_minutes()
    s = min_to_hhmm(hhmm_to_min(start) - off)
    e = min_to_hhmm(hhmm_to_min(end) - off)
    return ["-m", "time", "--timestart", s, "--timestop", e]


def window_segments(w):
    """把一个时段拆成不跨零点的若干段（xt_time 不支持 stop < start）。"""
    s = hhmm_to_min(w["s"])
    e = hhmm_to_min(w["e"])
    if e > s:
        return [(min_to_hhmm(s), min_to_hhmm(e))]
    # 跨零点：22:00-01:00 → 22:00-23:59 + 00:00-01:00
    if e == s:
        return []
    return [(min_to_hhmm(s), "23:59"), ("00:00", min_to_hhmm(e))]


def chain_in(did):
    return CHAIN_PREFIX + "I_" + did


def chain_fw(did):
    return CHAIN_PREFIX + "F_" + did


def purge(binpath):
    """清掉本脚本创建的全部链与跳转。删设备后残留规则靠它兜底。"""
    rc, out, _e = ipt(binpath, ["-S"])
    if rc != 0:
        return False
    lines = out.splitlines()
    # 先拆所有引用了 HNC_ 的规则（不论挂在哪个链上）
    for ln in lines:
        ln = ln.strip()
        if not ln.startswith("-A ") or CHAIN_PREFIX not in ln:
            continue
        try:
            parts = shlex.split(ln)
        except Exception:
            continue
        table = parts[1]
        spec = parts[2:]
        ipt(binpath, ["-D", table] + spec)
    # 再清链
    for ln in lines:
        ln = ln.strip()
        if ln.startswith("-N ") and ln[3:].strip().startswith(CHAIN_PREFIX):
            ch = ln[3:].strip()
            ipt(binpath, ["-F", ch])
            ipt(binpath, ["-X", ch])
    return True


def fill_chain(binpath, chain, kind, dev, dtype, net):
    """填一条设备链。

    kind = "in"  → 设备访问 NAS 本身（INPUT 链）
    kind = "fw"  → 设备访问外网 / 其它网段（FORWARD 链）
    """
    mode = dev.get("mode", "auto")
    if mode == "open":
        ipt(binpath, ["-A", chain, "-j", "RETURN"])
        return
    if mode == "block":
        ipt(binpath, ["-A", chain, "-j", "DROP"])
        return

    wins = dev.get("school") if dtype == "school" else dev.get("holiday")
    wins = wins or []

    if kind == "in":
        # DNS 例外：设备把群晖当 DNS 时，封内网不能顺手把上网也弄断。
        # 只对「有外网放行」的设备放 53，避免全封时还漏个口子。
        if any(w.get("wan") for w in wins):
            for proto in ("udp", "tcp"):
                ipt(binpath, ["-A", chain, "-p", proto, "--dport", "53", "-j", "RETURN"])
        for w in wins:
            if not w.get("lan"):
                continue
            for seg_s, seg_e in window_segments(w):
                ipt(binpath, ["-A", chain] + time_args(binpath, seg_s, seg_e) + ["-j", "RETURN"])
        ipt(binpath, ["-A", chain, "-j", "DROP"])
        return

    for w in wins:
        if not w.get("wan"):
            continue
        for seg_s, seg_e in window_segments(w):
            ipt(binpath, ["-A", chain] + time_args(binpath, seg_s, seg_e) + ["-j", "RETURN"])
    ipt(binpath, ["-A", chain, "-j", "DROP"])


def apply_devices(reason="manual"):
    """按设备清单全量重建防火墙规则。返回摘要 dict。"""
    with CMD_LOCK:
        doc = load_devices()
        net = load_net()
        dtype = day_type()
        devs = doc.get("devices") or []

        v4 = find_bin("iptables")
        v6 = find_bin("ip6tables")

        errors = []
        built = 0

        if not v4:
            errors.append("找不到 iptables 命令，规则未下发")

        for binpath, fam in ((v4, "v4"), (v6, "v6")):
            if not binpath:
                continue
            st = ipt(binpath, ["-S"], timeout=30)
            if st[0] != 0:
                errors.append("%s 不可用: %s" % (fam, (st[2] or "").strip()))
                continue
            purge(binpath)

            for d in devs:
                if not d.get("enabled", 1):
                    continue
                mac = d.get("mac", "")
                if not mac:
                    continue
                did = d.get("id", "")
                ci = chain_in(did)
                cf = chain_fw(did)

                rc, _o, e = ipt(binpath, ["-N", ci])
                if rc != 0:
                    errors.append("%s 建链失败 %s: %s" % (fam, ci, e.strip()))
                    continue
                ipt(binpath, ["-N", cf])

                rc, _o, e = ipt(binpath, ["-A", "INPUT", "-m", "mac",
                                          "--mac-source", mac, "-j", ci])
                if rc != 0:
                    errors.append("%s 挂 INPUT 失败 %s: %s" % (fam, mac, e.strip()))

                if fam == "v4" and net.get("lan_net"):
                    # 只对「去往外网/其它网段」的流量做外网管控，内网互通不误伤
                    rc, _o, e = ipt(binpath, ["-A", "FORWARD", "-m", "mac",
                                              "--mac-source", mac,
                                              "!", "-d", net["lan_net"], "-j", cf])
                else:
                    rc, _o, e = ipt(binpath, ["-A", "FORWARD", "-m", "mac",
                                              "--mac-source", mac, "-j", cf])
                if rc != 0:
                    errors.append("%s 挂 FORWARD 失败 %s: %s" % (fam, mac, e.strip()))

                fill_chain(binpath, ci, "in", d, dtype, net)
                fill_chain(binpath, cf, "fw", d, dtype, net)
                built += 1

        msg = "apply(%s) day=%s devices=%d chains=%d" % (reason, dtype, len(devs), built)
        logline(msg + ((" errors=" + "; ".join(errors)) if errors else ""))
        return {
            "ok": not errors,
            "day_type": dtype,
            "devices": len(devs),
            "built": built,
            "iptables": bool(v4),
            "ip6tables": bool(v6),
            "kerneltz": has_kerneltz(v4) if v4 else False,
            "errors": errors,
            "output": msg,
            "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
        }


def chain_pkts(binpath, chain):
    rc, out, _e = ipt(binpath, ["-L", chain, "-n", "-v", "-x"], timeout=20)
    if rc != 0:
        return None
    total = 0
    for ln in out.splitlines()[2:]:
        parts = ln.split()
        if parts and parts[0].isdigit():
            total += int(parts[0])
    return total


def ip_of_mac(mac):
    """从 ARP 表按 MAC 反查当前 IP（只用于界面展示 / 踢连接）。"""
    try:
        with open("/proc/net/arp", "r", encoding="utf-8", errors="replace") as f:
            for line in f.readlines()[1:]:
                p = line.split()
                if len(p) >= 4 and p[3].lower() == mac.lower():
                    return p[0]
    except Exception:
        pass
    return ""


def kick_device(mac):
    """清掉该设备已建立的连接（换模式时立刻生效）。"""
    ip = ip_of_mac(mac)
    if not ip:
        return False
    ct = find_bin("conntrack")
    if not ct:
        return False
    rc, _o, _e = run([ct, "-D", "-s", ip], timeout=20)
    return rc == 0


# ---------------------------------------------------------------- 状态

def now_state(dev, dtype, now_min=None):
    """算这台设备此刻应该是放行还是封禁（内网 / 外网分别算）。"""
    if now_min is None:
        lt = time.localtime()
        now_min = lt.tm_hour * 60 + lt.tm_min
    mode = dev.get("mode", "auto")
    if mode == "open":
        return {"lan": True, "wan": True, "text": "长期放行"}
    if mode == "block":
        return {"lan": False, "wan": False, "text": "长期封禁"}
    wins = dev.get("school") if dtype == "school" else dev.get("holiday")
    wins = wins or []
    lan_ok = False
    wan_ok = False
    for w in wins:
        s = hhmm_to_min(w["s"])
        e = hhmm_to_min(w["e"])
        inside = (s <= now_min < e) if e > s else (now_min >= s or now_min < e)
        if inside:
            if w.get("lan"):
                lan_ok = True
            if w.get("wan"):
                wan_ok = True
    if lan_ok and wan_ok:
        t = "时段内：内网+外网"
    elif wan_ok:
        t = "时段内：仅外网"
    elif lan_ok:
        t = "时段内：仅内网"
    else:
        t = "时段外：全封"
    return {"lan": lan_ok, "wan": wan_ok, "text": t}


def get_status():
    cal = read_conf(CAL_CONF)
    dtype = day_type()
    net = load_net()
    doc = load_devices()

    v4 = find_bin("iptables")
    v6 = find_bin("ip6tables")

    st = {
        "ok": True,
        "version": VERSION,
        "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
        "date": time.strftime("%Y-%m-%d"),
        "day_type": dtype,
        "day_label": "上学日" if dtype == "school" else (
            "放假日" if dtype == "holiday" else "未知（按放假日处理）"),
        "net": net,
        "host": HOME,
    }

    cal_fields = {
        "school_windows": cal.get("SCHOOL_WINDOWS", ""),
        "holiday_windows": cal.get("HOLIDAY_WINDOWS", ""),
        "stage": cal.get("STAGE", "primary"),
        "vacation_ranges": cal.get("VACATION_RANGES", ""),
        "range_start": cal.get("RANGE_START", ""),
        "range_end": cal.get("RANGE_END", ""),
    }
    st["calendar"] = calendar_info()
    st["calendar"].update(cal_fields)

    out = []
    for d in doc["devices"]:
        it = dict(d)
        it["ip"] = ip_of_mac(d["mac"])
        ns = now_state(d, dtype, None)
        it["lan_now"] = 1 if ns["lan"] else 0
        it["wan_now"] = 1 if ns["wan"] else 0
        it["state_text"] = ns["text"]
        it["pkts4"] = chain_pkts(v4, chain_in(d["id"])) if v4 else None
        it["pkts4_wan"] = chain_pkts(v4, chain_fw(d["id"])) if v4 else None
        it["pkts6"] = chain_pkts(v6, chain_in(d["id"])) if v6 else None
        out.append(it)
    st["devices"] = out
    st["count"] = len(out)
    return st


# ---------------------------------------------------------------- 设备增删改

def dev_add(name, mac, note=""):
    name = (name or "").strip()
    mac = norm_mac(mac)
    if not name:
        raise ValueError("设备名称不能为空")
    if len(name) > 32:
        raise ValueError("设备名称最多 32 个字")
    if not mac:
        raise ValueError("MAC 地址格式不对，应形如 D0:96:EA:0E:00:07")
    if mac in ("00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff"):
        raise ValueError("这个 MAC 是广播/全零地址，不能用")
    doc = load_devices()
    for d in doc["devices"]:
        if d["mac"] == mac:
            raise ValueError("这个 MAC 已经在列表里了（%s）" % d["name"])
    did = next_id(doc)
    doc["devices"].append(device_template(did, name, mac, (note or "").strip()))
    save_devices(doc)
    r = apply_devices("add:" + did)
    logline("add %s %s %s" % (did, name, mac))
    return {"ok": True, "id": did, "device": doc["devices"][-1],
            "apply": r, "devices": doc["devices"]}


def dev_del(did):
    doc = load_devices()
    d = find_device(doc, did)
    if not d:
        raise ValueError("没有这个设备：%s" % did)
    doc["devices"] = [x for x in doc["devices"] if x["id"] != did]
    save_devices(doc)
    # 立刻重建：purge 会把已删设备的链和跳转一并清掉，不留残留规则
    r = apply_devices("del:" + did)
    logline("del %s %s %s" % (did, d.get("name", ""), d.get("mac", "")))
    return {"ok": True, "id": did, "removed": d, "apply": r,
            "devices": doc["devices"]}


def dev_update(did, patch):
    doc = load_devices()
    d = find_device(doc, did)
    if not d:
        raise ValueError("没有这个设备：%s" % did)
    if "name" in patch:
        nm = str(patch.get("name", "")).strip()
        if not nm:
            raise ValueError("设备名称不能为空")
        if len(nm) > 32:
            raise ValueError("设备名称最多 32 个字")
        d["name"] = nm
    if "mac" in patch:
        mac = norm_mac(patch.get("mac", ""))
        if not mac:
            raise ValueError("MAC 地址格式不对")
        for x in doc["devices"]:
            if x["id"] != did and x["mac"] == mac:
                raise ValueError("这个 MAC 已经被另一台设备用了")
        d["mac"] = mac
    if "note" in patch:
        d["note"] = str(patch.get("note", "")).strip()
    if "enabled" in patch:
        d["enabled"] = 1 if str(patch.get("enabled")) in ("1", "true", "True") else 0
    if "mode" in patch:
        m = str(patch.get("mode", "auto"))
        if m not in ("auto", "open", "block"):
            raise ValueError("mode 只能是 auto / open / block")
        d["mode"] = m
    if "school" in patch:
        d["school"] = norm_windows(patch.get("school"), "上学日时段")
    if "holiday" in patch:
        d["holiday"] = norm_windows(patch.get("holiday"), "放假日时段")

    save_devices(doc)
    r = apply_devices("update:" + did)
    if d["mode"] == "block":
        kick_device(d["mac"])
    return {"ok": True, "device": d, "apply": r, "devices": doc["devices"]}


# ---------------------------------------------------------------- 命令行

def emit(obj):
    sys.stdout.write("__HNC_JSON__ " + json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def cli_main(argv):
    """命令行入口。

    App 通过 SSH 调用，形如：
        sudo -S -p '' python3 /volume1/docker/nas_tools/nas_api.py \
            --json '{"cmd":"status"}' --home X --cal-home Y
    定时任务直接：
        python3 /volume1/docker/nas_tools/nas_api.py --apply
    """
    payload = None
    cal_home = None
    home = None
    do_apply = False

    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--json" and i + 1 < len(argv):
            try:
                payload = json.loads(argv[i + 1])
            except Exception as e:
                emit({"ok": False, "error": "JSON 解析失败: %s" % e})
                return 2
            i += 2
            continue
        if a == "--home" and i + 1 < len(argv):
            home = argv[i + 1]
            i += 2
            continue
        if a == "--cal-home" and i + 1 < len(argv):
            cal_home = argv[i + 1]
            i += 2
            continue
        if a == "--apply":
            do_apply = True
            i += 1
            continue
        if a in ("--version", "-V"):
            sys.stdout.write(VERSION + "\n")
            return 0
        if a in ("--help", "-h"):
            sys.stdout.write(__doc__ or "nas_api.py")
            return 0
        i += 1

    if do_apply:
        if not cal_home:
            _c, _cands = detect_cal_home()
            if _c:
                cal_home = _c
        set_homes(home, cal_home)
        r = apply_devices("cron")
        emit(r)
        return 0 if r.get("ok") else 1

    if payload is None:
        emit({"ok": False, "error": "缺少 --json 参数"})
        return 2

    # 目录优先用 App 指定的，其次自动探测
    if not cal_home:
        _c, _cands = detect_cal_home()
        cal_home = _c or ""
    set_homes(home, cal_home or DEFAULT_CAL_HOME)

    cmd = payload.get("cmd", "")

    try:
        if cmd == "probe":
            cal, cands = detect_cal_home()
            v4 = find_bin("iptables")
            v6 = find_bin("ip6tables")
            emit({
                "ok": True,
                "version": VERSION,
                "python": sys.executable or "/bin/python3",
                "home": HOME,
                "cal_home": cal or CAL_HOME,
                "candidates_cal": ";".join(cands),
                "tool_home": HOME,
                "iptables": v4,
                "ip6tables": v6,
                "kerneltz": bool(v4) and has_kerneltz(v4),
                "day_type": day_type(),
            })
            return 0

        if cmd == "status":
            emit(get_status())
            return 0

        if cmd == "calendar":
            emit(month_days(payload.get("ym", "")))
            return 0

        if cmd == "devices":
            op = payload.get("op", "list")
            if op == "list":
                emit({"ok": True, "devices": load_devices()["devices"]})
                return 0
            if op == "add":
                emit(dev_add(payload.get("name", ""), payload.get("mac", ""),
                             payload.get("note", "")))
                return 0
            if op == "del":
                emit(dev_del(str(payload.get("id", "")).strip()))
                return 0
            if op == "update":
                did = str(payload.get("id", "")).strip()
                patch = payload.get("set") or {}
                if not isinstance(patch, dict):
                    raise ValueError("set 必须是对象")
                emit(dev_update(did, patch))
                return 0
            emit({"ok": False, "error": "op 必须是 list/add/del/update"})
            return 2

        if cmd == "apply":
            emit(apply_devices("app"))
            return 0

        if cmd == "net":
            op = payload.get("op", "get")
            if op == "get":
                emit({"ok": True, "net": load_net()})
                return 0
            if op == "set":
                kv = payload.get("set") or {}
                save_net(kv)
                emit({"ok": True, "net": load_net(), "apply": apply_devices("net")})
                return 0
            emit({"ok": False, "error": "op 必须是 get/set"})
            return 2

        if cmd == "overrides":
            op = payload.get("op", "list")
            if op == "list":
                emit({"ok": True, "items": read_overrides()})
                return 0
            emit({"ok": False, "error": "单日覆盖已废弃，只支持 list"})
            return 2

        if cmd == "logs":
            try:
                n = int(payload.get("n", 80))
            except Exception:
                n = 80
            n = max(1, min(n, 500))
            emit({"ok": True, "logs": {
                "联网控制": tail(SELF_LOG, n),
                "校历构建": tail(os.path.join(CAL_LOG, "build.log"), n),
            }})
            return 0

        if cmd == "config":
            # 只保留校历项目的配置写入（寒暑假区间 / 学段）；设备规则走 devices.update
            scope = payload.get("scope", "")
            kv = payload.get("set") or {}
            if scope != "calendar":
                raise ValueError("scope 只能是 calendar（设备规则请用 devices.update）")
            if not isinstance(kv, dict) or not kv:
                raise ValueError("缺少 set 字段")
            if not os.path.isfile(CAL_CONF):
                raise IOError("配置文件不存在: " + CAL_CONF)
            with CMD_LOCK:
                write_conf(CAL_CONF, kv)
                out = ""
                if payload.get("apply", True):
                    rc, o, e = run(["sh", os.path.join(CAL_BIN, "build_calendar.sh")], timeout=300)
                    out = ((o or "") + (e or "")).strip()
                r = apply_devices("config:calendar")
            emit({"ok": True, "changed": list(kv.keys()), "apply_output": out,
                  "rebuild": r})
            return 0

        if cmd == "action":
            act = payload.get("action", "")
            with CMD_LOCK:
                if act in ("apply_all", "apply"):
                    r = apply_devices("action")
                elif act == "cal_build":
                    rc, o, e = run(["sh", os.path.join(CAL_BIN, "build_calendar.sh")],
                                   timeout=300)
                    r = {"ok": rc == 0, "output": ((o or "") + (e or "")).strip(),
                         "rc": rc, "calendar": calendar_info()}
                elif act == "cal_init":
                    rc, o, e = run(["sh", os.path.join(CAL_BIN, "build_calendar.sh"), "--init"],
                                   timeout=300)
                    r = {"ok": rc == 0, "output": ((o or "") + (e or "")).strip(),
                         "rc": rc, "calendar": calendar_info()}
                else:
                    raise ValueError("未知 action: " + act)
            r["action"] = act
            emit(r)
            return 0

        emit({"ok": False, "error": "未知 cmd: " + cmd})
        return 2

    except (ValueError, RuntimeError, IOError, OSError) as e:
        emit({"ok": False, "error": str(e)})
        return 1
    except Exception as e:
        emit({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})
        return 1


def main():
    code = cli_main(sys.argv[1:])
    sys.exit(code)


if __name__ == "__main__":
    main()
