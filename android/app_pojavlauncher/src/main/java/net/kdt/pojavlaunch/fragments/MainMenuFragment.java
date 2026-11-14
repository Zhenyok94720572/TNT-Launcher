package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.tnt.TntItem;
import net.kdt.pojavlaunch.tnt.TntLauncher;
import net.kdt.pojavlaunch.tnt.TntLoader;
import net.kdt.pojavlaunch.tnt.TntModpacksFragment;
import net.kdt.pojavlaunch.tnt.TntModsFragment;
import net.kdt.pojavlaunch.tnt.TntMoreMenu;
import net.kdt.pojavlaunch.tnt.TntNews;
import net.kdt.pojavlaunch.tnt.TntVersionPicker;
import net.kdt.pojavlaunch.tnt.TntVersions;
import net.kdt.pojavlaunch.utils.interfaces.SimpleTextWatcher;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.util.List;

/**
 * Главный экран TNT Launcher: новости «Что нового», «Скачать сборку», выбор из установленных версий
 * (кнопка «Скачать новую версию» открывает весь каталог), изоляция, «Моды», «Ещё» и «Играть».
 */
public class MainMenuFragment extends Fragment implements TntLauncher.Callback {
    public static final String TAG = "MainMenuFragment";

    // Список версий грузится один раз за запуск лаунчера
    private static TntVersions.Data sData;
    private static boolean sLoading;

    private TextView mNewsTitle, mNewsSub, mNewsText, mVersion, mStatus;
    private ProgressBar mProgress;
    private Button mPlay;
    private List<TntItem> mItems; // установленные версии
    private TntItem mSelected;
    private boolean mBusy;
    private int mNewsRequest;

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mNewsFor = null; // экран пересоздан — новости нужно показать заново
        mNewsTitle = view.findViewById(R.id.tnt_news_title);
        mNewsSub = view.findViewById(R.id.tnt_news_sub);
        mNewsText = view.findViewById(R.id.tnt_news_text);
        mVersion = view.findViewById(R.id.tnt_version);
        mStatus = view.findViewById(R.id.tnt_status);
        mProgress = view.findViewById(R.id.tnt_progress);
        mPlay = view.findViewById(R.id.play_button);
        SwitchCompat isolation = view.findViewById(R.id.tnt_isolation);
        ImageButton more = view.findViewById(R.id.tnt_more);
        Button mods = view.findViewById(R.id.tnt_mods);

        Context ctx = requireContext();

        // Ник берётся из аккаунта вверху экрана; на его месте — «Скачать сборку» (модпаки Modrinth)
        view.findViewById(R.id.tnt_download_build).setOnClickListener(v -> Tools.swapFragment(requireActivity(),
                TntModpacksFragment.class, TntModpacksFragment.TAG, null));
        // раскладка управления TNT — сразу при запуске лаунчера, а не только при нажатии «Играть»
        PojavApplication.sExecutorService.execute(() -> TntLauncher.ensureDefaultControls(ctx.getApplicationContext()));

        isolation.setChecked(TntLauncher.isolation(ctx));
        isolation.setOnCheckedChangeListener((b, checked) -> {
            TntLauncher.prefs(ctx).edit().putBoolean(TntLauncher.KEY_ISOLATION, checked).apply();
            if (!checked) Toast.makeText(ctx,
                    R.string.tnt_isolation_off_toast, Toast.LENGTH_LONG).show();
        });

        // «Версия»: только установленные; сверху — «Скачать новую версию» (весь каталог с типами и сборками)
        mVersion.setOnClickListener(v -> {
            if (sData == null) return;
            if (mItems == null) mItems = TntItem.installed(sData);
            TntVersionPicker.showInstalled(requireContext(), mItems, mSelected, this::select, this::openCatalog,
                    new TntVersionPicker.Manager() {
                        @Override public List<TntItem> reload() {
                            mItems = TntItem.installed(sData);
                            return mItems;
                        }
                        // выбор хранится по ключу установки, поэтому переименование его не сбивает
                        @Override public void onRenamed(String oldName, String newName) { rebuild(); }
                        @Override public void onDeleted(String name) { rebuild(); }
                    });
        });
        more.setOnClickListener(v -> TntMoreMenu.show(requireActivity()));
        mods.setOnClickListener(v -> openMods());
        mPlay.setOnClickListener(v -> play());

        if (sData != null) rebuild();
        else load();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (sData != null) rebuild(); // после запуска или установки сборки список установленных меняется
    }

    private void openCatalog() {
        int cat = TntLauncher.prefs(requireContext()).getInt(TntLauncher.KEY_TYPE, TntVersions.T_RELEASE);
        TntVersionPicker.showCatalog(requireContext(), sData, cat, mSelected,
                c -> TntLauncher.prefs(requireContext()).edit().putInt(TntLauncher.KEY_TYPE, c).apply(),
                this::askNameAndInstall);
    }

    /**
     * Выбор в каталоге = новая установка (как в Pojav): одну версию можно поставить несколько раз,
     * у каждой копии своё название и своя папка.
     */
    private void askNameAndInstall(TntItem catalogItem) {
        if (catalogItem == null || catalogItem.entry == null) return;
        Context c = requireContext();
        EditText input = new EditText(c);
        input.setSingleLine(true);
        input.setTextColor(0xFFFFFFFF);
        input.setText(TntLauncher.uniqueName(catalogItem.entry.name()));
        input.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(c);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(c)
                .setTitle(R.string.tnt_new_install_title)
                .setMessage(getString(R.string.tnt_new_install_msg, catalogItem.entry.name()))
                .setView(box)
                .setPositiveButton(R.string.tnt_new_install_ok, (d, w) -> {
                    String key = TntLauncher.createInstall(c, catalogItem.entry, input.getText().toString());
                    TntLauncher.prefs(c).edit().putString(TntLauncher.KEY_SELECTED, key).apply();
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void load() {
        if (sLoading) return;
        sLoading = true;
        setBusy(true, getString(R.string.tnt_loading_versions));
        Context ctxApp = requireContext().getApplicationContext();
        PojavApplication.sExecutorService.execute(() -> {
            boolean online = Tools.isOnline(ctxApp);
            TntVersions.Data d = TntVersions.load(online); // без сети — из сохранённого списка
            if (online) TntNews.notes(); // заодно подгружаем ленту новостей
            Tools.runOnUiThread(() -> {
                sData = d;
                sLoading = false;
                if (!isAdded()) return;
                setBusy(false, d.offline ? getString(R.string.tnt_offline_versions) : "");
                rebuild();
            });
        });
    }

    private void rebuild() {
        if (!isAdded()) return;
        mItems = TntItem.installed(sData);
        // выбор — по ключу установки, а не по названию: одинаковые названия и копии одной версии не путаются
        String keep = TntLauncher.prefs(requireContext()).getString(TntLauncher.KEY_SELECTED, "");
        TntItem pick = null;
        for (TntItem i : mItems) if (keep.equals(i.profileKey)) { pick = i; break; }
        if (pick == null && !mItems.isEmpty()) pick = mItems.get(0);
        select(pick);
    }

    private void select(TntItem item) {
        mSelected = item;
        if (item == null) {
            mVersion.setText(R.string.tnt_choose_version);
            mPlay.setEnabled(false);
            return;
        }
        if (item.profileKey != null) TntLauncher.prefs(requireContext()).edit().putString(TntLauncher.KEY_SELECTED, item.profileKey).apply();
        mVersion.setText(item.name);
        mPlay.setEnabled(!mBusy);
        String version = item.mcVersion();
        if (!version.equals(mNewsFor)) { // не перезагружаем новости при каждом возврате на главный экран
            mNewsFor = version;
            showNews(version, item.entry != null ? item.entry.mc.type : "release");
        }
    }

    private String mNewsFor;

    private void showNews(String version, String type) {
        final int request = ++mNewsRequest;
        mNewsTitle.setText(getString(R.string.tnt_whats_new_in, version));
        mNewsSub.setText("");
        mNewsText.setText(R.string.tnt_loading);
        PojavApplication.sExecutorService.execute(() -> {
            // Сначала официальные патч-ноуты Mojang; если их нет или они не загрузились — своё описание TNT
            boolean online = isAdded() && Tools.isOnline(requireContext());
            TntNews.Note found = online ? TntNews.find(version) : null;
            if (found == null && isAdded()) found = TntNews.local(requireContext().getApplicationContext(), version, type);
            final TntNews.Note note = found;
            if (note == null) {
                post(request, () -> {
                    mNewsSub.setText("");
                    mNewsText.setText(getString(R.string.tnt_no_notes, version));
                });
                return;
            }
            boolean local = TntNews.isLocal(note);
            String original = TntNews.text(note);
            String lang = TntNews.targetLanguage();
            // своё описание уже на русском/английском; переводим только для других языков
            boolean translate = local ? !"ru".equals(lang) && !"en".equals(lang) : !"en".equals(lang);
            post(request, () -> {
                mNewsTitle.setText(note.title);
                mNewsSub.setText(translate ? R.string.tnt_translating
                        : local ? R.string.tnt_source_local : R.string.tnt_source_mojang);
                mNewsText.setText(original);
            });
            if (!translate) return;
            String tr = TntNews.translate(original);
            post(request, () -> {
                if (tr != null) {
                    mNewsSub.setText(local ? R.string.tnt_source_local_tr : R.string.tnt_source_mojang_tr);
                    mNewsText.setText(tr);
                } else {
                    mNewsSub.setText(R.string.tnt_translate_failed);
                }
            });
        });
    }

    private void post(int request, Runnable r) {
        Tools.runOnUiThread(() -> {
            if (isAdded() && request == mNewsRequest) r.run();
        });
    }

    private void openMods() {
        if (mSelected == null) return;
        TntItem.ModTarget t = mSelected.modTarget(requireContext());
        if (t == null) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.tnt_mods_title)
                    .setMessage(getString(R.string.tnt_mods_unsupported, mSelected.name))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        Tools.swapFragment(requireActivity(), TntModsFragment.class, TntModsFragment.TAG, TntModsFragment.args(t));
    }

    private void play() {
        if (mSelected == null || mBusy) return;
        Context ctx = requireContext();
        if (ProgressKeeper.getTaskCount() > 0) {
            Toast.makeText(ctx, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        if (TntLauncher.accountNick(ctx) == null) {
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.tnt_need_account_title)
                    .setMessage(R.string.tnt_need_account_msg_offline)
                    .setPositiveButton(R.string.tnt_sign_in, (d, w) -> ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        setBusy(true, getString(R.string.tnt_preparing));
        if (mSelected.entry != null) TntLauncher.play(requireActivity(), mSelected.entry, mSelected.profileKey, this);
        else TntLauncher.playProfile(requireActivity(), mSelected.profileKey, this);
    }

    private void setBusy(boolean busy, String status) {
        mBusy = busy;
        mProgress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        mStatus.setText(status);
        mPlay.setEnabled(!busy && mSelected != null);
    }

    // ---------- TntLauncher.Callback ----------

    @Override public void status(String text) {
        Tools.runOnUiThread(() -> { if (isAdded()) mStatus.setText(text); });
    }

    @Override public void installerStarted(String loaderName) {
        if (!isAdded()) return;
        setBusy(false, getString(R.string.tnt_installer_running, loaderName));
    }

    @Override public void error(String message) {
        if (!isAdded()) return;
        setBusy(false, "");
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.tnt_launch_failed)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override public void launched() {
        if (!isAdded()) return;
        setBusy(false, getString(R.string.tnt_launching_progress));
    }
}
