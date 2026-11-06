package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 关于页：应用简介、开源仓库（可点击跳转）、开发者与开源许可说明。
 * 纯静态页面，不涉及任何静默安装能力。
 */
public final class AboutActivity extends Activity {
    private static final String REPO_URL = "https://github.com/LianXia233/shizuku-installer-rs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.tvVersion)).setText(
                getString(R.string.about_version, BuildInfo.versionName(this)));

        ((TextView) findViewById(R.id.tvRepo)).setText(REPO_URL);
        findViewById(R.id.cardRepo).setOnClickListener(v -> openRepo());
    }

    private void openRepo() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)));
        } catch (ActivityNotFoundException e) {
            try {
                // 没有浏览器时退化为分享，用户仍可复制链接
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, REPO_URL);
                startActivity(Intent.createChooser(share, getString(R.string.about_repo_label)));
            } catch (Throwable ignored) {
                Toast.makeText(this, REPO_URL, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, REPO_URL, Toast.LENGTH_LONG).show();
        }
    }
}
