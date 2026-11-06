# shizuku-installer-rs

基于 **Rust 核心 + Shizuku** 的 Android 静默安装器。借助 Shizuku 提供的 shell 身份建立
PackageInstaller 会话，安装 / 卸载应用时无需拉起系统安装界面，也不会跳到应用主界面；
每一次后台操作都会写入本地安装日志。

| 项目 | 值 |
|---|---|
| 包名 | `io.github.lianxia233.shizukuinst` |
| 系统要求 | minSdk **26**（Android 8+）/ targetSdk **36**（Android 16）/ compileSdk **36** |
| 权限来源 | Shizuku API **13.1.5**（`rikka.shizuku.*` + `IShizukuService`），唯一特权执行通道 |
| 架构 | 业务逻辑 100% 在 `crates/core`（Rust，host 可单测）；Java 仅为无策略判断的 I/O 胶水 |
| 产物 | 四 ABI（`arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`）、16KB 页对齐、v2 签名 |
| 许可 | **GNU GPL-3.0**，见 `LICENSE` |
| 版本记录 | 见 [CHANGELOG.md](CHANGELOG.md) |

## 1. 功能

| 编号 | 功能 | 实现位置 |
|---|---|---|
| F-01 | Shizuku 服务/授权状态检查、请求授权、引导 | `state.rs`, `api.rs`, `ShizukuBridge` |
| F-02 | 静默安装（无确认、不弹界面）：单 APK / 多 split / 归档格式，含社交软件追加的 `.apk.1` 后缀 | `install.rs`, `archive.rs`, `plan.rs`, `util.rs`, `FileNames` |
| F-03 | 静默卸载（含保留数据），从应用列表选择目标 | `uninstall.rs`, `AppPickerActivity`, `Uninstaller` |
| F-04 | 免确认包名白名单；安装前确认为可选开关（默认关闭） | `config.rs`, `SettingsActivity` |
| F-05 | 通知气泡显示安装中 / 进度 / 结果，一键启动、查看日志 | `Notifier`, `SilentTask` |
| F-06 | 安装日志：自动记录、历史查看、导出分享、清空 | `logbook.rs`, `InstallLogStore`, `LogActivity` |
| F-07 | **调用方授权**：首次调用由短时前台服务保活，直接在当前应用上方弹悬浮卡片询问；无悬浮窗权限时退化为可当场决策的横幅通知；设置页按用户/系统应用筛选并勾选 | `auth.rs`, `AuthPrompt`, `AuthForegroundService`, `Notifier`, `SettingsActivity`, `AppList` |
| F-08 | **无遮罩后台指示**：左上角科幻风旋转指示器，点击穿透、不染黑系统栏 | `FloatingIndicator`, `SciFiSpinner` |
| F-09 | **失败降级（默认不打断）**：只发一条可点击通知，点通知才进系统安装器 | `SilentTask`, `Notifier` |
| F-10 | **中文展示**：通知、日志、错误原因显示应用实际名称与中文说明 | `AppNames`, `apk_meta.rs`, `logbook.rs` |
| F-11 | **写入兜底**：stdin 流式写入失败时自动改用文件路径写入 | `install.rs`, `plan.rs`, `ShizukuBridge` |
| F-12 | **成功后删除安装包**：删除调用方原文件，读写权限不足时由 shell 兜底并写日志 | `SilentTask`, `ShizukuBridge` |
| F-13 | **关于页**：简介、可点击仓库地址、开发者、开源许可 | `AboutActivity` |

## 2. 支持格式与拉起方式

`AndroidManifest.xml` 对全部支持格式做了显式声明，`SilentInstallActivity` 统一接管。

| 扩展名 | 说明 | 处理方式 |
|---|---|---|
| `.apk` | 单个 APK | 直接建立会话 |
| `.apks` | Android App Bundle 导出的分片归档 | 按 zip 挑 `.apk` 条目，`base.apk` 优先 |
| `.xapk` / `.akp` | APKPure 等渠道的分片包 | 同上 |
| `.apkm` / `.apkmx` | APKMirror 等渠道的分片包 | 同上 |
| `.zip` | 包含多个 `.apk` 的通用归档 | 同上 |
| `*.apk.1` / `*.apk.2` / `*.apk.bin` … | 微信 / QQ 等社交软件转发时追加的备份后缀，文件本体仍是原安装包 | 先剥掉后缀，再按真实扩展名处理 |
| 多个 APK | 系统分享 / 文件管理器多选 | 合并为一次 multi-split 会话 |

> 社交软件转发会把 `xxx.apk` 改成 `xxx.apk.1`（也有 `.2`、多位数字、`.bin`、
> `.download` 等）以绕过系统安装器识别。`FileNames.normalize()` 与 Rust
> `util::normalize_name()` 在链路最前端统一剥掉这层后缀：`xxx.apk.1` 按单个
> APK 安装，`xxx.apks.1` 仍按归档拆分；staged 缓存文件名与通知 / 日志里的
> 显示名同步归一，不会出现 `xxx.apk.1.apk` 这类拼接结果。

拉起通道：

- **打开方式 / 安装**：`ACTION_VIEW`、`ACTION_INSTALL_PACKAGE`，匹配 `content://` 与
  `file://` + APK / 归档类 MIME，末尾用 `*/*` 兜底未知类型；另有独立的扩展名
  分组（含大写形式）与「备份后缀」分组（`.*\.apk\.1`、`.*\.apk\..*` 等），
  适配只给路径、不给 MIME 的调用方。
- **系统分享**：`ACTION_SEND` / `ACTION_SEND_MULTIPLE` 独立分组，不声明 scheme，
  `mimeType` 用 `*/*` 兜底，保证系统分享菜单与第三方应用分享都能拉起。
  入口会按显示名过滤明显无关的类型（图片 / 文本 / 音视频等），不会误触发安装。
- **卸载**：`ACTION_DELETE` / `ACTION_UNINSTALL_PACKAGE` + `package:` URI。
- **应用内**：主界面「选择安装包并安装」支持 SAF 多选；「卸载应用」从已安装应用列表选择。

## 3. 架构

```
┌──────────────────────────── Android App ────────────────────────────┐
│  MainActivity / SettingsActivity / AppPickerActivity / AboutActivity  │
│  LogActivity (Java, UI 与生命周期)                                    │
│  SilentInstallActivity (零/短窗口入口) · CallerAuthActivity (兜底页) │
│  AuthPrompt (跨应用悬浮授权卡片) · AuthForegroundService (短时保活) │
│  StagedFiles + StagedProvider · FileNames (后缀归一化)              │
│  TaskLauncher · SilentTask (进程级后台任务) · FloatingIndicator      │
│  Notifier · AppNames · AppList                                       │
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

只有一个 JNI 入口、一个平台桥接原语，便于审计与测试。外部安装 / 卸载意图由
`SilentInstallActivity` 接管，任务在应用进程后台线程执行；主界面只在用户主动点图标时出现。

## 4. 关键行为

### 静默与窗口

- 入口按「调用方是否等待结果」走两条路径：**不等待结果**（多数文件管理器 / 分享）在
  `onCreate` 内立即 `finish()`，framework 因 `mFinished=true` 跳过 `wm.addView`，
  完全零窗口；**等待结果**（`startActivityForResult` / `EXTRA_RETURN_RESULT`）用
  `@style/SilentEntryTheme` 驻留 1400ms，把结果回推过调用方在 MIUI 上的 1 秒
  「结果忽略窗口」，窗口被压到 1x1、全透明、不可聚焦、不可触摸、无 dim、系统栏透明。
- 延迟只作用于**结果回传**，后台安装在 `onCreate` 立即开始，通知与进度不等待。
- 主题刻意不设 `windowIsFloating`：floating + translucent 在部分 ROM 上会被铺成
  整屏半透明背板，是早期「一层透明遮罩」的来源。
- **安装链路零窗口**：失败降级默认只发通知，不自动 `startActivity`；指示器与授权卡片
  只用 `TYPE_APPLICATION_OVERLAY`，不会切换 task、不会 pause 前台应用。
- 「按返回才能继续操作」是顶层存在**可聚焦窗口**的确定信号，且该窗口未必属于本应用，
  排查时先看 `mCurrentFocus` 属主。完整定位过程见 [CHANGELOG.md](CHANGELOG.md) 的 v1.0.2。

### 调用方授权

- Rust `auth::decide` 返回 `ALLOW` / `ASK` / `DENY`：
  - `ALLOW`：立即后台安装，回传 `RESULT_OK`；
  - `ASK`：**跨应用悬浮卡片**叠在当前应用上方直接询问，允许后立即续装本次请求，
    入口回传 `RESULT_CANCELED` + `CALLER_PENDING`；
  - `DENY`：静默阻止、记一条日志，回传 `RESULT_CANCELED`。
- 卡片由 `AuthForegroundService`（`shortService`）承载：入口对普通调用会立即
  `finish`，进程随即降为 cached，异步申请完悬浮窗权限再 `addView` 往往来不及，
  国产 ROM 上表现为「弹窗没弹出来」。前台服务把进程钉在前台优先级，
  决策完成或 90 秒超时后立即 `stopForeground` + `stopSelf`，不做常驻。
- 拿不到悬浮窗权限时先借 Shizuku(shell) 自动授权；仍失败则退化为一条
  **带「允许并继续 / 拒绝」按钮的横幅通知**，按钮携带完整请求快照，
  点按即完成授权并续装本次请求，**不降级成需要切回本应用的普通页面**。
  通知正文点击才进入普通不透明授权页，全程不弹 Activity/Dialog 遮罩窗口。
- 匿名来源（识别不到调用方）**永远不能被永久记住**，否则一次允许会放行后续所有匿名调用。
- 设置页列出系统已安装应用：支持「全部 / 用户应用 / 系统应用」筛选、搜索、一键清空，
  **已授权应用置顶**并带绿色徽章，系统应用以独立徽章标出。

### 数据生命周期

- 外部 `content://` 的读权限只在本 Activity 存活期间有效，入口在仍持有权限时把安装包
  复制到应用私有缓存，由自家只读 `StagedProvider` 暴露；后台任务全程只读这个 URI。
- 调用方给的**原始 URI** 单独保留一份：开启「成功后删除安装包」时删的是原文件。
  `ContentResolver.delete` 常因只有读权限而失败，此时由 Shizuku(shell) 解析真实路径
  执行 `rm -f`（argv 数组，不经 shell 拼接），并把删除结果写入安装日志。
- 输入统一走 `content://`；`stat` / `archiveEntries` / `readBytes` 只做字节与元数据搬运，
  所有策略判断在 Rust。包名经 `is_valid_package_name` 校验，避免命令注入。

### 日志与失败处理

- Rust 核心在成功与失败两条路径都生成结构化记录（操作类型、应用名、包名/版本、
  分片数与大小、结果码、原始输出、耗时、来源、调用方），落到
  `filesDir/logs/install-log.jsonl`，保留最近 **500** 条，原子替换、统一 LF。
- 准备阶段失败、授权被拒由入口通过 `logRecord` 补记；日志写入是旁路，不影响安装结果。
- 通知标题显示应用实际名称（已安装应用名 > APK 内 `application@label` > 文件名），
  失败原因显示中文说明并保留原始错误码；日志同样以中文名作为标题。
- 主界面不再展示「本次执行日志」，完整记录由独立的「安装日志」页承担。

## 5. 构建

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
python3 scripts/gen_icons.py                    # 生成各密度图标资源
python3 scripts/gen_axml_fixture.py             # 重新生成测试样本
```

**云编译已启用**：`.github/workflows/build.yml` 在 `push main` 或手动
`workflow_dispatch` 时构建，产物为 `ShizukuInstaller.apk`，并默认创建 GitHub Release
附上 APK（tag 形如 `v<version>-build.<run_number>`，依赖 `permissions: contents: write`，
由 runner 预装的 `gh` CLI 完成，不使用第三方 release action）。

### 签名一致性

`android/app/signing/release.keystore` 随仓库分发，`build.gradle.kts` 让 `debug`
（`scripts/build.sh` 走 `assembleDebug`）与 `release` 都显式指向它。因此**沙箱本地
构建与 GitHub Actions 云构建产出的 APK 证书逐字节一致**，可以互相覆盖安装。

此前两端各自使用随机生成的 `~/.android/debug.keystore`（实测证书 SHA-256 分别为
`a38042a6…` 与 `61e339a9…`），导致同名版本无法覆盖升级、不同来源的 Release
签名各不相同。固定密钥是唯一能保证两端一致的方案。

> 该密钥是**公开的自签名密钥**，只用于保证构建可复现与版本间签名一致，
> 不提供任何身份信任，请勿当作私有发布密钥使用。自 v1.0.4 起签名证书变更，
> 从更早版本升级需先卸载旧版，此后各版本可正常覆盖安装。

## 6. 使用

1. 安装并启动 Shizuku（无线调试或 root），确认服务运行中。
2. 首次打开本应用，完成 Shizuku 授权并允许通知权限（进度气泡需要）。
3. 在「设置」里确认静默安装授权总开关，按用户 / 系统应用筛选并勾选允许发起静默安装的应用。
4. 从文件管理器用「打开方式 / 系统分享」把 APK 交给本应用：已授权来源直接后台执行；
   首次调用直接在当前应用上方弹卡片询问，允许后立即安装。
5. 安装期间停留在当前应用，通知栏显示进度，屏幕左上角有小旋转指示器；不跳主界面。
6. 结果更新同一条通知；成功可一键启动，失败显示中文原因并可点通知改用系统安装器。
7. 「卸载应用」从应用列表选择，可筛选系统应用，确认时提示风险；卸载意图
   （`package:` URI）同样静默执行。
8. 打开「安装日志」查看每一次操作的时间、应用名、结果、耗时与原因，可导出或清空。

## 7. 兼容性

| 平台要求 | 处理 |
|---|---|
| Android 16 (API 36) | targetSdk 36；`android:exported` 显式声明；16KB page size |
| Android 15 (API 35) | `fitsSystemWindows` + 系统栏配色；小窗口避开状态栏 |
| Android 14 (API 34) | 授权询问用 `shortService` 前台服务保活；`PendingIntent.FLAG_IMMUTABLE`；FGS type 与 manifest 声明一致 |
| Android 13 (API 33) | `POST_NOTIFICATIONS` 动态申请；通知渠道；themed icon |
| Android 11+ (API 30) | `<queries>` 包可见性；`QUERY_ALL_PACKAGES` 用于枚举应用；全量 SAF |
| Android 10+ (API 29) | 后台启动 Activity 受限，指示器与授权卡片改用系统悬浮窗 |
| Android 8+ (API 26) | `TYPE_APPLICATION_OVERLAY`；adaptive icon + 单色图标 |

`SYSTEM_ALERT_WINDOW` 只用于可选的进度指示器与跨应用授权卡片，可由 Shizuku 自动授予；
未授予时退化为纯通知反馈。`QUERY_ALL_PACKAGES` 只用于设置页与卸载选择器展示应用列表，
不上传、不持久化到任何远端。

## 8. 目录

```
Cargo.toml                 Cargo workspace
crates/core/               纯 Rust 业务逻辑 + 单元测试
crates/android-ffi/        JNI cdylib
android/                   Gradle 工程（Java 胶水、Manifest、资源）
android/app/signing/       固定签名密钥（本地与云编译共用）
icons/ scripts/            图标素材与构建/资源生成脚本
.github/workflows/         CI（云编译并发布 Release）
CHANGELOG.md               版本更新日志
LICENSE                    GPL-3.0
```

## 9. 许可

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整条款见 `LICENSE`。
图标素材与生成的图形资源随仓库一同提供，同样适用该许可。
