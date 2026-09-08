#!/bin/sh
# ============================================================
#  media_access.sh - 按"今天类型 + 当前时间"自行决定 Media 账户开启/封闭
#
#  【v2 变更】新增"手动强制覆盖"：
#     data/.media_override 内容为 open / close 时，无视校历时段，一直保持该状态，
#     直到该文件被删除（APP 的"恢复自动"就是删它）。
#     文件不存在时，行为与 v1 完全一致 —— 零副作用，可安全替换。
#
#  开放/封闭时段写在 conf/calendar.conf 的 SCHOOL_WINDOWS / HOLIDAY_WINDOWS,
#  改时间段只需编辑 conf, 无需改动本脚本, 下次触发(或手动运行)即生效。
#
#  语义:
#    开放时段 -> 使用 PW_HOLIDAY (真密码, 孩子可登录)
#    封闭时段 -> 使用 PW_SCHOOL  (假密码, 登录失败, 拦截)
#    未知类型 -> 封闭 (最安全)
#
#  运行时自判目标状态, 仅在目标与上次记录不一致时才改密码 (幂等)。
#  群晖任务计划以非登录 shell 运行, 故 synouser 用绝对路径兜底。
#
#  用法:
#    sh media_access.sh            # 按当前时间判定并设置
#    sh media_access.sh --dry-run  # 仅打印将要做什么, 不改密码 (用于验证)
#    sh media_access.sh --show     # 打印今天类型/是否在窗口/当前状态
#
#  群晖任务计划(以 root 身份)每小时整点:
#    sh /volume1/docker/child_calendar/bin/media_access.sh
# ============================================================
set -u

SYNOUSER="/usr/syno/sbin/synouser"; command -v synouser  >/dev/null 2>&1 && SYNOUSER="synouser"
SMBSTATUS="/usr/syno/sbin/smbstatus"; command -v smbstatus >/dev/null 2>&1 && SMBSTATUS="smbstatus"

BASE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CONF="$BASE/conf/calendar.conf"
[ -r "$CONF" ] || { echo "ERROR: 找不到 $CONF" >&2; exit 1; }
. "$CONF"

LOGDIR="$BASE/log"; STATE="$BASE/data/.media_state"
mkdir -p "$LOGDIR" "$BASE/data"
LOG="$LOGDIR/switch.log"
log(){ echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG"; }

# 解析命令行: --dry-run 只演示不改密码; --show 打印诊断信息
DRY_RUN=0; SHOW=0
for a in "$@"; do
  case "$a" in
    --dry-run) DRY_RUN=1 ;;
    --show)    SHOW=1 ;;
  esac
done

# HH:MM -> 距零点分钟数 (10# 强制十进制, 防 08/09 被当八进制报错)
hm2min(){ echo $(( 10#${1%:*} * 60 + 10#${1#*:} )); }

# 判断 $1(分钟数) 是否落在窗口串 $2 的任一 "HH:MM-HH:MM" 区间内
#   返回 0=在窗口内(应开放)  1=不在
in_windows(){
  _now=$1; _win=$2
  [ -z "$_win" ] && return 1
  _ifs=$IFS; IFS=';'
  set -- $_win
  IFS=$_ifs
  for seg in "$@"; do
    [ -z "$seg" ] && continue
    _s=${seg%-*}; _e=${seg#*-}
    _sm=$(hm2min "$_s"); _em=$(hm2min "$_e")
    if [ "$_now" -ge "$_sm" ] && [ "$_now" -lt "$_em" ]; then
      return 0
    fi
  done
  return 1
}

NOW_HHMM=$(date +%H:%M)
NOW=$(hm2min "$NOW_HHMM")
DAY=$(sh "$BASE/bin/is_school_day.sh" "$(date +%Y-%m-%d)" 2>/dev/null)

# 按日期类型选取对应窗口 (未知类型 / 未配置 视为无开放窗口, 最安全)
case "$DAY" in
  school)  WIN="${SCHOOL_WINDOWS:-}" ;;
  holiday) WIN="${HOLIDAY_WINDOWS:-}" ;;
  *)       WIN="" ;;
esac

# ---- v2: 手动强制覆盖 (手机 APP 写入 data/.media_override, 内容 open/close) ----
# 文件不存在 = 按校历自动 (和 v1 行为完全一致)
MOVRF="$BASE/data/.media_override"
if [ -f "$MOVRF" ]; then
  MOVR=$(tr -d ' \t\r\n' < "$MOVRF" 2>/dev/null)
else
  MOVR=""
fi

if [ "$MOVR" = "open" ] || [ "$MOVR" = "close" ]; then
  WANT=$MOVR
elif in_windows "$NOW" "$WIN"; then
  WANT=open
else
  WANT=close
fi

if [ "$SHOW" = "1" ]; then
  echo "今天类型 : $DAY"
  echo "当前时间 : $NOW_HHMM"
  echo "适用窗口 : ${WIN:-（无）}"
  echo "应置状态 : $WANT"
  echo "强制模式 : ${MOVR:-（无，按校历自动）}"
  echo "上次状态 : $(cat "$STATE" 2>/dev/null || echo 无记录)"
  echo "开放密码 : PW_HOLIDAY=$PW_HOLIDAY    封闭密码 : PW_SCHOOL=$PW_SCHOOL"
  exit 0
fi

LAST=$(cat "$STATE" 2>/dev/null)
if [ "$LAST" = "$WANT" ]; then
  log "$DAY $NOW_HHMM 应为 $WANT, 状态未变, 跳过"
  [ "$DRY_RUN" = "1" ] && echo "[dry-run] 状态未变($WANT), 无需操作"
  exit 0
fi

# 开放=真密码(PW_HOLIDAY), 封闭=假密码(PW_SCHOOL)
if [ "$WANT" = "open" ]; then PW="$PW_HOLIDAY"; else PW="$PW_SCHOOL"; fi

if [ "$DRY_RUN" = "1" ]; then
  echo "[dry-run] $DAY $NOW_HHMM 将把 $TARGET_USER 切换为 $WANT (密码 $PW)"
  exit 0
fi

if "$SYNOUSER" --setpw "$TARGET_USER" "$PW" >>"$LOG" 2>&1; then
  echo "$WANT" > "$STATE"
  log "$DAY $NOW_HHMM 已切换 Media 为 $WANT ($([ "$WANT" = open ] && echo 可用 || echo 不可用))"
else
  log "ERROR: $SYNOUSER --setpw $TARGET_USER 失败 (是否以 root 运行?)"
  exit 1
fi

# 封闭时可选踢掉已建立的 SMB 会话 (默认关闭, 见 calendar.conf 的 KICK_SESSIONS)
if [ "$WANT" = "close" ] && [ "${KICK_SESSIONS:-0}" = "1" ]; then
  for pid in $("$SMBSTATUS" -b -u "$TARGET_USER" 2>/dev/null | awk '/^[0-9]+/{print $1}' | sort -u); do
    kill "$pid" 2>/dev/null && log "已断开 $TARGET_USER 的会话进程 $pid"
  done
fi

exit 0
