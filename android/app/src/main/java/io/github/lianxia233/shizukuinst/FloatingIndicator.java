package io.github.lianxia233.shizukuinst;

import android.app.Activity;
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
 * 安装期间的左上角科幻风圆形进度指示器，两条路径保证「一定能看见」：
 *
 *  1. 已授予 SYSTEM_ALERT_WINDOW：TYPE_APPLICATION_OVERLAY 的 40dp 小窗，
 *     完全不切换 task、不 pause 前台应用，体验最佳。
 *  2. 未授予：由前台入口 Activity 启动 {@link IndicatorActivity}（44dp 普通窗口，
 *     NOT_TOUCHABLE、透明、无 dim、非 translucent），不需要任何权限。
 *     绝不再像旧版那样「无权限就静默不显示」—— 那正是用户看不到小圆的原因。
 *
 * 生命周期跟随安装任务：show* 幂等，dismiss 由后台任务的 finally 调用。
 */
final class FloatingIndicator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static boolean active;
    private static IndicatorActivity hostActivity;
    private static SciFiSpinner hostSpinner;
    private static WindowManager overlayWm;
    private static View overlayView;
    private static SciFiSpinner overlaySpinner;

    private FloatingIndicator() {
    }

    static boolean canDraw(Context c) {
        try {
            return Settings.canDrawOverlays(c.getApplicationContext());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void openPermissionSettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 在仍位于前台的入口 Activity 中调用：两条路径都可用。 */
    static void showForeground(Activity a, boolean enabled) {
        if (!enabled) {
            return;
        }
        final Context app = a.getApplicationContext();
        synchronized (LOCK) {
            if (active) {
                return;
            }
            active = true;
        }
        if (canDraw(app)) {
            MAIN.post(() -> addOverlay(app));
        } else {
            try {
                Intent i = new Intent(a, IndicatorActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                a.startActivity(i);
            } catch (Throwable t) {
                synchronized (LOCK) {
                    active = false;
                }
            }
        }
    }

    /** 后台任务兜底：没有悬浮窗权限时不能可靠启动 Activity，只尝试 overlay。 */
    static void showBackground(Context app, boolean enabled) {
        if (!enabled) {
            return;
        }
        synchronized (LOCK) {
            if (active || !canDraw(app)) {
                return;
            }
            active = true;
        }
        MAIN.post(() -> addOverlay(app));
    }

    private static void addOverlay(Context app) {
        synchronized (LOCK) {
            if (!active || overlayView != null) {
                return;
            }
            try {
                WindowManager wm = (WindowManager)
                        app.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) {
                    return;
                }
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
                lp.y = statusBarHeight(app) + pad;
                wm.addView(v, lp);
                overlayWm = wm;
                overlayView = v;
                overlaySpinner = v;
                v.startAnimation();
            } catch (Throwable ignored) {
            }
        }
    }

    static void onActivityCreated(IndicatorActivity a, SciFiSpinner spinner) {
        boolean finishNow;
        synchronized (LOCK) {
            hostActivity = a;
            hostSpinner = spinner;
            finishNow = !active;
        }
        spinner.setProgress(-1);
        spinner.startAnimation();
        if (finishNow) {
            a.finish();
            a.overridePendingTransition(0, 0);
        }
    }

    static void onActivityDestroyed(IndicatorActivity a) {
        synchronized (LOCK) {
            if (hostActivity == a) {
                hostActivity = null;
                hostSpinner = null;
            }
        }
    }

    /** 0..100，负数表示不确定进度。 */
    static void progress(int pct) {
        MAIN.post(() -> {
            synchronized (LOCK) {
                if (overlaySpinner != null) {
                    overlaySpinner.setProgress(pct);
                }
                if (hostSpinner != null) {
                    hostSpinner.setProgress(pct);
                }
            }
        });
    }

    /** 安装结束（无论成功失败）后移除全部指示器。 */
    static void dismiss() {
        MAIN.post(() -> {
            synchronized (LOCK) {
                active = false;
                if (overlayView != null) {
                    try {
                        if (overlayWm != null) {
                            overlayWm.removeViewImmediate(overlayView);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (overlaySpinner != null) {
                    overlaySpinner.stopAnimation();
                }
                overlayView = null;
                overlaySpinner = null;
                overlayWm = null;
                if (hostActivity != null) {
                    try {
                        hostActivity.finish();
                        hostActivity.overridePendingTransition(0, 0);
                    } catch (Throwable ignored) {
                    }
                }
                hostActivity = null;
                hostSpinner = null;
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
