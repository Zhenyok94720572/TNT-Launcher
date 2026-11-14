package net.kdt.pojavlaunch.tnt;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.fragments.GamepadMapperFragment;
import net.kdt.pojavlaunch.fragments.ProfileEditorFragment;
import net.kdt.pojavlaunch.fragments.ProfileTypeSelectFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

import java.io.File;

/** Меню «Ещё»: остальные функции лаунчера, которые не поместились на главный экран. */
public final class TntMoreMenu {
    private TntMoreMenu() {}

    private static final int[] ITEMS = {
            R.string.tnt_more_accounts,
            R.string.tnt_more_tuning,
            R.string.tnt_more_settings,
            R.string.tnt_more_controls,
            R.string.tnt_more_installers,
            R.string.tnt_more_profile,
            R.string.tnt_more_jar,
            R.string.tnt_more_modpack,
            R.string.tnt_more_folder,
            R.string.tnt_more_log,
            R.string.tnt_more_gamepad,
            R.string.tnt_about_title,
    };

    public static void show(FragmentActivity a) {
        String[] names = new String[ITEMS.length];
        for (int i = 0; i < ITEMS.length; i++) names[i] = a.getString(ITEMS[i]);
        new AlertDialog.Builder(a)
                .setTitle("TNT Launcher")
                .setItems(names, (d, which) -> run(a, which))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void run(FragmentActivity a, int which) {
        switch (which) {
            case 0: ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true); break;
            case 1: showTuning(a); break;
            case 2: Tools.swapFragment(a, LauncherPreferenceFragment.class, LauncherActivity.SETTING_FRAGMENT_TAG, null); break;
            case 3: a.startActivity(new Intent(a, CustomControlsActivity.class)); break;
            case 4: Tools.swapFragment(a, ProfileTypeSelectFragment.class, ProfileTypeSelectFragment.TAG, null); break;
            case 5:
                String cur = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                if (cur.isEmpty()) Toast.makeText(a, R.string.tnt_launch_any_first, Toast.LENGTH_LONG).show();
                else Tools.swapFragment(a, ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
                break;
            case 6:
                if (!Tools.hasOnlineProfile()) { Tools.hasNoOnlineProfileDialog(a); break; }
                if (ProgressKeeper.getTaskCount() == 0) Tools.installMod(a, false);
                else Toast.makeText(a, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
                break;
            case 7:
                if (a instanceof LauncherActivity) ((LauncherActivity) a).modpackImportLauncher.launch(null);
                break;
            case 8: Tools.openPath(a, new File(Tools.DIR_GAME_HOME), false); break;
            case 9: Tools.shareLog(a); break;
            case 10: Tools.swapFragment(a, GamepadMapperFragment.class, GamepadMapperFragment.TAG, null); break;
            case 11: TntAbout.show(a); break;
        }
    }

    private static void showTuning(Context c) {
        SharedPreferences p = TntLauncher.prefs(c);
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);

        TextView info = new TextView(c);
        info.setTextColor(0xFFB4B4B4);
        info.setText(c.getString(R.string.tnt_tuning_info,
                TntTuning.tierName(TntTuning.deviceTier(c)), LauncherPreferences.PREF_RAM_ALLOCATION));
        box.addView(info);
        box.addView(sw(c, p, TntLauncher.KEY_TUNE_OPTIONS, c.getString(R.string.tnt_tune_options)));
        box.addView(sw(c, p, TntLauncher.KEY_AUTO_RENDERER, c.getString(R.string.tnt_auto_renderer)));

        new AlertDialog.Builder(c)
                .setTitle(R.string.tnt_more_tuning)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static SwitchCompat sw(Context c, SharedPreferences p, String key, String text) {
        SwitchCompat s = new SwitchCompat(c);
        s.setText(text);
        s.setTextColor(0xFFFFFFFF);
        s.setChecked(p.getBoolean(key, true));
        s.setOnCheckedChangeListener((b, on) -> p.edit().putBoolean(key, on).apply());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * c.getResources().getDisplayMetrics().density);
        s.setLayoutParams(lp);
        return s;
    }
}
