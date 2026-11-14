package net.kdt.pojavlaunch.tnt;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Окно «Моды» как в TLauncher: каталог Modrinth с поиском, установка в один клик, вкладка «Установленные». */
public class TntModsFragment extends Fragment {
    public static final String TAG = "TntModsFragment";
    private static final String A_TITLE = "title", A_MC = "mc", A_LOADERS = "loaders", A_DIR = "dir";

    private static final LruCache<String, Bitmap> sIcons = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final Set<String> sNoIcon = Collections.synchronizedSet(new HashSet<>());
    private static final ExecutorService sIconPool = Executors.newFixedThreadPool(4);
    private static final ExecutorService sNet = Executors.newFixedThreadPool(3);
    /** Моды, которые сейчас ставятся (переживает пересоздание экрана). */
    private static final Set<String> sInstalling = Collections.synchronizedSet(new HashSet<>());

    private String mTitle, mMc;
    private String[] mLoaders;
    private File mModsDir;

    private final List<TntModrinth.Mod> mResults = new ArrayList<>();
    private List<TntModrinth.Installed> mInstalled = new ArrayList<>();
    private final Set<String> mInstalledIds = new HashSet<>();
    private boolean mCatalogTab = true, mLoadingPage, mHasMore;
    private int mSearchSerial;
    private String mQuery = "";

    private TextView mStatus, mTabCatalog, mTabInstalled;
    private View mSearchRow;
    private ListView mList;
    private final ModsAdapter mAdapter = new ModsAdapter();

    public static Bundle args(TntItem.ModTarget t) {
        Bundle b = new Bundle();
        b.putString(A_TITLE, t.title);
        b.putString(A_MC, t.mc);
        b.putStringArray(A_LOADERS, t.loaders);
        b.putString(A_DIR, t.modsDir);
        return b;
    }

    public TntModsFragment() { super(R.layout.tnt_fragment_mods); }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle a = requireArguments();
        mTitle = a.getString(A_TITLE);
        mMc = a.getString(A_MC);
        mLoaders = a.getStringArray(A_LOADERS);
        mModsDir = new File(a.getString(A_DIR));

        ((TextView) view.findViewById(R.id.tnt_mods_sub)).setText(getString(R.string.tnt_mods_subtitle, mTitle));
        mStatus = view.findViewById(R.id.tnt_mods_status);
        mTabCatalog = view.findViewById(R.id.tnt_tab_catalog);
        mTabInstalled = view.findViewById(R.id.tnt_tab_installed);
        mSearchRow = view.findViewById(R.id.tnt_search_row);
        mList = view.findViewById(R.id.tnt_mods_list);
        EditText search = view.findViewById(R.id.tnt_mods_search);
        Button find = view.findViewById(R.id.tnt_mods_find);
        mList.setAdapter(mAdapter);

        mTabCatalog.setOnClickListener(v -> setTab(true));
        mTabInstalled.setOnClickListener(v -> setTab(false));
        Runnable doSearch = () -> {
            mQuery = search.getText().toString();
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search(true);
        };
        find.setOnClickListener(v -> doSearch.run());
        search.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch.run();
                return true;
            }
            return false;
        });
        view.findViewById(R.id.tnt_mods_folder).setOnClickListener(v -> {
            mModsDir.mkdirs();
            Tools.openPath(requireContext(), mModsDir, false);
        });
        // Подгрузка следующей страницы при прокрутке до конца
        mList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {}
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                if (mCatalogTab && mHasMore && !mLoadingPage && total > 0 && first + visible >= total - 3) search(false);
            }
        });

        refreshInstalled();
        setTab(true);
        search(true);
    }

    private void setTab(boolean catalog) {
        mCatalogTab = catalog;
        mTabCatalog.setSelected(catalog);
        mTabInstalled.setSelected(!catalog);
        mSearchRow.setVisibility(catalog ? View.VISIBLE : View.GONE);
        if (!catalog) refreshInstalled();
        mAdapter.notifyDataSetChanged();
        updateStatus();
    }

    private void refreshInstalled() {
        mInstalled = TntModrinth.listInstalled(mModsDir);
        mInstalledIds.clear();
        for (TntModrinth.Installed i : mInstalled) if (!i.projectId.isEmpty()) mInstalledIds.add(i.projectId);
        mTabInstalled.setText(getString(R.string.tnt_tab_installed_n, mInstalled.size()));
    }

    private void updateStatus() {
        if (mCatalogTab) {
            if (mLoadingPage && mResults.isEmpty()) mStatus.setText(R.string.tnt_searching);
            else if (mResults.isEmpty()) mStatus.setText(getString(R.string.tnt_nothing_found, mMc));
            else mStatus.setText(getString(R.string.tnt_found_n, mResults.size(), mHasMore ? "+" : ""));
        } else {
            mStatus.setText(mInstalled.isEmpty() ? getString(R.string.tnt_no_mods) : getString(R.string.tnt_mods_in_folder, mInstalled.size()));
        }
    }

    private void search(boolean reset) {
        final int serial = reset ? ++mSearchSerial : mSearchSerial;
        if (reset) {
            mResults.clear();
            mHasMore = false;
            mAdapter.notifyDataSetChanged();
        }
        mLoadingPage = true;
        updateStatus();
        final int offset = mResults.size();
        final String query = mQuery;
        sNet.execute(() -> {
            try {
                TntModrinth.SearchResult r = TntModrinth.search(query, mMc, mLoaders, offset);
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || serial != mSearchSerial) return;
                    mResults.addAll(r.mods);
                    mHasMore = mResults.size() < r.total && !r.mods.isEmpty();
                    mLoadingPage = false;
                    mAdapter.notifyDataSetChanged();
                    updateStatus();
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || serial != mSearchSerial) return;
                    mLoadingPage = false;
                    mStatus.setText(getString(R.string.tnt_modrinth_error, e.getMessage()));
                });
            }
        });
    }

    private void install(TntModrinth.Mod m) {
        if (!sInstalling.add(m.id)) return;
        mAdapter.notifyDataSetChanged();
        mStatus.setText(getString(R.string.tnt_installing_mod, m.title));
        sNet.execute(() -> {
            String error = null;
            try {
                TntModrinth.install(m.id, m.title, mMc, mLoaders, mModsDir,
                        s -> Tools.runOnUiThread(() -> { if (isAdded()) mStatus.setText(s); }));
            } catch (Exception e) {
                error = e.getMessage();
            }
            sInstalling.remove(m.id);
            final String err = error;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                refreshInstalled();
                mAdapter.notifyDataSetChanged();
                mStatus.setText(err == null ? getString(R.string.tnt_mod_installed, m.title) : getString(R.string.tnt_error_x, err));
            });
        });
    }

    private void confirmRemove(String title, Runnable action) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.tnt_remove_mod_title)
                .setMessage(getString(R.string.tnt_remove_mod_msg, title))
                .setPositiveButton(R.string.tnt_remove, (d, w) -> action.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static void loadIcon(ImageView iv, String url) {
        iv.setTag(url);
        iv.setImageBitmap(null);
        if (url == null || url.isEmpty() || sNoIcon.contains(url)) return;
        Bitmap cached = sIcons.get(url);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        int size = (int) (56 * iv.getResources().getDisplayMetrics().density);
        sIconPool.execute(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                byte[] data;
                try (InputStream is = c.getInputStream()) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    data = bo.toByteArray();
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (Exception ignored) {}
            if (b == null) { sNoIcon.add(url); return; }
            sIcons.put(url, b);
            final Bitmap fb = b;
            iv.post(() -> { if (url.equals(iv.getTag())) iv.setImageBitmap(fb); });
        });
    }

    private String formatDownloads(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, getString(R.string.tnt_million), n / 1e6);
        if (n >= 1_000) return String.format(Locale.ROOT, getString(R.string.tnt_thousand), n / 1e3);
        return String.valueOf(n);
    }

    private final class ModsAdapter extends BaseAdapter {
        @Override public int getCount() { return mCatalogTab ? mResults.size() : mInstalled.size(); }
        @Override public Object getItem(int i) { return mCatalogTab ? mResults.get(i) : mInstalled.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View row, ViewGroup parent) {
            if (row == null) row = LayoutInflater.from(parent.getContext()).inflate(R.layout.tnt_item_mod, parent, false);
            TextView title = row.findViewById(R.id.tnt_mod_title);
            TextView author = row.findViewById(R.id.tnt_mod_author);
            TextView desc = row.findViewById(R.id.tnt_mod_desc);
            TextView letter = row.findViewById(R.id.tnt_mod_letter);
            ImageView icon = row.findViewById(R.id.tnt_mod_icon);
            Button btn = row.findViewById(R.id.tnt_mod_button);

            if (mCatalogTab) {
                TntModrinth.Mod m = mResults.get(i);
                title.setText(m.title);
                author.setText(getString(R.string.tnt_by_author, m.author, formatDownloads(m.downloads)));
                desc.setText(m.description);
                letter.setText(m.title.isEmpty() ? "?" : m.title.substring(0, 1).toUpperCase(Locale.ROOT));
                loadIcon(icon, m.iconUrl);
                boolean installing = sInstalling.contains(m.id);
                boolean installed = mInstalledIds.contains(m.id);
                btn.setEnabled(!installing);
                if (installing) {
                    btn.setText(R.string.tnt_btn_installing);
                    btn.setBackgroundResource(R.drawable.tnt_btn_play);
                    btn.setOnClickListener(null);
                } else if (installed) {
                    btn.setText(R.string.tnt_btn_remove);
                    btn.setBackgroundResource(R.drawable.tnt_btn_red);
                    btn.setOnClickListener(v -> confirmRemove(m.title, () -> {
                        for (TntModrinth.Installed inst : mInstalled)
                            if (inst.projectId.equals(m.id)) TntModrinth.remove(mModsDir, inst);
                        refreshInstalled();
                        notifyDataSetChanged();
                        mStatus.setText(getString(R.string.tnt_mod_removed, m.title));
                    }));
                } else {
                    btn.setText(R.string.tnt_btn_install);
                    btn.setBackgroundResource(R.drawable.tnt_btn_play);
                    btn.setOnClickListener(v -> install(m));
                }
            } else {
                TntModrinth.Installed m = mInstalled.get(i);
                title.setText(m.title);
                author.setText(m.projectId.isEmpty() ? R.string.tnt_added_manually : R.string.tnt_from_modrinth);
                desc.setText(m.file);
                letter.setText(m.title.isEmpty() ? "?" : m.title.substring(0, 1).toUpperCase(Locale.ROOT));
                loadIcon(icon, null);
                btn.setEnabled(true);
                btn.setText(R.string.tnt_btn_remove);
                btn.setBackgroundResource(R.drawable.tnt_btn_red);
                btn.setOnClickListener(v -> confirmRemove(m.title, () -> {
                    TntModrinth.remove(mModsDir, m);
                    refreshInstalled();
                    notifyDataSetChanged();
                    updateStatus();
                }));
            }
            return row;
        }
    }
}
