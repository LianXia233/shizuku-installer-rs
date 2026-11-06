package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
 * 首次调用的授权弹窗。
 *
 * 这是整条静默链路里唯一会显示界面的地方（用户必须做一次决策），
 * 因此用标准的居中对话框主题并允许 dim。安装入口本身是 NoDisplay，
 * 用户点完按钮后本 Activity 立即销毁，安装期间屏幕上不再有本应用的窗口。
 */
public final class CallerAuthActivity extends Activity {
    private List<String> uris = new ArrayList<>();
    private boolean uninstall;
    private boolean ended;
    private String uninstallPkg = "";
    private String label = "安装包";
    private String caller = "@unknown";
    private String callerLabel = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_auth);
        hardenDialogWindow();
        readExtras(getIntent());

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
    }

    /**
     * 把窗口显式约束成居中的浮动卡片，避免某些 ROM 把 Dialog 主题拉伸成一个
     * 全屏透明窗口（那样用户看不到卡片，只会觉得「屏幕卡住、要按返回」）。
     * 同时不让它绘制系统栏背景，避免把状态栏/导航栏染黑。
     */
    private void hardenDialogWindow() {
        try {
            Window w = getWindow();
            if (w == null) {
                return;
            }
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.32f;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            w.setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    private void readExtras(android.content.Intent i) {
        if (i == null) {
            return;
        }
        ArrayList<String> list = i.getStringArrayListExtra(SilentInstallActivity.EXTRA_URIS);
        if (list != null) {
            uris = list;
        }
        caller = i.getStringExtra(SilentInstallActivity.EXTRA_CALLER);
        callerLabel = i.getStringExtra(SilentInstallActivity.EXTRA_CALLER_LABEL);
        label = i.getStringExtra(SilentInstallActivity.EXTRA_LABEL);
        uninstall = i.getBooleanExtra(SilentInstallActivity.EXTRA_UNINSTALL, false);
        uninstallPkg = i.getStringExtra(SilentInstallActivity.EXTRA_UNINSTALL_PKG);
        if (caller == null || caller.isEmpty()) {
            caller = "@unknown";
        }
        if (callerLabel == null) {
            callerLabel = "";
        }
        if (label == null || label.isEmpty()) {
            label = "安装包";
        }
        if (uninstallPkg == null) {
            uninstallPkg = "";
        }
        if (callerLabel.trim().isEmpty()) {
            callerLabel = AppNames.labelForCaller(this, caller);
        }
    }

    private void onDecision(boolean allow, boolean remember) {
        if (ended) {
            return;
        }
        ended = true;
        final boolean rememberFinal = remember && !"@unknown".equals(caller);
        final String callerFinal = caller;
        App.executor().execute(() -> {
            try {
                JSONObject a = new JSONObject()
                        .put("caller", callerFinal)
                        .put("remember", rememberFinal);
                RustCore.dispatch(allow ? "callerGrant" : "callerDeny", a.toString());
            } catch (Throwable ignored) {
            }
        });
        if (!allow) {
            DenyLog.record(getApplicationContext(), uninstall, label, caller, callerLabel);
        } else {
            TaskLauncher.launch(this, uninstall, uris, uninstallPkg, caller, label);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        onDecision(false, false);
    }
}
