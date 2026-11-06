package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部「打开方式 / 分享 / 安装 / 卸载」意图的入口。
 *
 * 关键：不指望任何主题的「不显示窗口」承诺。
 *
 * 之前四轮分别试过全屏 translucent、不透明 1x1、Theme.NoDisplay，在部分国产
 * ROM 上始终出现「状态栏/导航栏变黑、触摸被吞、必须按返回才能恢复」。共同机制是：
 * 系统仍为这个 Activity 创建了一个可聚焦的透明窗口，按 windowIsTranslucent
 * 接管了系统栏配色，并把它作为焦点窗口接收按键。
 *
 * 因此这里在 onCreate 里显式把窗口参数写死（见 hardenWindow）：1x1、
 * NOT_FOCUSABLE、NOT_TOUCHABLE、NOT_TOUCH_MODAL、不绘制系统栏背景、系统栏透明色、
 * 无 dim。这样无论 ROM 是否真的创建窗口，都不会抢焦点、不吞触摸、不染黑系统栏，
 * 用户也就不必再按返回键。
 *
 * 注意：windowIsTranslucent=false + @null 背景会得到 opaque surface 并合成出整屏
 * 纯黑，所以主题必须保持 translucent。
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
        // 绝不 setContentView。窗口参数必须显式写死，不能依赖 NoDisplay 的实现：
        // 已有实机证据表明部分 ROM 会忽略 windowNoDisplay，仍创建一个可聚焦的
        // 透明全屏窗口（黑系统栏、吞触摸、按返回才消失）。
        hardenWindow();
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
     * 把可能存在的窗口彻底做成「不可聚焦、不可触摸、1x1、不绘制系统栏」。
     *
     * 在标准 AOSP 上 Theme.NoDisplay 不创建窗口，这些参数只是无害的兜底；
     * 在会创建窗口的 ROM 上，它们才是真正起作用的保险：
     *  - FLAG_NOT_FOCUSABLE：窗口不再成为焦点窗口，返回键不会落到它上面，
     *    用户不会遇到「必须按返回才能操作」的情况；
     *  - FLAG_NOT_TOUCHABLE / NOT_TOUCH_MODAL：所有触摸穿透到前台应用；
     *  - 清 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS + 透明系统栏色：不接管也不染黑
     *    状态栏/导航栏；
     *  - 1x1 + TOP|START + dimAmount=0：不覆盖屏幕、不加暗色遮罩。
     */
    private void hardenWindow() {
        try {
            Window w = getWindow();
            if (w == null) {
                return;
            }
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = 1;
            lp.height = 1;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            lp.dimAmount = 0f;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.windowAnimations = 0;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            lp.flags &= ~(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setAttributes(lp);
        } catch (Throwable ignored) {
            // 某些 ROM 对 NoDisplay Activity 的 Window 做限制；主题层仍有兜底。
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
            // 用应用级 Context + NEW_TASK 启动：入口 Activity 马上 finish 且是
            // noHistory，若复用它的 task，部分 ROM 会把刚启动的授权弹窗一起收掉。
            android.content.Context app = App.context();
            Intent auth = new Intent(app, CallerAuthActivity.class);
            auth.putStringArrayListExtra(EXTRA_URIS, new ArrayList<>(uris));
            auth.putExtra(EXTRA_CALLER, caller);
            auth.putExtra(EXTRA_CALLER_LABEL, callerLabel);
            auth.putExtra(EXTRA_LABEL, label);
            auth.putExtra(EXTRA_UNINSTALL, uninstall);
            auth.putExtra(EXTRA_UNINSTALL_PKG, uninstallPkg);
            auth.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                app.startActivity(auth);
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
