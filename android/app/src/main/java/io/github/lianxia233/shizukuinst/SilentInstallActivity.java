package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 透明无界面入口：接管外部「打开方式 / 分享 / 安装 / 卸载」意图。
 * 不加载布局、不拦截触摸、不获取焦点，全程后台执行，只通过通知气泡反馈。
 */
public final class SilentInstallActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile String label = "安装包";
    private volatile boolean uninstall;
    private volatile boolean ended;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        Notifier.ensureChannels(this);
        ShizukuBridge.setProgressListener((stage, index, total) ->
                Notifier.progress(App.context(), label, uninstall, index, total, stage));
        handle(getIntent());
    }

    @Override
    protected void onDestroy() {
        ShizukuBridge.setProgressListener(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void handle(Intent i) {
        if (i == null || i.getAction() == null) {
            endQuietly();
            return;
        }
        String action = i.getAction();
        if (Intent.ACTION_DELETE.equals(action)
                || "android.intent.action.UNINSTALL_PACKAGE".equals(action)) {
            Uri u = i.getData();
            String pkg = u == null ? "" : u.getSchemeSpecificPart();
            if (pkg.isEmpty()) {
                fail("未指定包名");
                return;
            }
            uninstall = true;
            label = pkg;
            Notifier.start(this, label, true);
            worker.execute(() -> doUninstall(pkg));
            return;
        }

        List<String> uris = new ArrayList<>();
        Uri single = i.getData();
        if (single == null) {
            single = i.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (single != null) {
            uris.add(single.toString());
        }
        ArrayList<Uri> extras = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (extras != null) {
            for (Uri u : extras) {
                if (u != null && !uris.contains(u.toString())) {
                    uris.add(u.toString());
                }
            }
        }
        ClipData clip = i.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) {
                Uri u = clip.getItemAt(k).getUri();
                if (u != null && !uris.contains(u.toString())) {
                    uris.add(u.toString());
                }
            }
        }
        for (String s : uris) {
            try {
                getContentResolver().takePersistableUriPermission(
                        Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
        if (uris.isEmpty()) {
            fail("未收到安装包");
            return;
        }
        label = displayName(Uri.parse(uris.get(0)));
        Notifier.start(this, label, false);
        worker.execute(() -> doInstall(uris));
    }

    private void doInstall(List<String> uris) {
        try {
            JSONObject opts = defaultOptions();
            JSONObject prepare = new JSONObject()
                    .put("uris", new JSONArray(uris))
                    .put("options", opts);
            JSONObject r = call("prepareInstall", prepare.toString());
            if (!r.optBoolean("ok")) {
                fail(errorText(r));
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject plan = d == null ? null : d.optJSONObject("plan");
            JSONObject summary = d == null ? null : d.optJSONObject("summary");
            if (plan == null) {
                fail("未能生成安装计划");
                return;
            }
            String pkg = summary == null ? "" : summary.optString("package", "");
            JSONObject commit = new JSONObject().put("plan", plan).put("options", opts)
                    .put("context", new JSONObject().put("label", label).put("source", "silent"));
            JSONObject cr = call("commitInstall", commit.toString());
            if (!cr.optBoolean("ok")) {
                complete(false, errorText(cr), "");
                return;
            }
            JSONObject crd = cr.optJSONObject("data");
            JSONObject result = crd == null ? null : crd.optJSONObject("result");
            JSONObject outcome = result == null ? null : result.optJSONObject("outcome");
            boolean ok = outcome != null && outcome.optBoolean("ok");
            String raw = outcome == null ? "" : outcome.optString("raw", "");
            String code = outcome == null ? "" : outcome.optString("code", "");
            String detail = raw.trim().isEmpty() ? code : raw.trim();
            complete(ok, detail, ok ? pkg : "");
        } catch (Throwable t) {
            fail(String.valueOf(t));
        }
    }

    private void doUninstall(String pkg) {
        try {
            JSONObject a = new JSONObject()
                    .put("package", pkg)
                    .put("options", new JSONObject().put("keepData", false))
                    .put("context", new JSONObject().put("label", pkg).put("source", "silent"));
            JSONObject r = call("uninstall", a.toString());
            if (!r.optBoolean("ok")) {
                complete(false, errorText(r), "");
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject result = d == null ? null : d.optJSONObject("result");
            JSONObject outcome = result == null ? null : result.optJSONObject("outcome");
            boolean ok = outcome != null && outcome.optBoolean("ok");
            String raw = outcome == null ? "" : outcome.optString("raw", "");
            String code = outcome == null ? "" : outcome.optString("code", "");
            complete(ok, raw.trim().isEmpty() ? code : raw.trim(), "");
        } catch (Throwable t) {
            fail(String.valueOf(t));
        }
    }

    private JSONObject defaultOptions() throws Exception {
        return new JSONObject()
                .put("replace", true)
                .put("grantRuntimePermissions", true)
                .put("allowDowngrade", false)
                .put("deleteSourceAfter", false)
                .put("skipConfirm", true);
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
        return e == null ? "未知错误" : e.optString("message", "未知错误");
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (n >= 0 && !c.isNull(n)) {
                    String s = c.getString(n);
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String seg = u.getLastPathSegment();
        return seg == null || seg.isEmpty() ? "安装包" : seg;
    }

    private void fail(String msg) {
        // 准备阶段失败未进入核心执行，补记一条，保证后台链路任何结果都留有记录。
        try {
            JSONObject record = new JSONObject()
                    .put("op", uninstall ? "uninstall" : "install")
                    .put("label", label)
                    .put("source", "silent")
                    .put("ok", false)
                    .put("code", "PREPARE_FAILED")
                    .put("detail", msg);
            call("logRecord", record.toString());
        } catch (Throwable ignored) {
        }
        complete(false, msg, "");
    }

    private void complete(boolean ok, String detail, String launchPackage) {
        Notifier.finish(App.context(), label, uninstall, ok, detail, launchPackage);
        endQuietly();
    }

    private void endQuietly() {
        if (ended) {
            return;
        }
        ended = true;
        ShizukuBridge.setProgressListener(null);
        runOnUiThread(() -> {
            if (!isFinishing()) {
                finish();
                overridePendingTransition(0, 0);
            }
        });
    }
}
