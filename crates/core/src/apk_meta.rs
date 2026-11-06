//! 轻量 binary-XML 读取器，仅用于安装前确认时展示包名/版本。
//!
//! 这是一个 best-effort 解析器：任何格式异常都返回 `None`，
//! 绝不阻塞安装主流程。实现只读取 `manifest` 元素的少数属性。

use serde::Serialize;

const RES_STRING_POOL_TYPE: u16 = 0x0001;
const RES_XML_TYPE: u16 = 0x0003;
const RES_XML_START_ELEMENT_TYPE: u16 = 0x0102;
const TYPE_STRING: u8 = 0x03;
const TYPE_INT_DEC: u8 = 0x10;
const TYPE_INT_HEX: u8 = 0x11;

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct ApkMeta {
    pub package: Option<String>,
    pub version_name: Option<String>,
    pub version_code: Option<i64>,
    pub split: Option<String>,
}

fn u16(d: &[u8], o: usize) -> Option<u16> {
    Some(u16::from_le_bytes([*d.get(o)?, *d.get(o + 1)?]))
}
fn u32(d: &[u8], o: usize) -> Option<u32> {
    Some(u32::from_le_bytes([*d.get(o)?, *d.get(o + 1)?, *d.get(o + 2)?, *d.get(o + 3)?]))
}

/// 解析 AXML/ARSC 共用的 ResStringPool chunk。
fn parse_string_pool(d: &[u8]) -> Option<Vec<String>> {
    if u16(d, 0)? != RES_STRING_POOL_TYPE {
        return None;
    }
    let hs = u16(d, 2)? as usize;
    let size = u32(d, 4)? as usize;
    if size > d.len() || hs < 28 {
        return None;
    }
    let count = u32(d, 8)? as usize;
    let flags = u32(d, 16)?;
    let str_start = u32(d, 20)? as usize;
    let utf8 = flags & 0x100 != 0;
    if hs + count * 4 > size {
        return None;
    }
    let mut out = Vec::with_capacity(count.min(4096));
    for i in 0..count {
        let off = u32(d, hs + i * 4)? as usize;
        let mut q = str_start.checked_add(off)?;
        if q >= size {
            out.push(String::new());
            continue;
        }
        let s = if utf8 {
            // 变长长度：UTF-16 长度、UTF-8 字节长度
            let read_len = |pos: &mut usize| -> Option<usize> {
                let a = *d.get(*pos)?;
                if a & 0x80 != 0 {
                    let b = *d.get(*pos + 1)?;
                    *pos += 2;
                    Some((((a & 0x7f) as usize) << 8) | b as usize)
                } else {
                    *pos += 1;
                    Some(a as usize)
                }
            };
            let _len16 = read_len(&mut q)?;
            let len8 = read_len(&mut q)?;
            let end = q.checked_add(len8)?;
            if end > size {
                return None;
            }
            String::from_utf8_lossy(&d[q..end]).into_owned()
        } else {
            let mut n = u16(d, q)? as usize;
            q += 2;
            if n & 0x8000 != 0 {
                n = ((n & 0x7fff) << 16) | u16(d, q)? as usize;
                q += 2;
            }
            let end = q.checked_add(n * 2)?;
            if end > size {
                return None;
            }
            let mut units = Vec::with_capacity(n);
            for k in 0..n {
                units.push(u16(d, q + k * 2)?);
            }
            String::from_utf16_lossy(&units)
        };
        out.push(s);
    }
    Some(out)
}

fn pool_get(pool: &[String], idx: u32) -> Option<String> {
    if idx == 0xFFFF_FFFF {
        return None;
    }
    pool.get(idx as usize).cloned()
}

pub fn parse_axml(data: &[u8]) -> Option<ApkMeta> {
    if u16(data, 0)? != RES_XML_TYPE {
        return None;
    }
    let total = (u32(data, 4)? as usize).min(data.len());
    let mut pool: Vec<String> = Vec::new();
    let mut off = 8usize;
    while off + 8 <= total {
        let t = u16(data, off)?;
        let sz = u32(data, off + 4)? as usize;
        if sz < 8 || off + sz > total {
            break;
        }
        let chunk = &data[off..off + sz];
        match t {
            RES_STRING_POOL_TYPE => pool = parse_string_pool(chunk).unwrap_or_default(),
            RES_XML_START_ELEMENT_TYPE => {
                if let Some(m) = parse_manifest_element(chunk, &pool) {
                    return Some(m);
                }
            }
            _ => {}
        }
        off += sz;
    }
    None
}

fn parse_manifest_element(c: &[u8], pool: &[String]) -> Option<ApkMeta> {
    let name = pool_get(pool, u32(c, 20)?)?;
    if name != "manifest" {
        return None;
    }
    let attr_start = u16(c, 24)? as usize;
    let attr_size = u16(c, 26)? as usize;
    let attr_count = u16(c, 28)? as usize;
    if attr_size < 20 || attr_count > 256 {
        return None;
    }
    let base = 16usize.checked_add(attr_start)?;
    let mut meta = ApkMeta::default();
    for i in 0..attr_count {
        let a = base.checked_add(i.checked_mul(attr_size)?)?;
        if a + 20 > c.len() {
            break;
        }
        let aname = pool_get(pool, u32(c, a + 4)?).unwrap_or_default();
        let raw = u32(c, a + 8)?;
        let dtype = *c.get(a + 15)?;
        let data = u32(c, a + 16)?;
        let value = if dtype == TYPE_STRING {
            pool_get(pool, data)
        } else if dtype == TYPE_INT_DEC || dtype == TYPE_INT_HEX {
            None
        } else {
            pool_get(pool, raw)
        };
        match aname.as_str() {
            "package" => meta.package = value,
            "versionName" => meta.version_name = value,
            "versionCode" | "versionCodeMajor" => {
                if dtype == TYPE_INT_DEC || dtype == TYPE_INT_HEX {
                    meta.version_code = Some(data as i64);
                } else {
                    meta.version_code = value.and_then(|v| v.parse::<i64>().ok());
                }
            }
            "split" => meta.split = value,
            _ => {}
        }
    }
    Some(meta)
}

#[cfg(test)]
mod tests {
    use super::*;

    const FIXTURE: &[u8] = include_bytes!("../tests/fixtures/manifest_sample.bin");

    #[test]
    fn non_axml_returns_none() {
        assert_eq!(parse_axml(b"not an axml"), None);
        assert_eq!(parse_axml(&[]), None);
    }

    #[test]
    fn truncated_input_never_panics() {
        for n in 0..FIXTURE.len().min(64) {
            let _ = parse_axml(&FIXTURE[..n]);
        }
    }

    #[test]
    fn reads_manifest_attributes() {
        let m = parse_axml(FIXTURE).expect("样本应可解析");
        assert_eq!(m.package.as_deref(), Some("io.github.lianxia233.sample"));
        assert_eq!(m.version_name.as_deref(), Some("2.3.4"));
        assert_eq!(m.version_code, Some(234));
    }
}
