use serde::Serialize;
use serde_json::json;

use crate::apk_meta;
use crate::archive;
use crate::config;
use crate::error::{CoreError, Result};
use crate::model::{Config, InstallOptions, InstallPlan, OpOutcome, Progress, SplitPart};
use crate::parse;
use crate::plan;
use crate::platform::{self, PlatformBridge, StdinSource};
use crate::state;
use crate::util;

const MANIFEST_ENTRY: &str = "AndroidManifest.xml";
const META_LIMIT: usize = 512 * 1024;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct InstallOutcome {
    pub outcome: OpOutcome,
    pub session_id: Option<i64>,
    pub stages: Vec<Progress>,
    pub deleted_source: bool,
}

pub(crate) fn require_authorization(b: &dyn PlatformBridge) -> Result<()> {
    let raw = platform::shizuku_state(b)?;
    let st = state::classify(&raw);
    if st != crate::model::ShizukuStatus::Authorized {
        return Err(CoreError::ShizukuNotReady(state::guidance(st).title));
    }
    Ok(())
}

fn stdin_uri(part: &SplitPart) -> Option<String> {
    match &part.stdin {
        StdinSource::Uri { uri } => Some(uri.clone()),
        StdinSource::ArchiveEntry { uri, .. } => Some(uri.clone()),
        StdinSource::None => None,
    }
}

/// F-02 准备阶段：检查授权、枚举 split、推断元数据、判定是否需要确认。
pub fn prepare(
    b: &dyn PlatformBridge,
    uris: &[String],
    opts: &InstallOptions,
    cfg: &Config,
) -> Result<InstallPlan> {
    require_authorization(b)?;
    if uris.is_empty() || uris.len() > 32 {
        return Err(CoreError::InvalidArgument("安装包数量需在 1..32 之间".into()));
    }
    for u in uris {
        if u.is_empty() || u.len() > 4096 {
            return Err(CoreError::InvalidArgument("URI 无效".into()));
        }
    }

    let parts: Vec<SplitPart> = if uris.len() == 1 {
        let sd = platform::stat(b, &uris[0])?;
        let owned_kind = util::kind_of(&sd.display_name).to_string();
        let kind = if sd.kind.is_empty() { owned_kind.as_str() } else { sd.kind.as_str() };
        match kind {
            "apks" | "xapk" | "zip" => {
                let entries = platform::archive_entries(b, &sd.uri)?;
                archive::parts_from_archive(&sd.uri, &entries)?
            }
            _ => archive::parts_from_sources(&[(sd.uri, sd.display_name, sd.size)])?,
        }
    } else {
        let mut tuples = Vec::with_capacity(uris.len());
        for u in uris {
            let sd = platform::stat(b, u)?;
            tuples.push((sd.uri, sd.display_name, sd.size));
        }
        archive::parts_from_sources(&tuples)?
    };

    let total_size: u64 = parts.iter().map(|p| p.size).sum();
    if total_size == 0 {
        return Err(CoreError::InvalidArgument("无法获取安装包大小".into()));
    }

    // best-effort：仅对直接传入的 APK 解析 binary manifest；归档场景跳过。
    let (mut package_hint, mut version_hint) = (None, None);
    if let Some(StdinSource::Uri { uri }) = parts.first().map(|p| &p.stdin) {
        let src = StdinSource::ArchiveEntry { uri: uri.clone(), entry: MANIFEST_ENTRY.into() };
        if let Ok(bytes) = platform::read_bytes(b, &src, META_LIMIT) {
            if let Some(m) = apk_meta::parse_axml(&bytes) {
                package_hint = m.package;
                version_hint = m.version_name;
            }
        }
    }

    Ok(InstallPlan {
        parts,
        total_size,
        create_args: plan::install_create_args(opts, total_size),
        package_hint: package_hint.clone(),
        version_hint,
        requires_confirmation: config::needs_confirmation(
            cfg,
            package_hint.as_deref(),
            opts.skip_confirm,
        ),
    })
}

/// F-02 执行阶段：会话式安装，字节流不落盘。
pub fn execute(
    b: &dyn PlatformBridge,
    plan_data: &InstallPlan,
    opts: &InstallOptions,
) -> Result<InstallOutcome> {
    require_authorization(b)?;
    if plan_data.parts.is_empty() {
        return Err(CoreError::InvalidArgument("安装计划为空".into()));
    }

    let total = plan_data.parts.len() + 2usize;
    let mut stages = Vec::new();

    platform::publish_progress(b, "create_session", 0, total);
    let created = platform::exec(b, &plan_data.create_args, &StdinSource::None)?;
    let created_raw = created.combined();
    if created.code != 0 || !created_raw.contains("Success") {
        return Err(CoreError::Session(format!(
            "install-create 失败 (exit {}): {}",
            created.code,
            created_raw.trim()
        )));
    }
    let session = parse::parse_session_id(&created_raw)?;
    stages.push(Progress { stage: "create_session".into(), index: 0, total });

    for (i, part) in plan_data.parts.iter().enumerate() {
        platform::publish_progress(b, "write_split", i + 1, total);
        let argv = plan::install_write_args(session, &part.name, part.size);
        let r = platform::exec(b, &argv, &part.stdin)?;
        let raw = r.combined();
        let ok = r.code == 0
            && (raw.contains("Success") || raw.contains("streamed") || raw.trim().is_empty());
        if !ok {
            let _ = platform::exec(b, &plan::install_abandon_args(session), &StdinSource::None);
            return Err(CoreError::Write(format!(
                "写入 {} 失败 (exit {}): {}",
                part.name,
                r.code,
                raw.trim()
            )));
        }
        stages.push(Progress { stage: format!("write_split:{}", part.name), index: i + 1, total });
    }

    platform::publish_progress(b, "commit", total - 1, total);
    let committed = platform::exec(b, &plan::install_commit_args(session), &StdinSource::None)?;
    let outcome = parse::install_outcome(&committed);
    stages.push(Progress { stage: "commit".into(), index: total - 1, total });

    let mut deleted_source = false;
    if outcome.ok && opts.delete_source_after {
        let mut seen = Vec::new();
        for p in &plan_data.parts {
            if let Some(u) = stdin_uri(p) {
                if !seen.contains(&u) {
                    seen.push(u.clone());
                    if platform::delete_source(b, &u).unwrap_or(false) {
                        deleted_source = true;
                    }
                }
            }
        }
    }
    platform::publish_progress(b, "done", total, total);

    Ok(InstallOutcome { outcome, session_id: Some(session), stages, deleted_source })
}

/// 供 UI 展示的计划摘要（不参与执行决策）。
pub fn describe(plan_data: &InstallPlan) -> serde_json::Value {
    json!({
        "parts": plan_data.parts.len(),
        "totalSize": plan_data.total_size,
        "totalSizeHuman": util::human_size(plan_data.total_size),
        "package": plan_data.package_hint,
        "version": plan_data.version_hint,
        "requiresConfirmation": plan_data.requires_confirmation,
        "splitNames": plan_data.parts.iter().map(|p| p.name.clone()).collect::<Vec<_>>(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mock::MockBridge;
    use serde_json::json;

    fn authorized(b: &MockBridge) {
        b.push("shizukuState", json!({"available": true, "permission": 1, "serverVersion": 13, "serverUid": 2000}));
    }

    #[test]
    fn prepare_single_apk_builds_create_args() {
        let b = MockBridge::new();
        authorized(&b);
        b.push("stat", json!({"uri":"content://apk/1","displayName":"demo.apk","size":4096,"kind":"apk"}));
        b.push("readBytes", json!({"hex":""}));
        let p = prepare(&b, &["content://apk/1".into()], &InstallOptions::default(), &Config::default()).unwrap();
        assert_eq!(p.parts.len(), 1);
        assert_eq!(p.parts[0].name, "base.apk");
        assert_eq!(p.total_size, 4096);
        assert_eq!(p.create_args.last().unwrap(), "4096");
        assert!(!p.requires_confirmation, "默认静默安装，不应要求确认");
    }

    #[test]
    fn prepare_archive_expands_splits() {
        let b = MockBridge::new();
        authorized(&b);
        b.push("stat", json!({"uri":"content://bundle","displayName":"app.apks","size":999,"kind":"apks"}));
        b.push("archiveEntries", json!({"entries":[
            {"name":"base.apk","size":100},
            {"name":"split_config.arm64_v8a.apk","size":50},
            {"name":"meta.json","size":5}
        ]}));
        let p = prepare(&b, &["content://bundle".into()], &InstallOptions::default(), &Config::default()).unwrap();
        assert_eq!(p.parts.len(), 2);
        assert_eq!(p.parts[0].name, "base.apk");
        assert_eq!(p.total_size, 150);
        assert!(!p.requires_confirmation);
    }

    #[test]
    fn prepare_rejects_without_authorization() {
        let b = MockBridge::new();
        b.push("shizukuState", json!({"available": false, "permission": 0}));
        let e = prepare(&b, &["content://x".into()], &InstallOptions::default(), &Config::default()).unwrap_err();
        assert!(matches!(e, CoreError::ShizukuNotReady(_)));
    }

    #[test]
    fn execute_full_session_flow() {
        let b = MockBridge::new();
        authorized(&b);
        for _ in 0..2 { authorized(&b); }
        b.push("exec", json!({"code":0,"stdout":"Success: created install session [777]","stderr":""}));
        b.push("exec", json!({"code":0,"stdout":"Success: streamed 10 bytes","stderr":""}));
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let plan_data = InstallPlan {
            parts: vec![SplitPart {
                name: "base.apk".into(), size: 10,
                stdin: StdinSource::Uri { uri: "content://a".into() },
            }],
            total_size: 10,
            create_args: plan::install_create_args(&InstallOptions::default(), 10),
            package_hint: None, version_hint: None, requires_confirmation: false,
        };
        let out = execute(&b, &plan_data, &InstallOptions::default()).unwrap();
        assert!(out.outcome.ok);
        assert_eq!(out.session_id, Some(777));
        let calls = b.calls();
        let execs: Vec<_> = calls.iter().filter(|(m, _)| m == "exec").collect();
        assert_eq!(execs.len(), 3);
        assert!(execs[0].1["argv"].as_array().unwrap()[1].as_str() == Some("install-create"));
        assert!(execs[1].1["argv"].as_array().unwrap().last().unwrap().as_str() == Some("-"));
        assert!(execs[2].1["argv"].as_array().unwrap()[1].as_str() == Some("install-commit"));
    }

    #[test]
    fn execute_abandons_on_write_failure() {
        let b = MockBridge::new();
        for _ in 0..2 { authorized(&b); }
        b.push("exec", json!({"code":0,"stdout":"Success: created install session [9]","stderr":""}));
        b.push("exec", json!({"code":1,"stdout":"","stderr":"write error"}));
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let plan_data = InstallPlan {
            parts: vec![SplitPart { name: "base.apk".into(), size: 1, stdin: StdinSource::Uri { uri: "u".into() } }],
            total_size: 1,
            create_args: vec!["pm".into(), "install-create".into()],
            package_hint: None, version_hint: None, requires_confirmation: false,
        };
        let e = execute(&b, &plan_data, &InstallOptions::default()).unwrap_err();
        assert!(matches!(e, CoreError::Write(_)));
        let execs: Vec<_> = b.calls().into_iter().filter(|(m, _)| m == "exec").collect();
        assert!(execs.iter().any(|(_, a)| a["argv"].as_array().unwrap().contains(&json!("install-abandon"))));
    }
}
