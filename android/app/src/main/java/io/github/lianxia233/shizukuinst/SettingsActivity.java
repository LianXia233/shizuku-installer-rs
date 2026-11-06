package io.github.lianxia233.shizukuinst;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页：静默安装授权总开关、系统应用授权列表、安装行为开关。
 * 应用列表由 Rust 核心经平台桥接读取（QUERY_ALL_PACKAGES），
 * 授权状态与决策逻辑全部由 Rust 侧负责，这里只做渲染与调用。
 */
public final class SettingsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<AppRow> allApps = new ArrayList<>();
    private final List<AppRow> shownApps = new ArrayList<>();
    private final Set<String> allowed = new HashSet<>();

    private LinearLayout list;
    private LinearLayout advancedBody;
    private TextView advancedArrow;
    private TextView tvAuthWarn;
    private TextView tvAllowedCount;
    private TextView appEmpty;
    private EditText etPackage;
    private EditText etSearch;
    private Switch swConfirm;
    private Switch swCallerAuth;
    private Switch swFloating;
    private Switch swFallback;
    private ListView appList;
    private AppAdapter adapter;
    private JSONObject cachedConfig;
    private boolean suppress;

    static final class AppRow {
        final String pkg;
        final String label;
        final boolean system;
        boolean allowed;

        AppRow(String pkg, String label, boolean system) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        list = findViewById(R.id.list);
        advancedBody = findViewById(R.id.advancedBody);
        advancedArrow = findViewById(R.id.advancedArrow);
        tvAuthWarn = findViewById(R.id.tvAuthWarn);
        tvAllowedCount = findViewById(R.id.tvAllowedCount);
        appEmpty = findViewById(R.id.appEmpty);
        etPackage = findViewById(R.id.etPackage);
        etSearch = findViewById(R.id.etSearch);
        swConfirm = findViewById(R.id.swConfirm);
        swCallerAuth = findViewById(R.id.swCallerAuth);
        swFloating = findViewById(R.id.swFloating);
        swFallback = findViewById(R.id.swFallback);
        appList = findViewById(R.id.appList);

        adapter = new AppAdapter();
        appList.setAdapter(adapter);

        findViewById(R.id.advancedToggle).setOnClickListener(v -> {
            boolean expand = advancedBody.getVisibility() != View.VISIBLE;
            advancedBody.setVisibility(expand ? View.VISIBLE : View.GONE);
            advancedArrow.setText(expand
                    ? R.string.advanced_collapse : R.string.advanced_expand);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.btnRevokeAll).setOnClickListener(
                v -> call("callerClear", new JSONObject()));
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
        findViewById(R.id.btnClear).setOnClickListener(
                v -> call("allowListClear", new JSONObject()));

        swCallerAuth.setOnCheckedChangeListener((b, checked) -> {
            if (suppress) {
                return;
            }
            tvAuthWarn.setVisibility(checked ? View.GONE : View.VISIBLE);
            saveConfig();
        });
        swFloating.setOnCheckedChangeListener((b, checked) -> {
            if (suppress) {
                return;
            }
            if (checked && !FloatingIndicator.canDraw(this)) {
                // 不再把开关强制关掉：未授权时会降级为 44dp 小窗，指示器仍然可见
                new AlertDialog.Builder(this)
                        .setTitle(R.string.overlay_perm_title)
                        .setMessage(R.string.overlay_perm_msg)
                        .setPositiveButton(R.string.overlay_perm_grant,
                                (d, w) -> FloatingIndicator.openPermissionSettings(this))
                        .setNegativeButton(R.string.overlay_perm_skip, null)
                        .show();
            }
            saveConfig();
        });
        swFallback.setOnCheckedChangeListener((b, checked) -> {
            if (!suppress) {
                saveConfig();
            }
        });
        swConfirm.setOnCheckedChangeListener((b, checked) -> {
            if (!suppress) {
                saveConfig();
            }
        });

        loadAll();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void loadAll() {
        worker.execute(() -> {
            JSONObject status = dispatch("status", "{}");
            JSONObject d = status.optJSONObject("data");
            JSONObject cfg = d == null ? null : d.optJSONObject("config");

            JSONObject appsR = dispatch("installedApps", "{}");
            JSONObject ad = appsR.optJSONObject("data");
            JSONArray arr = ad == null ? null : ad.optJSONArray("apps");
            List<AppRow> rows = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String pkg = o.optString("package", "");
                    if (pkg.isEmpty()) {
                        continue;
                    }
                    String name = o.optString("label", "");
                    rows.add(new AppRow(pkg, name.isEmpty() ? pkg : name,
                            o.optBoolean("system", false)));
                }
            }
            final JSONObject cfgFinal = cfg;
            final List<AppRow> rowsFinal = rows;
            ui(() -> {
                applyConfig(cfgFinal);
                setApps(rowsFinal);
            });
        });
    }

    private void applyConfig(JSONObject cfg) {
        if (cfg == null) {
            return;
        }
        cachedConfig = cfg;
        allowed.clear();
        JSONArray al = cfg.optJSONArray("allowedCallers");
        if (al != null) {
            for (int i = 0; i < al.length(); i++) {
                allowed.add(al.optString(i));
            }
        }
        suppress = true;
        swCallerAuth.setChecked(cfg.optBoolean("callerAuthEnabled", true));
        swFloating.setChecked(cfg.optBoolean("floatingIndicator", true));
        swFallback.setChecked(cfg.optBoolean("fallbackToSystemInstaller", true));
        swConfirm.setChecked(cfg.optBoolean("confirmBeforeInstall", false));
        suppress = false;
        tvAuthWarn.setVisibility(swCallerAuth.isChecked() ? View.GONE : View.VISIBLE);

        JSONArray wl = cfg.optJSONArray("allowList");
        renderAllowList(wl == null ? new JSONArray() : wl);
        syncAllowedFlags();
    }

    private void setApps(List<AppRow> rows) {
        allApps.clear();
        allApps.addAll(rows);
        syncAllowedFlags();
        filter(etSearch.getText() == null ? "" : etSearch.getText().toString());
    }

    private void syncAllowedFlags() {
        for (AppRow r : allApps) {
            r.allowed = allowed.contains(r.pkg);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateCount();
    }

    private void filter(String query) {
        shownApps.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (AppRow r : allApps) {
            if (q.isEmpty()
                    || r.label.toLowerCase(Locale.ROOT).contains(q)
                    || r.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                shownApps.add(r);
            }
        }
        adapter.notifyDataSetChanged();
        appEmpty.setVisibility(shownApps.isEmpty() ? View.VISIBLE : View.GONE);
        appEmpty.setText(allApps.isEmpty()
                ? R.string.auth_loading : R.string.auth_no_match);
    }

    private void updateCount() {
        int n = 0;
        for (AppRow r : allApps) {
            if (r.allowed) {
                n++;
            }
        }
        tvAllowedCount.setText(getString(R.string.auth_allowed_count, n));
    }

    private void toggle(AppRow row) {
        boolean next = !row.allowed;
        row.allowed = next;
        if (next) {
            allowed.add(row.pkg);
        } else {
            allowed.remove(row.pkg);
        }
        adapter.notifyDataSetChanged();
        updateCount();
        try {
            call("callerSet", new JSONObject()
                    .put("caller", row.pkg)
                    .put("allowed", next));
        } catch (JSONException ignored) {
        }
    }

    private void saveConfig() {
        if (cachedConfig == null) {
            return;
        }
        try {
            JSONObject patch = new JSONObject()
                    .put("callerAuthEnabled", swCallerAuth.isChecked())
                    .put("floatingIndicator", swFloating.isChecked())
                    .put("fallbackToSystemInstaller", swFallback.isChecked())
                    .put("confirmBeforeInstall", swConfirm.isChecked());
            call("setConfig", new JSONObject().put("config", patch));
        } catch (JSONException ignored) {
        }
    }

    private void renderAllowList(JSONArray al) {
        list.removeAllViews();
        if (al.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.allow_empty);
            empty.setTextSize(13);
            empty.setTextColor(getResources().getColor(R.color.text_muted, getTheme()));
            empty.setPadding(8, 16, 8, 16);
            list.addView(empty);
            return;
        }
        for (int i = 0; i < al.length(); i++) {
            final String pkg = al.optString(i);
            View row = getLayoutInflater().inflate(R.layout.item_allow, list, false);
            ((TextView) row.findViewById(R.id.itemAllowName)).setText(pkg);
            row.findViewById(R.id.itemAllowRemove).setOnClickListener(v -> {
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
                            e == null ? getString(R.string.auth_toggle_failed)
                                    : e.optString("message", getString(R.string.auth_toggle_failed)),
                            Toast.LENGTH_SHORT).show();
                    loadAll();
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

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shownApps.size();
        }

        @Override
        public Object getItem(int position) {
            return shownApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(SettingsActivity.this)
                        .inflate(R.layout.item_app, parent, false);
            }
            final AppRow row = shownApps.get(position);
            ((TextView) v.findViewById(R.id.itemAppName)).setText(row.label);
            ((TextView) v.findViewById(R.id.itemAppPkg)).setText(row.pkg);
            ((TextView) v.findViewById(R.id.itemAppTag)).setText(getString(
                    row.system ? R.string.auth_tag_system : R.string.auth_tag_user));
            Switch sw = v.findViewById(R.id.itemAppSwitch);
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(row.allowed);
            v.setOnClickListener(x -> toggle(row));
            return v;
        }
    }
}
