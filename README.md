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
│  SilentInstallActivity (无窗口入口) · CallerAuthActivity (授权卡片)  │
│  StagedFiles + StagedProvider (外部 URI 私有化) · TaskLauncher       │
│  SilentTask (进程级后台任务) · FloatingIndicator + IndicatorActivity │
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
| F-07 | **调用方授权**：外部应用首次调用弹窗询问，设置页可勾选允许哪些应用 | `auth.rs`, `CallerAuthActivity`, `SettingsActivity` |
| F-08 | **无遮罩后台指示**：左上角科幻风旋转指示器，点击穿透、不染黑系统栏；无悬浮窗权限也能显示 | `FloatingIndicator`, `IndicatorActivity`, `SciFiSpinner` |
| F-09 | **失败降级**：静默安装失败时自动拉起系统安装界面 | `SilentTask`, `Config` |
| F-10 | **中文展示**：通知、日志、错误原因显示应用实际名称与中文说明 | `AppNames`, `apk_meta.rs`, `util.rs`, `logbook.rs` |
| F-11 | **写入兜底**：stdin 流式写入失败时自动改用文件路径写入，适配不同 ROM | `install.rs`, `plan.rs`, `ShizukuBridge` |

### 静默安装流程

- 外部 `VIEW` / `INSTALL_PACKAGE` / `SEND` / `SEND_MULTIPLE` / `DELETE` / `UNINSTALL_PACKAGE`
  意图由 `SilentInstallActivity` 接管。它使用**无窗口主题**（无背景、无 `windowIsTranslucent`、
  无 `windowIsFloating`、禁用启动预览），不调用 `setContentView`，并把窗口压到 1x1 且
  `FLAG_NOT_TOUCHABLE`，在 `onCreate` 内立即 finish。安装期间屏幕上不存在本应用的窗口，
  因此不会出现状态栏 / 导航栏变黑或点击屏幕卡住的问题。
- **外部 URI 私有化**：调用方通过 `FLAG_GRANT_READ_URI_PERMISSION` 授予的读权限只在其
  Intent 对应的 Activity 存活期间有效。入口在仍持有权限时把安装包复制到应用私有缓存，
  并由自家只读 `StagedProvider` 暴露；后台任务全程只读这个 URI，不会因 Activity 销毁、
  授权被回收而在写入阶段失败。降级到系统安装器时也通过临时授权安全地传同一个 URI。
- 入口通过 `getCallingPackage()`、`getReferrer()` 与 `EXTRA_REFERRER*` 尽力识别调用方；
  识别不到一律视为匿名来源。Rust `auth::decide` 返回 `ALLOW` / `ASK` / `DENY`：
  - `ALLOW`：直接执行；
  - `ASK`：转交给独立的 `CallerAuthActivity`（居中卡片）询问「允许 / 拒绝」，可勾选「记住本次选择」；
  - `DENY`：静默阻止并记一条日志。
  匿名来源**永远不能被永久记住**，否则一次允许会放行后续所有匿名调用。
- 用户完成授权（或已获授权）后，入口立即销毁，安装交给 `SilentTask` 在应用进程后台线程
  串行执行。整个安装期间屏幕上只有通知与左上角的小指示器。
- 默认不做任何安装前确认：静默入口的 `skipConfirm` 恒为 `true`，
  Rust 侧 `Config::default()` 的 `confirm_before_install` 为 `false`。
- 通知标题显示应用的**实际名称**（已安装应用名 > APK 内 `application@label` > 清洗后的文件名），
  失败原因显示中文说明并保留原始错误码；日志同样以中文应用名作为标题。
- 静默安装失败时，若设置里的「降级为系统安装器」开启，会显式指定系统安装器组件并拉起
  安装界面，避免再次匹配到本应用自己的 intent-filter；通知点击也可直接进入该界面。

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
- **悬浮指示器双路径**：已授予 `SYSTEM_ALERT_WINDOW` 时用 `TYPE_APPLICATION_OVERLAY`
  的 40dp 小窗（不切换 task、不 pause 前台）；未授予时由前台入口启动 44dp 的
  `IndicatorActivity`（普通窗口、透明、点击穿透、无 dim），同样能看到小圆且不需要任何权限。
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

CI 见 `.github/workflows/build.yml`。

## 5. 使用

1. 安装并启动 Shizuku（无线调试或 root），确认服务运行中。
2. 首次打开本应用，完成 Shizuku 授权并允许通知权限（进度气泡需要）。
3. 在「设置」里确认静默安装授权总开关，并勾选允许发起静默安装的应用；
   「显示在其他应用上层」权限是**可选**的：不授权也会显示左上角小圆（点击穿透），
   授权后指示器完全不打断前台应用。
4. 从文件管理器用「打开方式 / 分享」把 APK 交给本应用：已授权来源直接后台执行，
   首次调用会弹一张小卡片询问是否允许，之后不再打扰。
5. 安装期间停留在当前应用，通知栏显示「XXX 正在安装中」及进度，
   屏幕左上角有一个小旋转指示器；不会跳到主界面。
6. 安装结果更新同一条通知；成功可一键启动，失败显示中文原因，
   并按需自动打开系统安装界面供手动确认。
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
| Android 10+ (API 29) | 后台启动 Activity 受限，指示器小窗由前台入口启动 |
| Android 8+ (API 26) | `TYPE_APPLICATION_OVERLAY`；adaptive icon + 单色图标 |

权限说明：`SYSTEM_ALERT_WINDOW` 只用于可选的安装进度小指示器，
未授予时自动降级为前台入口启动的 44dp 小窗；`QUERY_ALL_PACKAGES` 只用于设置页展示
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
