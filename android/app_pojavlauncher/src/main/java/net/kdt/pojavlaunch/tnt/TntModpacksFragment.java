package net.kdt.pojavlaunch.tnt;

import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** «Скачать сборку»: готовые модпаки Modrinth с выбором версии игры, загрузчика, названия и версии сборки. */
public class TntModpacksFragment extends Fragment {
    public static final String TAG = "TntModpacksFragment";
    private static final String API = "https://api.modrinth.com/v2/";
    private static final String[] LOADER_IDS = {null, "fabric", "forge", "neoforge", "quilt"};
    private static final String[] LOADER_NAMES = {null, "Fabric", "Forge", "NeoForge", "Quilt"};
    private static final ExecutorService sNet = Executors.newFixedThreadPool(3);
    private static List<String> sGameVersions;

    private final List<TntModrinth.Mod> mResults = new ArrayList<>();
    private final List<String> mVersionChoices = new ArrayList<>();
    private Spinner mVersion, mLoader;
    private TextView mStatus;
    private boolean mLoadingPage, mHasMore;
    private int mSerial;
    private String mQuery = "";
    private final Adapter mAdapter = new Adapter();

    public TntModpacksFragment() { super(R.layout.tnt_fragment_modpacks); }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mVersion = view.findViewById(R.id.tnt_mp_version);
        mLoader = view.findViewById(R.id.tnt_mp_loader);
        mStatus = view.findViewById(R.id.tnt_mp_status);
        EditText search = view.findViewById(R.id.tnt_mp_search);
        ListView list = view.findViewById(R.id.tnt_mp_list);
        list.setAdapter(mAdapter);

        String[] loaders = LOADER_NAMES.clone();
        loaders[0] = getString(R.string.tnt_any_loader);
        mLoader.setAdapter(spinnerAdapter(loaders));
        mVersionChoices.add(getString(R.string.tnt_any_version));
        mVersion.setAdapter(spinnerAdapter(mVersionChoices.toArray(new String[0])));
        loadGameVersions();

        AdapterView.OnItemSelectedListener refilter = new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) { first = false; return; }
                search(true);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        mLoader.setOnItemSelectedListener(refilter);

        Runnable doSearch = () -> {
            mQuery = search.getText().toString();
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search(true);
        };
        view.findViewById(R.id.tnt_mp_find).setOnClickListener(v -> doSearch.run());
        search.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch.run();
                return true;
            }
            return false;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {}
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                if (mHasMore && !mLoadingPage && total > 0 && first + visible >= total - 3) search(false);
            }
        });
        search(true);
    }

    private ArrayAdapter<String> spinnerAdapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), R.layout.tnt_spinner_item, items);
        a.setDropDownViewResource(R.layout.tnt_spinner_item);
        return a;
    }

    /** Релизные версии игры для фильтра (теги Modrinth). */
    private void loadGameVersions() {
        if (sGameVersions != null) { applyGameVersions(); return; }
        sNet.execute(() -> {
            List<String> out = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(TntVersions.get(API + "tag/game_version"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject v = a.getJSONObject(i);
                    if ("release".equals(v.optString("version_type"))) out.add(v.getString("version"));
                }
            } catch (Exception ignored) {}
            if (!out.isEmpty()) sGameVersions = out;
            Tools.runOnUiThread(() -> { if (isAdded()) applyGameVersions(); });
        });
    }

    private void applyGameVersions() {
        if (sGameVersions == null) return;
        mVersionChoices.clear();
        mVersionChoices.add(getString(R.string.tnt_any_version));
        mVersionChoices.addAll(sGameVersions);
        mVersion.setAdapter(spinnerAdapter(mVersionChoices.toArray(new String[0])));
        mVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) { first = false; return; }
                search(true);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private String selectedGameVersion() {
        int p = mVersion.getSelectedItemPosition();
        return p <= 0 || p >= mVersionChoices.size() ? null : mVersionChoices.get(p);
    }

    private String selectedLoader() {
        int p = mLoader.getSelectedItemPosition();
        return p <= 0 ? null : LOADER_IDS[p];
    }

    private void search(boolean reset) {
        final int serial = reset ? ++mSerial : mSerial;
        if (reset) { mResults.clear(); mHasMore = false; mAdapter.notifyDataSetChanged(); }
        mLoadingPage = true;
        mStatus.setText(R.string.tnt_searching);
        final int offset = mResults.size();
        final String query = mQuery.trim(), mc = selectedGameVersion(), loader = selectedLoader();
        sNet.execute(() -> {
            try {
                StringBuilder facets = new StringBuilder("[[\"project_type:modpack\"]");
                if (mc != null) facets.append(",[\"versions:").append(mc).append("\"]");
                if (loader != null) facets.append(",[\"categories:").append(loader).append("\"]");
                facets.append(']');
                String url = API + "search?limit=20&offset=" + offset
                        + "&index=" + (query.isEmpty() ? "downloads" : "relevance")
                        + "&facets=" + TntModrinth.enc(facets.toString())
                        + (query.isEmpty() ? "" : "&query=" + TntModrinth.enc(query));
                JSONObject r = new JSONObject(TntVersions.get(url));
                JSONArray hits = r.getJSONArray("hits");
                List<TntModrinth.Mod> page = new ArrayList<>();
                for (int i = 0; i < hits.length(); i++) {
                    JSONObject h = hits.getJSONObject(i);
                    TntModrinth.Mod m = new TntModrinth.Mod();
                    m.id = h.getString("project_id");
                    m.title = h.optString("title");
                    m.author = h.optString("author");
                    m.description = h.optString("description");
                    m.iconUrl = h.optString("icon_url", null);
                    m.downloads = h.optLong("downloads");
                    page.add(m);
                }
                int total = r.optInt("total_hits");
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || serial != mSerial) return;
                    mResults.addAll(page);
                    mHasMore = mResults.size() < total && !page.isEmpty();
                    mLoadingPage = false;
                    mAdapter.notifyDataSetChanged();
                    mStatus.setText(mResults.isEmpty()
                            ? getString(R.string.tnt_nothing_found, mc == null ? "Modrinth" : mc)
                            : getString(R.string.tnt_modpacks_found, mResults.size(), mHasMore ? "+" : ""));
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || serial != mSerial) return;
                    mLoadingPage = false;
                    mStatus.setText(getString(R.string.tnt_modrinth_error, e.getMessage()));
                });
            }
        });
    }

    /** Диалог установки: название сборки + версия модпака под выбранные фильтры. */
    private void showInstallDialog(TntModrinth.Mod m) {
        Context c = requireContext();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);

        TextView nameLabel = label(c, R.string.tnt_modpack_name);
        EditText name = new EditText(c);
        name.setText(m.title);
        name.setSingleLine(true);
        name.setTextColor(0xFFFFFFFF);
        TextView verLabel = label(c, R.string.tnt_modpack_version);
        Spinner versions = new Spinner(c);
        TextView info = new TextView(c);
        info.setTextColor(0xFFB4B4B4);
        info.setText(R.string.tnt_modpack_loading_versions);
        box.addView(nameLabel);
        box.addView(name);
        box.addView(verLabel);
        box.addView(versions);
        box.addView(info);

        AlertDialog dlg = new AlertDialog.Builder(c)
                .setTitle(m.title)
                .setView(box)
                .setPositiveButton(R.string.tnt_install, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.show();
        Button install = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        install.setEnabled(false);

        final String mc = selectedGameVersion(), loader = selectedLoader();
        final List<JSONObject> found = new ArrayList<>();
        sNet.execute(() -> {
            try {
                String url = API + "project/" + m.id + "/version?"
                        + (loader != null ? "loaders=" + TntModrinth.enc("[\"" + loader + "\"]") + "&" : "")
                        + (mc != null ? "game_versions=" + TntModrinth.enc("[\"" + mc + "\"]") : "");
                JSONArray a = new JSONArray(TntVersions.get(url));
                for (int i = 0; i < a.length(); i++) found.add(a.getJSONObject(i));
            } catch (Exception ignored) {}
            Tools.runOnUiThread(() -> {
                if (!dlg.isShowing()) return;
                if (found.isEmpty()) { info.setText(R.string.tnt_modpack_no_versions); return; }
                String[] labels = new String[found.size()];
                for (int i = 0; i < found.size(); i++) labels[i] = versionLabel(found.get(i));
                versions.setAdapter(spinnerAdapter(labels));
                info.setText("");
                install.setEnabled(true);
            });
        });

        install.setOnClickListener(v -> {
            int idx = versions.getSelectedItemPosition();
            if (idx < 0 || idx >= found.size()) return;
            String title = name.getText().toString().trim();
            if (title.isEmpty()) title = m.title;
            try {
                ModDetail detail = toModDetail(m, title, found.get(idx));
                new ModrinthApi().handleInstallation(c.getApplicationContext(), detail, 0);
                Toast.makeText(c, getString(R.string.tnt_modpack_started, title), Toast.LENGTH_LONG).show();
                dlg.dismiss();
                Tools.backToMainMenu(requireActivity());
            } catch (Exception e) {
                Toast.makeText(c, getString(R.string.tnt_error_x, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static TextView label(Context c, int text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xFFFF8024);
        t.setPadding(0, (int) (10 * c.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static String versionLabel(JSONObject v) {
        StringBuilder sb = new StringBuilder(v.optString("version_number", v.optString("name")));
        JSONArray gv = v.optJSONArray("game_versions"), lo = v.optJSONArray("loaders");
        if (gv != null && gv.length() > 0) sb.append(" · ").append(gv.optString(0));
        if (lo != null && lo.length() > 0) sb.append(" · ").append(lo.optString(0));
        return sb.toString();
    }

    /** Модель движка для установщика .mrpack: одна выбранная версия, название — заданное игроком. */
    private static ModDetail toModDetail(TntModrinth.Mod m, String title, JSONObject v) throws Exception {
        JSONArray files = v.getJSONArray("files");
        JSONObject file = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++)
            if (files.getJSONObject(i).optBoolean("primary")) { file = files.getJSONObject(i); break; }
        JSONObject hashes = file.optJSONObject("hashes");
        JSONArray gv = v.optJSONArray("game_versions");
        ModItem item = new ModItem(Constants.SOURCE_MODRINTH, true, m.id, title, m.description, m.iconUrl);
        return new ModDetail(item,
                new String[]{v.optString("version_number", v.optString("name"))},
                new String[]{v.getString("id")},
                new String[]{gv != null && gv.length() > 0 ? gv.getString(0) : ""},
                new String[]{file.getString("url")},
                new String[]{hashes != null ? hashes.optString("sha1", null) : null},
                new ModDetail.Dependencies[][]{new ModDetail.Dependencies[0]});
    }

    private static String formatDownloads(Context c, long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, c.getString(R.string.tnt_million), n / 1e6);
        if (n >= 1_000) return String.format(Locale.ROOT, c.getString(R.string.tnt_thousand), n / 1e3);
        return String.valueOf(n);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return mResults.size(); }
        @Override public Object getItem(int i) { return mResults.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View row, ViewGroup parent) {
            if (row == null) row = LayoutInflater.from(parent.getContext()).inflate(R.layout.tnt_item_mod, parent, false);
            TntModrinth.Mod m = mResults.get(i);
            ((TextView) row.findViewById(R.id.tnt_mod_title)).setText(m.title);
            ((TextView) row.findViewById(R.id.tnt_mod_author)).setText(
                    getString(R.string.tnt_by_author, m.author, formatDownloads(parent.getContext(), m.downloads)));
            ((TextView) row.findViewById(R.id.tnt_mod_desc)).setText(m.description);
            ((TextView) row.findViewById(R.id.tnt_mod_letter)).setText(
                    m.title.isEmpty() ? "?" : m.title.substring(0, 1).toUpperCase(Locale.ROOT));
            TntModsFragment.loadIcon((ImageView) row.findViewById(R.id.tnt_mod_icon), m.iconUrl);
            Button btn = row.findViewById(R.id.tnt_mod_button);
            btn.setEnabled(true);
            btn.setText(R.string.tnt_btn_download);
            btn.setBackgroundResource(R.drawable.tnt_btn_play);
            btn.setOnClickListener(v -> showInstallDialog(m));
            return row;
        }
    }
}
