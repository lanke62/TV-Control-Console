#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
nas_api.py —— 「TV管控台」NAS 端 API 服务

用途
    给安卓手机 APP 提供接口（常驻 HTTP 或命令行均可），用来：
      * 查看 校历 / 电视端 的实时状态
      * 修改开放时段（SCHOOL_WINDOWS / HOLIDAY_WINDOWS）
      * 修改功能开关（BLOCK_WAN / BLOCK_NAS / KICK_* ...）
      * 重建 / 联网更新校历，编辑寒暑假区间
      * 立即执行 projector_block.sh（不用等定时任务）
      * 查看日志

设计约束（重要）
    1. 只用 Python 标准库，群晖自带 python3 即可运行，无需 pip 安装任何东西。
    2. 兼容 Python 3.8（群晖 DSM 7.2 自带 python3 3.8.x）。
    3. 改配置文件时：先备份成 .bak，再按 KEY= 逐行精确替换，
       绝不整体重写文件（保留注释和结构）。
    4. 写文件统一用 UTF-8 + LF 换行（Windows 上传的 CRLF 会让 sh 脚本失效）。
    5. 所有会改系统状态的命令走同一把全局锁，避免并发打架。

部署位置： /volume1/docker/nas_tools/nas_api.py
配置文件： /volume1/docker/nas_tools/conf/api.conf
启动方式： nohup /bin/python3 /volume1/docker/nas_tools/nas_api.py >> /volume1/docker/nas_tools/log/api.log 2>&1 &
"""

import os
import re
import sys
import json
import time
import threading
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

VERSION = "1.0.0"

# ---------------------------------------------------------------- 路径

SELF_HOME = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SELF_CONF = os.path.join(SELF_HOME, "conf", "api.conf")
SELF_LOG = os.path.join(SELF_HOME, "log", "api.log")

DEFAULT_CAL_HOME = "/volume1/docker/child_calendar"
DEFAULT_PROJ_HOME = "/volume1/docker/projector_block"

CAL_HOME = DEFAULT_CAL_HOME
PROJ_HOME = DEFAULT_PROJ_HOME
CAL_BIN = ""
CAL_CONF = ""
CAL_DATA = ""
CAL_LOG = ""
CAL_TSV = ""
CAL_OVERRIDES = ""
PROJ_BIN = ""
PROJ_CONF = ""
PROJ_LOG = ""


def set_homes(cal=None, proj=None):
    """重设两个项目的根目录并刷新所有派生路径。

    命令行模式下由 App 传入实际路径（不同 NAS 上目录名可能不一样），
    所以这些不能写死成常量。
    """
    global CAL_HOME, PROJ_HOME
    global CAL_BIN, CAL_CONF, CAL_DATA, CAL_LOG, CAL_TSV
    global CAL_OVERRIDES
    global PROJ_BIN, PROJ_CONF, PROJ_LOG

    if cal:
        CAL_HOME = cal.rstrip("/")
    if proj:
        PROJ_HOME = proj.rstrip("/")

    CAL_BIN = os.path.join(CAL_HOME, "bin")
    CAL_CONF = os.path.join(CAL_HOME, "conf", "calendar.conf")
    CAL_DATA = os.path.join(CAL_HOME, "data")
    CAL_LOG = os.path.join(CAL_HOME, "log")
    CAL_TSV = os.path.join(CAL_DATA, "school_calendar.txt")
    CAL_OVERRIDES = os.path.join(CAL_HOME, "conf", "overrides.txt")

    PROJ_BIN = os.path.join(PROJ_HOME, "bin")
    PROJ_CONF = os.path.join(PROJ_HOME, "conf", "projector.conf")
    PROJ_LOG = os.path.join(PROJ_HOME, "log")


set_homes(DEFAULT_CAL_HOME, DEFAULT_PROJ_HOME)


def detect_homes():
    """按特征文件自动找两个项目的目录。

    不同人的部署目录名不一样（school-calendar / child_calendar / ...），
    写死路径迟早对不上，所以直接搜 conf 文件。
    """
    found_cal = []
    found_proj = []
    for root in ("/volume1", "/volume2"):
        if not os.path.isdir(root):
            continue
        rc, out, _e = run(["find", root, "-maxdepth", "5",
                           "-name", "calendar.conf", "-path", "*/conf/*"], timeout=25)
        if rc == 0:
            for ln in out.splitlines():
                ln = ln.strip()
                if ln:
                    found_cal.append(os.path.dirname(os.path.dirname(ln)))
        rc, out, _e = run(["find", root, "-maxdepth", "5",
                           "-name", "projector.conf", "-path", "*/conf/*"], timeout=25)
        if rc == 0:
            for ln in out.splitlines():
                ln = ln.strip()
                if ln:
                    found_proj.append(os.path.dirname(os.path.dirname(ln)))
        if found_cal or found_proj:
            break

    # 再补一层浅目录扫描，防止 find 不可用
    if not found_cal or not found_proj:
        for root in ("/volume1/docker", "/volume2/docker", "/volume1/homes"):
            if not os.path.isdir(root):
                continue
            try:
                names = sorted(os.listdir(root))
            except Exception:
                continue
            for n in names:
                d = os.path.join(root, n)
                if not os.path.isdir(d):
                    continue
                if not found_cal and os.path.isfile(
                        os.path.join(d, "conf", "calendar.conf")):
                    found_cal.append(d)
                if not found_proj and os.path.isfile(
                        os.path.join(d, "conf", "projector.conf")):
                    found_proj.append(d)

    cal = found_cal[0] if found_cal else ""
    proj = found_proj[0] if found_proj else ""
    return cal, proj, found_cal, found_proj

# ---------------------------------------------------------------- 全局

CFG = {"PORT": "8899", "BIND": "0.0.0.0", "TOKEN": "nastools"}
CMD_LOCK = threading.Lock()

# 这些键在 conf 里是"裸数字"（不带引号）
NUM_KEYS = {
    "BLOCK_WAN", "BLOCK_NAS", "KICK_PROJECTOR_SMB",
    "KICK_SESSIONS", "CHILDRENS_DAY", "FETCH_TIMEOUT",
}

WIN_RE = re.compile(r"^\d{2}:\d{2}-\d{2}:\d{2}(;\d{2}:\d{2}-\d{2}:\d{2})*$")
IP_RE = re.compile(r"^(\d{1,3}\.){3}\d{1,3}$")
NET_RE = re.compile(r"^(\d{1,3}\.){3}\d{1,3}/\d{1,2}$")
MAC_RE = re.compile(r"^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
YM_RE = re.compile(r"^\d{4}-\d{2}$")


# ---------------------------------------------------------------- 基础工具

def dec(b):
    if b is None:
        return ""
    if isinstance(b, bytes):
        return b.decode("utf-8", "replace")
    return b


def run(cmd, timeout=120, cwd=None):
    """执行命令，返回 (returncode, stdout, stderr)，永不抛异常"""
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout, cwd=cwd)
        return p.returncode, dec(p.stdout), dec(p.stderr)
    except subprocess.TimeoutExpired:
        return 124, "", "命令超时（>%ss）" % timeout
    except Exception as e:
        return 1, "", "执行异常: %s" % e


def hm(s):
    """HH:MM -> 分钟"""
    try:
        h, m = s.split(":")
        return int(h) * 60 + int(m)
    except Exception:
        return -1


def tail(path, n):
    if not os.path.isfile(path):
        return []
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except Exception:
        return []
    return lines[-n:]


def find_bin(name):
    for p in ("/sbin/" + name, "/usr/sbin/" + name, "/usr/syno/sbin/" + name):
        if os.path.exists(p):
            return p
    return name


# ---------------------------------------------------------------- 配置读写

def load_self_conf():
    cfg = dict(CFG)
    try:
        with open(SELF_CONF, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith("#") or "=" not in s:
                    continue
                k, v = s.split("=", 1)
                v = v.strip().strip('"').strip("'")
                if k.strip():
                    cfg[k.strip()] = v
    except Exception:
        pass
    if os.environ.get("NAS_API_PORT"):
        cfg["PORT"] = os.environ["NAS_API_PORT"]
    if os.environ.get("NAS_API_TOKEN"):
        cfg["TOKEN"] = os.environ["NAS_API_TOKEN"]
    return cfg


def split_value(rest):
    """把 KEY= 后面的内容切成 (值, 行尾注释)。

    必须正确处理 `KEY="值"   # 注释` 这种写法 —— 群晖上的 conf 通篇都是这样，
    早期版本只做 strip + 去首尾引号，结果读出来的值带着引号和一整段注释。
    """
    rest = rest.strip()
    if rest[:1] in ("'", '"'):
        q = rest[0]
        buf = []
        i = 1
        while i < len(rest):
            ch = rest[i]
            if ch == "\\" and i + 1 < len(rest):
                buf.append(rest[i + 1])
                i += 2
                continue
            if ch == q:
                break
            buf.append(ch)
            i += 1
        return "".join(buf), rest[i + 1:].strip()
    idx = rest.find("#")
    if idx < 0:
        return rest.strip(), ""
    return rest[:idx].strip(), rest[idx:].strip()


def read_conf(path):
    """把 sh 风格的 KEY=VALUE 配置读成 dict"""
    d = {}
    if not os.path.isfile(path):
        return d
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$", s)
            if not m:
                continue
            v, _comment = split_value(m.group(2))
            d[m.group(1)] = v
    return d


def trailing_comment(rest):
    """取出值后面跟着的 # 注释（不在引号内的才算）"""
    q = None
    for i, ch in enumerate(rest):
        if q:
            if ch == q:
                q = None
        elif ch in ("'", '"'):
            q = ch
        elif ch == "#":
            return rest[i:]
    return ""


def valid_hm(s):
    if not re.fullmatch(r"\d{2}:\d{2}", s):
        return False
    h = int(s[:2])
    m = int(s[3:])
    if m > 59 or h > 24:
        return False
    return not (h == 24 and m != 0)       # 允许结束时间写 24:00


def validate_kv(key, val):
    v = "" if val is None else str(val)
    if key in ("SCHOOL_WINDOWS", "HOLIDAY_WINDOWS"):
        v = v.strip()
        if v == "":
            return ""                      # 空 = 当天永不开放
        if not WIN_RE.match(v):
            raise ValueError("时段格式应为 HH:MM-HH:MM，多段用英文分号隔开")
        for seg in v.split(";"):
            a, b = seg.split("-")
            if not valid_hm(a) or not valid_hm(b):
                raise ValueError("时段 %s 不合法（小时 00-24，分钟 00-59，结束可用 24:00）" % seg)
            if hm(a) >= hm(b):
                raise ValueError("时段 %s 的结束时间必须晚于开始时间" % seg)
        return v
    if key == "BLOCK_MODE":
        v = v.strip()
        if v not in ("reject", "drop"):
            raise ValueError("BLOCK_MODE 只能是 reject 或 drop")
        return v
    if key == "STAGE":
        v = v.strip()
        if v not in ("primary", "high"):
            raise ValueError("STAGE 只能是 primary 或 high")
        return v
    if key in ("BLOCK_WAN", "BLOCK_NAS", "KICK_SESSIONS",
               "KICK_PROJECTOR_SMB", "CHILDRENS_DAY"):
        v = v.strip()
        if v not in ("0", "1"):
            raise ValueError("%s 只能是 0 或 1" % key)
        return v
    if key == "FETCH_TIMEOUT":
        v = v.strip()
        if not re.fullmatch(r"\d+", v):
            raise ValueError("FETCH_TIMEOUT 必须是整数秒")
        return v
    if key in ("PROJECTOR_IP", "NAS_IP"):
        v = v.strip()
        if v and not IP_RE.match(v):
            raise ValueError("%s 不是合法 IP 地址" % key)
        return v
    if key == "PROJECTOR_MAC":
        v = v.strip()
        if v and not MAC_RE.match(v):
            raise ValueError("MAC 格式应为 AA:BB:CC:DD:EE:FF")
        return v
    if key == "LAN_NET":
        v = v.strip()
        if v and not NET_RE.match(v):
            raise ValueError("网段格式应为 192.168.1.0/24")
        return v
    if key == "WAN_IF":
        v = v.strip()
        if v and not re.fullmatch(r"[A-Za-z0-9_.:@-]{1,32}", v):
            raise ValueError("网口名不合法")
        return v
    if key in ("RANGE_START", "RANGE_END"):
        v = v.strip()
        if not DATE_RE.match(v):
            raise ValueError("%s 必须是 YYYY-MM-DD" % key)
        return v
    if key == "VACATION_RANGES":
        v = v.strip()
        if v == "":
            return ""
        for seg in v.split(";"):
            seg = seg.strip()
            if not seg:
                continue
            p = seg.split("|")
            if len(p) != 3 or not DATE_RE.match(p[0].strip()) \
                    or not DATE_RE.match(p[1].strip()) or not p[2].strip():
                raise ValueError("寒暑假区间格式应为 起始日|结束日|名称，"
                                 "多段用英文分号隔开")
            if p[0].strip() > p[1].strip():
                raise ValueError("区间 %s 的起始日不能晚于结束日" % p[0])
        return v
    return v


def fmt_kv(key, val):
    v = "" if val is None else str(val)
    if key in NUM_KEYS:
        return "%s=%s" % (key, v.strip())
    esc = (v.replace("\\", "\\\\").replace('"', '\\"')
            .replace("$", "\\$").replace("`", "\\`"))
    return '%s="%s"' % (key, esc)


def write_conf(path, kv):
    """按 KEY= 逐行替换；找不到就追加到文件末尾。返回被改动的键列表。"""
    if not os.path.isfile(path):
        raise IOError("配置文件不存在: %s" % path)
    with open(path, "r", encoding="utf-8", newline="") as f:
        raw = f.read()
    # 从 Windows 传上来的 conf 常带 CRLF，会让 sh 脚本解析出错。
    # 既然要重写整个文件，顺手把全文归一化成 LF。
    raw = raw.replace("\r\n", "\n").replace("\r", "\n")
    try:
        with open(path + ".bak", "w", encoding="utf-8", newline="\n") as f:
            f.write(raw)
    except Exception:
        pass

    lines = raw.split("\n")
    changed = []
    for key, val in kv.items():
        v = validate_kv(key, val)
        newline = fmt_kv(key, v)
        pat = re.compile(r"^\s*" + re.escape(key) + r"\s*=")
        hit = -1
        for i, ln in enumerate(lines):
            if ln.strip().startswith("#"):
                continue
            if pat.match(ln):
                hit = i
                break
        if hit >= 0:
            m = re.match(r"^\s*[A-Za-z_][A-Za-z0-9_]*\s*=\s*(.*)$", lines[hit])
            tailc = trailing_comment(m.group(1)) if m else ""
            lines[hit] = newline + (("   " + tailc) if tailc else "")
        else:
            if lines and lines[-1].strip() == "":
                lines.insert(len(lines) - 1, newline)
            else:
                lines.append(newline)
        changed.append(key)

    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    return changed


# ---------------------------------------------------------------- 脚本封装

def sh_proj(args, timeout=90):
    return run(["sh", os.path.join(PROJ_BIN, "projector_block.sh")] + list(args), timeout)


def sh_build(init=False, timeout=300):
    args = ["--init"] if init else []
    return run(["sh", os.path.join(CAL_BIN, "build_calendar.sh")] + args, timeout)


def sh_isday(q, timeout=30):
    return run(["sh", os.path.join(CAL_BIN, "is_school_day.sh"), q], timeout)


def parse_show(out):
    """把 --show 的中文输出解析成 dict（只按第一个冒号切）"""
    d = {}
    for line in out.splitlines():
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        d[k.strip()] = v.strip()
    return d


def clean_win(v):
    if not v:
        return ""
    v = re.sub(r"（.*?）", "", v).strip()
    return "" if v in ("无", "无，视为封禁") else v


def iptables_refs(binpath, chain):
    """返回引用了 chain 的规则行"""
    res = []
    for tbl in ("INPUT", "FORWARD"):
        rc, out, err = run([binpath, "-S", tbl], timeout=20)
        if rc != 0:
            continue
        for ln in out.splitlines():
            if chain in ln:
                res.append(ln.strip())
    return res


def iptables_pkts(binpath, chain):
    rc, out, err = run([binpath, "-L", chain, "-n", "-v", "-x"], timeout=20)
    if rc != 0:
        return None
    total = 0
    for ln in out.splitlines()[2:]:
        parts = ln.split()
        if parts and parts[0].isdigit():
            total += int(parts[0])
    return total


# ---------------------------------------------------------------- 业务：状态

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


def get_status():
    cal = read_conf(CAL_CONF)
    proj = read_conf(PROJ_CONF)

    st = {
        "ok": True,
        "version": VERSION,
        "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
        "date": time.strftime("%Y-%m-%d"),
    }

    # ---- 校历相关字段：直接来自 calendar.conf ----
    # （media 密码管控已整体移除，校历字段一律直接读 conf，挂到 calendar 对象里。）
    cal_fields = {
        "school_windows": cal.get("SCHOOL_WINDOWS", ""),
        "holiday_windows": cal.get("HOLIDAY_WINDOWS", ""),
        "stage": cal.get("STAGE", "primary"),
        "vacation_ranges": cal.get("VACATION_RANGES", ""),
        "childrens_day": cal.get("CHILDRENS_DAY", "1"),
        "kick_sessions": cal.get("KICK_SESSIONS", "0"),
        "fetch_timeout": cal.get("FETCH_TIMEOUT", "15"),
        "target_user": cal.get("TARGET_USER", ""),
        "range_start": cal.get("RANGE_START", ""),
        "range_end": cal.get("RANGE_END", ""),
    }

    # ---- 电视端 ----
    rc2, out2, err2 = sh_proj(["--show"])
    if rc2 != 0 and not out2:
        st["projector"] = {"error": (err2 or out2 or "projector_block.sh 执行失败").strip()}
    else:
        p = parse_show(out2)
        ipt = find_bin("iptables")
        ip6t = find_bin("ip6tables")
        st["projector"] = {
            "want": p.get("应置状态", ""),
            "last": p.get("上次状态", ""),
            "override": p.get("强制模式", ""),
            "windows": clean_win(p.get("适用窗口", "")),
            "day_type": p.get("今天类型", ""),
            "now": p.get("当前时间", ""),
            "ip": proj.get("PROJECTOR_IP", ""),
            "mac": proj.get("PROJECTOR_MAC", ""),
            "nas_ip": proj.get("NAS_IP", ""),
            "wan_if": proj.get("WAN_IF", ""),
            "lan_net": proj.get("LAN_NET", ""),
            "block_wan": proj.get("BLOCK_WAN", "1"),
            "block_nas": proj.get("BLOCK_NAS", "1"),
            "block_mode": proj.get("BLOCK_MODE", "reject"),
            "kick_smb": proj.get("KICK_PROJECTOR_SMB", "1"),
            "pkts4": iptables_pkts(ipt, "PROJ_BLOCK"),
            "pkts6": iptables_pkts(ip6t, "PROJ_BLOCK6"),
            "refs4": iptables_refs(ipt, "PROJ_BLOCK"),
            "refs6": iptables_refs(ip6t, "PROJ_BLOCK6"),
        }

    # ---- 校历 ----
    st["calendar"] = calendar_info()
    st["calendar"].update(cal_fields)
    return st


def get_month(ym):
    if not YM_RE.match(ym or ""):
        raise ValueError("月份格式应为 YYYY-MM")
    rc, out, err = sh_isday(ym, timeout=40)
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
    ovr = {}
    for it in read_overrides():
        if it.get("enabled") and it.get("date", "").startswith(ym):
            ovr[it["date"]] = {"flag": it.get("flag", ""), "note": it.get("note", "")}
    for d in days:
        d["override"] = ovr.get(d["date"], None)
    return {"ok": True, "ym": ym, "days": days}


def read_overrides():
    items = []
    if not os.path.isfile(CAL_OVERRIDES):
        return items
    with open(CAL_OVERRIDES, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            s = line.strip()
            if not s:
                continue
            if s.startswith("#"):
                continue
            p = s.split("|")
            items.append({
                "enabled": True,
                "date": p[0].strip() if len(p) > 0 else "",
                "flag": p[1].strip() if len(p) > 1 else "",
                "note": p[2].strip() if len(p) > 2 else "",
            })
    return items


def set_override(date, flag, note):
    if not DATE_RE.match(date or ""):
        raise ValueError("日期格式应为 YYYY-MM-DD")
    if flag not in ("0", "1"):
        raise ValueError("flag 只能是 0（放假）或 1（上学）")
    note = (note or "").replace("|", "/").replace("\n", " ").strip()

    lines = []
    if os.path.isfile(CAL_OVERRIDES):
        with open(CAL_OVERRIDES, "r", encoding="utf-8", newline="") as f:
            lines = f.read().replace("\r\n", "\n").replace("\r", "\n").split("\n")
    try:
        with open(CAL_OVERRIDES + ".bak", "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines))
    except Exception:
        pass

    newline = "%s|%s|%s" % (date, flag, note)
    hit = -1
    for i, ln in enumerate(lines):
        if ln.strip().startswith("#"):
            continue
        p = ln.split("|")
        if p and p[0].strip() == date:
            hit = i
            break
    if hit >= 0:
        lines[hit] = newline
    else:
        while lines and lines[-1].strip() == "":
            lines.pop()
        lines.append(newline)
        lines.append("")

    with open(CAL_OVERRIDES, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    return newline


def del_override(date):
    if not os.path.isfile(CAL_OVERRIDES):
        return False
    with open(CAL_OVERRIDES, "r", encoding="utf-8", newline="") as f:
        lines = f.read().replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out = []
    removed = False
    for ln in lines:
        if not ln.strip().startswith("#"):
            p = ln.split("|")
            if p and p[0].strip() == date:
                removed = True
                continue
        out.append(ln)
    if removed:
        with open(CAL_OVERRIDES, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(out))
    return removed


# ---------------------------------------------------------------- 业务：动作

def act_apply_all():
    return act_proj_apply()


def act_proj_apply():
    rc, o, e = sh_proj([])
    return {"ok": rc == 0, "output": (o + e).strip(), "rc": rc}


def act_proj_mode(mode):
    if mode not in ("on", "off", "auto"):
        raise ValueError("mode 只能是 on/off/auto")
    rc, o, e = sh_proj(["--" + mode])
    return {"ok": rc == 0, "output": (o + e).strip(), "rc": rc}


def act_build(init):
    rc, o, e = sh_build(init)
    out = (o or "") + (("\n[stderr]\n" + e) if e.strip() else "")
    return {"ok": rc == 0, "output": out.strip(), "rc": rc,
            "calendar": calendar_info()}


def do_config(body):
    """改配置 + 可选立即生效。HTTP 与命令行模式共用这一份实现。"""
    scope = body.get("scope", "")
    kv = body.get("set") or {}
    do_apply = bool(body.get("apply", True))

    if scope == "calendar":
        path = CAL_CONF
    elif scope == "projector":
        path = PROJ_CONF
    else:
        raise ValueError("scope 必须是 calendar 或 projector")
    if not isinstance(kv, dict) or not kv:
        raise ValueError("缺少 set 字段")
    if not os.path.isfile(path):
        raise IOError("配置文件不存在: " + path)
    if not os.access(path, os.W_OK):
        raise IOError("配置文件不可写（未以 root 运行?）: " + path)

    with CMD_LOCK:
        changed = write_conf(path, kv)
        out = ""
        if do_apply:
            out = act_apply_all().get("output", "")
    return {"ok": True, "changed": changed, "apply_output": out}


def do_action(body):
    """执行一个动作。HTTP 与命令行模式共用这一份实现。"""
    act = body.get("action", "")
    with CMD_LOCK:
        if act == "apply_all":
            r = act_apply_all()
        elif act == "proj_apply":
            r = act_proj_apply()
        elif act in ("proj_on", "proj_off", "proj_auto"):
            r = act_proj_mode(act.replace("proj_", ""))
        elif act == "cal_build":
            r = act_build(False)
        elif act == "cal_init":
            r = act_build(True)
        else:
            raise ValueError("未知 action: " + act)
    r["action"] = act
    return r


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "NASToolsAPI/" + VERSION
    protocol_version = "HTTP/1.1"

    # ---------- 底层 ----------
    def _body(self):
        n = 0
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except Exception:
            n = 0
        if n <= 0:
            return {}
        raw = self.rfile.read(n)
        try:
            return json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            return {}

    def _send(self, code, obj):
        try:
            body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        except Exception:
            body = b'{"ok":false,"error":"serialize failed"}'
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def _token_ok(self):
        tok = self.headers.get("X-Token") or ""
        if not tok:
            q = parse_qs(urlparse(self.path).query)
            tok = (q.get("token") or [""])[0]
        return tok == CFG["TOKEN"]

    def log_message(self, fmt, *args):
        try:
            msg = "[%s] %s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"),
                                    self.address_string(), fmt % args)
        except Exception:
            msg = "[%s] %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), self.path)
        sys.stderr.write(msg)
        sys.stderr.flush()

    # ---------- GET ----------
    def do_GET(self):
        u = urlparse(self.path)
        p = u.path.rstrip("/")
        q = parse_qs(u.query)

        if p in ("/", "/api/health"):
            ok = self._token_ok()
            info = {"ok": True, "service": "nas_tools_api",
                    "version": VERSION, "auth_ok": ok}
            if ok:
                info["cal_home"] = CAL_HOME
                info["proj_home"] = PROJ_HOME
                info["paths_ok"] = {
                    "calendar_conf": os.path.isfile(CAL_CONF),
                    "projector_conf": os.path.isfile(PROJ_CONF),
                    "projector_block": os.path.isfile(os.path.join(PROJ_BIN, "projector_block.sh")),
                }
            return self._send(200, info)

        if not self._token_ok():
            return self._send(401, {"ok": False, "error": "token 不正确", "code": 401})

        try:
            if p == "/api/status":
                return self._send(200, get_status())

            if p == "/api/calendar":
                ym = (q.get("ym") or [""])[0]
                return self._send(200, get_month(ym))

            if p == "/api/overrides":
                return self._send(200, {"ok": True, "items": read_overrides()})

            if p == "/api/logs":
                try:
                    n = int((q.get("n") or ["80"])[0])
                except Exception:
                    n = 80
                n = max(1, min(n, 500))
                return self._send(200, {"ok": True, "logs": {
                    "校历构建": tail(os.path.join(CAL_LOG, "build.log"), n),
                    "电视端": tail(os.path.join(PROJ_LOG, "projector.log"), n),
                    "API 服务": tail(SELF_LOG, n),
                }})

            return self._send(404, {"ok": False, "error": "no such endpoint: " + p})
        except Exception as e:
            return self._send(500, {"ok": False, "error": "%s: %s" % (type(e).__name__, e)})

    # ---------- POST ----------
    def do_POST(self):
        u = urlparse(self.path)
        p = u.path.rstrip("/")

        if not self._token_ok():
            return self._send(401, {"ok": False, "error": "token 不正确", "code": 401})

        body = self._body()

        # 改配置 + 可选立即生效（加锁，避免和别的命令抢）
        if p == "/api/config":
            try:
                return self._send(200, do_config(body))
            except ValueError as e:
                return self._send(400, {"ok": False, "error": str(e)})
            except Exception as e:
                return self._send(500, {"ok": False, "error": "%s: %s" % (type(e).__name__, e)})

        # 各类动作
        if p == "/api/action":
            try:
                return self._send(200, do_action(body))
            except (ValueError, RuntimeError) as e:
                return self._send(400, {"ok": False, "error": str(e)})
            except Exception as e:
                return self._send(500, {"ok": False, "error": "%s: %s" % (type(e).__name__, e)})

        # 单日覆盖
        if p == "/api/overrides":
            op = body.get("op", "")
            date = (body.get("date") or "").strip()
            try:
                with CMD_LOCK:
                    if op == "set":
                        line = set_override(date, str(body.get("flag", "")).strip(),
                                            body.get("note", ""))
                        r = act_build(False)
                        return self._send(200, {"ok": True, "line": line,
                                                "build_output": r.get("output", ""),
                                                "items": read_overrides()})
                    elif op == "del":
                        removed = del_override(date)
                        r = act_build(False)
                        return self._send(200, {"ok": True, "removed": removed,
                                                "build_output": r.get("output", ""),
                                                "items": read_overrides()})
                    elif op == "list":
                        return self._send(200, {"ok": True, "items": read_overrides()})
                    else:
                        return self._send(400, {"ok": False, "error": "op 必须是 set/del/list"})
            except ValueError as e:
                return self._send(400, {"ok": False, "error": str(e)})
            except Exception as e:
                return self._send(500, {"ok": False, "error": "%s: %s" % (type(e).__name__, e)})

        return self._send(404, {"ok": False, "error": "no such endpoint: " + p})


# ---------------------------------------------------------------- 命令行模式

# 命令行模式下 stdout 只有一行结果，且带这个前缀，
# 因为 sudo -S 会把 "[sudo] password for xxx:" 之类的提示混进输出里，
# App 端靠前缀把真正的 JSON 捞出来。
OUT_MARK = "__NAS_JSON__ "


def emit(obj):
    sys.stdout.write(OUT_MARK + json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def do_probe():
    """环境探测：给 App 首次连接时用（找目录、看权限、看脚本版本）"""
    cal, proj, all_cal, all_proj = detect_homes()
    if cal or proj:
        set_homes(cal, proj)
    try:
        euid = os.geteuid()
    except Exception:
        euid = -1
    return {
        "ok": True,
        "version": VERSION,
        "python": sys.version.split()[0],
        "euid": euid,
        "is_root": euid == 0,
        "cal_home": cal,
        "proj_home": proj,
        "candidates_cal": all_cal,
        "candidates_proj": all_proj,
        "paths_ok": {
            "calendar_conf": bool(cal) and os.path.isfile(CAL_CONF),
            "projector_conf": bool(proj) and os.path.isfile(PROJ_CONF),
            "projector_block": bool(proj) and os.path.isfile(
                os.path.join(PROJ_BIN, "projector_block.sh")),
        },
    }


def cli_main(argv):
    """命令行入口。

    App 通过 SSH 调用，形如：
        sudo -S -p '' python3 /volume1/docker/nas_tools/nas_api.py \
            --json '{"cmd":"status"}' --cal-home X --proj-home Y
    """
    payload = None
    cal_home = None
    proj_home = None

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
        if a == "--cal-home" and i + 1 < len(argv):
            cal_home = argv[i + 1]
            i += 2
            continue
        if a == "--proj-home" and i + 1 < len(argv):
            proj_home = argv[i + 1]
            i += 2
            continue
        if a in ("--version", "-V"):
            sys.stdout.write(VERSION + "\n")
            return 0
        if a in ("--help", "-h"):
            sys.stdout.write(__doc__ or "nas_api.py")
            return 0
        i += 1

    if payload is None:
        emit({"ok": False, "error": "缺少 --json 参数"})
        return 2

    # 目录优先用 App 指定的，其次自动探测
    if not (cal_home and proj_home):
        d_cal, d_proj, _a, _b = detect_homes()
        cal_home = cal_home or d_cal
        proj_home = proj_home or d_proj
    set_homes(cal_home, proj_home)

    cmd = payload.get("cmd", "")

    try:
        if cmd == "probe":
            emit(do_probe())
            return 0

        if cmd == "status":
            emit(get_status())
            return 0

        if cmd == "calendar":
            emit(get_month(payload.get("ym", "")))
            return 0

        if cmd == "overrides":
            op = payload.get("op", "list")
            with CMD_LOCK:
                if op == "list":
                    emit({"ok": True, "items": read_overrides()})
                elif op == "set":
                    line = set_override(str(payload.get("date", "")).strip(),
                                        str(payload.get("flag", "")).strip(),
                                        payload.get("note", ""))
                    r = act_build(False)
                    emit({"ok": True, "line": line,
                          "build_output": r.get("output", ""),
                          "items": read_overrides()})
                elif op == "del":
                    removed = del_override(str(payload.get("date", "")).strip())
                    r = act_build(False)
                    emit({"ok": True, "removed": removed,
                          "build_output": r.get("output", ""),
                          "items": read_overrides()})
                else:
                    emit({"ok": False, "error": "op 必须是 set/del/list"})
                    return 2
            return 0

        if cmd == "logs":
            try:
                n = int(payload.get("n", 80))
            except Exception:
                n = 80
            n = max(1, min(n, 500))
            emit({"ok": True, "logs": {
                "校历构建": tail(os.path.join(CAL_LOG, "build.log"), n),
                "电视端": tail(os.path.join(PROJ_LOG, "projector.log"), n),
            }})
            return 0

        if cmd == "config":
            emit(do_config(payload))
            return 0

        if cmd == "action":
            emit(do_action(payload))
            return 0

        emit({"ok": False, "error": "未知 cmd: " + cmd})
        return 2

    except (ValueError, RuntimeError, IOError, OSError) as e:
        emit({"ok": False, "error": str(e)})
        return 1
    except Exception as e:
        emit({"ok": False, "error": "%s: %s" % (type(e).__name__, e)})
        return 1


# ---------------------------------------------------------------- 入口

def serve_main():
    global CFG
    CFG = load_self_conf()

    for d in (os.path.join(SELF_HOME, "log"), os.path.join(SELF_HOME, "conf")):
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:
            pass

    logf = None
    try:
        logf = open(SELF_LOG, "a", encoding="utf-8", newline="\n")
        sys.stderr = logf
    except Exception:
        pass

    port = int(CFG.get("PORT", "8899"))
    bind = CFG.get("BIND", "0.0.0.0")

    banner = []
    banner.append("=" * 58)
    banner.append(" nas_tools API %s 启动" % VERSION)
    banner.append(" 时间     : %s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    banner.append(" 监听     : %s:%d" % (bind, port))
    banner.append(" python   : %s" % sys.version.split()[0])
    banner.append(" euid     : %s %s" % (
        os.geteuid(), "" if os.geteuid() == 0 else "  <== 警告: 非 root，iptables/改密码会失败"))
    banner.append(" 校历目录 : %s %s" % (CAL_HOME, "OK" if os.path.isdir(CAL_HOME) else "缺失"))
    banner.append(" 投影目录 : %s %s" % (PROJ_HOME, "OK" if os.path.isdir(PROJ_HOME) else "缺失"))
    banner.append("=" * 58)
    sys.stderr.write("\n".join(banner) + "\n")
    sys.stderr.flush()
    print("\n".join(banner))

    srv = ThreadingHTTPServer((bind, port), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


def main():
    # 有 --json / --version 就走命令行模式，否则常驻 HTTP 服务
    for a in sys.argv[1:]:
        if a in ("--json", "--version", "-V", "--help", "-h"):
            return cli_main(sys.argv[1:])
    return serve_main()


if __name__ == "__main__":
    sys.exit(main() or 0)
