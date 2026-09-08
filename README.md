# 群晖管控台（NAS Tools）

把 `projector_block`（投影仪网络管控）和 `school-calendar`（校历 + Media 账号管控）
两个项目的日常操作，合并进一个安卓 App 里，手机上改完立刻生效。

- **最低系统**：Android 10（minSdk 29），targetSdk 34。
- **工作原理**：手机不直接碰 iptables / 密码，而是调用群晖上的一个纯标准库 Python 服务
  `nas_api.py`；该服务以 root 运行，去跑你现有的那两个脚本。
- **改完即生效**：配置一改，服务立刻补跑一遍 `media_access.sh` 和 `projector_block.sh`，
  不用等每小时 / 每 5 分钟的定时任务。

---

## 一、你要装两样东西

| 文件 | 装到哪 | 说明 |
|---|---|---|
| `群晖管控台-v1.0.apk` | 手机 | Android 10 及以上 |
| `nas_tools_NAS端_v1.0.tar.gz` | 群晖 `/volume1/docker/` | 内含 `nas_api.py`、`media_access.sh`、`conf/api.conf` |

**部署步骤全部在 `部署手册.md` 里**，一步一步照着做即可（约 15 分钟），
每步都写了「预期看到什么」。

快速流程：

1. SSH 进群晖，`sudo -i`，跑第 0 步环境探测。
2. 第 2 步用 **File Station** 上传 tar.gz 并解压到 `/volume1/docker/`。
3. 第 3 步改令牌（App 里要填同一个）。
4. 第 4 步（可选但推荐）升级 `media_access.sh`，解锁「强制开放 / 强制封闭」。
5. 第 5 步启动服务并验证。
6. 第 6 步建 DSM 任务计划（**用户账号必须选 root**，触发方式选「启动」）。
7. 装 App，右上角 ⋮ → 连接设置 → 测试连接。

---

## 二、App 的五个页签

| 页签 | 能干什么 |
|---|---|
| **状态** | 今天类型、当前时段、Media 状态、投影仪状态、已拦截包数；「立即应用」「强制封禁/放行」「恢复自动」 |
| **时段** | 编辑上学日 / 放假日的开放时段（可增删多段），保存即生效 |
| **开关** | `BLOCK_WAN`、`BLOCK_NAS`、`KICK_*`、`BLOCK_MODE`、学段、儿童节，以及投影仪 IP/MAC/网口等 |
| **校历** | 月历查看；点某天临时改成上学/放假；重建校历；联网更新国务院节假日；编辑寒暑假区间 |
| **日志** | 直接看 NAS 上的 4 份日志（校历构建、Media 切换、投影仪、API 服务） |

### 几个设计上的说明

- **单一数据源**：两个项目共用 `calendar.conf` 里的 `SCHOOL_WINDOWS` / `HOLIDAY_WINDOWS`，
  App 改一处两边同时生效（和现有设计一致）。
- **强制模式是「粘性」的**：投影仪的 `--on/--off` 原本就写文件；Media 的强制是新加的
  `data/.media_override`，一直保持到你点「恢复自动」，每小时的定时任务不会顶掉它。
- **Media 的强制按钮需要第 4 步升级脚本**。不升级也能用，只是那两个按钮是灰的，
  其余功能不受影响（App 会自动检测并提示）。
- **改 conf 的方式**：只按 `KEY=` 逐行精确替换，保留注释和文件结构，
  改之前先备份成 `.bak`。写回一律 UTF-8 + LF（顺手把 CRLF 也归一化）。
- **所有输入都做校验**：时段必须是 `HH:MM-HH:MM` 且结束晚于开始；
  `0/1` 类开关只接受 0 和 1；IP / MAC / 网段有格式检查。非法值会被服务挡掉并返回 400。

---

## 三、目录结构

```
NAS_Tools_apk/
├── 群晖管控台-v1.0.apk            <- 手机装这个
├── nas_tools_NAS端_v1.0.tar.gz    <- 群晖上解压这个
├── 部署手册.md                     <- 一步一步的部署说明
├── nas/                            <- NAS 端源码（改这里后重新打包）
│   ├── nas_api.py                  API 服务（只用标准库，兼容 Python 3.8）
│   ├── media_access.sh             v2，比线上版多了 .media_override 强制支持
│   └── conf/api.conf               端口 / 令牌
├── keystore/nastools.jks           自签名（store/alias 口令都是 nastools）
└── app/                            Android 工程（Java 17 / AGP 8.7.3 / compileSdk 34）
```

## 四、重新构建

```bat
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20+8
set ANDROID_HOME=C:\Users\Lanke62\AppData\Local\Android\Sdk
gradlew.bat assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

改了 `nas/` 下任何文件后，重新打包：

```sh
mkdir -p .pkg/nas_tools/conf .pkg/nas_tools/log
cp nas/nas_api.py nas/media_access.sh .pkg/nas_tools/
cp nas/conf/api.conf .pkg/nas_tools/conf/
cd .pkg && tar czf ../nas_tools_NAS端_v1.0.tar.gz nas_tools
```

## 五、接口一览（给将来接别的客户端用）

全部需要请求头 `X-Token: <令牌>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 免鉴权也能调，用来区分「服务在但令牌错」 |
| GET | `/api/status` | 校历 / Media / 投影仪 全量状态 |
| GET | `/api/calendar?ym=2026-09` | 某月每天的类型与覆盖 |
| GET | `/api/logs?n=120` | 4 份日志的末尾 |
| GET | `/api/overrides` | 单日覆盖列表 |
| POST | `/api/config` | `{"scope":"calendar"/"projector","set":{...},"apply":true}` |
| POST | `/api/action` | `{"action":"apply_all"/"media_open"/"proj_on"/"cal_init"/...}` |
| POST | `/api/overrides` | `{"op":"set"/"del","date":"2026-09-30","flag":"1"}` |

## 六、安全提醒

- 服务是**明文 HTTP**，靠令牌保护，只应在局域网使用。
- **不要把 8899 端口做端口转发 / 映射到公网。**
- 默认令牌是 `nastools`，部署时请改掉。

---

## 七、更新记录（按日期）

> 早期 README 停在 v1.0（5 页签 + 含 Media 账号）。v1.2 起结构大改，故这里按日期记关键变更，
> 不再另写独立交接文档。架构与页面结构的权威描述以 `.workbuddy/memory/MEMORY.md` 为准。

### 2026-09-07（v1.3.2：纯代码清理，无行为变更）
- **已发布** `TV管控台-v1.3.2-代码清理版.apk`（versionCode 10）：
  - 删除 HTTP 模式全部残留：`Prefs`（port/token/save/baseUrl/mode/useSsh 等）、`Conn` 分流、
    `Api` 的整个 HTTP 客户端（只留结果类型 `R` 与超时常量）；错误提示改用 SSH 端口。
  - 删除 Media 功能全部残留（`nas_api.py`）：`sh_media` / `media_override_supported` / `act_media_*`
    / dispatch 的 media_* 分支 / `.media_override` 路径 / apply 时顺带跑 media_access.sh /
    health·probe·banner 里的 media 字样；`get_status` 不再输出 media 占位对象。
  - 删除死代码与死资源：`ProjectorFragment.doAction`（立即应用移除后无调用）、
    `CalendarFragment.color()`、`BaseFragment.str()`、`TvParamsActivity` 冗余别名、
    未使用的 strings（tab_*/menu_*/pref_* 等）、`menu_main.xml`、重复的 `app:title`。
  - 界面与功能完全不变；Python 语法检查 + Java 编译均通过。

### 2026-09-07（v1.3.1：待保存语义 + 修下拉刷新 + 日志整理）
- **已发布** `TV管控台-v1.3.1-待保存语义修刷新日志滚动.apk`（versionCode 9）：
  - **电视端页全部改为「待保存」语义**：模式按钮（长期放行/校历模式/长期封禁）、独立功能开关、
    开放时段的任何改动都只先记在手机上，**必须点底部「保存并立即生效」才下发到群晖**；
    刷新或重开 App 一律丢弃未保存改动、重新读取 NAS 上的配置。
  - **移除**电视端状态卡里的「立即应用」按钮。
  - **隐藏**「封禁时踢掉 SMB 会话」开关：固定按默认（封禁时踢会话）执行，不再对外提供。
  - **NAS 日志**：去掉「Media 切换」项（App 端另做了旧版脚本兼容过滤），「投影仪」改「电视端」
    （`nas_api.py` HTTP 与 CLI 两处都改，App 首次连接会把新版脚本传上去）；
    **修复日志区无法向下滚动**（外层 ScrollView 抢触摸，现触摸日志区时禁止外层拦截）。
  - **修下拉刷新**：根因是 `BaseFragment.makeScroller` 造了 SwipeRefreshLayout 却把里面的
    ScrollView 返回给了视图树——swipe 从未挂载，三个页面都没有下拉刷新。现返回 swipe 本身，修复。
  - **去掉右上角「刷新」「关于」菜单**（下拉刷新已覆盖刷新需求）；标题「TV管控台」旁用小字显示当前版本号。
  - 「恢复校历模式」改名「**校历模式**」；选中（待保存）的模式按钮高亮，并提示「还没保存」。
  - **校历月视图**：日期数字与上学/放假文本在色块内**水平居中**；删除月视图底部的颜色说明注释。

### 2026-09-07（v1.3：改名 TV管控台 + 开关语义反转 + TV端参数页）
- **已发布** `TV管控台-v1.3-改名反转开关加TV端参数页.apk`（versionCode 8）：
  - **APP 改名**：群晖管控台 → **TV管控台**（桌面名 + 关于弹窗等 App 内相关字样）。
  - **刷新按钮修复**：工具栏右上角新增「刷新」按钮（此前 `menu_main` 存在但从未 inflate，一直没显示）；
    各页本身也一直支持下拉刷新。
  - **开关语义反转**：原「电视端封禁开关」改名**「独立功能开关」**；「外网」「群晖」两个开关改为
    **开 = 放行（不封禁）、关 = 封禁**（conf 里 BLOCK_WAN/BLOCK_NAS 仍是 1=封禁，App 读写时自动取反）。
  - **状态按钮改名并重排**：「强制封禁→长期封禁」「强制放行→长期放行」「恢复自动→恢复校历模式」，
    三个按钮按 长期放行 / 恢复校历模式 / 长期封禁 排成一行。
    模式按钮是**即时生效**的，点完不用再按「立即应用」。
    「立即应用」保留在状态卡内：它只把**已保存**的规则重新推上去（应对重启 / 网络变动后规则被清），
    不改任何设置；与底部「保存并立即生效」（写配置 + 应用）不是一回事。
  - **网络参数 → TV端参数页**：电视端页的内联「网络参数」卡片移除，改为「TV端参数」卡片 + 跳转按钮，
    进入新页面 `TvParamsActivity`（电视端 IP / MAC、群晖 IP、出网网口、局域网段）。
    群晖 IP 默认跟随设置页 NAS 地址（`Prefs.host`）；页面提供「恢复默认值」
    （恢复为本页打开时 NAS 上生效的值，群晖 IP 恢复为设置页 NAS 地址），改错可回退。

### 2026-09-07（v1.2.4 收尾 + 修「时段读不到」真因）
- **已发布** `群晖管控台-v1.2.4-电视端改名并修时段为空.apk`：
  - 全 App「投影仪」→「电视端」，去「当贝 DBX3P」字样（对所有用户通用）；标签页「电视端 / 校历 / 设置」。
  - 设置页去掉连接方式选择，**后台固定 SSH 直连**（HTTP 模式整体移除）；密码框 helperText 修复重叠；
    「SSH 连接诊断」改为按钮弹窗（删 `SshProbeActivity`），可复制、关即清空。
  - 校历页学段 Spinner 改中文「小学/高中」并去翻译注释；寒暑假区间改**起止日期选择器**；删除「单日覆盖」卡片。
  - 封禁开关卡排在倒数第 3。
- **「上学日/放假日开放时段读不到」真因与修复**：
  - 真因不是 App，而是 NAS 端 `school-calendar/conf/calendar.conf` 里的
    `SCHOOL_WINDOWS` / `HOLIDAY_WINDOWS` 两段，被另一个 AI 在「移除 Media 账号密码」时**一并误删**了
    （连同账号密码段一起）。`projector.conf` 写明这两个时段默认复用 child_calendar 的同一套，删掉后 App 读到即空。
  - App 端代码其实是对的：读取与保存都走 `scope="calendar"`（即 `CAL_CONF` = school-calendar 的 calendar.conf），
    读/写同源一致。所以**只要把这两个键恢复回去，App 立即能显示**。
  - 已在本机 `C:\AI\Project\school-calendar\conf\calendar.conf` 恢复这两段（用 v1.2.3 时期的默认值）；
    **NAS 上的实际文件须同步恢复**，两条命令见下方「NAS 端恢复时段」。
  - 临时替代方案：App 电视端页点「添加时段」手动加回也能立即生效（会写回 calendar.conf）。
- **删除** `续作交接文档_2026-09-07.md`，改为本文档按日期记录更新。

#### NAS 端恢复时段（在群晖 SSH 里执行）
```sh
# 路径按你实际部署目录调整（默认 child_calendar）
echo 'SCHOOL_WINDOWS="08:00-12:00;14:00-16:30"   # 上学日开放时段' >> /volume1/docker/child_calendar/conf/calendar.conf
echo 'HOLIDAY_WINDOWS="18:00-21:00"              # 放假日开放时段' >> /volume1/docker/child_calendar/conf/calendar.conf
```
回车后**无输出即正常**。改完在 App 里下拉刷新「电视端」页即可看到；若有上网/访问限制想立刻生效，
在 App 电视端页点「保存并立即生效」（会重跑 `projector_block.sh` 应用时段）。

### 2026-09-06（v1.2.2 / v1.2.3 概要，补录）
- v1.2.2：移除 Media 账号卡片与 `media_*` 动作（NAS 端任务计划已删，改用电视端内外网开关）；
  加主线程 `ui.accept()` 渲染兜底，从此任何界面异常只报错不闪退。
- v1.2.3：去掉冗余状态页，重组为「投影仪 / 校历 / 设置」三页；校历页修 GridLayout 崩溃（改用嵌套 LinearLayout）；
  学段切换（primary/high）移入校历页。


## 构建 Release（签名密钥）

签名密钥 `keystore/nastools.jks` **不在仓库里**（见 `.gitignore`）。两种方式：

1. **本地构建**：把自己的 `nastools.jks` 放到 `keystore/` 目录，然后
   ```
   gradlew.bat assembleRelease
   ```
2. **换机构建**：用 Android Studio 重新生成一个 keystore，改 `app/build.gradle` 里的
   `storeFile / storePassword / keyAlias / keyPassword`。注意签名变了，手机上旧版必须先卸载。

> 提醒：密钥丢了不影响开发，只影响「覆盖升级」。换密钥 = 装上新版本要卸载旧版本。
