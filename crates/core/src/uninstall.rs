use crate::error::{CoreError, Result};
use crate::install::require_authorization;
use crate::model::{OpOutcome, UninstallOptions};
use crate::parse;
use crate::plan;
use crate::platform::{self, PlatformBridge, StdinSource};
use crate::util::is_valid_package_name;

/// F-03 静默卸载。
pub fn uninstall(
    b: &dyn PlatformBridge,
    package: &str,
    opts: &UninstallOptions,
) -> Result<OpOutcome> {
    require_authorization(b)?;
    let pkg = package.trim();
    if !is_valid_package_name(pkg) {
        return Err(CoreError::InvalidArgument(format!("非法包名: {pkg}")));
    }
    platform::publish_progress(b, "uninstall", 0, 1);
    let r = platform::exec(b, &plan::uninstall_args(pkg, opts), &StdinSource::None)?;
    let outcome = parse::uninstall_outcome(&r);
    platform::publish_progress(b, "done", 1, 1);
    Ok(outcome)
}

/// 启动已安装应用的 LAUNCHER Activity（F-05）。
pub fn launch(b: &dyn PlatformBridge, package: &str) -> Result<OpOutcome> {
    require_authorization(b)?;
    let pkg = package.trim();
    if !is_valid_package_name(pkg) {
        return Err(CoreError::InvalidArgument(format!("非法包名: {pkg}")));
    }
    let resolved = platform::exec(b, &plan::resolve_activity_args(pkg), &StdinSource::None)?;
    let component = parse::resolve_activity_component(&resolved.combined()).ok_or_else(|| {
        CoreError::Rejected(format!("找不到 {pkg} 的启动 Activity: {}", resolved.combined().trim()))
    })?;
    let r = platform::exec(b, &plan::am_start_args(&component), &StdinSource::None)?;
    if r.code == 0 {
        Ok(OpOutcome::success(r.combined()))
    } else {
        Ok(OpOutcome {
            ok: false,
            code: "LAUNCH_FAILED".into(),
            message: r.combined().trim().to_string(),
            raw: r.combined(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mock::MockBridge;
    use serde_json::json;

    fn authorized(b: &MockBridge) {
        b.push("shizukuState", json!({"available":true,"permission":1}));
    }

    #[test]
    fn rejects_shell_metacharacters() {
        let b = MockBridge::new();
        authorized(&b);
        let e = uninstall(&b, "a.b; rm -rf /", &UninstallOptions::default()).unwrap_err();
        assert!(matches!(e, CoreError::InvalidArgument(_)));
    }

    #[test]
    fn uninstall_success() {
        let b = MockBridge::new();
        authorized(&b);
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let o = uninstall(&b, "dev.example.app", &UninstallOptions::default()).unwrap();
        assert!(o.ok);
        let (_m, a) = &b.calls().into_iter().find(|(m, _)| m == "exec").unwrap();
        assert_eq!(a["argv"].as_array().unwrap().last().unwrap(), "dev.example.app");
    }

    #[test]
    fn uninstall_failure_keeps_raw_output() {
        let b = MockBridge::new();
        authorized(&b);
        b.push("exec", json!({"code":1,"stdout":"Failure [DELETE_FAILED_INTERNAL_ERROR]","stderr":""}));
        let o = uninstall(&b, "dev.example.app", &UninstallOptions::default()).unwrap();
        assert!(!o.ok);
        assert_eq!(o.code, "DELETE_FAILED_INTERNAL_ERROR");
        assert!(o.raw.contains("DELETE_FAILED_INTERNAL_ERROR"));
    }
}
