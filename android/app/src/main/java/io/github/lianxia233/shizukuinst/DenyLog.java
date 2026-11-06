package io.github.lianxia233.shizukuinst;

import android.content.Context;

import org.json.JSONObject;

/** 授权被拒绝时的统一记录 + 通知，入口与授权弹窗共用。 */
final class DenyLog {
    private DenyLog() {
    }

    static void record(Context app, boolean uninstall, String label,
                       String caller, String callerLabel) {
        App.executor().execute(() -> {
            try {
                JSONObject e = new JSONObject()
                        .put("op", uninstall ? "uninstall" : "install")
                        .put("label", label)
                        .put("appName", label)
                        .put("source", "silent")
                        .put("caller", caller)
                        .put("ok", false)
                        .put("code", "CALLER_DENIED")
                        .put("detail", "调用方未获静默安装授权");
                RustCore.dispatch("logRecord", e.toString());
            } catch (Throwable ignored) {
            }
        });
        Notifier.denied(app, label, callerLabel);
    }
}
