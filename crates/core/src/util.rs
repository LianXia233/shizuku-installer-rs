use crate::error::{CoreError, Result};

pub fn hex_decode(s: &str) -> Result<Vec<u8>> {
    let b = s.as_bytes();
    if b.len() % 2 != 0 {
        return Err(CoreError::Bridge("hex 长度非偶数".into()));
    }
    let mut out = Vec::with_capacity(b.len() / 2);
    let mut i = 0;
    while i < b.len() {
        let hi = hex_val(b[i]).ok_or_else(|| CoreError::Bridge("非法 hex 字符".into()))?;
        let lo = hex_val(b[i + 1]).ok_or_else(|| CoreError::Bridge("非法 hex 字符".into()))?;
        out.push((hi << 4) | lo);
        i += 2;
    }
    Ok(out)
}

fn hex_val(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

/// Android 包名规则：至少两段，段以字母开头，仅含 [A-Za-z0-9_]。
/// 用于防止把用户输入直接拼进 shell 命令造成参数注入。
pub fn is_valid_package_name(s: &str) -> bool {
    if s.is_empty() || s.len() > 255 {
        return false;
    }
    let segs: Vec<&str> = s.split('.').collect();
    if segs.len() < 2 {
        return false;
    }
    segs.iter().all(|seg| {
        !seg.is_empty()
            && seg.as_bytes()[0].is_ascii_alphabetic()
            && seg.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
    })
}

/// 把 SAF 提供的显示名推导为输入类型。
pub fn kind_of(display_name: &str) -> &'static str {
    let n = display_name.to_ascii_lowercase();
    if n.ends_with(".apks") {
        "apks"
    } else if n.ends_with(".xapk") || n.ends_with(".akp") {
        "xapk"
    } else if n.ends_with(".apk") {
        "apk"
    } else if n.ends_with(".zip") {
        "zip"
    } else {
        "unknown"
    }
}

/// 生成合法的 split 名，避免路径分隔符与冲突。
pub fn normalize_split_name(raw: &str, index: usize, used: &[String]) -> String {
    let leaf = raw.rsplit(['/', '\\']).next().unwrap_or("").trim();
    let mut name: String = if leaf.is_empty() {
        format!("split_{index}.apk")
    } else {
        leaf.chars()
            .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-' { c } else { '_' })
            .collect()
    };
    if name.len() > 96 {
        name.truncate(96);
    }
    if used.contains(&name) {
        let base = name.clone();
        let mut n = 2;
        while used.contains(&name) {
            name = format!("{base}.{n}");
            n += 1;
        }
    }
    name
}

pub fn human_size(bytes: u64) -> String {
    const U: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut v = bytes as f64;
    let mut i = 0;
    while v >= 1024.0 && i < U.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if i == 0 {
        format!("{} {}", bytes, U[i])
    } else {
        format!("{:.2} {}", v, U[i])
    }
}
