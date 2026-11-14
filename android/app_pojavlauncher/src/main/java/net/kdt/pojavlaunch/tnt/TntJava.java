package net.kdt.pojavlaunch.tnt;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Автоустановка Java: по версии игры узнаём нужную Java (8 / 17 / 21 / 25), ставим её, если её ещё нет,
 * и закрепляем за сборкой. В APK из исходников Java не вшита, поэтому без этого старые версии не запускались.
 */
public final class TntJava {
    private static final String TAG = "TntJava";

    private TntJava() {}

    /** Нужная версия Java для версии игры (по json Mojang, с учётом inheritsFrom). */
    public static int requiredJava(String versionId, String mojangUrl) {
        try {
            JSONObject j = localJson(versionId);
            for (int depth = 0; j != null && depth < 4; depth++) {
                if (j.has("javaVersion")) return j.getJSONObject("javaVersion").optInt("majorVersion", 8);
                String parent = j.optString("inheritsFrom", "");
                if (parent.isEmpty()) break;
                versionId = parent;
                mojangUrl = null;
                j = localJson(parent);
            }
            if (mojangUrl == null) mojangUrl = urlFromManifest(versionId);
            if (mojangUrl != null) {
                JSONObject remote = new JSONObject(TntVersions.get(mojangUrl));
                if (remote.has("javaVersion")) return remote.getJSONObject("javaVersion").optInt("majorVersion", 8);
            }
        } catch (Exception e) {
            Log.w(TAG, "requiredJava " + versionId, e);
        }
        return 8; // у старых версий в json нет javaVersion — им нужна Java 8
    }

    private static JSONObject localJson(String id) {
        try {
            return new JSONObject(Tools.read(Tools.DIR_HOME_VERSION + "/" + id + "/" + id + ".json"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String urlFromManifest(String id) {
        try {
            File cache = new File(Tools.DIR_DATA, "tnt_version_manifest.json");
            String s = cache.exists() ? Tools.read(cache.getAbsolutePath()) : TntVersions.get(TntVersions.MANIFEST_URL);
            JSONArray a = new JSONObject(s).getJSONArray("versions");
            for (int i = 0; i < a.length(); i++)
                if (a.getJSONObject(i).getString("id").equals(id)) return a.getJSONObject(i).getString("url");
        } catch (Exception ignored) {}
        return null;
    }

    /** Подходящая установленная Java: для 8 — ровно 8 (старые версии на новой Java ломаются), иначе ближайшая не ниже. */
    private static Runtime findInstalled(int required) {
        Runtime best = null;
        for (Runtime r : MultiRTUtils.getInstalledRuntimes()) {
            if (r.versionString == null) continue;
            boolean ok = required <= 8 ? r.javaVersion == 8 : r.javaVersion >= required;
            if (ok && (best == null || r.javaVersion < best.javaVersion)) best = r;
        }
        return best;
    }

    /**
     * Ставит нужную Java, если её нет. Прогресс скачивания показывается внизу экрана.
     * @return имя установленной Java
     */
    public static String ensure(Context c, int required, TntModrinth.Progress p) throws Exception {
        Runtime r = findInstalled(required);
        if (r == null) {
            NewJREUtil.ExternalRuntime pick = null;
            for (NewJREUtil.ExternalRuntime e : NewJREUtil.ExternalRuntime.values()) {
                boolean ok = required <= 8 ? e.majorVersion == 8 : e.majorVersion >= required;
                if (ok && (pick == null || e.majorVersion < pick.majorVersion)) pick = e;
            }
            if (pick == null) throw new Exception(TntText.s(R.string.tnt_java_none, required));
            if (p != null) p.onStatus(TntText.s(R.string.tnt_java_installing, pick.majorVersion));
            try {
                pick.downloadRuntime(c);
            } catch (RuntimeException e) {
                throw new Exception(TntText.s(R.string.tnt_java_failed, pick.majorVersion,
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            }
            r = findInstalled(required);
            if (r == null) throw new Exception(TntText.s(R.string.tnt_java_broken, pick.majorVersion));
        }
        // Если у лаунчера ещё нет Java по умолчанию (нужна установщикам Forge/OptiFine) — назначаем эту
        if (LauncherPreferences.PREF_DEFAULT_RUNTIME == null || LauncherPreferences.PREF_DEFAULT_RUNTIME.isEmpty()
                || MultiRTUtils.forceReread(LauncherPreferences.PREF_DEFAULT_RUNTIME).versionString == null) {
            setDefault(r.name);
        }
        return r.name;
    }

    public static void setDefault(String name) {
        LauncherPreferences.PREF_DEFAULT_RUNTIME = name;
        LauncherPreferences.DEFAULT_PREF.edit().putString("defaultRuntime", name).apply();
    }

    public static String profileJavaDir(String runtimeName) {
        return Tools.LAUNCHERPROFILES_RTPREFIX + runtimeName;
    }
}
