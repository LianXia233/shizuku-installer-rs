use serde::Serialize;

use crate::model::{ShizukuStateRaw, ShizukuStatus};

pub fn classify(raw: &ShizukuStateRaw) -> ShizukuStatus {
    if !raw.available {
        ShizukuStatus::Unavailable
    } else {
        match raw.permission {
            1 => ShizukuStatus::Authorized,
            0 => ShizukuStatus::Denied,
            _ => ShizukuStatus::NeedPermission,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Guidance {
    pub status: ShizukuStatus,
    pub title: String,
    pub action: String,
    pub hint: String,
}

/// 状态对应的下一步引导。文案为本实现原创。
pub fn guidance(status: ShizukuStatus) -> Guidance {
    let (title, action, hint) = match status {
        ShizukuStatus::Unavailable => (
            "Shizuku 服务未运行",
            "启动 Shizuku",
            "请先安装 Shizuku，并通过无线调试或 root 方式启动其服务后再返回本应用。",
        ),
        ShizukuStatus::NeedPermission => (
            "尚未获得 Shizuku 授权",
            "请求权限",
            "点击后向 Shizuku 申请 API 授权；授权仅用于以 shell 身份执行安装与卸载。",
        ),
        ShizukuStatus::Denied => (
            "Shizuku 授权被拒绝",
            "前往 Shizuku 手动授权",
            "请在 Shizuku 的应用管理中找到本应用并允许授权。",
        ),
        ShizukuStatus::Authorized => (
            "Shizuku 已就绪",
            "",
            "现在可以静默安装 APK 或卸载应用。",
        ),
    };
    Guidance {
        status,
        title: title.into(),
        action: action.into(),
        hint: hint.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_all_states() {
        assert_eq!(classify(&ShizukuStateRaw { available: false, permission: 1, ..Default::default() }), ShizukuStatus::Unavailable);
        assert_eq!(classify(&ShizukuStateRaw { available: true, permission: -1, ..Default::default() }), ShizukuStatus::NeedPermission);
        assert_eq!(classify(&ShizukuStateRaw { available: true, permission: 0, ..Default::default() }), ShizukuStatus::Denied);
        assert_eq!(classify(&ShizukuStateRaw { available: true, permission: 1, ..Default::default() }), ShizukuStatus::Authorized);
    }

    #[test]
    fn authorized_has_no_pending_action() {
        assert_eq!(guidance(ShizukuStatus::Authorized).action, "");
        assert_ne!(guidance(ShizukuStatus::NeedPermission).action, "");
    }
}
