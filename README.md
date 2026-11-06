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
│        │  RustCore.dispatch(method, argsJson) : String               │
│        ▼                                                             │
│  libshizuku_core.so  (crates/android-ffi, JNI, Rust)                 │
│        │  PlatformBridge.call(method, args) -> JSON                  │
│        ▼                                                             │
│  shizuku-core (Rust, 纯逻辑, host 可单测)                            │
│   状态机 · 命令构造 · 会话流程 · 归档拆分 · 结果解析 · 配置 · 日志    │
│        │  ShizukuBridge.call(...) 回调 Java                          │
│        ▼                                                             │
│  ShizukuBridge (Java, 仅 I/O): Shizuku / ContentResolver / Process   │
│  InstallLogStore (Java, 仅 I/O): 本地 JSONL 日志                     │
└──────────────────────────────────────────────────────────────────────┘
```

只有一个 JNI 入口、一个平台桥接原语，便于审计与测试。
外部安装 / 卸载意图由 `SilentInstallActivity`（透明无界面）接管，`Notifier` 负责进度通知；
主界面只在用户主动点击图标时出现。

## 2. 功能

| 编号 | 功能 | 实现位置 |
|---|---|---|
| F-01 | Shizuku 服务/授权状态检查、请求授权、引导 | `state.rs`, `api.rs`, `ShizukuBridge` |
| F-02 | 静默安装（无确认、不弹界面）：单 APK / 多 split / `.apks` / `.xapk` | `install.rs`, `archive.rs`, `plan.rs` |
| F-03 | 静默卸载（含保留数据） | `uninstall.rs` |
| F-04 | 免确认包名白名单；安装前确认为可选开关（默认关闭） | `config.rs`, `SettingsActivity` |
| F-05 | 通知气泡显示安装中 / 进度 / 结果，错误复制、一键启动、查看日志 | `Notifier`, `MainActivity` |
| F-06 | 安装日志：自动记录、历史查看、导出分享、清空 | `logbook.rs`, `InstallLogStore`, `LogActivity` |

### 静默安装流程

- 外部 `VIEW` / `INSTALL_PACKAGE` / `SEND` / `DELETE` 意图由 `SilentInstallActivity` 接管：
  透明主题 + `FLAG_NOT_TOUCHABLE` / `FLAG_NOT_FOCUSABLE`，无布局、无转场动画、不进最近任务，
  用户停留在当前应用，界面不会被拉起。
- 默认不做任何安装前确认：静默入口的 `skipConfirm` 恒为 `true`，
  Rust 侧 `Config::default()` 的 `confirm_before_install` 为 `false`。
  主界面与设置里的「安装前确认」开关默认关闭，只在主动打开时才生效。
- `Notifier` 发一条 heads-up 气泡通知（静音、无震动），标题为「<安装包名> 正在安装中」，
  带进度条；完成或失败时更新同一条通知，成功可一键启动应用，任何结果都可点「查看日志」。
- 失败不会拉起任何界面，原始错误文本保留在通知详情与安装日志里。

### 安装日志

- Rust 核心在 `install::execute_with_context` / `uninstall::uninstall_with_context`
  的**成功与失败两条路径**上生成结构化记录：操作类型、安装包名/包名/版本、分片数与总大小、
  结果码、原始输出（裁剪至 2000 字符）、各阶段、耗时、会话 id、触发来源（`silent` / `ui`）。
- 记录通过 `PlatformBridge` 的 `logAppend` 落到 `filesDir/logs/install-log.jsonl`，
  Java 侧补时间戳与可读时间，保留最近 **500** 条，原子替换写入、统一 LF。
- 日志写入是旁路操作：持久化失败不影响安装结果。
- 准备阶段失败（未进入核心执行）由静默入口通过 `logRecord` 补记，保证后台链路任何结果都有记录。
- 应用内「安装日志」页可查看历史、导出为纯文本分享、一键清空；结果通知带「查看日志」动作。

## 3. 关键实现选择

- **静默安装走 `pm install-create / install-write / install-commit` 会话**，
  每个 split 通过 `pm install-write ... -` 从 stdin 流式写入，APK 不落盘。
- Shizuku 侧用 `IShizukuService.newProcess()` 以 shell 身份执行命令，
  通过 `IRemoteProcess` 的 `ParcelFileDescriptor` 三路流完成双向 I/O。
- 输入统一走 SAF `content://`；`stat` / `archiveEntries` / `readBytes`
  只做字节与元数据搬运，所有策略判断在 Rust。
- 安装前展示的包名/版本由 Rust 自写的 binary-XML 读取器解析
  `AndroidManifest.xml`，解析失败自动降级、不阻塞安装。
- 所有拼进 shell 的参数由 Rust 生成，包名经 `is_valid_package_name` 校验，避免注入。
- 写失败调用 `pm install-abandon` 回滚会话。
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
3. 之后从文件管理器用「打开方式 / 分享」把 APK 交给本应用即可：无确认框、不会跳到主界面，
   只在通知栏显示「XXX 正在安装中」及进度。
4. 也可在应用内「选择安装包并安装」，支持多选 APK / `.apks` / `.xapk`。
5. 安装结果更新同一条通知；成功可一键启动，失败可点开查看原始错误。
6. 卸载意图（`package:` URI）同样静默执行；应用内也可输入包名手动卸载。
7. 打开「安装日志」查看每一次操作的时间、结果、耗时与原始输出，可导出或清空。

## 6. 兼容性

| 平台要求 | 处理 |
|---|---|
| Android 16 (API 36) | targetSdk 36；`android:exported` 显式声明；16KB page size |
| Android 15 (API 35) | edge-to-edge：`fitsSystemWindows` + 系统栏配色 |
| Android 14 (API 34) | 无前台服务；`PendingIntent.FLAG_IMMUTABLE` |
| Android 13 (API 33) | `POST_NOTIFICATIONS` 动态申请；通知渠道；themed icon |
| Android 11+ (API 30) | `<queries>` 包可见性；全量 SAF，不用 `MANAGE_EXTERNAL_STORAGE` |
| Android 8+ (API 26) | adaptive icon + 单色图标 |

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
