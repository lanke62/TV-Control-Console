# 家庭联网控制（HomeNet Control）

手机上管理家里每台被控设备的联网规则：**按 MAC 认设备**，每台设备一套开放时段，
每个时段里再分别决定「外网」和「内网（群晖）」放不放行。

- **源码仓库**：https://github.com/lanke62/TV-Control-Console （public，main 分支）
  > 仓库名还是旧的（GitHub 改名会废掉已有 remote），App 与文档已统一为「家庭联网控制」。
- **最低系统**：Android 10（minSdk 29），targetSdk 34。
- **当前版本**：1.4.0（versionCode 11）。

---

## 一、它是怎么工作的

手机上不装任何常驻服务。App 用 JSch SSH 登录群晖管理员账号，`sudo` 执行
`nas_api.py`（打包在 assets 里，首次经 SFTP/exec 管道传到 NAS 的
`/volume1/docker/nas_tools/`），靠命令行 `--json` 收发结果。

```
手机 App（Java）
  └─ SSH 登录（administrators 组账号 + 密码）
       └─ sudo python3 /volume1/docker/nas_tools/nas_api.py --json '{...}' --home … --cal-home …
            ├─ 读写 conf/devices.json（设备与规则）
            ├─ iptables / ip6tables 全量重建 HNC_* 链（按 MAC 匹配）
            └─ 读 child_calendar 的校历数据，判定今天「上学日 / 放假日」
```

**NAS 端没有常驻进程，但有一条定时任务**（DSM 任务计划，root，开机 + 每 10 分钟）：

```sh
/bin/python3 /volume1/docker/nas_tools/nas_api.py --apply
```

它负责两件事：**重启后把规则装回去**，以及**跨零点切换「上学日/放假日」那套时段**。
不建这条任务，规则在 NAS 重启后会消失（防火墙规则本来就只存在内存里），
而且过了零点还会按昨天的校历跑。

## 二、四个页签

| 页签 | 能干什么 |
|---|---|
| **控制** | 顶部选被控设备 → 看该设备状态（此刻外网/内网是放是封、已拦截包数）→ 三个模式键（长期放行 / 校历模式 / 长期封禁）→ 上学日时段 + 放假日时段（每段带「外网」「内网」两个开关）→ 保存并立即生效 |
| **设备** | 被控设备列表，**增 / 删**都在这里；增删会立即同步 NAS 并重建防火墙规则，删除时清掉该设备的全部残留规则 |
| **校历** | 月历查看（只读）、寒暑假起止、学段切换（小学/高中）、重建校历、联网更新节假日 |
| **设置** | SSH 连接配置（主机/端口/账号/密码）、连接状态、NAS 日志、SSH 连接诊断 |

### 规则语义

- **身份是 MAC**：`iptables -m mac --mac-source`，设备 IP 变了、DHCP 重新分配都不影响，
  不需要固定 IP。代价是只在**同一二层网段**内有效（设备必须把群晖当网关）。
- **外网** = `FORWARD` 链里按 MAC 丢包（只对「去往局域网段以外」的流量生效，内网互通不误伤）。
- **内网** = `INPUT` 链里按 MAC 丢包（设备访问群晖本身：SMB / DLNA / DSM 网页）。
- **时段之外一律全封**（内网 + 外网都断），时段之内按该时段自己的两个开关决定。
- 时段用 `-m time` 交给内核按点生效，不靠轮询；跨零点时段（如 22:00-01:00）自动拆成两段。
- 「内网封禁」时会**给 DNS（53 端口）留例外**，否则设备把群晖当 DNS 时，封内网会顺带把外网也弄断。
- 校历是**全局共享**的（`child_calendar` 项目独立运行），设备只决定用哪套时段。

### 待保存语义

模式、时段、开关的改动都只先记在手机上，点「保存并立即生效」才下发；
下拉刷新或**切换设备**会丢弃未保存改动，重新读 NAS 上的真实配置。

## 三、目录结构

```
Network Control/
├── 家庭联网控制-v1.4.0.apk        <- 手机装这个（APK 不入库）
├── 部署手册.md                     <- NAS 端部署 + 定时任务
├── SSH直连使用说明.md              <- 连接配置与排查
├── nas/                           <- NAS 端源码
│   └── nas_api.py                 设备存储 + iptables 引擎 + CLI（只用标准库，兼容 Python 3.8）
├── keystore/nastools.jks          <- 自签名密钥，不入库（口令 nastools）
└── app/                           Android 工程（Java 17 / AGP 8.7.3 / compileSdk 34）
```

NAS 上的落点（文档里的真实路径，写代码照这个）：

| 内容 | 路径 |
|---|---|
| 脚本 | `/volume1/docker/nas_tools/nas_api.py` |
| 设备与规则 | `/volume1/docker/nas_tools/conf/devices.json` |
| 网络参数 | `/volume1/docker/nas_tools/conf/net.conf` |
| 运行日志 | `/volume1/docker/nas_tools/log/netctl.log` |
| 校历项目（只读） | `/volume1/docker/child_calendar/` |

**不入库**：`*.apk`、`*.tar.gz`、`keystore/*.jks`、`local.properties`、`.gradle/`、`build/`、`.workbuddy/`。

## 四、重新构建

```bat
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20+8
set ANDROID_HOME=C:\Users\Lanke62\AppData\Local\Android\Sdk
gradlew.bat assembleRelease
```

产物是**未签名**的 `app/build/outputs/apk/release/app-release-unsigned.apk`，签名见文末。

`nas/nas_api.py` 会被 gradle 的 `copyNasScript` 自动拷进 `app/src/main/assets/`，
所以只改 `nas/` 下那份，不要手改 assets。

## 五、NAS 端命令（调试用）

```sh
# 看设备清单
python3 /volume1/docker/nas_tools/nas_api.py --json '{"cmd":"devices","op":"list"}'

# 加一台设备（MAC 为身份）
python3 /volume1/docker/nas_tools/nas_api.py --json \
  '{"cmd":"devices","op":"add","name":"VIVO","mac":"D0:96:EA:0E:00:07"}'

# 改规则（mode: auto/open/block；时段每段 s/e/wan/lan）
python3 /volume1/docker/nas_tools/nas_api.py --json \
  '{"cmd":"devices","op":"update","id":"d1","set":{"mode":"auto",
    "school":[{"s":"08:00","e":"12:00","wan":1,"lan":1}],
    "holiday":[{"s":"18:00","e":"21:00","wan":1,"lan":0}]}}'

# 删设备（会立即清掉它的链与跳转）
python3 /volume1/docker/nas_tools/nas_api.py --json '{"cmd":"devices","op":"del","id":"d1"}'

# 只重建规则（定时任务用的就是这条）
python3 /volume1/docker/nas_tools/nas_api.py --apply

# 看当前状态
python3 /volume1/docker/nas_tools/nas_api.py --json '{"cmd":"status"}'
```

规则链命名：每台设备两条 —— `HNC_I_<id>`（INPUT/内网）、`HNC_F_<id>`（FORWARD/外网）。
**每次 apply 都是「先清掉所有 `HNC_*` 链 → 再按 devices.json 重建」**，
所以删设备不会留残留规则，手工改坏了也可以用一次 `--apply` 复原。

## 六、安全提醒

- App 只在局域网内 SSH 登录群晖，NAS 上不开任何新端口。**不要把 SSH 端口映射到公网**。
- 群晖有 auto-block：密码连错几次，手机 IP 会被临时封禁。
- DSM 6+ **禁止 root 直接 SSH 登录**，只能用 administrators 组账号登录再 `sudo`。
- **签名密钥 `keystore/nastools.jks` 绝不要提交**：拿到它 + 口令就能签冒名升级包覆盖你的 App。
- 本仓库是 public，别把内网 IP / 口令 / token 写进代码或文档。

---

## 七、更新记录

### 2026-10-19（v1.4.0：改名 + 多设备 + MAC 管控 + 执行层重写）

- **改名**：App 名 / 标题 / 文档统一为「家庭联网控制」（原 TV管控台）。
- **执行层重写**：`projector_block`（已废弃删除）不再被调用。封禁逻辑整体搬进 `nas_api.py`：
  按 MAC 建 `HNC_*` 链、`-m time` 控制时段、全量重建保证无残留。
- **多设备**：新增 `conf/devices.json`，每台设备独立 `mode` + 上学日/放假日两套时段。
- **每段两个开关**：时段内可分别放行/封禁「外网」与「内网（群晖）」；时段外一律全封。
- **页面**：3 页 → 4 页（控制 / 设备 / 校历 / 设置）；「控制」页顶部选设备，
  「设备」页增删；TV端参数页改成全局「网络参数」（不再有单设备 IP/MAC）。
- **脚本落点**：`/tmp/nas_tools/` → `/volume1/docker/nas_tools/`（定时任务要用持久路径）。
- **定时任务**：新增 DSM 任务计划（root，开机 + 每 10 分钟）跑 `--apply`，
  解决重启丢规则与跨零点不切校历两个问题。

### 2026-09-08 之前

见 git 历史（v1.3.2 及更早：纯 SSH 直连、单台电视端、Media 账号管控已移除）。

---

## 构建与签名

仓库里不含签名密钥，也不含任何口令。Gradle 只负责编译，签名用 `apksigner` 手工做。

```bash
gradlew.bat assembleRelease

"<Android SDK>/build-tools/34.0.0/apksigner.bat" sign ^
  --ks keystore/nastools.jks --ks-key-alias nastools ^
  --out 家庭联网控制-v1.4.0.apk ^
  app/build/outputs/apk/release/app-release-unsigned.apk
```

> 密钥丢了不影响编译，只影响「覆盖升级」。换密钥 = 旧版本必须先卸载。
