package io.github.lianxia233.shizukuinst;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨应用授权卡片（系统悬浮窗）。
 *
 * 目标：外部应用第一次发起静默安装时，用户**不必切回本应用**就能决定允许还是拒绝。
 * 卡片浮在当前前台应用之上，点「允许」后直接续装本次请求。
 *
 * 为什么不用 Activity / Dialog：
 *  - Dialog 在部分 ROM 上会被铺成整屏半透明背板并抢焦点，正是用户反复反馈的
 *    「一层遮罩弹窗、屏幕点不动」；
 *  - 普通 Activity 会把本应用 task 拉到前台，打断用户正在做的事。
 *  - 系统悬浮窗 {@code TYPE_APPLICATION_OVERLAY} 是唯一「浮在别人之上、不切换任务、
 *    不 pause 前台应用」的窗口；配合 WRAP_CONTENT + NOT_FOCUSABLE + NOT_TOUCH_MODAL，
 *    卡片之外的触摸与返回键全部穿透给前台应用，不会挡住别人。
 *
 * 没有悬浮窗权限时借 Shizuku(shell) 自动授权（{@link FloatingIndicator}）；
 * 自动授权也失败则回退成一条「待授权」通知，绝不降级成 Activity。
 *
 * sources 是调用方给的原始 URI 快照，允许后续「安装成功后删除安装包」
 * 删到原文件；uris 是本应用私有缓存里的 staged 副本，只用于安装。
 */
final class AuthPrompt {
    private static final String TAG = "AuthPrompt";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    /** 无人处理时自动收起，避免卡片长期悬在屏幕上。 */
    private static final long TIMEOUT_MS = 90_000L;

    private static WindowManager wm;
    private static View view;
    private static Runnable timeout;

    private AuthPrompt() {
    }

    /**
     * 弹出授权卡片。
     *
     * @return {@code true} 表示已接管本次询问（正在弹出，或正在申请悬浮窗权限）；
     *         {@code false} 表示无法弹出，调用方应改发通知。
     */
    static boolean show(Context c, String label, String caller, String callerLabel,
                        List<String> uris, List<String> sources,
                        boolean uninstall, String uninstallPkg) {
        final Context app = c.getApplicationContext();
        final List<String> staged = uris == null ? new ArrayList<>() : new ArrayList<>(uris);
        final List<String> originals = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
        if (FloatingIndicator.canDraw(app)) {
            MAIN.post(() -> add(app, label, caller, callerLabel, staged, originals,
                    uninstall, uninstallPkg));
            return true;
        }
        FloatingIndicator.ensurePermissionAsync(app, () -> {
            if (FloatingIndicator.canDraw(app)) {
                add(app, label, caller, callerLabel, staged, originals, uninstall, uninstallPkg);
            } else {
                // 权限拿不到：回退通知，让用户点通知进入普通授权页
                Notifier.authPending(app, label, caller, callerLabel,
                        new ArrayList<>(staged), new ArrayList<>(originals),
                        uninstall, uninstallPkg);
            }
        });
        return true;
    }

    /** 主动收起（例如安装被取消）。 */
    static void dismiss() {
        MAIN.post(AuthPrompt::removeNow);
    }

    private static void removeNow() {
        synchronized (LOCK) {
            if (timeout != null) {
                MAIN.removeCallbacks(timeout);
                timeout = null;
            }
            if (view != null && wm != null) {
                try {
                    wm.removeViewImmediate(view);
                } catch (Throwable ignored) {
                }
            }
            view = null;
            wm = null;
        }
    }

    private static void add(Context app, String label, String caller, String callerLabel,
                            List<String> uris, List<String> sources,
                            boolean uninstall, String uninstallPkg) {
        removeNow();
        View root;
        try {
            root = LayoutInflater.from(app).inflate(R.layout.overlay_auth, null);
        } catch (Throwable t) {
            Log.w(TAG, "inflate failed", t);
            Notifier.authPending(app, label, caller, callerLabel,
                    new ArrayList<>(uris), new ArrayList<>(sources), uninstall, uninstallPkg);
            return;
        }

        String who = callerLabel == null || callerLabel.trim().isEmpty()
                ? app.getString(R.string.caller_unknown) : callerLabel.trim();
        String message = uninstall
                ? app.getString(R.string.auth_dialog_uninstall, who)
                : app.getString(R.string.auth_dialog_install, who);
        ((TextView) root.findViewById(R.id.ovMessage)).setText(message);
        ((TextView) root.findViewById(R.id.ovCaller)).setText(
                "@unknown".equals(caller) ? app.getString(R.string.caller_unknown) : caller);

        CheckBox remember = root.findViewById(R.id.ovRemember);
        if ("@unknown".equals(caller)) {
            // 匿名来源不能被永久记住，否则一次允许会放行后续所有匿名调用
            remember.setEnabled(false);
            remember.setChecked(false);
            remember.setText(R.string.auth_remember_anonymous);
        }

        root.findViewById(R.id.btnOvAllow).setOnClickListener(
                v -> decide(app, true, remember.isChecked(), caller, callerLabel,
                        label, uris, sources, uninstall, uninstallPkg));
        root.findViewById(R.id.btnOvDeny).setOnClickListener(
                v -> decide(app, false, false, caller, callerLabel,
                        label, uris, sources, uninstall, uninstallPkg));

        try {
            WindowManager manager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) {
                Notifier.authPending(app, label, caller, callerLabel,
                        new ArrayList<>(uris), new ArrayList<>(sources), uninstall, uninstallPkg);
                return;
            }
            float density = app.getResources().getDisplayMetrics().density;
            int maxWidth = (int) (360 * density);
            int screen = app.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(maxWidth, (int) (screen * 0.88f));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    width,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            // 不要 dim：悬浮窗自带半透明背板就是「遮罩」，这里必须全透明
            lp.dimAmount = 0f;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.windowAnimations = 0;
            manager.addView(root, lp);
            synchronized (LOCK) {
                wm = manager;
                view = root;
                timeout = () -> {
                    Log.i(TAG, "auth prompt timeout, dismissed");
                    removeNow();
                };
                MAIN.postDelayed(timeout, TIMEOUT_MS);
            }
        } catch (Throwable t) {
            // ROM 额外拦截时 addView 会抛异常：回退通知，绝不改弹 Activity
            Log.w(TAG, "addView auth prompt failed", t);
            synchronized (LOCK) {
                view = null;
                wm = null;
            }
            Notifier.authPending(app, label, caller, callerLabel,
                    new ArrayList<>(uris), new ArrayList<>(sources), uninstall, uninstallPkg);
        }
    }

    private static void decide(Context app, boolean allow, boolean remember,
                               String caller, String callerLabel, String label,
                               List<String> uris, List<String> sources,
                               boolean uninstall, String uninstallPkg) {
        removeNow();
        final boolean rememberFinal = remember && !"@unknown".equals(caller);
        App.executor().execute(() -> {
            try {
                JSONObject a = new JSONObject()
                        .put("caller", caller)
                        .put("remember", rememberFinal);
                RustCore.dispatch(allow ? "callerGrant" : "callerDeny", a.toString());
            } catch (Throwable ignored) {
            }
        });
        if (!allow) {
            DenyLog.record(app, uninstall, label, caller, callerLabel);
            return;
        }
        if (!uris.isEmpty() || uninstall) {
            // 直接续装本次请求：卡片是浮窗，用户根本不用回到原应用重新点一次
            TaskLauncher.launch(app, uninstall, uris, sources, uninstallPkg, caller, label);
        }
    }
}
