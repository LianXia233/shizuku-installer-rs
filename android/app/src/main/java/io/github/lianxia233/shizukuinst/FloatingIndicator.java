package io.github.lianxia233.shizukuinst;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 左上角科幻风圆形进度指示器。
 *
 * 用 TYPE_APPLICATION_OVERLAY 的小窗口（40dp），而不是 Activity 的全屏透明窗口：
 * 小窗口 NOT_TOUCHABLE / NOT_FOCUSABLE，不铺满屏幕，因此不会让状态栏和导航栏
 * 变黑，也不会拦截前台应用的点击。没有悬浮窗权限时静默跳过，不影响安装。
 *
 * 生命周期跟随应用进程而非 Activity，安装任务在后台线程持有引用计数。
 */
final class FloatingIndicator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static SciFiSpinner spinner;
    private static View attached;
    private static WindowManager windowManager;
    private static int refs;

    private FloatingIndicator() {
    }

    static boolean canDraw(Context c) {
        try {
            return Settings.canDrawOverlays(c.getApplicationContext());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 打开系统的「显示在其他应用上层」设置页。 */
    static void openPermissionSettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 开始一次安装：引用计数 +1，首个任务创建悬浮窗。 */
    static void acquire(Context c, boolean enabled) {
        final Context app = c.getApplicationContext();
        synchronized (LOCK) {
            refs++;
            if (!enabled || !canDraw(app) || attached != null) {
                return;
            }
        }
        MAIN.post(() -> {
            synchronized (LOCK) {
                if (attached != null || refs <= 0) {
                    return;
                }
                try {
                    WindowManager wm = (WindowManager)
                            app.getSystemService(Context.WINDOW_SERVICE);
                    if (wm == null) {
                        return;
                    }
                    windowManager = wm;
                    float density = app.getResources().getDisplayMetrics().density;
                    int size = (int) (40 * density);
                    int pad = (int) (8 * density);
                    SciFiSpinner v = new SciFiSpinner(app);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            size, size,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.x = pad;
                    // 避开状态栏，但仍在视觉上的左上角
                    lp.y = statusBarHeight(app) + pad;
                    wm.addView(v, lp);
                    attached = v;
                    spinner = v;
                    v.startAnimation();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 结束一次安装：引用计数归零时移除悬浮窗。 */
    static void release() {
        synchronized (LOCK) {
            refs = Math.max(0, refs - 1);
            if (refs > 0 || attached == null) {
                return;
            }
        }
        MAIN.post(() -> {
            synchronized (LOCK) {
                if (refs > 0 || attached == null) {
                    return;
                }
                try {
                    if (windowManager != null) {
                        windowManager.removeViewImmediate(attached);
                    }
                } catch (Throwable ignored) {
                }
                if (spinner != null) {
                    spinner.stopAnimation();
                }
                attached = null;
                spinner = null;
                windowManager = null;
            }
        });
    }

    /** 0..100，负数表示不确定进度。 */
    static void progress(int pct) {
        MAIN.post(() -> {
            synchronized (LOCK) {
                if (spinner != null) {
                    spinner.setProgress(pct);
                }
            }
        });
    }

    private static int statusBarHeight(Context c) {
        try {
            int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? c.getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
