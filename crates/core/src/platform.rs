use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::error::{CoreError, Result};
use crate::model::{ArchiveEntry, ShizukuStateRaw, SourceDescriptor};

/// 命令 stdin 的来源。UI 侧只搬运字节，不做任何业务判断。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum StdinSource {
    None,
    /// 直接把一个 content:// URI 的字节流作为 stdin。
    Uri { uri: String },
    /// 把归档内某个条目的解压字节流作为 stdin。
    ArchiveEntry { uri: String, entry: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExecResult {
    pub code: i32,
    pub stdout: String,
    pub stderr: String,
}

impl ExecResult {
    pub fn combined(&self) -> String {
        match (self.stdout.is_empty(), self.stderr.is_empty()) {
            (true, true) => String::new(),
            (false, true) => self.stdout.clone(),
            (true, false) => self.stderr.clone(),
            (false, false) => format!("{}\n{}", self.stdout, self.stderr),
        }
    }
}

/// 平台桥接：唯一一个原语。Android 侧由 JNI 转发到 Java `ShizukuBridge.call`。
///
/// 所有入参/出参都是 JSON，便于审计与在 host 侧完整模拟。
pub trait PlatformBridge {
    fn call(&self, method: &str, args: &Value) -> Result<Value>;
}

fn field_str<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}

pub fn exec(b: &dyn PlatformBridge, argv: &[String], stdin: &StdinSource) -> Result<ExecResult> {
    let v = b.call("exec", &json!({ "argv": argv, "stdin": stdin }))?;
    Ok(ExecResult {
        code: v.get("code").and_then(Value::as_i64).unwrap_or(-1) as i32,
        stdout: field_str(&v, "stdout").to_string(),
        stderr: field_str(&v, "stderr").to_string(),
    })
}

pub fn stat(b: &dyn PlatformBridge, uri: &str) -> Result<SourceDescriptor> {
    let v = b.call("stat", &json!({ "uri": uri }))?;
    let uri = field_str(&v, "uri").to_string();
    if uri.is_empty() {
        return Err(CoreError::Bridge("stat 未返回 uri".into()));
    }
    Ok(SourceDescriptor {
        uri,
        display_name: field_str(&v, "displayName").to_string(),
        size: v.get("size").and_then(Value::as_u64).unwrap_or(0),
        kind: field_str(&v, "kind").to_string(),
    })
}

pub fn archive_entries(b: &dyn PlatformBridge, uri: &str) -> Result<Vec<ArchiveEntry>> {
    let v = b.call("archiveEntries", &json!({ "uri": uri }))?;
    let arr = v.get("entries").and_then(Value::as_array).cloned().unwrap_or_default();
    let mut out = Vec::new();
    for e in arr {
        out.push(ArchiveEntry {
            name: field_str(&e, "name").to_string(),
            size: e.get("size").and_then(Value::as_u64).unwrap_or(0),
        });
    }
    Ok(out)
}

pub fn shizuku_state(b: &dyn PlatformBridge) -> Result<ShizukuStateRaw> {
    let v = b.call("shizukuState", &json!({}))?;
    Ok(ShizukuStateRaw {
        available: v.get("available").and_then(Value::as_bool).unwrap_or(false),
        permission: v.get("permission").and_then(Value::as_i64).unwrap_or(-1) as i32,
        server_version: v.get("serverVersion").and_then(Value::as_i64).unwrap_or(0) as i32,
        server_uid: v.get("serverUid").and_then(Value::as_i64).unwrap_or(-1) as i32,
    })
}

pub fn request_permission(b: &dyn PlatformBridge) -> Result<bool> {
    let v = b.call("requestPermission", &json!({}))?;
    Ok(v.get("granted").and_then(Value::as_bool).unwrap_or(false))
}

/// 读取某个 stdin 来源的字节（hex 编码），上限由调用方控制。
pub fn read_bytes(b: &dyn PlatformBridge, stdin: &StdinSource, limit: usize) -> Result<Vec<u8>> {
    let v = b.call("readBytes", &json!({ "stdin": stdin, "limit": limit }))?;
    let hex = field_str(&v, "hex");
    crate::util::hex_decode(hex)
}

pub fn delete_source(b: &dyn PlatformBridge, uri: &str) -> Result<bool> {
    let v = b.call("deleteSource", &json!({ "uri": uri }))?;
    Ok(v.get("deleted").and_then(Value::as_bool).unwrap_or(false))
}

/// 把配置读写也走同一个桥（避免在 Rust 侧引入文件 I/O 差异）。
pub fn config_load(b: &dyn PlatformBridge) -> Result<crate::model::Config> {
    let v = b.call("configLoad", &json!({}))?;
    serde_json::from_value(v).map_err(CoreError::from)
}

pub fn config_save(b: &dyn PlatformBridge, cfg: &crate::model::Config) -> Result<()> {
    let v = b.call("configSave", &serde_json::to_value(cfg)?)?;
    // 平台侧可以返回 {}（fire-and-forget），只有显式 ok:false 才算失败。
    if v.get("ok").and_then(Value::as_bool) == Some(false) {
        return Err(CoreError::Bridge("configSave 失败".into()));
    }
    Ok(())
}

/// 请求平台侧发送通知/更新 UI 进度（fire-and-forget）。
pub fn publish_progress(b: &dyn PlatformBridge, stage: &str, index: usize, total: usize) {
    let _ = b.call("progress", &json!({ "stage": stage, "index": index, "total": total }));
}
