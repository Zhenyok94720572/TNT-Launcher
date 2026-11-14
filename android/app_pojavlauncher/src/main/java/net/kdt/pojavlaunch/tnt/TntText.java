package net.kdt.pojavlaunch.tnt;

import android.content.Context;

/** Доступ к строкам TNT из фоновых классов (контекст уже с выбранным языком). */
public final class TntText {
    private static Context sContext;

    private TntText() {}

    /** Вызывается из LauncherActivity.onCreate — контекст активности уже с языком из настроек. */
    public static void init(Context c) { sContext = c; }

    public static String s(int id, Object... args) {
        if (sContext == null) return "";
        return args.length == 0 ? sContext.getString(id) : sContext.getString(id, args);
    }
}
