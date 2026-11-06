# shizuku-installer-rs

基于 Rust 与 Shizuku 的 Android 静默安装器。借助 Shizuku 提供的 shell 身份建立
PackageInstaller 会话，安装 / 卸载应用时无需拉起系统安装界面，也不会跳到应用主界面。
每一次后台静默操作都会写入本地安装日志。

- 包名：`io.github.lianxia233.shizukuinst`
- minSdk **26** / targetSdk **36**（Android 16）/ compileSdk **36**
- Shizuku API **13.1.5**（`rikka.shizuku.*` + `IShizukuService`）
- 业务逻辑 100% 在 `crates/core`（Rust），Java 仅为无策略判断的 I/O 胶水
- 启动图标素材位于 `icons/`，各密度资源由 `scripts/gen_icons.py` 生成

## 1. 架构

```
┌──────────────────────────── Android App ────────────────────────────┐
│  MainActivity / SettingsActivity / LogActivity (Java, UI 与生命周期) │
│  SilentInstallActivity (零/短窗口入口) · CallerAuthActivity (授权页) │
│  StagedFiles + StagedProvider (外部 URI 私有化) · TaskLauncher       │
│  SilentTask (进程级后台任务) · FloatingIndicator (系统悬浮窗)      │
│  Notifier (通知) · AppNames (应用名与错误文案)                      │
│        │  RustCore.dispatch(method, argsJson) : String               │
│        ▼                                                             │
│  libshizuku_core.so  (crates/android-ffi, JNI, Rust)                 │
│        │  PlatformBridge.call(method, args) -> JSON                  │
│        ▼                                                             │
│  shizuku-core (Rust, 纯逻辑, host 可单测)                            │
│   状态机 · 命令构造 · 会话流程 · 归档拆分 · 结果解析                 │
│   配置 · 调用方授权策略 · 日志                                       │
│        │  ShizukuBridge.call(...) 回调 Java                          │
│        ▼                                                             │
│  ShizukuBridge (Java, 仅 I/O): Shizuku / ContentResolver / Process   │
│  InstallLogStore (Java, 仅 I/O): 本地 JSONL 日志                     │
└──────────────────────────────────────────────────────────────────────┘
```

只有一个 JNI 入口、一个平台桥接原语，便于审计与测试。
外部安装 / 卸载意图由无窗口的 `SilentInstallActivity` 接管，安装任务在应用进程后台线程执行；
主界面只在用户主动点击图标时出现。

## 2. 功能

| 编号 | 功能 | 实现位置 |
|---|---|---|
| F-01 | Shizuku 服务/授权状态检查、请求授权、引导 | `state.rs`, `api.rs`, `ShizukuBridge` |
| F-02 | 静默安装（无确认、不弹界面）：单 APK / 多 split / `.apks` / `.xapk` | `install.rs`, `archive.rs`, `plan.rs` |
| F-03 | 静默卸载（含保留数据） | `uninstall.rs` |
| F-04 | 免确认包名白名单；安装前确认为可选开关（默认关闭） | `config.rs`, `SettingsActivity` |
| F-05 | 通知气泡显示安装中 / 进度 / 结果，一键启动、查看日志 | `Notifier`, `SilentTask` |
| F-06 | 安装日志：自动记录、历史查看、导出分享、清空 | `logbook.rs`, `InstallLogStore`, `LogActivity` |
| F-07 | **调用方授权**：外部应用首次调用只发待授权通知（不弹窗），授权后继续本次安装；设置页可勾选允许哪些应用 | `auth.rs`, `CallerAuthActivity`, `Notifier`, `SettingsActivity` |
| F-08 | **无遮罩后台指示**：左上角科幻风旋转指示器，点击穿透、不染黑系统栏；悬浮窗权限可由 Shizuku 自动开启 | `FloatingIndicator`, `SciFiSpinner` |
| F-09 | **失败降级（默认不打断）**：静默安装失败时只发一条可点击通知，点通知才进系统安装器；可显式开启「自动打开」 | `SilentTask`, `Notifier`, `Config` |
| F-10 | **中文展示**：通知、日志、错误原因显示应用实际名称与中文说明 | `AppNames`, `apk_meta.rs`, `util.rs`, `logbook.rs` |
| F-11 | **写入兜底**：stdin 流式写入失败时自动改用文件路径写入，适配不同 ROM | `install.rs`, `plan.rs`, `ShizukuBridge` |

### 静默安装流程

- 外部 `VIEW` / `INSTALL_PACKAGE` / `SEND` / `SEND_MULTIPLE` / `DELETE` / `UNINSTALL_PACKAGE`
  意图由 `SilentInstallActivity` 接管。它按「调用方是否等待结果」走两条路径：
  - **不等待结果**（多数文件管理器 / 分享）：在 `onCreate` 内立即 `finish()`，framework
    因 `mFinished=true` 跳过 `wm.addView`，代码完全不碰 Window，仍是零窗口；
  - **等待结果**（`startActivityForResult` 或 `EXTRA_RETURN_RESULT=true`）：使用
    `@style/SilentEntryTheme` 驻留 `RESULT_DELAY_MS = 1400ms`，期间窗口由
    `configureWindow()` 压到 1x1、全透明、`FLAG_NOT_FOCUSABLE`、
    `FLAG_NOT_TOUCHABLE`、无 dim、系统栏透明并保留
    `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`；到期再 `setResult()` + `finish()`。
- 延迟是为了越过调用方在 MIUI 上的 1 秒「结果忽略窗口」（见下节）。
  `Theme.NoDisplay` 要求 `onResume` 前 `finish()`、无法驻留，因此改用自定义主题；
  窗口属性在 `onCreate` / `onResume` / `addView` 后三次申明，防止 ROM 重算覆盖。
  主题刻意不设 `windowIsFloating` —— floating + translucent 在 MIUI 上会被铺成
  整屏半透明背板，正是前几轮「一层透明遮罩」的来源。

- **安装链路零窗口**：入口遇到 `ASK` 时不再 `startActivity` 弹任何 dialog —— 那正是
  「一层遮罩弹窗、屏幕点不动」的来源之一（Dialog 窗口在部分 ROM 上会被铺成整屏半透明
  背板并抢焦点）。未授权调用改为「拦截本次 + 发一条待授权通知」，用户点通知才进入授权页；
  通知同时携带已私有化的安装包，授权后可直接继续本次安装，不再白白丢包。
- 授权页 `CallerAuthActivity` 因此改成了**普通不透明页面**（`@style/AppTheme`），不再使用
  Dialog 主题，也不带 `dim`，不存在任何浮层；且它已完全脱离安装链路，安装过程中屏幕上
  不可能出现由本应用创建的窗口。
- 「按返回才能继续操作」是**顶层存在可聚焦窗口**的确定信号：系统悬浮窗是
  `FLAG_NOT_FOCUSABLE`，按返回不会作用于它；只有 Activity 窗口会接收返回键。
  该窗口**未必属于本应用** —— 调用方等待结果的透明中转 Activity 同样满足全部症状，
  因此排查必须先看 `mCurrentFocus` 的属主，不能只审计自己的主题。
  静默链路在正常路径上不启动任何 Activity —— 失败降级默认也只发通知。
- **外部 URI 私有化**：调用方通过 `FLAG_GRANT_READ_URI_PERMISSION` 授予的读权限只在其
  Intent 对应的 Activity 存活期间有效。入口在仍持有权限时把安装包复制到应用私有缓存，
  并由自家只读 `StagedProvider` 暴露；后台任务全程只读这个 URI，不会因 Activity 销毁、
  授权被回收而在写入阶段失败。降级到系统安装器时也通过临时授权安全地传同一个 URI。
- 入口通过 `getCallingActivity()`、`getCallingPackage()`、`getReferrer()` 与
  `EXTRA_REFERRER*` 尽力识别调用方；识别不到一律视为匿名来源。Rust `auth::decide`
  返回 `ALLOW` / `ASK` / `DENY`：
  - `ALLOW`：立即启动后台安装，回传 `RESULT_OK`（调用方据此把 UI 更新为「已安装」）；
  - `ASK`：**拦截本次安装**并发「待授权」通知（携带 staged URI），点通知进入授权页勾选
    「记住」，允许后由授权页继续安装；入口回传 `RESULT_CANCELED` 并附 `CALLER_PENDING`；
  - `DENY`：静默阻止、记一条日志，同样回传 `RESULT_CANCELED`。
  匿名来源**永远不能被永久记住**，否则一次允许会放行后续所有匿名调用。
- 后台任务由 `SilentTask` 在应用进程后台线程串行执行，**安装不等待结果延迟**：
  通知、进度、悬浮指示器都在 `onCreate` 立即启动。只有「结果回传」会为等待结果的
  调用方延后 1400ms；其余路径入口立即销毁。整个安装期间屏幕上只有通知与左上角的
  小指示器。入口的 `intent-filter` 只匹配 APK 相关 MIME，不使用 `*/*`，
  避免抢走任意 `content://` 意图。
- 默认不做任何安装前确认：静默入口的 `skipConfirm` 恒为 `true`，
  Rust 侧 `Config::default()` 的 `confirm_before_install` 为 `false`。
- 通知标题显示应用的**实际名称**（已安装应用名 > APK 内 `application@label` > 清洗后的文件名），
  失败原因显示中文说明并保留原始错误码；日志同样以中文应用名作为标题。
- 静默安装失败时，若「提供系统安装器入口」开启，**默认只发一条可点击通知**，点通知才
  显式进入系统安装器组件（排除本应用自己的 intent-filter）。之所以不自动 `startActivity`：
  系统安装器 / 选择器是可聚焦窗口，会覆盖前台应用、把状态栏与导航栏染黑，且不按返回不会
  消失，与「静默」语义直接冲突。确有需要可在设置里打开「失败时自动打开系统安装器」
  （默认关闭，开启时有风险提示）。降级方式会写入安装日志便于排查。

### 调用方结果回传契约（2026-11-07 二次实机定位，更正版）

> 早期版本曾把根因误判为「没有回传 `RESULT_OK`」。1.0.1 实机复测证明
> `setResult(RESULT_OK)` 已执行（`finish result=-1 detail=ACCEPTED`，往返 2ms），
> 弹窗依旧，故该结论作废。以下为修正后的根因与方案。

- 现场证据（`adb shell dumpsys window` / `dumpsys activity activities` /
  `uiautomator dump` / `logcat`）：`mCurrentFocus` 是**调用方**窗口（Mihon
  `eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity`），`fmt=TRANSPARENT`、
  全屏、视图树只有空的 `android:id/content`、零 `clickable`；`state=RESUMED
  finishing=false` 停留数分钟；`taskDescription.statusBarColor=ff000000` 且窗口缺
  `DRAWS_SYSTEM_BAR_BACKGROUNDS` → 黑系统栏、触摸被吞、只能按返回键退出。
- 真根因在调用方源码（`mihonapp/mihon` 的 `ExtensionInstallActivity.kt`）：检测到
  `hasMiuiPackageInstaller`（设备上存在 `com.miui.packageinstaller`）时，`onCreate` 设
  `ignoreResult=true` 与 `ignoreUntil = System.nanoTime() + 1s`；`onActivityResult`
  若在 1 秒内到达，执行 `hasIgnoredResult = true; return` —— **不调用 `finish()`，
  且判断发生在 resultCode 之前，`RESULT_OK` / `RESULT_CANCELED` 一律被吞**。
  它只靠后续 `onStart()` 里的 `if (hasIgnoredResult) finish()` 兜底。
- 零窗口安装器 2ms 内结束，调用方在结果派发时已走完 `onStart/onResume`，此后再无
  `onStart` → `hasIgnoredResult` 永远不被消费 → 透明窗口永久停留。系统安装器之所以
  正常，是因为它显示真实界面让调用方进入 stopped，返回时 `onStart()` 会兜底 finish。
- 修复（方案 A）：把结果回推到 `ignoreUntil` 之后。`RESULT_DELAY_MS = 1400ms`
  留出 MIUI 调度余量，使 `onActivityResult` 走
  `checkInstallationResult(resultCode); finish()` 正常分支；`RESULT_OK` 还能让调用方
  把 UI 更新为「已安装」而非「空闲」。驻留只作用于结果回传，后台安装在 `onCreate`
  立即开始。
- 诊断：`adb logcat -s SilentEntry` 会输出 `result delayed 1400ms ...` 与
  `finish result=-1 ... elapsedMs=140x`，用 `elapsedMs` 确认延迟生效；
  `adb shell dumpsys window | grep mCurrentFocus` 应看到焦点在结果返回后立即
  回到调用方主界面，而不是停在透明中转页。
- 结论：**零窗口与正确回传结果都只是必要条件**。被 `startActivityForResult` 拉起的
  安装器还必须适配调用方自身的结果处理时序，否则「透明弹窗」会以调用方窗口的形式
  出现，且单改 resultCode 无效。

### 调用方授权

- 设置页列出系统已安装应用（用户应用在前），每个应用一个开关：
  开启 = 允许直接静默安装，关闭 = 明确拒绝。可搜索、可一键清空所有授权记录。
- 授权总开关默认开启；关闭后任何外部应用都可直接发起静默安装，界面会给出风险提示。
- 白名单、黑名单、首次询问策略全部由 Rust `auth.rs` 判定并做 host 侧单测，
  Java 层只负责弹窗与渲染。

### 安装日志

- Rust 核心在 `install::execute_with_context` / `uninstall::uninstall_with_context`
  的**成功与失败两条路径**上生成结构化记录：操作类型、应用名、包名/版本、分片数与总大小、
  结果码、原始输出（裁剪至 2000 字符）、耗时、会话 id、触发来源、调用方。
- 记录通过 `PlatformBridge` 的 `logAppend` 落到 `filesDir/logs/install-log.jsonl`，
  Java 侧补时间戳与可读时间，保留最近 **500** 条，原子替换写入、统一 LF。
- 日志写入是旁路操作：持久化失败不影响安装结果。
- 准备阶段失败、授权被拒（未进入核心执行）由静默入口通过 `logRecord` 补记，
  保证后台链路任何结果都有记录。
- 应用内「安装日志」页可查看历史、导出为纯文本分享、一键清空；结果通知带「查看日志」动作。

## 3. 关键实现选择

- **等待结果的调用方走「延迟结果 + 1x1 短窗口」**：`RESULT_DELAY_MS = 1400ms` 越过
  MIUI 侧 1 秒忽略窗口；窗口属性在 `onCreate` / `onResume` / `addView` 后三次申明，
  避免 ROM 依据主题重算 LayoutParams。普通调用仍是零窗口，安装任务不等待延迟。
- **静默安装走 `pm install-create / install-write / install-commit` 会话**，
  首选 `pm install-write -S <size> <session> <name> -` 从 stdin 流式写入。
- **写入兜底**：若 stdin 写入失败（管道破裂、ROM 对 Shizuku `newProcess` 的 stdin 支持不佳等），
  自动把该 split 复制到 shell 可读的临时文件，改用
  `pm install-write -S <size> <session> <name> <path>` 重试一次；仍失败才 `install-abandon` 回滚，
  并在日志里同时保留两条路径的真实错误，不再把异常伪装成无意义的 `exit -1`。
- **平台错误不吞**：JNI 桥接与 `platform.rs` 都会检查 Java 侧返回的 `error` 字段并转成
  明确的错误，避免真实异常被当成「成功但字段缺失」。
- Shizuku 侧用 `IShizukuService.newProcess()` 以 shell 身份执行命令，
  通过 `IRemoteProcess` 的 `ParcelFileDescriptor` 三路流完成双向 I/O。
- 输入统一走 `content://`；`stat` / `archiveEntries` / `readBytes` 只做字节与元数据搬运，
  所有策略判断在 Rust。
- 安装前展示的包名/版本/应用名由 Rust 自写的 binary-XML 读取器解析
  `AndroidManifest.xml`，解析失败自动降级、不阻塞安装。
- 所有拼进 shell 的参数由 Rust 生成，包名经 `is_valid_package_name` 校验，避免注入。
- **悬浮指示器只用系统悬浮窗**：`TYPE_APPLICATION_OVERLAY` 的 40dp 小窗，
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`、透明像素格式、不 dim —— 不切换 task、
  不 pause 前台应用、点击完全穿透。
  没有 `SYSTEM_ALERT_WINDOW` 权限时，借本应用已有的 Shizuku(shell) 身份执行
  `appops set <pkg> SYSTEM_ALERT_WINDOW allow` 自动开启；自动授权也失败则静默放弃指示器，
  安装照常由通知反馈。**绝不用普通 Activity 窗口模拟悬浮窗**：那会强制回全屏尺寸，
  配合透明背景产生整屏黑，且 `startActivity` 会把本应用 task 拉到前台。
- NDK r27 + `max-page-size=16384`，满足 Android 15/16 的 **16KB 内存页**要求。
- UI 为纯原生 View + vector/gradient/ripple drawable，无额外 UI 框架依赖；深浅色主题齐备。

## 4. 构建

依赖：Rust stable + 4 个 Android target、cargo-ndk、Android SDK 36 / build-tools 36 /
NDK 27.2.12479018、JDK 17+、Gradle 8.14（wrapper 已包含）、Python 3 + Pillow。

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
bash scripts/build.sh
# 产物: ShizukuInstaller.apk
```

分步：

```bash
cargo test -p shizuku-core                       # 单元测试
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
    -o android/app/src/main/jniLibs build --release -p shizuku-android-ffi
cd android && ./gradlew assembleDebug
python3 scripts/gen_icons.py                    # 从 icons/app_icon_source.png 生成图标资源
python3 scripts/gen_axml_fixture.py             # 重新生成测试样本
```

CI 见 `.github/workflows/build.yml`（`push main` 或手动 `workflow_dispatch` 触发，
产物为 `ShizukuInstaller.apk`）。**构建完成后默认创建 GitHub Release 并附上 APK**：
tag 形如 `v1.0.2-build.<run_number>`（每次构建唯一，避免同名 tag 冲突；重跑同一
run 时改为覆盖上传同名资产），由 runner 预装的 `gh` CLI 完成，依赖
`permissions: contents: write`；不使用第三方 release action，避免引入新的
Node.js 运行时弃用警告。第一方 action 已升级到声明 `node24` 运行时的版本：
`actions/checkout@v5`、`actions/setup-java@v5`、`actions/upload-artifact@v6`
（注意 `upload-artifact@v5` 仍声明 `node20`，不能消除弃用警告）。

## 5. 使用

1. 安装并启动 Shizuku（无线调试或 root），确认服务运行中。
2. 首次打开本应用，完成 Shizuku 授权并允许通知权限（进度气泡需要）。
3. 在「设置」里确认静默安装授权总开关，并勾选允许发起静默安装的应用；
   「显示在其他应用上层」权限是**可选**的：应用会尝试通过 Shizuku 自动开启，
   若设备限制导致失败可手动开启。未开启时安装照常进行，仅以通知反馈进度，
   绝不会遮挡屏幕或切换应用。
4. 从文件管理器用「打开方式 / 分享」把 APK 交给本应用：已授权来源直接后台执行，
   首次调用只发一条「待授权」通知，点通知进入普通页面确认，之后不再打扰。
5. 安装期间停留在当前应用，通知栏显示「XXX 正在安装中」及进度，
   屏幕左上角有一个小旋转指示器；不会跳到主界面。
6. 安装结果更新同一条通知；成功可一键启动，失败显示中文原因，
   并可点击通知改用系统安装器手动安装（默认不会自动弹出界面）。
7. 卸载意图（`package:` URI）同样静默执行；应用内也可输入包名手动卸载。
8. 打开「安装日志」查看每一次操作的时间、应用名、结果、耗时与原因，可导出或清空。

## 6. 兼容性

| 平台要求 | 处理 |
|---|---|
| Android 16 (API 36) | targetSdk 36；`android:exported` 显式声明；16KB page size |
| Android 15 (API 35) | edge-to-edge：`fitsSystemWindows` + 系统栏配色；小窗口避开状态栏 |
| Android 14 (API 34) | 无前台服务；`PendingIntent.FLAG_IMMUTABLE` |
| Android 13 (API 33) | `POST_NOTIFICATIONS` 动态申请；通知渠道；themed icon |
| Android 11+ (API 30) | `<queries>` 包可见性；`QUERY_ALL_PACKAGES` 用于设置页枚举应用；全量 SAF |
| Android 10+ (API 29) | 后台启动 Activity 受限，指示器改用系统悬浮窗，不启动任何 Activity |
| Android 8+ (API 26) | `TYPE_APPLICATION_OVERLAY`；adaptive icon + 单色图标 |

权限说明：`SYSTEM_ALERT_WINDOW` 只用于可选的安装进度小指示器，可由 Shizuku 自动授予，
未授予时静默退化为纯通知反馈；`QUERY_ALL_PACKAGES` 只用于设置页展示
系统应用列表，不上传、不持久化到任何远端。

## 7. 目录

```
Cargo.toml                 Cargo workspace
crates/core/               纯 Rust 业务逻辑 + 单元测试
crates/android-ffi/        JNI cdylib
android/                   Gradle 工程（Java 胶水、Manifest、资源）
icons/                     启动图标素材
scripts/                   构建与资源生成
.github/workflows/         CI
```

## 8. 许可

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整条款见 `LICENSE`。
图标素材与生成的图形资源随仓库一同提供，同样适用该许可。
