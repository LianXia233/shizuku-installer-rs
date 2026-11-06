package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一次静默安装/卸载交给进程级后台线程。
 *
 * 指示器只使用系统悬浮窗（TYPE_APPLICATION_OVERLAY），不启动任何 Activity；
 * 没有权限时由 FloatingIndicator 借 Shizuku 自动授权，失败则静默放弃，
 * 因此不存在「后台启动 Activity 被限制」或「把本应用切到前台」的问题。
 */
final class TaskLauncher {
    private TaskLauncher() {
    }

    static void launch(Activity a, boolean uninstall, List<String> uris,
                       String uninstallPkg, String caller, String label) {
        Context app = a.getApplicationContext();
        FloatingIndicator.show(app, floatingEnabled());
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
