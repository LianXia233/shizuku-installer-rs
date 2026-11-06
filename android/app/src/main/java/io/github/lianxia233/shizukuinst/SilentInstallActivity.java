package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部「打开方式 / 分享 / 安装 / 卸载」意图的入口。
 *
 * 使用 {@code Theme.SilentEntry}（无背景、无 translucent、无 floating、禁预览）：
 * 系统不会为它创建任何可见窗口，因此安装期间屏幕上不存在全屏透明遮罩，
 * 状态栏/导航栏不会变黑，也不会拦截前台应用的触摸。
 * 这与之前「1x1 浮动窗口 + windowIsFloating」的方案不同 —— 后者在部分 ROM 上
 * 仍会绘制全屏 scrim 并吞掉点击。
 *
 * 本 Activity 只负责三件事，随后立刻 finish：
 *   1. 在仍持有外部 URI 读权限时把安装包复制到应用私有缓存（{@link StagedFiles}）；
 *   2. 识别调用方并查询授权决策；
 *   3. 放行则把安装交给进程级后台任务，需询问则转发给 {@link CallerAuthActivity}。
 */
public final class SilentInstallActivity extends Activity {
    static final String EXTRA_URIS = "staged_uris";
    static final String EXTRA_CALLER = "caller";
    static final String EXTRA_CALLER_LABEL = "caller_label";
    static final String EXTRA_LABEL = "label";
    static final String EXTRA_UNINSTALL = "uninstall";
    static final String EXTRA_UNINSTALL_PKG = "uninstall_pkg";

    private final List<String> uris = new ArrayList<>();
    private boolean valid;
    private boolean uninstall;
    private boolean ended;
    private String uninstallPkg = "";
    private String label = "安装包";
    private String caller = "@unknown";
    private String callerLabel = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 无窗口主题：绝不 setContentView。再把窗口压到 1x1 并完全点击穿透，
        // 即便某些 ROM 仍为 NoDisplay Activity 创建一个 DecorView，也不会出现
        // 全屏透明遮罩、系统栏变黑或吞掉前台触摸。
        shrinkWindow();
        Notifier.ensureChannels(this);
        parseIntent(getIntent());
        if (!valid) {
            finishQuietly();
            return;
        }

        // 关键：外部 content:// 的读权限只在本 Activity 存活期间有效。
        // 先复制到自家私有缓存，后台任务全程只读 StagedProvider，不会因
        // finish 后授权被回收而在 install-write 阶段抛 SecurityException。
        if (!uninstall) {
            List<String> staged = StagedFiles.stage(this, uris);
            uris.clear();
            uris.addAll(staged);
        }

        caller = resolveCaller(getIntent());
        evaluate();
    }

    /** 1x1、透明、点击完全穿透的窗口；不改变系统栏配色，不 dim。 */
    private void shrinkWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0));
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = 1;
        lp.height = 1;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.dimAmount = 0f;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        w.setAttributes(lp);
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

        addUri(i.getData());
        Uri stream = i.getParcelableExtra(Intent.EXTRA_STREAM);
        addUri(stream);
        ArrayList<Uri> extras = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (extras != null) {
            for (Uri u : extras) {
                addUri(u);
            }
        }
        ClipData clip = i.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) {
                addUri(clip.getItemAt(k).getUri());
            }
        }
        if (uris.isEmpty()) {
            return;
        }
        String name = AppNames.displayName(this, uris.get(0));
        label = name.isEmpty() ? "安装包" : name;
        valid = true;
    }

    private void addUri(Uri u) {
        if (u != null && !uris.contains(u.toString())) {
            uris.add(u.toString());
        }
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
            DenyLog.record(App.context(), uninstall, label, caller, callerLabel);
            finishQuietly();
        } else if ("ASK".equals(decision)) {
            Intent auth = new Intent(this, CallerAuthActivity.class);
            auth.putStringArrayListExtra(EXTRA_URIS, new ArrayList<>(uris));
            auth.putExtra(EXTRA_CALLER, caller);
            auth.putExtra(EXTRA_CALLER_LABEL, callerLabel);
            auth.putExtra(EXTRA_LABEL, label);
            auth.putExtra(EXTRA_UNINSTALL, uninstall);
            auth.putExtra(EXTRA_UNINSTALL_PKG, uninstallPkg);
            auth.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try {
                startActivity(auth);
            } catch (Throwable ignored) {
            }
            finishQuietly();
        } else {
            TaskLauncher.launch(this, uninstall, uris, uninstallPkg, caller, label);
            finishQuietly();
        }
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

    private void finishQuietly() {
        if (ended) {
            return;
        }
        ended = true;
        finish();
        overridePendingTransition(0, 0);
    }
}
