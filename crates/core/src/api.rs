//! JSON 门面：JNI 层只暴露 `dispatch(method, argsJson) -> json` 一个入口。

use serde::de::DeserializeOwned;
use serde_json::{json, Value};

use crate::config;
use crate::error::{CoreError, Result};
use crate::install;
use crate::model::{Config, InstallOptions, InstallPlan, UninstallOptions};
use crate::platform::{self, PlatformBridge};
use crate::state;
use crate::uninstall;

pub struct Core<B: PlatformBridge> {
    bridge: B,
}

fn error_kind(e: &CoreError) -> &'static str {
    match e {
        CoreError::ShizukuNotReady(_) => "SHIZUKU_NOT_READY",
        CoreError::Bridge(_) => "BRIDGE",
        CoreError::Session(_) => "SESSION",
        CoreError::Write(_) => "WRITE",
        CoreError::Archive(_) => "ARCHIVE",
        CoreError::InvalidArgument(_) => "INVALID_ARGUMENT",
        CoreError::Rejected(_) => "REJECTED",
        CoreError::Json(_) => "JSON",
    }
}

fn opt<T: DeserializeOwned + Default>(a: &Value, key: &str) -> T {
    a.get(key)
        .and_then(|v| serde_json::from_value(v.clone()).ok())
        .unwrap_or_default()
}

fn string_list(a: &Value, key: &str) -> Vec<String> {
    a.get(key)
        .and_then(Value::as_array)
        .map(|v| v.iter().filter_map(Value::as_str).map(str::to_string).collect())
        .unwrap_or_default()
}

impl<B: PlatformBridge> Core<B> {
    pub fn new(bridge: B) -> Self {
        Self { bridge }
    }

    pub fn bridge(&self) -> &B {
        &self.bridge
    }

    /// 永不 panic：任何错误都编码为 JSON 返回。
    pub fn dispatch(&self, method: &str, args_json: &str) -> String {
        let args: Value = serde_json::from_str(args_json).unwrap_or_else(|_| json!({}));
        match self.inner(method, &args) {
            Ok(data) => json!({ "ok": true, "data": data }).to_string(),
            Err(e) => json!({
                "ok": false,
                "error": { "kind": error_kind(&e), "message": e.to_string() }
            })
            .to_string(),
        }
    }

    fn cfg(&self) -> Config {
        platform::config_load(&self.bridge).unwrap_or_default()
    }

    fn save_cfg(&self, c: &Config) -> Result<()> {
        platform::config_save(&self.bridge, c)
    }

    fn inner(&self, method: &str, a: &Value) -> Result<Value> {
        match method {
            "status" => {
                let raw = platform::shizuku_state(&self.bridge)?;
                let st = state::classify(&raw);
                Ok(json!({
                    "state": st,
                    "guidance": state::guidance(st),
                    "raw": raw,
                    "config": self.cfg(),
                }))
            }
            "requestPermission" => {
                let granted = platform::request_permission(&self.bridge)?;
                let raw = platform::shizuku_state(&self.bridge)?;
                let st = state::classify(&raw);
                Ok(json!({ "granted": granted, "state": st, "guidance": state::guidance(st) }))
            }
            "setConfig" => {
                let c: Config = opt(a, "config");
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "allowListAdd" => {
                let mut c = self.cfg();
                config::allow_list_add(&mut c, a["package"].as_str().unwrap_or(""))?;
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "allowListRemove" => {
                let mut c = self.cfg();
                config::allow_list_remove(&mut c, a["package"].as_str().unwrap_or(""));
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "allowListClear" => {
                let mut c = self.cfg();
                config::allow_list_clear(&mut c);
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "prepareInstall" => {
                let p = install::prepare(
                    &self.bridge,
                    &string_list(a, "uris"),
                    &opt::<InstallOptions>(a, "options"),
                    &self.cfg(),
                )?;
                Ok(json!({ "plan": p, "summary": install::describe(&p) }))
            }
            "commitInstall" => {
                let p: InstallPlan = serde_json::from_value(
                    a.get("plan").cloned().ok_or_else(|| CoreError::InvalidArgument("缺少 plan".into()))?,
                )?;
                let out = install::execute(&self.bridge, &p, &opt::<InstallOptions>(a, "options"))?;
                Ok(json!({ "result": out }))
            }
            "uninstall" => {
                let out = uninstall::uninstall(
                    &self.bridge,
                    a["package"].as_str().unwrap_or(""),
                    &opt::<UninstallOptions>(a, "options"),
                )?;
                Ok(json!({ "result": out }))
            }
            "launch" => {
                let out = uninstall::launch(&self.bridge, a["package"].as_str().unwrap_or(""))?;
                Ok(json!({ "result": out }))
            }
            other => Err(CoreError::InvalidArgument(format!("未知方法: {other}"))),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mock::MockBridge;

    #[test]
    fn dispatch_never_panics_on_bad_input() {
        let c = Core::new(MockBridge::new());
        let r: Value = serde_json::from_str(&c.dispatch("status", "not-json")).unwrap();
        assert_eq!(r["ok"], true); // 空参数 -> 默认状态
        let r: Value = serde_json::from_str(&c.dispatch("nope", "{}")).unwrap();
        assert_eq!(r["ok"], false);
        assert_eq!(r["error"]["kind"], "INVALID_ARGUMENT");
    }

    #[test]
    fn status_reports_unavailable_with_guidance() {
        let b = MockBridge::new();
        b.push("shizukuState", json!({"available":false,"permission":0}));
        b.push("configLoad", json!({}));
        let c = Core::new(b);
        let r: Value = serde_json::from_str(&c.dispatch("status", "{}")).unwrap();
        assert_eq!(r["data"]["state"], "UNAVAILABLE");
        assert_ne!(r["data"]["guidance"]["action"], "");
    }

    #[test]
    fn allow_list_roundtrip() {
        let b = MockBridge::new();
        b.push("configLoad", json!({"confirmBeforeInstall":true,"deleteApkAfterInstall":false,"allowList":[]}));
        let c = Core::new(b);
        let r: Value = serde_json::from_str(&c.dispatch("allowListAdd", r#"{"package":"dev.example.app"}"#)).unwrap();
        assert_eq!(r["ok"], true);
        let saved = c.bridge().calls().into_iter().find(|(m, _)| m == "configSave").unwrap().1;
        assert_eq!(saved["allowList"][0], "dev.example.app");
    }
}
