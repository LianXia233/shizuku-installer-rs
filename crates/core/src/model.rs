use serde::{Deserialize, Serialize};

use crate::platform::StdinSource;

/// Shizuku 服务状态（来自平台桥接的原始观测值）。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShizukuStateRaw {
    /// Shizuku 服务是否可用（binder 已连接）。
    pub available: bool,
    /// -1 未知 / 0 拒绝 / 1 已授权。
    pub permission: i32,
    /// Shizuku 服务端版本，未知为 0。
    pub server_version: i32,
    /// 服务端 uid（shell=2000 / root=0），未知为 -1。
    pub server_uid: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ShizukuStatus {
    Unavailable,
    NeedPermission,
    Denied,
    Authorized,
}

impl ShizukuStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            ShizukuStatus::Unavailable => "UNAVAILABLE",
            ShizukuStatus::NeedPermission => "NEED_PERMISSION",
            ShizukuStatus::Denied => "DENIED",
            ShizukuStatus::Authorized => "AUTHORIZED",
        }
    }
}

/// 一个待安装输入源的元数据（由 SAF 提供，纯 I/O 观测）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceDescriptor {
    pub uri: String,
    pub display_name: String,
    pub size: u64,
    /// apk / apks / xapk / zip / unknown
    pub kind: String,
}

/// 归档内条目（纯枚举结果，不含业务判断）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArchiveEntry {
    pub name: String,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SplitPart {
    /// 传给 `pm install-write` 的 split 名。
    pub name: String,
    pub size: u64,
    pub stdin: StdinSource,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallOptions {
    /// -r 覆盖安装
    pub replace: bool,
    /// -g 授予清单声明的运行时权限
    pub grant_runtime_permissions: bool,
    /// -d 允许降级（默认关闭）
    pub allow_downgrade: bool,
    pub user_id: Option<i32>,
    pub installer_package: Option<String>,
    /// 成功后尝试删除安装包（仅当来源可删除）
    pub delete_source_after: bool,
    /// 跳过安装前确认（由 UI 在用户确认后置 true）
    pub skip_confirm: bool,
}

impl Default for InstallOptions {
    fn default() -> Self {
        Self {
            replace: true,
            grant_runtime_permissions: true,
            allow_downgrade: false,
            user_id: None,
            installer_package: None,
            delete_source_after: false,
            skip_confirm: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallPlan {
    pub parts: Vec<SplitPart>,
    pub total_size: u64,
    pub create_args: Vec<String>,
    pub package_hint: Option<String>,
    pub version_hint: Option<String>,
    pub requires_confirmation: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UninstallOptions {
    /// -k 保留数据与缓存
    pub keep_data: bool,
    pub user_id: Option<i32>,
}

impl Default for UninstallOptions {
    fn default() -> Self {
        Self { keep_data: false, user_id: None }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OpOutcome {
    pub ok: bool,
    /// SUCCESS / INSTALL_FAILED_* / DELETE_FAILED_* / SHELL_ERROR / ...
    pub code: String,
    pub message: String,
    /// 未裁剪的平台原始输出，供「复制错误」使用。
    pub raw: String,
}

impl OpOutcome {
    pub fn success(raw: impl Into<String>) -> Self {
        Self { ok: true, code: "SUCCESS".into(), message: "Success".into(), raw: raw.into() }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    pub stage: String,
    pub index: usize,
    pub total: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub confirm_before_install: bool,
    pub delete_apk_after_install: bool,
    pub allow_list: Vec<String>,
}

impl Default for Config {
    fn default() -> Self {
        // 静默安装语义：默认不要求任何安装前确认；确认是可选的高级开关。
        Self { confirm_before_install: false, delete_apk_after_install: false, allow_list: Vec::new() }
    }
}

/// 应用配置持久化的抽象；Android 侧由 Java SharedPreferences 桥接实现。
pub trait ConfigStore: Send + Sync {
    fn load(&self) -> Result<Config, crate::error::CoreError>;
    fn save(&self, cfg: &Config) -> Result<(), crate::error::CoreError>;
}
