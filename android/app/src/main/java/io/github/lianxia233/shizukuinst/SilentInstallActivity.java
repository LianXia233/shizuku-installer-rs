package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部「打开方式 / 分享 / 安装 / 卸载」意图的入口。
 *
 * 零窗口契约（回归 Android 框架原生语义）：
 *  - Manifest 使用 {@code @android:style/Theme.NoDisplay}，其
 *    {@code windowNoDisplay=true} 让 ActivityThread 跳过 {@code wm.addView}；
 *  - 本类绝不调用 {@code setContentView}、{@code getWindow()}、
 *    {@code getDecorView}、{@code setAttributes}、{@code setFlags} 等任何
 *    Window API。曾经存在的 {@code neutralizeWindow()} 会清除
 *    {@code FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS}，一旦 ROM 忽略 windowNoDisplay
 *    而真的创建窗口，系统就会用黑色绘制状态栏/导航栏；且部分 ROM 的
 *    {@code Window.setFlags} 会触发 installDecor，把零窗口契约破坏掉。
 *    因此这里彻底不碰 Window，只依赖框架主题。
 *
 * 调用方结果契约（2026-11-07 实机日志定位）：
 *  - 文件管理器、Mihon/Tachiyomi 等调用方普遍用
 *    {@code startActivityForResult(new Intent(ACTION_INSTALL_PACKAGE))} 拉起安装器，
 *    并用一个 {@code windowIsTranslucent=true} 的透明中转 Activity 等待结果
 *    （logcat 中表现为 {@code (rr=<ActivityRecord>)}）。
 *  - 这类中转 Activity 往往只在收到 {@code RESULT_OK} 时才自行 finish。
 *    本入口过去从不调用 {@code setResult()}、又声明了 {@code noHistory=true}，
 *    结果默认是 {@code RESULT_CANCELED}，中转 Activity 便一直停留在 RESUMED：
 *    全屏透明窗口抢走焦点、视图树为空导致触摸无响应、自身不带
 *    {@code DRAWS_SYSTEM_BAR_BACKGROUNDS} 且 taskDescription 为黑色，
 *    于是状态栏/导航栏变黑，用户只能按返回键退出。
 *  - 修复：接管安装后显式 {@code setResult(RESULT_OK)} 再 finish；
 *    拦截/拒绝/异常路径也明确回传 {@code RESULT_CANCELED}，保证调用方
 *    一定能收到回调并关闭自己的透明窗口。Manifest 同时移除 noHistory，
 *    避免框架提前清理 Activity 记录而干扰结果传递。
 *
 * 本 Activity 只负责三件事，随后立刻 finish：
 *   1. 在仍持有外部 URI 读权限时把安装包复制到应用私有缓存（{@link StagedFiles}）；
 *   2. 识别调用方并查询授权决策；
 *   3. 放行则把安装交给进程级后台任务，需询问则把待安装包交给通知。
 */
public final class SilentInstallActivity extends Activity {
    private static final String TAG = "SilentEntry";
    static final String EXTRA_URIS = "staged_uris";
    static final String EXTRA_CALLER = "caller";
    static final String EXTRA_CALLER_LABEL = "caller_label";
    static final String EXTRA_LABEL = "label";
    static final String EXTRA_UNINSTALL = "uninstall";
    static final String EXTRA_UNINSTALL_PKG = "uninstall_pkg";
    /** 回传给调用方的结果说明，仅用于调用方自查，不含任何 URI 或用户数据。 */
    private static final String EXTRA_RESULT_DETAIL = "silent_result_detail";

    private final List<String> uris = new ArrayList<>();
    private boolean valid;
    private boolean uninstall;
    private boolean ended;
    private String uninstallPkg = "";
    private String label = "安装包";
    private String caller = "@unknown";
    private String callerLabel = "";
    /** 默认取消：任何未走到 ALLOW 的路径都必须给调用方一个明确回调。 */
    private int resultCode = RESULT_CANCELED;
    private String resultDetail = "NOT_HANDLED";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 绝不 setContentView、绝不触碰 Window。Theme.NoDisplay 的
        // windowNoDisplay=true 让 ActivityThread 跳过 wm.addView，框架层面就没有窗口。
        // NoDisplay 要求 Activity 在 onResume 之前 finish；用 try/finally 保证
        // 任何异常路径（坏 Parcelable、通知服务不可用等）都立即销毁并回传结果。
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
            resultCode = RESULT_CANCELED;
            resultDetail = "ENTRY_ERROR";
        } finally {
            finishWithResult();
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
        // startActivityForResult 场景下这是最可靠、由框架背书的来源，
        // 优先于 getCallingPackage() 与 referrer。
        ComponentName callingActivity = getCallingActivity();
        if (callingActivity != null) {
            candidates.add(callingActivity.getPackageName());
        }
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
        // 诊断日志：adb logcat -s SilentEntry 可直接看到决策、调用方以及
        // 是否存在等待结果的 resultTo，用于定位「调用方透明窗口不退场」。
        Log.i(TAG, "decision=" + decision + " caller=" + caller
                + " resultTo=" + safeCallingActivity() + " valid=" + valid);

        if ("DENY".equals(decision)) {
            DenyLog.record(App.context(), uninstall, label, caller, callerLabel);
            resultCode = RESULT_CANCELED;
            resultDetail = "CALLER_DENIED";
        } else if ("ASK".equals(decision)) {
            // 安装链路绝不创建任何窗口：未授权调用只发一条通知。
            // 但必须把已私有化的安装包随通知一并交给授权页，否则用户点通知
            // 只能完成授权、本次安装被白白丢弃，需要回到原应用重新发起。
            DenyLog.record(App.context(), uninstall, label, caller, callerLabel, "ask");
            Notifier.authPending(App.context(), label, caller, callerLabel,
                    new ArrayList<>(uris), uninstall, uninstallPkg);
            resultCode = RESULT_CANCELED;
            resultDetail = "CALLER_PENDING";
        } else {
            // 关键：调用方常用 startActivityForResult 拉起本入口，并只有收到
            // RESULT_OK 才结束自己的透明中转 Activity。不显式 setResult 会让
            // 那个窗口一直停留在栈顶，表现为「透明遮罩、点不动、只能按返回」。
            TaskLauncher.launch(this, uninstall, uris, uninstallPkg, caller, label);
            resultCode = RESULT_OK;
            resultDetail = "ACCEPTED";
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

    private String safeCallingActivity() {
        ComponentName c = getCallingActivity();
        return c == null ? "none" : c.flattenToShortString();
    }

    /**
     * 所有路径的唯一收口：先回传结果，再 finish。
     *
     * 绝不调用 overridePendingTransition 等任何窗口转场 API：
     * Theme.NoDisplay 下框架未 addView，无需转场，也避免触碰 decor。
     */
    private void finishWithResult() {
        if (ended) {
            return;
        }
        ended = true;
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_DETAIL, resultDetail);
            setResult(resultCode, data);
        } catch (Throwable t) {
            Log.w(TAG, "setResult failed", t);
        }
        Log.i(TAG, "finish result=" + resultCode + " detail=" + resultDetail
                + " caller=" + caller + " resultTo=" + safeCallingActivity());
        finish();
    }
}
