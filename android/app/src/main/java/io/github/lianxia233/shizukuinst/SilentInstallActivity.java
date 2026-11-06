package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部「打开方式 / 分享 / 安装 / 卸载」意图的入口。
 *
 * 零窗口契约（回归 Android 框架原生语义，不再与 ROM 争窗口参数）：
 *  - Manifest 使用 {@code @android:style/Theme.NoDisplay}，其
 *    {@code windowNoDisplay=true} 让 ActivityThread 跳过 {@code wm.addView}；
 *  - 本类绝不调用 {@code setContentView}、{@code getDecorView}、
 *    {@code setAttributes} 或任何 installDecor 触发点。一旦 installDecor，
 *    就会把一个本不该存在的窗口激活出来；而 makeVisible() 又会依据主题重新
 *    生成 LayoutParams，使代码里设置的尺寸与 flags 全部失效。
 *  - 仅保留一处 {@code Window.setFlags} 兜底（见 {@link #neutralizeWindow()}），
 *    它只写 LayoutParams、不触发 installDecor，用于个别忽略 windowNoDisplay 的 ROM。
 *  - 因此屏幕上不存在任何本应用的窗口：没有透明遮罩、没有可聚焦窗口、
 *    不吞触摸、不染黑系统栏、不需要按返回键。
 *
 * 之前四轮（全屏 translucent、不透明 1x1、floating translucent、代码加固）
 * 都失败，根因正是自定义主题覆盖了 windowIsFloating/windowIsTranslucent，
 * floating + translucent 强制系统创建带半透明背板的窗口 —— 即那层「透明遮罩」。
 *
 * 本 Activity 只负责三件事，随后立刻 finish：
 *   1. 在仍持有外部 URI 读权限时把安装包复制到应用私有缓存（{@link StagedFiles}）；
 *   2. 识别调用方并查询授权决策；
 *   3. 放行则把安装交给进程级后台任务，需询问则转发给 {@link CallerAuthActivity}。
 */
public final class SilentInstallActivity extends Activity {
    private static final String TAG = "SilentEntry";
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
        // 绝不 setContentView、绝不 getDecorView()/installDecor()。Theme.NoDisplay 的
        // windowNoDisplay=true 让 ActivityThread 跳过 wm.addView，框架层面就没有窗口。
        neutralizeWindow();
        // NoDisplay 要求 Activity 在 onResume 之前 finish；用 try/finally 保证
        // 任何异常路径（坏 Parcelable、通知服务不可用等）都立即销毁。
        try {
            Notifier.ensureChannels(this);
            parseIntent(getIntent());
            if (valid) {
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
        } catch (Throwable t) {
            Log.w(TAG, "silent entry failed", t);
        } finally {
            finishQuietly();
        }
    }

    /**
     * 纯 flags 兜底：只改窗口属性，绝不触碰 decor。
     *
     * {@code Window.setFlags} / {@code setDimAmount} 只写
     * {@code WindowManager.LayoutParams}，不会触发 {@code installDecor()}，
     * 因此不会把 NoDisplay 的「零窗口」契约破坏掉：
     *  - 框架遵守 windowNoDisplay 时不 addView，本方法实际上是无副作用的空操作；
     *  - 个别忽略 windowNoDisplay 的 ROM 真建了窗口时，addView 用的是
     *    {@code getWindow().getAttributes()}，这里的 NOT_FOCUSABLE /
     *    NOT_TOUCHABLE / 清 DIM 会生效，窗口既不抢焦点、不吞触摸、也不带暗色遮罩，
     *    用户感知不到一层「透明遮罩」，也不需要按返回键。
     */
    private void neutralizeWindow() {
        try {
            Window w = getWindow();
            if (w == null) {
                return;
            }
            w.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                            | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setDimAmount(0f);
        } catch (Throwable ignored) {
            // ROM 限制时忽略；NoDisplay 本身仍保证不创建窗口。
        }
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
            // 安装链路绝不创建任何窗口。
            //
            // 历史教训（第五轮实机反馈）：这里曾 startActivity 弹出 CallerAuthActivity
            // 的 dialog，用户看到的是「一层遮罩弹窗、屏幕不能触摸」——即使我们把
            // dim 关掉、把窗口限制成 WRAP_CONTENT，部分 ROM 仍会把 dialog 的
            // decor 铺成整屏半透明背板并抢走焦点。@unknown 恒返回 ASK 且不可记住，
            // 于是每次安装都会命中该分支，遮罩反复出现。
            //
            // 结论：安装过程中不允许出现任何由本应用创建的窗口。未授权的调用改为
            // 「拒绝本次 + 发一条待授权通知」，用户点通知才进入授权页（那是用户
            // 主动打开的普通页面，与静默链路完全解耦）。
            DenyLog.record(App.context(), uninstall, label, caller, callerLabel, "ask");
            Notifier.authPending(App.context(), label, caller, callerLabel);
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
        // 只 finish，不调用 overridePendingTransition 等任何窗口转场 API：
        // Theme.NoDisplay 下框架未 addView，无需转场，也避免触碰 decor。
        finish();
    }
}
