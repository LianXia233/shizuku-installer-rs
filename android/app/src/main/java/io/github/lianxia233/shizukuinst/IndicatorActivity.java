package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

/**
 * 没有 SYSTEM_ALERT_WINDOW 权限时的悬浮指示器降级方案。
 *
 * 这是一个 44dp 的普通 Activity 窗口（刻意不用 windowIsFloating / windowIsTranslucent，
 * 避免系统给浮动窗口绘制全屏 scrim、把状态栏/导航栏染黑）。窗口左上角定位、
 * 完全透明、NOT_TOUCHABLE，点击会穿透到下面的前台应用，视觉上只有一个小圆。
 *
 * 不使用任何敏感权限；由前台的入口 Activity 启动，绕开 Android 10+ 后台启动限制。
 */
public final class IndicatorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_indicator);

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams lp = w.getAttributes();
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (44 * d);
        int pad = (int) (8 * d);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = size;
        lp.height = size;
        lp.x = pad;
        lp.y = statusBarHeight() + pad;
        lp.dimAmount = 0f;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        w.setAttributes(lp);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);

        SciFiSpinner spinner = findViewById(R.id.indicatorSpinner);
        FloatingIndicator.onActivityCreated(this, spinner);
    }

    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    protected void onDestroy() {
        FloatingIndicator.onActivityDestroyed(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 点击穿透窗口，不消费返回键
    }
}
