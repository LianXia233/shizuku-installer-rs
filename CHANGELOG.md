# 更新日志

本文件记录各版本的可见变更。按时间倒序排列，版本号与 `android/app/build.gradle.kts`
中的 `versionName`、`Cargo.toml` 中的 `version` 一致。

---

## v1.0.3

### 新增

- **跨应用授权卡片**：外部应用首次发起静默安装时，以系统悬浮窗直接叠在**当前应用上方**
  弹出「允许 / 拒绝」，用户不必切回本应用；点「允许」后立即续装本次请求。
  卡片使用 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`、无 dim，卡片外触摸全部穿透。
  拿不到悬浮窗权限时先借 Shizuku(shell) 自动授权，仍失败才回退为「待授权」通知。
- **关于页**：应用简介、可点击跳转的开源仓库地址、开发者信息与 GPL-3.0 开源许可说明。
- **卸载应用选择器**：主界面「卸载应用」改为读取已安装应用列表选择，不再要求手输包名；
  保留「输入包名」兼容入口。
- **应用列表筛选**：设置页授权列表与卸载选择器均支持
  「全部 / 用户应用 / 系统应用」筛选，系统应用以独立徽章与警示文案标出。
- **声明全部支持格式与系统分享**：`AndroidManifest.xml` 显式声明
  `.apk` / `.apks` / `.xapk` / `.akp` / `.apkm` / `.apkmx` / `.zip`（含大写），
  MIME 组覆盖 `ACTION_VIEW` / `INSTALL_PACKAGE`，独立的 `ACTION_SEND` /
  `SEND_MULTIPLE` 分组用 `*/*` 兜底，保证系统分享菜单与第三方应用分享都能拉起。
  入口按显示名过滤明显无关的文件类型，避免误分享触发安装。
- `CHANGELOG.md`：把原先散落在 README 里的版本演进与实机定位记录剪切归档。

### 优化

- **设置页排版重做**：标题栏加返回；五个开关改为「短标题 + 小字说明」两行结构，
  避免长文案换行撑高卡片；应用授权卡片独立占满剩余空间，筛选、搜索、计数、提示分区清晰。
- **已授权应用置顶**：授权列表排序改为「已授权 → 用户应用 → 系统应用 → 名称」，
  已授权项显示绿色徽章，切换开关后立即重新归组。
- **成功后删除安装包可用**：外部调用路径把调用方给的原始 URI 一路传到后台任务，
  开启开关后删除的是**原文件**而非本应用缓存副本；ContentResolver 删除失败时
  由 Shizuku(shell) 解析真实路径执行 `rm -f`，并写入日志说明删了几个、有无失败。
  悬浮卡片授权与通知授权页两条续装路径同样携带原始 URI。
- 主界面移除「本次执行日志」区域，完整记录统一由独立的「安装日志」页承担。
- 新增 `.apkm` / `.apkmx` 归档输入支持，归档分支统一按 zip 挑 `.apk` 条目。

---

## v1.0.2

### 修复：调用方透明弹窗与黑色系统栏（2026-11-07 二次实机定位）

早期版本曾把根因误判为「没有回传 `RESULT_OK`」。1.0.1 实机复测证明
`setResult(RESULT_OK)` 已执行（`finish result=-1 detail=ACCEPTED`，往返 2ms），
弹窗依旧，故该结论作废。以下为修正后的根因与方案。

- 现场证据（`dumpsys window` / `dumpsys activity activities` / `uiautomator dump` /
  `logcat`）：`mCurrentFocus` 是**调用方**窗口（Mihon
  `ExtensionInstallActivity`），`fmt=TRANSPARENT`、全屏、视图树只有空的
  `android:id/content`、零 `clickable`；`state=RESUMED finishing=false` 停留数分钟；
  `taskDescription.statusBarColor=ff000000` 且窗口缺
  `DRAWS_SYSTEM_BAR_BACKGROUNDS` → 黑系统栏、触摸被吞、只能按返回键退出。
- 真根因在调用方源码：检测到 `hasMiuiPackageInstaller` 时，`onCreate` 设
  `ignoreResult=true` 与 `ignoreUntil = System.nanoTime() + 1s`；`onActivityResult`
  若在 1 秒内到达，执行 `hasIgnoredResult = true; return` —— **不调用 `finish()`**，
  且判断发生在 resultCode 之前，`RESULT_OK` / `RESULT_CANCELED` 一律被吞。
  它只靠后续 `onStart()` 里的 `if (hasIgnoredResult) finish()` 兜底。
- 零窗口安装器 2ms 内结束，调用方在结果派发时已走完 `onStart/onResume`，此后再无
  `onStart` → 透明窗口永久停留。系统安装器之所以正常，是因为它显示真实界面让调用方
  进入 stopped，返回时 `onStart()` 会兜底 finish。
- 修复：把结果回推到忽略窗口之后。`RESULT_DELAY_MS = 1400ms` 留出 MIUI 调度余量，
  使 `onActivityResult` 走 `checkInstallationResult(resultCode); finish()` 正常分支。
  等待结果的调用方改用 `@style/SilentEntryTheme` 短窗口驻留，窗口在
  `onCreate` / `onResume` / `addView` 后三次申明为 1x1、全透明、
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`、无 dim、系统栏透明。
  普通调用（不等待结果）仍是零窗口。后台安装不等待延迟，在 `onCreate` 立即开始。
- 结论：**零窗口与正确回传结果都只是必要条件**。被 `startActivityForResult` 拉起的
  安装器还必须适配调用方自身的结果处理时序，否则「透明弹窗」会以调用方窗口的形式
  出现，且单改 resultCode 无效。

### CI

- GitHub Actions 第一方 action 升级到声明 `node24` 运行时的版本：
  `actions/checkout@v5`、`actions/setup-java@v5`、`actions/upload-artifact@v6`
  （注意 `upload-artifact@v5` 仍是 node20）。
- 每次 `push main` / `workflow_dispatch` 构建完成后**默认创建 GitHub Release 并附上 APK**，
  tag 形如 `v<version>-build.<run_number>`，由 runner 预装的 `gh` CLI 完成，
  重跑同一 run 改为 `--clobber` 覆盖同名资产。

---

## v1.0.1

- 回传安装结果（`setResult` + `EXTRA_RESULT_DETAIL`），消除调用方透明中转窗口残留。
- 入口改用 framework `Theme.NoDisplay`，安装链路零窗口；失败降级默认只发通知，
  不再自动 `startActivity` 拉起系统安装器（可显式开启，带风险提示）。
- 调用方授权：外部应用未获授权时只发一条「待授权」通知，安装过程中不弹任何窗口；
  设置页可勾选允许哪些应用，匿名来源永不记住。
- 左上角科幻风旋转指示器、中文应用名与错误文案、写入失败自动兜底为文件路径写入。
- 以 **GPL-3.0** 发布；启用 GitHub Actions 云编译产出 Release APK。

---

## v1.0.0

- 首个可用版本：Rust 核心 + JNI + Java 胶水，Shizuku(shell) 身份执行
  `pm install-create / install-write / install-commit` 会话。
- 静默安装单 APK / 多 split / `.apks` / `.xapk`、静默卸载、免确认白名单、
  通知进度与结果、安装日志（记录 / 导出 / 清空）、四 ABI 与 16KB 页对齐。
