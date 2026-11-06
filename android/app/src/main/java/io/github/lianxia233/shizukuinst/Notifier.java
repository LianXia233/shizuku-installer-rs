package io.github.lianxia233.shizukuinst;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 轻量反馈通道：安装中以 heads-up 气泡显示进度，完成后把同一条通知更新为结果。
 * 不创建 Activity、不启动任何界面；未授予通知权限时降级为短暂文本提示。
 */
final class Notifier {
    static final String CH_PROGRESS = "silent_progress";
    static final String CH_RESULT = "install_result";
    private static final int ID = 2001;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Notifier() {
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel p = new NotificationChannel(CH_PROGRESS,
                c.getString(R.string.notify_channel_progress), NotificationManager.IMPORTANCE_HIGH);
        p.setDescription(c.getString(R.string.notify_channel_progress_desc));
        p.enableVibration(false);
        p.setSound(null, null);
        nm.createNotificationChannel(p);

        NotificationChannel r = new NotificationChannel(CH_RESULT,
                c.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT);
        r.setDescription(c.getString(R.string.notify_channel_desc));
        nm.createNotificationChannel(r);
    }

    private static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void toast(Context c, String s) {
        MAIN.post(() -> Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show());
    }

    private static Notification.Builder builder(Context c, String channel) {
        return new Notification.Builder(c, channel);
    }

    private static void post(Context c, Notification.Builder b) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(ID, b.build());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String installingTitle(Context c, String label, boolean uninstall) {
        return c.getString(uninstall ? R.string.notify_uninstalling : R.string.notify_installing, label);
    }

    static void start(Context c, String label, boolean uninstall) {
        ensureChannels(c);
        String t = installingTitle(c, label, uninstall);
        if (!allowed(c)) {
            toast(c, t);
            return;
        }
        post(c, builder(c, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_action_notification)
                .setContentTitle(t)
                .setContentText(c.getString(R.string.notify_stage_prepare))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, 0, true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC));
    }

    static void progress(Context c, String label, boolean uninstall,
                         int index, int total, String stage) {
        if (!allowed(c)) {
            return;
        }
        int safeTotal = Math.max(1, total);
        int pct = Math.min(100, index * 100 / safeTotal);
        String text = stageText(c, stage) + " · " + pct + "%";
        post(c, builder(c, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_action_notification)
                .setContentTitle(installingTitle(c, label, uninstall))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(safeTotal, Math.min(index, safeTotal), false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC));
    }

    static void finish(Context c, String label, boolean uninstall, boolean ok,
                       String detail, String launchPackage) {
        ensureChannels(c);
        int tid = uninstall
                ? (ok ? R.string.notify_uninstall_ok : R.string.notify_uninstall_fail)
                : (ok ? R.string.notify_install_ok : R.string.notify_install_fail);
        String t = c.getString(tid, label);
        String text = detail == null || detail.trim().isEmpty() ? t : detail.trim();
        if (!allowed(c)) {
            toast(c, t);
            return;
        }

        Notification.Builder b = builder(c, CH_RESULT)
                .setSmallIcon(R.drawable.ic_action_notification)
                .setContentTitle(t)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        PendingIntent pi = null;
        if (ok && launchPackage != null && !launchPackage.isEmpty()
                && !"null".equals(launchPackage)) {
            Intent launch = c.getPackageManager().getLaunchIntentForPackage(launchPackage);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                pi = PendingIntent.getActivity(c, 21, launch,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                b.addAction(R.drawable.ic_action_launch,
                        c.getString(R.string.notify_action_launch), pi);
            }
        }
        if (pi == null) {
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pi = PendingIntent.getActivity(c, 22, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        b.setContentIntent(pi);
        Intent logs = new Intent(c, LogActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent logsPi = PendingIntent.getActivity(c, 23, logs,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(R.drawable.ic_action_logs,
                c.getString(R.string.notify_action_logs), logsPi);
        if (ok) {
            b.setTimeoutAfter(20_000);
        }
        post(c, b);
    }

    /** 调用方被拒绝：明确告知「已阻止」，而不是伪装成安装失败。 */
    static void denied(Context c, String label, String callerLabel) {
        ensureChannels(c);
        String who = callerLabel == null || callerLabel.trim().isEmpty()
                ? c.getString(R.string.caller_unknown) : callerLabel.trim();
        String title = c.getString(R.string.notify_auth_denied_title);
        String text = c.getString(R.string.auth_denied, who);
        if (!allowed(c)) {
            toast(c, text);
            return;
        }
        post(c, builder(c, CH_RESULT)
                .setSmallIcon(R.drawable.ic_action_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setTimeoutAfter(15_000));
    }

    /** 静默失败后的降级提示：点击通知即可进入系统安装界面。 */
    static void fallback(Context c, String label, String detail, Intent installIntent) {
        ensureChannels(c);
        String title = c.getString(R.string.notify_fallback_title, label);
        String text = detail == null || detail.trim().isEmpty()
                ? c.getString(R.string.notify_fallback_text) : detail.trim();
        if (!allowed(c)) {
            toast(c, title);
            return;
        }
        Notification.Builder b = builder(c, CH_RESULT)
                .setSmallIcon(R.drawable.ic_action_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (installIntent != null) {
            PendingIntent pi = PendingIntent.getActivity(c, 31, installIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setContentIntent(pi);
        }
        post(c, b);
    }

    private static String stageText(Context c, String stage) {
        if (stage == null) {
            return c.getString(R.string.notify_stage_prepare);
        }
        if (stage.startsWith("write_split")) {
            return c.getString(R.string.notify_stage_write);
        }
        if (stage.startsWith("create_session")) {
            return c.getString(R.string.notify_stage_create);
        }
        if (stage.startsWith("commit")) {
            return c.getString(R.string.notify_stage_commit);
        }
        if (stage.startsWith("done")) {
            return c.getString(R.string.notify_stage_done);
        }
        return c.getString(R.string.notify_stage_prepare);
    }
}
