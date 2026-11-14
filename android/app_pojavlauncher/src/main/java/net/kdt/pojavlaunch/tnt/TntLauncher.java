package net.kdt.pojavlaunch.tnt;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import net.kdt.pojavlaunch.JavaGUILauncherActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ForgeDownloadTask;
import net.kdt.pojavlaunch.modloaders.ForgeUtils;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.modloaders.OFDownloadPageScraper;
import net.kdt.pojavlaunch.modloaders.OptiFineDownloadTask;
import net.kdt.pojavlaunch.modloaders.OptiFineUtils;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Готовит выбранную сборку (загрузчик, моды, папка) и запускает игру. */
public final class TntLauncher {
    private static final String TAG = "TntLauncher";
    public static final String PREFS = "tnt_launcher";
    public static final String KEY_NICK = "nick", KEY_VERSION = "version", KEY_TYPE = "type", KEY_SELECTED = "selected_profile",
            KEY_ISOLATION = "isolation", KEY_AUTO_RENDERER = "auto_renderer", KEY_TUNE_OPTIONS = "tune_options";
    private static final Pattern NICK = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    public interface Callback {
        void status(String text);
        /** Запущен отдельный установщик — после него нужно нажать «Играть» ещё раз. */
        void installerStarted(String loaderName);
        void error(String message);
        void launched();
    }

    private TntLauncher() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isolation(Context c) { return prefs(c).getBoolean(KEY_ISOLATION, true); }

    public static boolean validNick(String nick) { return nick != null && NICK.matcher(nick).matches(); }

    // ---------- установки: у каждой своя запись (профиль) и своя папка ----------

    /** Папка модов установки (по её профилю). */
    public static File modsDir(String profileKey) {
        LauncherProfiles.load();
        MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(profileKey);
        return new File(p == null ? new File(Tools.DIR_GAME_NEW) : Tools.getGameDirPath(p), "mods");
    }

    /** Свободное название: «Fabric 1.20.1», «Fabric 1.20.1 (2)», ... */
    public static String uniqueName(String base) {
        LauncherProfiles.load();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (MinecraftProfile p : LauncherProfiles.mainProfileJson.profiles.values()) if (p.name != null) used.add(p.name);
        if (!used.contains(base)) return base;
        for (int i = 2; ; i++) if (!used.contains(base + " (" + i + ")")) return base + " (" + i + ")";
    }

    /** Свободная папка установки: instances/Fabric-1.20.1, instances/Fabric-1.20.1-2, ... */
    private static String uniqueGameDir(String instance) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (MinecraftProfile p : LauncherProfiles.mainProfileJson.profiles.values()) if (p.gameDir != null) used.add(p.gameDir);
        String base = "./instances/" + instance;
        if (!used.contains(base)) return base;
        for (int i = 2; ; i++) if (!used.contains(base + "-" + i)) return base + "-" + i;
    }

    /**
     * Новая установка версии/сборки — как профиль в Pojav: одну и ту же версию можно поставить сколько угодно раз,
     * у каждой копии своё название и своя папка (миры, моды, настройки).
     * @return ключ профиля (UUID)
     */
    public static String createInstall(Context c, TntVersions.Entry e, String name) {
        LauncherProfiles.load();
        String instance = e.loader.instanceName(e.mc.id);
        MinecraftProfile p = new MinecraftProfile();
        p.type = PROFILE_TYPE + instance;
        p.name = name == null || name.trim().isEmpty() ? uniqueName(e.name()) : name.trim();
        p.lastVersionId = e.mc.id; // настоящий id (fabric-loader-... и т.п.) пропишется при первом запуске
        p.gameDir = isolation(c) ? uniqueGameDir(instance) : null;
        p.icon = iconFor(e.loader);
        String key = LauncherProfiles.getFreeProfileKey();
        LauncherProfiles.mainProfileJson.profiles.put(key, p);
        LauncherProfiles.write();
        return key;
    }

    // ---------- аккаунт ----------

    /** Ник = имя выбранного аккаунта (сменить можно только сменой аккаунта). null — аккаунта нет. */
    public static String accountNick(Context c) {
        MinecraftAccount acc = PojavProfile.getCurrentProfileContent(c, null);
        return acc == null || acc.isDemo() ? null : acc.username;
    }

    static void requireAccount(Context c) throws Exception {
        if (accountNick(c) == null) throw new Exception(TntText.s(R.string.tnt_need_sign_in_error));
    }

    /** Управление в игре — стандартная раскладка TNT Launcher (восстанавливаем её, если файла нет). */
    public static void ensureDefaultControls(Context c) {
        File def = new File(Tools.CTRLDEF_FILE);
        // Раскладка TNT по умолчанию: один раз заменяем старую (у кого лаунчер уже стоял), дальше — только если файл пропал
        boolean upgrade = !prefs(c).getBoolean("controls_tnt_v2", false);
        if (upgrade || !def.exists()) {
            try {
                Tools.copyAssetFile(c, "default.json", Tools.CTRLMAP_PATH, true);
                new File(Tools.CTRLMAP_PATH, "new_default.json").delete(); // копия движка больше не нужна
                prefs(c).edit().putBoolean("controls_tnt_v2", true).putBoolean("controls_reset", false).apply();
            } catch (Exception ex) { Log.w(TAG, "default controls", ex); }
        }
        if (!prefs(c).getBoolean("controls_reset", false)) {
            // первый запуск TNT: раскладка по умолчанию, без TouchController
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString("defaultCtrl", Tools.CTRLDEF_FILE)
                    .putBoolean("forceEnableTouchController", false)
                    .apply();
            LauncherPreferences.PREF_DEFAULTCTRL_PATH = Tools.CTRLDEF_FILE;
            LauncherPreferences.PREF_FORCE_ENABLE_TOUCHCONTROLLER = false;
            prefs(c).edit().putBoolean("controls_reset", true).apply();
        }
    }

    // ---------- запуск ----------

    public static void play(Activity activity, TntVersions.Entry e, String profileKey, Callback cb) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                if (prepare(activity, e, profileKey, cb)) {
                    Tools.runOnUiThread(() -> {
                        cb.launched();
                        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                    });
                }
            } catch (Exception ex) {
                Log.e(TAG, "prepare", ex);
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Tools.runOnUiThread(() -> cb.error(msg));
            }
        });
    }

    /** Запуск собственного профиля лаунчера (модпак, NeoForge и т.п.). */
    public static void playProfile(Activity activity, String profileKey, Callback cb) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                requireAccount(activity);
                ensureDefaultControls(activity);
                LauncherProfiles.load();
                MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(profileKey);
                if (p == null) throw new Exception(TntText.s(R.string.tnt_profile_not_found));
                // Java ставим, только если у профиля своей нет или она удалена
                String rt = Tools.getRuntimeName(p.javaDir);
                if (rt == null || MultiRTUtils.forceReread(rt).versionString == null) {
                    int need = TntJava.requiredJava(p.lastVersionId, null);
                    p.javaDir = TntJava.profileJavaDir(TntJava.ensure(activity, need, cb::status));
                    LauncherProfiles.write();
                }
                LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey).commit();
                Tools.runOnUiThread(() -> {
                    cb.launched();
                    ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                });
            } catch (Exception ex) {
                Tools.runOnUiThread(() -> cb.error(String.valueOf(ex.getMessage())));
            }
        });
    }

    /** @return true — можно запускать; false — запущен установщик или показана ошибка. */
    private static boolean prepare(Activity a, TntVersions.Entry e, String key, Callback cb) throws Exception {
        String mc = e.mc.id;
        cb.status(TntText.s(R.string.tnt_preparing_x, e.name()));
        requireAccount(a);
        ensureDefaultControls(a);

        // Автоустановка Java нужной версии (сборки используют ту же Java, что и ванилла)
        cb.status(TntText.s(R.string.tnt_checking_java));
        String runtime = TntJava.ensure(a, TntJava.requiredJava(mc, e.mc.url), cb::status);

        String versionId;
        switch (e.loader) {
            case FABRIC:
            case FABRIC_SODIUM:
                versionId = installFabricLike(FabriclikeUtils.FABRIC_UTILS, mc, cb);
                break;
            case QUILT:
                versionId = installFabricLike(FabriclikeUtils.QUILT_UTILS, mc, cb);
                break;
            case FORGE:
            case FORGE_OPTIFINE:
                versionId = findInstalled(mc, "forge");
                if (versionId == null) {
                    TntJava.setDefault(runtime); // установщик запускается на Java по умолчанию
                    startForgeInstaller(a, mc, cb);
                    return false;
                }
                break;
            case OPTIFINE:
                versionId = findInstalled(mc, "optifine");
                if (versionId == null) {
                    TntJava.setDefault(runtime);
                    startOptiFineInstaller(a, mc, cb);
                    return false;
                }
                break;
            case LITELOADER:
                versionId = installLiteLoader(e, cb);
                break;
            default:
                versionId = mc;
        }

        // Своя запись этой установки (если её удалили между выбором и запуском — создаём заново)
        LauncherProfiles.load();
        cleanupStaleProfiles();
        if (key == null || !LauncherProfiles.mainProfileJson.profiles.containsKey(key)) key = createInstall(a, e, null);
        MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(key);
        File gameDir = Tools.getGameDirPath(p);
        File mods = new File(gameDir, "mods");
        FileUtils.ensureDirectory(mods);

        boolean online = Tools.isOnline(a);
        if (e.loader == TntLoader.FABRIC_SODIUM) {
            cb.status(TntText.s(R.string.tnt_installing_sodium));
            try {
                TntModrinth.install("AANobbMI", "Sodium", mc, e.loader.modLoaders, mods, cb::status);
            } catch (Exception ex) {
                if (online) throw ex; // без интернета играем с тем, что уже установлено
            }
            LauncherPreferences.DEFAULT_PREF.edit().putBoolean("sodium_override", true).apply();
        }
        if (e.loader == TntLoader.FORGE_OPTIFINE && !hasFile(mods, "optifine") && online) {
            cb.status(TntText.s(R.string.tnt_downloading_optifine));
            downloadOptiFineJar(mc, new File(mods, "OptiFine_" + mc + ".jar"));
        }

        if (prefs(a).getBoolean(KEY_TUNE_OPTIONS, true)) TntTuning.prepareOptions(a, gameDir, mc);

        // Обновляем запись именно этой установки (название и папку игрока не трогаем)
        LauncherProfiles.load();
        p = LauncherProfiles.mainProfileJson.profiles.get(key);
        p.type = PROFILE_TYPE + e.loader.instanceName(mc);
        if (p.name == null || p.name.isEmpty()) p.name = e.name();
        p.lastVersionId = versionId;
        p.icon = iconFor(e.loader);
        p.javaDir = TntJava.profileJavaDir(runtime);
        p.controlFile = null; // стандартное управление
        if (prefs(a).getBoolean(KEY_AUTO_RENDERER, true)) p.pojavRendererName = TntTuning.pickRenderer(a, mc);
        LauncherProfiles.mainProfileJson.profiles.put(key, p);
        LauncherProfiles.write();
        LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).commit();
        cb.status(TntText.s(R.string.tnt_launching_x, e.name()));
        return true;
    }

    // ---------- переименование и удаление установленных версий ----------

    /** Текущее название профиля (без суффикса версии). */
    public static String profileName(String key) {
        LauncherProfiles.load();
        MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(key);
        return p == null || p.name == null ? "" : p.name;
    }

    public static void rename(String key, String newName) {
        LauncherProfiles.load();
        MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(key);
        if (p == null) return;
        p.name = newName;
        LauncherProfiles.write();
    }

    /** Папка игры профиля, если она своя (изоляция или модпак); null — общая .minecraft, её не трогаем. */
    public static File ownGameDir(String key) {
        LauncherProfiles.load();
        MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(key);
        if (p == null || p.gameDir == null) return null;
        File dir = Tools.getGameDirPath(p);
        File shared = new File(Tools.DIR_GAME_NEW);
        try {
            if (dir.getCanonicalPath().equals(shared.getCanonicalPath())) return null;
            if (!dir.getCanonicalPath().startsWith(new File(Tools.DIR_GAME_HOME).getCanonicalPath() + File.separator)) return null;
        } catch (Exception e) {
            return null;
        }
        return dir;
    }

    /** Убирает версию из списка; deleteFiles — ещё и её папку (миры, моды, настройки). */
    public static void delete(String key, boolean deleteFiles) {
        File dir = deleteFiles ? ownGameDir(key) : null;
        LauncherProfiles.load();
        LauncherProfiles.mainProfileJson.profiles.remove(key);
        if (LauncherProfiles.mainProfileJson.profiles.isEmpty()) {
            // движку нужен хотя бы один профиль — он сам создаст стандартный при загрузке
            LauncherProfiles.write();
            LauncherProfiles.load();
        }
        LauncherProfiles.write();
        String cur = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        if (key.equals(cur)) LauncherPreferences.DEFAULT_PREF.edit().remove(LauncherPreferences.PREF_KEY_CURRENT_PROFILE).apply();
        if (dir != null) {
            try { org.apache.commons.io.FileUtils.deleteDirectory(dir); }
            catch (Exception e) { Log.w(TAG, "delete " + dir, e); }
        }
    }

    /** Пометка профилей, созданных TNT: поле type = "tnt:" + имя сборки. */
    public static final String PROFILE_TYPE = "tnt:";

    public static boolean isTntProfile(MinecraftProfile p) {
        return p.type != null && p.type.startsWith(PROFILE_TYPE);
    }

    /**
     * Убирает только профили самой первой версии TNT (без пометки type, с битым ключом).
     * Ключи профилей — UUID (иначе движок переименует их и игра не найдёт профиль); дубликаты одной
     * версии — это нормально: каждая установка независима.
     */
    private static void cleanupStaleProfiles() {
        java.util.Iterator<java.util.Map.Entry<String, MinecraftProfile>> it =
                LauncherProfiles.mainProfileJson.profiles.entrySet().iterator();
        while (it.hasNext()) {
            MinecraftProfile p = it.next().getValue();
            if (p.type == null && p.gameDir != null && p.gameDir.startsWith("./instances/") && p.lastVersionId == null) it.remove();
        }
    }

    private static String iconFor(TntLoader l) {
        switch (l) {
            case FABRIC: case FABRIC_SODIUM: return "fabric";
            case QUILT: return "quilt";
            case FORGE: case FORGE_OPTIFINE: return "forge";
            default: return null;
        }
    }

    private static boolean hasFile(File dir, String needle) {
        File[] f = dir.listFiles();
        if (f != null) for (File x : f) if (x.getName().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return false;
    }

    /** Уже установленная версия загрузчика для mc (по папкам versions). */
    static String findInstalled(String mc, String needle) {
        File[] dirs = new File(Tools.DIR_HOME_VERSION).listFiles();
        if (dirs == null) return null;
        String best = null;
        for (File d : dirs) {
            String n = d.getName();
            String l = n.toLowerCase(Locale.ROOT);
            if (!l.contains(needle)) continue;
            if (!(n.startsWith(mc + "-") || l.endsWith("-" + mc) || l.contains("-" + mc + "-"))) continue;
            if (!new File(d, n + ".json").exists()) continue;
            if (best == null || n.compareTo(best) > 0) best = n;
        }
        return best;
    }

    private static String installFabricLike(FabriclikeUtils utils, String mc, Callback cb) throws Exception {
        String installed = findInstalled(mc, utils.getName().toLowerCase(Locale.ROOT) + "-loader");
        if (installed != null) return installed;
        cb.status(TntText.s(R.string.tnt_installing_x, utils.getName()));
        FabricVersion[] loaders = utils.downloadLoaderVersions(mc);
        if (loaders == null || loaders.length == 0) throw new Exception(TntText.s(R.string.tnt_not_found_for, utils.getName(), mc));
        String loader = loaders[0].version;
        for (FabricVersion v : loaders) if (v.stable) { loader = v.version; break; }
        String json = DownloadUtils.downloadString(utils.createJsonDownloadUrl(mc, loader));
        String id = new JSONObject(json).getString("id");
        File dir = new File(Tools.DIR_HOME_VERSION, id);
        FileUtils.ensureDirectory(dir);
        Tools.write(new File(dir, id + ".json").getAbsolutePath(), json);
        return id;
    }

    private static void startForgeInstaller(Activity a, String mc, Callback cb) throws Exception {
        cb.status(TntText.s(R.string.tnt_searching_forge, mc));
        JSONObject promos = new JSONObject(DownloadUtils.downloadString(
                "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")).getJSONObject("promos");
        String ver = promos.optString(mc + "-recommended", promos.optString(mc + "-latest", ""));
        if (ver.isEmpty()) throw new Exception(TntText.s(R.string.tnt_not_found_for, "Forge", mc));
        cb.status(TntText.s(R.string.tnt_downloading_x, "Forge " + ver));
        new ForgeDownloadTask(installerListener(a, cb, "Forge", false), mc, ver).run();
    }

    private static void startOptiFineInstaller(Activity a, String mc, Callback cb) throws Exception {
        OptiFineUtils.OptiFineVersion v = findOptiFine(mc);
        cb.status(TntText.s(R.string.tnt_downloading_x, v.versionName));
        new OptiFineDownloadTask(v, installerListener(a, cb, "OptiFine", true), a).run();
    }

    private static ModloaderDownloadListener installerListener(Activity a, Callback cb, String name, boolean optifine) {
        return new ModloaderDownloadListener() {
            @Override public void onDownloadFinished(File file) {
                Tools.runOnUiThread(() -> {
                    Intent i = new Intent(a, JavaGUILauncherActivity.class);
                    if (optifine) OptiFineUtils.addAutoInstallArgs(i, file);
                    else ForgeUtils.addAutoInstallArgs(i, file, false);
                    a.startActivity(i);
                    cb.installerStarted(name);
                });
            }
            @Override public void onDataNotAvailable() {
                Tools.runOnUiThread(() -> cb.error(TntText.s(R.string.tnt_loader_unavailable, name)));
            }
            @Override public void onDownloadError(Exception e) {
                Tools.runOnUiThread(() -> cb.error(TntText.s(R.string.tnt_download_failed_x, name, e.getMessage())));
            }
        };
    }

    static OptiFineUtils.OptiFineVersion findOptiFine(String mc) throws Exception {
        OptiFineUtils.OptiFineVersions all = OptiFineUtils.downloadOptiFineVersions();
        if (all == null) throw new Exception(TntText.s(R.string.tnt_optifine_list_failed));
        String tag = "OptiFine_" + mc + "_HD";
        OptiFineUtils.OptiFineVersion preview = null;
        for (List<OptiFineUtils.OptiFineVersion> list : all.optifineVersions)
            for (OptiFineUtils.OptiFineVersion v : list) {
                if (v.downloadUrl == null || !v.downloadUrl.contains(tag)) continue;
                if (!v.downloadUrl.toLowerCase(Locale.ROOT).contains("pre")) return v;
                if (preview == null) preview = v;
            }
        if (preview != null) return preview;
        throw new Exception(TntText.s(R.string.tnt_not_found_for, "OptiFine", mc));
    }

    private static void downloadOptiFineJar(String mc, File out) throws Exception {
        OptiFineUtils.OptiFineVersion v = findOptiFine(mc);
        String url = OFDownloadPageScraper.run(v.downloadUrl);
        if (url == null) throw new Exception(TntText.s(R.string.tnt_optifine_link_failed));
        DownloadUtils.downloadFile(url, out);
    }

    /** LiteLoader: профиль собираем сами из versions.json (как в версии для ПК). */
    private static String installLiteLoader(TntVersions.Entry e, Callback cb) throws Exception {
        String mc = e.mc.id;
        String installed = findInstalled(mc, "liteloader");
        if (installed != null) return installed;
        cb.status(TntText.s(R.string.tnt_installing_x, "LiteLoader"));
        JSONObject ver = new JSONObject(DownloadUtils.downloadString("https://dl.liteloader.com/versions/versions.json"))
                .getJSONObject("versions").getJSONObject(mc);
        JSONObject art = ver.getJSONObject("artefacts").getJSONObject("com.mumfrey:liteloader").getJSONObject("latest");
        String repo = ver.has("repo") ? ver.getJSONObject("repo").optString("url", "https://dl.liteloader.com/versions/")
                : "https://dl.liteloader.com/versions/";
        repo = repo.replace("http://", "https://");
        JSONObject vanilla = new JSONObject(DownloadUtils.downloadString(e.mc.url));

        String id = mc + "-LiteLoader" + mc;
        JSONArray libs = new JSONArray();
        libs.put(new JSONObject().put("name", "com.mumfrey:liteloader:" + mc).put("url", repo));
        JSONArray src = art.optJSONArray("libraries");
        if (src != null) for (int i = 0; i < src.length(); i++) {
            JSONObject l = src.getJSONObject(i);
            String n = l.getString("name");
            JSONObject o = new JSONObject().put("name", n);
            // asm-all нет на серверах Mojang — берём из Maven Central
            if (n.startsWith("org.ow2.asm")) o.put("url", "https://repo1.maven.org/maven2/");
            else if (l.has("url")) o.put("url", l.getString("url"));
            libs.put(o);
        }
        JSONObject json = new JSONObject()
                .put("id", id)
                .put("inheritsFrom", mc)
                .put("type", "release")
                .put("mainClass", "net.minecraft.launchwrapper.Launch")
                .put("libraries", libs)
                .put("minecraftArguments", vanilla.optString("minecraftArguments") + " --tweakClass "
                        + art.optString("tweakClass", "com.mumfrey.liteloader.launch.LiteLoaderTweaker"));
        File dir = new File(Tools.DIR_HOME_VERSION, id);
        FileUtils.ensureDirectory(dir);
        Tools.write(new File(dir, id + ".json").getAbsolutePath(), json.toString(2));
        return id;
    }
}
