//! JNI 边界。Java 胶水层只保留一个 native 方法：
//! `RustCore.dispatch(method, argsJson) -> resultJson`
//!
//! Rust 通过 `ShizukuBridge.call(method, argsJson)` 回调 Java，
//! 由 Java 完成 Shizuku API / ContentResolver / Process 的无逻辑 I/O。

use std::cell::RefCell;

use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::Value;
use shizuku_core::error::{CoreError, Result};
use shizuku_core::platform::PlatformBridge;
use shizuku_core::Core;

const BRIDGE_CLASS: &str = "io/github/lianxia233/shizukuinst/ShizukuBridge";
const BRIDGE_SIG: &str =
    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

struct JniBridge<'a, 'local> {
    env: RefCell<&'a mut JNIEnv<'local>>,
}

impl<'a, 'local> JniBridge<'a, 'local> {
    fn new(env: &'a mut JNIEnv<'local>) -> Self {
        Self { env: RefCell::new(env) }
    }
}

fn jni_err(e: impl std::fmt::Display) -> CoreError {
    CoreError::Bridge(format!("JNI: {e}"))
}

impl PlatformBridge for JniBridge<'_, '_> {
    fn call(&self, method: &str, args: &Value) -> Result<Value> {
        let mut binding = self.env.borrow_mut();
        let env: &mut JNIEnv = &mut **binding;

        let jm = env.new_string(method).map_err(jni_err)?;
        let ja = env.new_string(args.to_string()).map_err(jni_err)?;
        let om: &JObject = jm.as_ref();
        let oa: &JObject = ja.as_ref();
        let ret = env
            .call_static_method(
                BRIDGE_CLASS,
                "call",
                BRIDGE_SIG,
                &[JValue::Object(om), JValue::Object(oa)],
            )
            .map_err(jni_err)?;
        let obj = ret.l().map_err(jni_err)?;
        let js = JString::from(obj);
        let text: String = env.get_string(&js).map_err(jni_err)?.into();
        serde_json::from_str(&text).map_err(|e| {
            CoreError::Bridge(format!("桥接返回非法 JSON: {e}; raw={text}"))
        })
    }
}

/// `RustCore.dispatch(String method, String argsJson): String`
#[no_mangle]
pub extern "system" fn Java_io_github_lianxia233_shizukuinst_RustCore_dispatch<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    method: JString<'local>,
    args: JString<'local>,
) -> jstring {
    let m: String = env.get_string(&method).map(|x| x.into()).unwrap_or_default();
    let a: String = env.get_string(&args).map(|x| x.into()).unwrap_or_default();

    let bridge = JniBridge::new(&mut env);
    let core = Core::new(bridge);
    let out = core.dispatch(&m, &a);

    match env.new_string(out) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
