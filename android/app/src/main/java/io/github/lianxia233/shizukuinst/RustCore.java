package io.github.lianxia233.shizukuinst;

/** Rust 核心的唯一入口。所有业务逻辑都在 shizuku-core crate 中。 */
public final class RustCore {
    static {
        System.loadLibrary("shizuku_core");
    }

    private RustCore() {
    }

    public static native String dispatch(String method, String argsJson);
}
