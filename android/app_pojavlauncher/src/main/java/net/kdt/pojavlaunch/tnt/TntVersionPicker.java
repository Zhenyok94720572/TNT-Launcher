package net.kdt.pojavlaunch.tnt;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.utils.interfaces.SimpleTextWatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Выбор версии: «Мои версии» (только установленные, сверху — «Скачать новую версию»)
 * и каталог всех версий со сборками и типами (Релизы / Снапшоты / Бета / Альфа / Первоапрельские).
 */
public final class TntVersionPicker {
    public interface Listener { void onPicked(TntItem item); }
    public interface CategoryListener { void onCategory(int category); }
    /** Переименование/удаление установленной версии; reload — свежий список установленных. */
    public interface Manager {
        List<TntItem> reload();
        void onRenamed(String oldName, String newName);
        void onDeleted(String name);
    }

    private static final int[] TYPES = {R.string.tnt_type_release, R.string.tnt_type_snapshot, R.string.tnt_type_beta,
            R.string.tnt_type_alpha, R.string.tnt_type_april};

    private TntVersionPicker() {}

    /** Установленные версии; кнопка сверху открывает каталог. */
    public static void showInstalled(Context ctx, List<TntItem> installed, TntItem current, Listener listener,
                                     Runnable downloadNew, Manager manager) {
        Dialog d = new Dialog(ctx, ctx.getString(R.string.tnt_my_versions), installed, current, listener);
        d.manager = manager;
        Button download = new Button(ctx);
        download.setText(R.string.tnt_download_new_version);
        download.setTextColor(0xFFFFFFFF);
        download.setTypeface(download.getTypeface(), android.graphics.Typeface.BOLD);
        download.setBackgroundResource(R.drawable.tnt_btn_play);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48));
        lp.topMargin = dp(ctx, 10);
        d.top.addView(download, lp);
        download.setOnClickListener(v -> {
            d.dialog.dismiss();
            downloadNew.run();
        });
        if (installed.isEmpty()) {
            d.empty.setVisibility(View.VISIBLE);
            d.empty.setText(R.string.tnt_no_installed);
            d.search.setVisibility(View.GONE);
        }
        d.show();
    }

    /** Каталог всех версий со сборками; переключатель типа версий сверху. */
    public static void showCatalog(Context ctx, TntVersions.Data data, int category, TntItem current,
                                   CategoryListener onCategory, Listener listener) {
        Dialog d = new Dialog(ctx, ctx.getString(R.string.tnt_download_new_title),
                TntItem.fromEntries(TntVersions.build(data, category)), current, listener);
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        scroll.addView(row);
        TextView[] chips = new TextView[TYPES.length];
        for (int i = 0; i < TYPES.length; i++) {
            TextView c = new TextView(ctx);
            c.setText(TYPES[i]);
            c.setTextColor(0xFFFFFFFF);
            c.setTextSize(13);
            c.setBackgroundResource(R.drawable.tnt_chip);
            c.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(ctx, 6));
            final int idx = i;
            c.setOnClickListener(v -> {
                for (int j = 0; j < chips.length; j++) chips[j].setSelected(j == idx);
                onCategory.onCategory(idx);
                d.setItems(TntItem.fromEntries(TntVersions.build(data, idx)));
            });
            row.addView(c, lp);
            chips[i] = c;
        }
        chips[Math.max(0, Math.min(category, TYPES.length - 1))].setSelected(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(ctx, 10);
        d.top.addView(scroll, sp);
        d.show();
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    /** Общее окно: заголовок, верхняя панель, поиск и список. */
    private static final class Dialog {
        final AlertDialog dialog;
        final LinearLayout top;
        final TextView empty;
        final EditText search;
        private final List<TntItem> all = new ArrayList<>();
        private final List<TntItem> shown = new ArrayList<>();
        private final BaseAdapter adapter;
        private final ListView list;
        Manager manager; // не null — у строк есть «⋮» (переименовать / удалить)

        Dialog(Context ctx, String title, List<TntItem> items, TntItem current, Listener listener) {
            View v = LayoutInflater.from(ctx).inflate(R.layout.tnt_dialog_versions, null);
            ((TextView) v.findViewById(R.id.tnt_dlg_title)).setText(title);
            top = v.findViewById(R.id.tnt_dlg_top);
            empty = v.findViewById(R.id.tnt_dlg_empty);
            search = v.findViewById(R.id.tnt_dlg_search);
            list = v.findViewById(R.id.tnt_dlg_list);
            int h = ctx.getResources().getDisplayMetrics().heightPixels;
            list.getLayoutParams().height = Math.min(list.getLayoutParams().height, (int) (h * 0.5f));

            adapter = new BaseAdapter() {
                @Override public int getCount() { return shown.size(); }
                @Override public Object getItem(int i) { return shown.get(i); }
                @Override public long getItemId(int i) { return i; }
                @Override public View getView(int i, View row, ViewGroup parent) {
                    if (row == null) row = LayoutInflater.from(ctx).inflate(R.layout.tnt_item_version, parent, false);
                    TntItem it = shown.get(i);
                    TextView badge = row.findViewById(R.id.tnt_ver_badge);
                    badge.setText(it.badge);
                    badge.setSelected(it.entry != null && it.entry.loader != TntLoader.VANILLA);
                    ((TextView) row.findViewById(R.id.tnt_ver_name)).setText(it.name);
                    ((TextView) row.findViewById(R.id.tnt_ver_date)).setText(it.date);
                    boolean cur = current != null && (current.profileKey != null && it.profileKey != null ? current.profileKey.equals(it.profileKey) : current.name.equals(it.name));
                    row.setBackgroundColor(cur ? 0x33FF8024 : Color.TRANSPARENT);
                    View menu = row.findViewById(R.id.tnt_ver_menu);
                    boolean manageable = manager != null && it.profileKey != null;
                    menu.setVisibility(manageable ? View.VISIBLE : View.GONE);
                    menu.setOnClickListener(manageable ? mv -> showMenu(ctx, mv, it) : null);
                    // ImageButton сам делает себя фокусируемым, и тогда ListView перестаёт получать нажатия
                    // на строку — версия не выбиралась. Поэтому нажатие на строку обрабатываем напрямую.
                    menu.setFocusable(false);
                    menu.setFocusableInTouchMode(false);
                    row.setOnClickListener(rv -> {
                        listener.onPicked(it);
                        dialog.dismiss();
                    });
                    return row;
                }
            };
            list.setAdapter(adapter);
            setItems(items);
            for (int i = 0; current != null && i < shown.size(); i++)
                if (shown.get(i).name.equals(current.name)) { list.setSelection(Math.max(0, i - 2)); break; }

            search.addTextChangedListener(new SimpleTextWatcher() {
                @Override public void afterTextChanged(Editable s) { filter(); }
            });
            dialog = new AlertDialog.Builder(ctx).setView(v).create();
            list.setOnItemClickListener((p, view, i, id) -> {
                listener.onPicked(shown.get(i));
                dialog.dismiss();
            });
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        void setItems(List<TntItem> items) {
            all.clear();
            all.addAll(items);
            filter();
            list.setSelection(0);
        }

        void filter() {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (TntItem it : all) {
                if (q.isEmpty()) { shown.add(it); continue; }
                String n = it.name.toLowerCase(Locale.ROOT);
                boolean ok = true;
                for (String w : q.split("\\s+")) if (!n.contains(w)) { ok = false; break; }
                if (ok) shown.add(it);
            }
            adapter.notifyDataSetChanged();
        }

        void show() { dialog.show(); }

        private void showMenu(Context ctx, View anchor, TntItem it) {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(ctx, anchor);
            pm.getMenu().add(0, 1, 0, R.string.tnt_rename);
            pm.getMenu().add(0, 2, 1, R.string.tnt_delete);
            pm.setOnMenuItemClickListener(mi -> {
                if (mi.getItemId() == 1) rename(ctx, it); else delete(ctx, it);
                return true;
            });
            pm.show();
        }

        private void rename(Context ctx, TntItem it) {
            EditText input = new EditText(ctx);
            input.setSingleLine(true);
            input.setTextColor(0xFFFFFFFF);
            input.setText(TntLauncher.profileName(it.profileKey));
            input.setSelectAllOnFocus(true);
            LinearLayout box = new LinearLayout(ctx);
            int pad = dp(ctx, 20);
            box.setPadding(pad, dp(ctx, 8), pad, 0);
            box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.tnt_rename_title)
                    .setView(box)
                    .setPositiveButton(R.string.tnt_rename, (dd, w) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) return;
                        TntLauncher.rename(it.profileKey, name);
                        List<TntItem> fresh = manager.reload();
                        String shown = name;
                        for (TntItem f : fresh) if (it.profileKey.equals(f.profileKey)) { shown = f.name; break; }
                        manager.onRenamed(it.name, shown);
                        setItems(fresh);
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.tnt_renamed, name), android.widget.Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void delete(Context ctx, TntItem it) {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, 20);
            box.setPadding(pad, dp(ctx, 8), pad, 0);
            TextView msg = new TextView(ctx);
            msg.setTextColor(0xFFB4B4B4);
            msg.setText(ctx.getString(R.string.tnt_delete_msg, it.name));
            box.addView(msg);
            android.widget.CheckBox files = new android.widget.CheckBox(ctx);
            files.setText(R.string.tnt_delete_files);
            files.setTextColor(0xFFFFFFFF);
            boolean hasOwnDir = TntLauncher.ownGameDir(it.profileKey) != null;
            if (hasOwnDir) box.addView(files);
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.tnt_delete_title)
                    .setView(box)
                    .setPositiveButton(R.string.tnt_delete, (dd, w) -> {
                        boolean withFiles = hasOwnDir && files.isChecked();
                        net.kdt.pojavlaunch.PojavApplication.sExecutorService.execute(() -> {
                            TntLauncher.delete(it.profileKey, withFiles);
                            net.kdt.pojavlaunch.Tools.runOnUiThread(() -> {
                                manager.onDeleted(it.name);
                                setItems(manager.reload());
                                if (all.isEmpty()) {
                                    empty.setVisibility(View.VISIBLE);
                                    empty.setText(R.string.tnt_no_installed);
                                    search.setVisibility(View.GONE);
                                }
                                android.widget.Toast.makeText(ctx, ctx.getString(R.string.tnt_deleted, it.name),
                                        android.widget.Toast.LENGTH_SHORT).show();
                            });
                        });
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }
}
