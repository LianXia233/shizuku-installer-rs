package io.github.lianxia233.shizukuinst;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 进程级静默安装 / 卸载任务。
 *
 * 刻意不持有 Activity 引用：入口 Activity 在授权结束后立刻 finish，
 * 屏幕上只剩通知与左上角 40dp 小指示器，不存在全屏透明遮罩。
 * 任务串行执行，避免多个安装互相覆盖进度、通知与悬浮窗。
 */
final class SilentTask implements Runnable {
    private static final Object SERIAL = new Object();

    private final Context app;
    private final boolean uninstall;
    private final List<String> uris;
    private final String targetPackage;
    private final String caller;
    private volatile String label;
    private boolean floating = true;
    private boolean fallback = true;

    private SilentTask(Context app, boolean uninstall, List<String> uris,
                       String targetPackage, String caller, String label) {
        this.app = app;
        this.uninstall = uninstall;
        this.uris = uris;
        this.targetPackage = targetPackage;
        this.caller = caller;
        this.label = label == null || label.trim().isEmpty() ? "安装包" : label.trim();
    }

    static SilentTask install(Context c, List<String> uris, String caller, String label) {
        return new SilentTask(c.getApplicationContext(), false, uris, null, caller, label);
    }

    static SilentTask uninstall(Context c, String pkg, String caller) {
        return new SilentTask(c.getApplicationContext(), true, null, pkg, caller, pkg);
    }

    @Override
    public void run() {
        synchronized (SERIAL) {
            readConfig();
            // 幂等兜底：TaskLauncher 已调用过 show；这里是后台任务的再次确认。
            // 无论有无悬浮窗权限都不会启动 Activity，屏幕始终只有系统悬浮小窗。
            FloatingIndicator.show(app, floating);
            try {
                if (uninstall) {
                    runUninstall();
                } else {
                    runInstall();
                }
            } finally {
                ShizukuBridge.setProgressListener(null);
                FloatingIndicator.dismiss();
            }
        }
    }

    private void readConfig() {
        try {
            JSONObject r = call("status", "{}");
            JSONObject d = r.optJSONObject("data");
            JSONObject cfg = d == null ? null : d.optJSONObject("config");
            if (cfg != null) {
                floating = cfg.optBoolean("floatingIndicator", true);
                fallback = cfg.optBoolean("fallbackToSystemInstaller", true);
            }
        } catch (Throwable ignored) {
            // 配置读取失败时保持默认：显示指示器、失败可降级
        }
    }

    // ---------------------------------------------------------------- 安装

    private void runInstall() {
        if (uris == null || uris.isEmpty()) {
            failPrepare("未收到安装包");
            return;
        }
        Notifier.start(app, label, false);
        attachProgress();
        try {
            JSONObject opts = defaultOptions();
            JSONObject prepare = new JSONObject()
                    .put("uris", new JSONArray(uris))
                    .put("options", opts);
            JSONObject r = call("prepareInstall", prepare.toString());
            if (!r.optBoolean("ok")) {
                failPrepare(errorText(r));
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject plan = d == null ? null : d.optJSONObject("plan");
            JSONObject summary = d == null ? null : d.optJSONObject("summary");
            if (plan == null) {
                failPrepare("未能生成安装计划");
                return;
            }

            // 解析真实应用名（中文优先）：已安装应用名 > AXML label > 清洗后的文件名
            String resolved = AppNames.resolveInstallLabel(app, uris.get(0), summary);
            if (resolved != null && !resolved.isEmpty()) {
                label = resolved;
            }
            Notifier.start(app, label, false);

            String pkg = summary == null ? "" : summary.optString("package", "");
            JSONObject commit = new JSONObject()
                    .put("plan", plan)
                    .put("options", opts)
                    .put("context", context());
            JSONObject cr = call("commitInstall", commit.toString());
            if (!cr.optBoolean("ok")) {
                finishFailure(AppNames.errorText(app, errorCodeOf(cr), errorText(cr)));
                return;
            }
            JSONObject outcome = outcomeOf(cr);
            boolean ok = outcome != null && outcome.optBoolean("ok");
            String raw = outcome == null ? "" : outcome.optString("raw", "");
            String code = outcome == null ? "" : outcome.optString("code", "");
            if (ok) {
                Notifier.finish(app, label, false, true, "", safePkg(pkg));
            } else {
                finishFailure(AppNames.errorText(app, code, raw));
            }
        } catch (Throwable t) {
            failPrepare(String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- 卸载

    private void runUninstall() {
        String pkg = targetPackage == null ? "" : targetPackage.trim();
        if (pkg.isEmpty()) {
            failPrepare("未指定包名");
            return;
        }
        String installed = AppNames.labelForPackage(app, pkg);
        label = installed == null ? pkg : installed;
        Notifier.start(app, label, true);
        attachProgress();
        try {
            JSONObject a = new JSONObject()
                    .put("package", pkg)
                    .put("options", new JSONObject().put("keepData", false))
                    .put("context", context());
            JSONObject r = call("uninstall", a.toString());
            if (!r.optBoolean("ok")) {
                finishFailure(AppNames.errorText(app, errorCodeOf(r), errorText(r)));
                return;
            }
            JSONObject outcome = outcomeOf(r);
            boolean ok = outcome != null && outcome.optBoolean("ok");
            String raw = outcome == null ? "" : outcome.optString("raw", "");
            String code = outcome == null ? "" : outcome.optString("code", "");
            if (ok) {
                Notifier.finish(app, label, true, true, "", "");
            } else {
                finishFailure(AppNames.errorText(app, code, raw));
            }
        } catch (Throwable t) {
            failPrepare(String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- 收尾

    private void attachProgress() {
        ShizukuBridge.setProgressListener((stage, index, total) -> {
            Notifier.progress(app, label, uninstall, index, total, stage);
            FloatingIndicator.progress(total > 0 ? Math.min(100, index * 100 / total) : -1);
        });
    }

    private void failPrepare(String msg) {
        record("PREPARE_FAILED", msg);
        finishFailure(AppNames.errorText(app, "PREPARE_FAILED", msg));
    }

    private void finishFailure(String zh) {
        Notifier.finish(app, label, uninstall, false, zh, "");
        if (!uninstall && fallback && uris != null && !uris.isEmpty()) {
            Intent i = buildSystemInstallIntent(uris.get(0));
            if (i != null) {
                Notifier.fallback(app, label, zh, i);
                try {
                    app.startActivity(i);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 降级：显式指定系统安装器组件，避免再次匹配到本应用的 INSTALL_PACKAGE 过滤器。
     * uriString 是自家 StagedProvider 的 content:// URI，通过临时 grant 安全交给
     * 系统安装器，既不依赖已失效的调用方授权，也不会暴露 file:// 触发异常。
     */
    private Intent buildSystemInstallIntent(String uriString) {
        try {
            Uri u = Uri.parse(uriString);
            PackageManager pm = app.getPackageManager();
            String self = app.getPackageName();
            Intent base = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            base.setDataAndType(u, "application/vnd.android.package-archive");
            base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ComponentName target = resolveExternal(pm, base, self);
            if (target == null) {
                base.setAction(Intent.ACTION_VIEW);
                target = resolveExternal(pm, base, self);
            }
            Intent out;
            if (target != null) {
                out = new Intent(base);
                out.setComponent(target);
            } else {
                out = Intent.createChooser(base, null);
            }
            out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ComponentName resolveExternal(PackageManager pm, Intent i, String self) {
        try {
            List<ResolveInfo> rs = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (rs == null) {
                return null;
            }
            for (ResolveInfo r : rs) {
                if (r.activityInfo == null || self.equals(r.activityInfo.packageName)) {
                    continue;
                }
                return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 准备阶段 / 异常路径补记日志，保证后台链路任何结果都留痕。 */
    private void record(String code, String detail) {
        try {
            JSONObject e = new JSONObject()
                    .put("op", uninstall ? "uninstall" : "install")
                    .put("label", label)
                    .put("appName", label)
                    .put("source", "silent")
                    .put("ok", false)
                    .put("code", code)
                    .put("detail", detail == null ? "" : detail);
            if (caller != null && !caller.isEmpty()) {
                e.put("caller", caller);
            }
            call("logRecord", e.toString());
        } catch (Throwable ignored) {
        }
    }

    private JSONObject context() throws Exception {
        JSONObject c = new JSONObject()
                .put("label", label)
                .put("appName", label)
                .put("source", "silent");
        if (caller != null && !caller.isEmpty()) {
            c.put("caller", caller);
        }
        return c;
    }

    private JSONObject defaultOptions() throws Exception {
        return new JSONObject()
                .put("replace", true)
                .put("grantRuntimePermissions", true)
                .put("allowDowngrade", false)
                .put("deleteSourceAfter", false)
                .put("skipConfirm", true);
    }

    private JSONObject outcomeOf(JSONObject r) {
        JSONObject d = r.optJSONObject("data");
        JSONObject result = d == null ? null : d.optJSONObject("result");
        return result == null ? null : result.optJSONObject("outcome");
    }

    private JSONObject call(String method, String args) {
        try {
            return new JSONObject(RustCore.dispatch(method, args));
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false)
                        .put("error", new JSONObject().put("message", String.valueOf(e)));
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private String errorText(JSONObject r) {
        JSONObject e = r.optJSONObject("error");
        return e == null ? "" : e.optString("message", "");
    }

    private String errorCodeOf(JSONObject r) {
        JSONObject e = r.optJSONObject("error");
        if (e == null) {
            return "";
        }
        String k = e.optString("kind", "");
        return k.isEmpty() ? e.optString("message", "") : k;
    }

    private String safePkg(String p) {
        return p == null || "null".equals(p) ? "" : p;
    }
}
