package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout list;
    private EditText etPackage;
    private Switch swConfirm;
    private JSONObject cachedConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        list = findViewById(R.id.list);
        etPackage = findViewById(R.id.etPackage);
        swConfirm = findViewById(R.id.swConfirm);

        findViewById(R.id.btnAdd).setOnClickListener(v -> {
            String pkg = etPackage.getText().toString().trim();
            if (!pkg.isEmpty()) {
                try {
                    call("allowListAdd", new JSONObject().put("package", pkg));
                } catch (JSONException ignored) {
                }
                etPackage.setText("");
            }
        });
        findViewById(R.id.btnClear).setOnClickListener(v -> call("allowListClear", new JSONObject()));
        swConfirm.setOnCheckedChangeListener((b, checked) -> {
            if (cachedConfig == null) {
                return;
            }
            try {
                cachedConfig.put("confirmBeforeInstall", checked);
                call("setConfig", new JSONObject().put("config", cachedConfig));
            } catch (JSONException ignored) {
            }
        });
        loadConfig();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void loadConfig() {
        worker.execute(() -> {
            JSONObject r = dispatch("status", "{}");
            JSONObject cfg = r.optJSONObject("data") == null
                    ? null : r.optJSONObject("data").optJSONObject("config");
            ui(() -> applyConfig(cfg));
        });
    }

    private void applyConfig(JSONObject cfg) {
        if (cfg == null) {
            return;
        }
        cachedConfig = cfg;
        swConfirm.setChecked(cfg.optBoolean("confirmBeforeInstall", false));
        JSONArray al = cfg.optJSONArray("allowList");
        renderList(al == null ? new JSONArray() : al);
    }

    private void renderList(JSONArray al) {
        list.removeAllViews();
        if (al.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("(空)");
            empty.setPadding(8, 8, 8, 8);
            list.addView(empty);
            return;
        }
        for (int i = 0; i < al.length(); i++) {
            final String pkg = al.optString(i);
            TextView row = new TextView(this);
            row.setText(pkg + "   ✕");
            row.setTextSize(15);
            row.setPadding(8, 12, 8, 12);
            row.setOnClickListener(v -> {
                try {
                    call("allowListRemove", new JSONObject().put("package", pkg));
                } catch (JSONException ignored) {
                }
            });
            list.addView(row);
        }
    }

    private void call(String method, JSONObject args) {
        worker.execute(() -> {
            JSONObject r = dispatch(method, args.toString());
            JSONObject cfg = null;
            if (r.optBoolean("ok")) {
                JSONObject d = r.optJSONObject("data");
                cfg = d == null ? null : d.optJSONObject("config");
            }
            final JSONObject result = cfg;
            ui(() -> {
                if (result != null) {
                    applyConfig(result);
                } else if (!r.optBoolean("ok")) {
                    JSONObject e = r.optJSONObject("error");
                    Toast.makeText(this,
                            e == null ? "操作失败" : e.optString("message", "操作失败"),
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private JSONObject dispatch(String method, String args) {
        try {
            return new JSONObject(RustCore.dispatch(method, args));
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false)
                        .put("error", new JSONObject().put("message", String.valueOf(e)));
            } catch (JSONException ignored) {
                return new JSONObject();
            }
        }
    }

    private void ui(Runnable r) {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(r);
        }
    }
}
