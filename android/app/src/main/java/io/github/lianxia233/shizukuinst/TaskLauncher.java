package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一次静默安装/卸载交给进程级后台线程。
 *
 * 悬浮指示器必须在这个「仍在前台」的时刻准备：没有 SYSTEM_ALERT_WINDOW 权限时
 * 会降级启动一个 44dp 的 Activity 小窗，而 Android 10+ 限制后台启动 Activity，
 * 所以在后台线程里再启动是不可靠的。
 */
final class TaskLauncher {
    private TaskLauncher() {
    }

    static void launch(Activity a, boolean uninstall, List<String> uris,
                       String uninstallPkg, String caller, String label) {
        FloatingIndicator.showForeground(a, floatingEnabled());
        Context app = a.getApplicationContext();
        final List<String> snapshot = new ArrayList<>(uris);
        App.executor().execute(uninstall
                ? SilentTask.uninstall(app, uninstallPkg, caller)
                : SilentTask.install(app, snapshot, caller, label));
    }

    private static boolean floatingEnabled() {
        try {
            JSONObject r = new JSONObject(RustCore.dispatch("status", "{}"));
            JSONObject d = r.optJSONObject("data");
            JSONObject cfg = d == null ? null : d.optJSONObject("config");
            return cfg == null || cfg.optBoolean("floatingIndicator", true);
        } catch (Throwable t) {
            return true;
        }
    }
}
