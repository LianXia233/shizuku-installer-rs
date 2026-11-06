package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部「打开方式 / 分享 / 安装 / 卸载」意图的入口。
 *
 * 这个 Activity 只做两件事：识别调用方、按授权策略决定是否放行。
 * 真正的安装在进程级后台线程执行（{@link SilentTask}），因此本 Activity
 * 可以立刻 finish —— 屏幕上不会残留任何全屏透明窗口，状态栏/导航栏不会变黑，
 * 也不会拦截前台应用的触摸。
 *
 * 唯一会显示界面的情况是「首次调用需要授权」：此时把窗口放大为一张居中小卡片，
 * 用户点完按钮后立即恢复 1x1 窗口并销毁。
 */
public final class SilentInstallActivity extends Activity {
    private final List<String> uris = new ArrayList<>();
    private boolean valid;
    private boolean uninstall;
    private boolean ended;
    private boolean authShowing;
    private String uninstallPkg = "";
    private String label = "安装包";
    private String caller = "@unknown";
    private String callerLabel = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shrinkWindow();
        Notifier.ensureChannels(this);
        parseIntent(getIntent());
        if (!valid) {
            endQuietly();
            return;
        }
        caller = resolveCaller(getIntent());
        evaluate();
    }

    /** 默认形态：1x1、完全不可触摸、无 dim 的浮动窗口，对前台零影响。 */
    private void shrinkWindow() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        w.setLayout(1, 1);
        w.setGravity(Gravity.TOP | Gravity.START);
    }

    // ---------------------------------------------------------------- 意图解析

    private void parseIntent(Intent i) {
        if (i == null || i.getAction() == null) {
            return;
        }
        String action = i.getAction();
        if (Intent.ACTION_DELETE.equals(action)
                || "android.intent.action.UNINSTALL_PACKAGE".equals(action)) {
            Uri u = i.getData();
            String pkg = u == null ? "" : u.getSchemeSpecificPart();
            if (pkg.startsWith("package:")) {
                pkg = pkg.substring("package:".length());
            }
            pkg = pkg.trim();
            if (pkg.isEmpty()) {
                return;
            }
            uninstall = true;
            uninstallPkg = pkg;
            String installed = AppNames.labelForPackage(this, pkg);
            label = installed == null ? pkg : installed;
            valid = true;
            return;
        }

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
            return;
        }
        String name = AppNames.displayName(this, uris.get(0));
        label = name.isEmpty() ? "安装包" : name;
        valid = true;
    }

    /** 尽力识别是谁发起的调用；拿不到一律视为匿名来源，必须每次询问。 */
    private String resolveCaller(Intent i) {
        String self = getPackageName();
        List<String> candidates = new ArrayList<>();
        String calling = getCallingPackage();
        if (calling != null) {
            candidates.add(calling);
        }
        try {
            Uri ref = getReferrer();
            if (ref != null) {
                candidates.add(packageFromReferrer(ref));
            }
        } catch (Throwable ignored) {
        }
        try {
            Uri extra = i.getParcelableExtra(Intent.EXTRA_REFERRER);
            if (extra != null) {
                candidates.add(packageFromReferrer(extra));
            }
        } catch (Throwable ignored) {
        }
        String refName = i.getStringExtra(Intent.EXTRA_REFERRER_NAME);
        if (refName != null && !refName.isEmpty()) {
            try {
                candidates.add(packageFromReferrer(Uri.parse(refName)));
            } catch (Throwable ignored) {
            }
        }
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && !c.equals(self) && plausiblePackage(c)) {
                return c;
            }
        }
        return "@unknown";
    }

    private String packageFromReferrer(Uri u) {
        if (u == null) {
            return null;
        }
        String scheme = u.getScheme();
        if ("android-app".equals(scheme) || "app".equals(scheme)) {
            return u.getHost();
        }
        return null;
    }

    private boolean plausiblePackage(String s) {
        if (s.length() < 3 || s.length() > 128) {
            return false;
        }
        int dots = 0;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '.') {
                dots++;
            } else if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return false;
            }
        }
        return dots >= 1;
    }

    // ---------------------------------------------------------------- 授权决策

    private void evaluate() {
        JSONObject r;
        try {
            r = call("callerDecision",
                    new JSONObject().put("caller", caller).toString());
        } catch (Throwable t) {
            r = new JSONObject();
        }
        JSONObject d = r.optJSONObject("data");
        if (d != null) {
            caller = d.optString("caller", caller);
            callerLabel = d.optString("callerLabel", "");
        }
        if (callerLabel == null || callerLabel.trim().isEmpty()) {
            callerLabel = AppNames.labelForCaller(this, caller);
        }
        String decision = d == null ? "ASK" : d.optString("decision", "ASK");
        if ("DENY".equals(decision)) {
            denyQuietly();
        } else if ("ASK".equals(decision)) {
            showAuthDialog();
        } else {
            launchTask();
            endQuietly();
        }
    }

    private void showAuthDialog() {
        authShowing = true;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            Window w = getWindow();
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            setContentView(R.layout.dialog_auth);

            String who = callerLabel.trim().isEmpty()
                    ? getString(R.string.caller_unknown) : callerLabel.trim();
            ((TextView) findViewById(R.id.authMessage)).setText(getString(
                    uninstall ? R.string.auth_dialog_uninstall : R.string.auth_dialog_install, who));
            ((TextView) findViewById(R.id.authCaller)).setText(
                    "@unknown".equals(caller) ? getString(R.string.caller_unknown) : caller);

            CheckBox remember = findViewById(R.id.authRemember);
            // 匿名来源不能被永久记住：否则一次允许会放行后续所有匿名调用
            if ("@unknown".equals(caller)) {
                remember.setEnabled(false);
                remember.setChecked(false);
            }
            findViewById(R.id.btnAllow).setOnClickListener(
                    v -> onDecision(true, remember.isChecked()));
            findViewById(R.id.btnDeny).setOnClickListener(
                    v -> onDecision(false, remember.isChecked()));
        });
    }

    private void onDecision(boolean allow, boolean remember) {
        if (ended) {
            return;
        }
        final boolean rememberFinal = remember && !"@unknown".equals(caller);
        final String callerFinal = caller;
        App.executor().execute(() -> {
            try {
                JSONObject a = new JSONObject()
                        .put("caller", callerFinal)
                        .put("remember", rememberFinal);
                call(allow ? "callerGrant" : "callerDeny", a.toString());
            } catch (Throwable ignored) {
            }
        });
        if (!allow) {
            recordDenied();
            Notifier.denied(App.context(), label, callerLabel);
            endQuietly();
            return;
        }
        // 恢复最小窗口并立刻销毁：安装全程不再有任何 Activity 窗口
        shrinkWindow();
        launchTask();
        endQuietly();
    }

    private void denyQuietly() {
        recordDenied();
        Notifier.denied(App.context(), label, callerLabel);
        endQuietly();
    }

    private void recordDenied() {
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
                call("logRecord", e.toString());
            } catch (Throwable ignored) {
            }
        });
    }

    private void launchTask() {
        Context app = getApplicationContext();
        App.executor().execute(uninstall
                ? SilentTask.uninstall(app, uninstallPkg, caller)
                : SilentTask.install(app, uris, caller, label));
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

    @Override
    public void onBackPressed() {
        if (authShowing) {
            onDecision(false, false);
        } else {
            endQuietly();
        }
    }

    private void endQuietly() {
        if (ended) {
            return;
        }
        ended = true;
        runOnUiThread(() -> {
            if (!isFinishing()) {
                finish();
                overridePendingTransition(0, 0);
            }
        });
    }
}
