package net.kdt.pojavlaunch.utils;


import static net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF;

import android.content.*;
import android.content.res.*;
import android.os.Build;
import android.os.LocaleList;

import androidx.preference.*;
import java.util.*;

/** Язык интерфейса из настроек TNT («app_language»: system / ru / en / uk / ...). */
public class LocaleUtils extends ContextWrapper {
    public static final String PREF_LANGUAGE = "app_language";
    public static final String LANG_SYSTEM = "system";
    private static Locale sSystemLocale;

    public LocaleUtils(Context base) {
        super(base);
    }

    /** Код языка из настроек; старая галочка «Force English» превращается в "en". */
    public static String getLanguage(Context context) {
        if (DEFAULT_PREF == null) DEFAULT_PREF = PreferenceManager.getDefaultSharedPreferences(context);
        String fallback = DEFAULT_PREF.getBoolean("force_english", false) ? "en" : LANG_SYSTEM;
        return DEFAULT_PREF.getString(PREF_LANGUAGE, fallback);
    }

    static Locale toLocale(String code) {
        String[] p = code.split("[-_]");
        return p.length > 1 ? new Locale(p[0], p[1]) : new Locale(p[0]);
    }

    public static ContextWrapper setLocale(Context context) {
        if (sSystemLocale == null) sSystemLocale = Locale.getDefault();
        String lang = getLanguage(context);
        Locale locale = LANG_SYSTEM.equals(lang) ? sSystemLocale : toLocale(lang);

        Resources resources = context.getResources();
        Configuration configuration = resources.getConfiguration();
        configuration.setLocale(locale);
        Locale.setDefault(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList localeList = new LocaleList(locale);
            LocaleList.setDefault(localeList);
            configuration.setLocales(localeList);
        }
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            context = context.createConfigurationContext(configuration);
        }
        return new LocaleUtils(context);
    }
}
