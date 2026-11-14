package net.kdt.pojavlaunch.tnt;

import android.content.Context;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Пункт списка версий: либо версия/сборка TNT, либо собственный профиль лаунчера (модпак, NeoForge и т.п.). */
public final class TntItem {
    public final String name;
    public final TntVersions.Entry entry;   // для версий TNT
    public final String profileKey;         // для собственных профилей лаунчера
    public final String badge;
    public final String date;
    private final String mVersionId;

    public static final class ModTarget {
        public final String title, mc, modsDir;
        public final String[] loaders;
        ModTarget(String title, String mc, String[] loaders, String modsDir) {
            this.title = title; this.mc = mc; this.loaders = loaders; this.modsDir = modsDir;
        }
    }

    private TntItem(String name, TntVersions.Entry entry, String profileKey, String badge, String date, String versionId) {
        this.name = name; this.entry = entry; this.profileKey = profileKey;
        this.badge = badge; this.date = date; this.mVersionId = versionId;
    }

    public static List<TntItem> fromEntries(List<TntVersions.Entry> entries) {
        List<TntItem> out = new ArrayList<>(entries.size());
        for (TntVersions.Entry e : entries) {
            String date = e.mc.time != null && e.mc.time.length() >= 10 ? e.mc.time.substring(0, 10) : "";
            String badge = e.loader == TntLoader.VANILLA ? typeBadge(e.mc.type) : e.loader.title;
            out.add(new TntItem(e.name(), e, null, badge, date, e.mc.id));
        }
        return out;
    }

    private static String typeBadge(String type) {
        switch (type) {
            case "release": return "Minecraft";
            case "old_beta": return "Beta";
            case "old_alpha": return "Alpha";
            default: return "Snapshot";
        }
    }

    /** Профили лаунчера, созданные не главным экраном TNT: модпаки, NeoForge, BTA, установленные .jar и т.д. */
    public static List<TntItem> profiles() {
        List<TntItem> out = new ArrayList<>();
        LauncherProfiles.load();
        if (LauncherProfiles.mainProfileJson == null) return out;
        for (Map.Entry<String, MinecraftProfile> e : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            MinecraftProfile p = e.getValue();
            if (TntLauncher.isTntProfile(p)) continue;
            if (p.type == null && p.gameDir != null && p.gameDir.startsWith("./instances/")) continue;
            if (p.lastVersionId == null) continue;
            String n = p.name == null || p.name.isEmpty() ? e.getKey() : p.name;
            out.add(new TntItem(n + " (" + p.lastVersionId + ")", null, e.getKey(), TntText.s(net.kdt.pojavlaunch.R.string.tnt_badge_profile), "", p.lastVersionId));
        }
        return out;
    }

    /**
     * Установленные версии: сборки TNT, которые уже запускались (профиль есть), и собственные профили
     * лаунчера (модпаки, NeoForge и т.п.).
     */
    public static List<TntItem> installed(TntVersions.Data data) {
        List<TntItem> tnt = new ArrayList<>();
        List<TntItem> other = new ArrayList<>();
        LauncherProfiles.load();
        if (LauncherProfiles.mainProfileJson == null) return tnt;
        for (Map.Entry<String, MinecraftProfile> e : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            MinecraftProfile p = e.getValue();
            if (p.lastVersionId == null) continue;
            if (TntLauncher.isTntProfile(p)) {
                TntVersions.Entry entry = entryForInstance(data, p.type.substring(TntLauncher.PROFILE_TYPE.length()));
                if (entry != null) {
                    List<TntVersions.Entry> one = new ArrayList<>();
                    one.add(entry);
                    TntItem base = fromEntries(one).get(0);
                    // своё название (если переименовали) + ключ профиля, чтобы можно было переименовать/удалить
                    String shown = p.name == null || p.name.isEmpty() ? base.name : p.name;
                    tnt.add(new TntItem(shown, entry, e.getKey(), base.badge, base.date, base.mVersionId));
                    continue;
                }
            } else if (p.type == null && p.gameDir != null && p.gameDir.startsWith("./instances/")) {
                continue; // устаревший профиль первой версии TNT
            }
            String n = p.name == null || p.name.isEmpty() ? e.getKey() : p.name;
            other.add(new TntItem(n + " (" + p.lastVersionId + ")", null, e.getKey(),
                    TntText.s(net.kdt.pojavlaunch.R.string.tnt_badge_profile), "", p.lastVersionId));
        }
        // новые версии сверху
        java.util.Collections.sort(tnt, (a, b) -> b.date.compareTo(a.date));
        tnt.addAll(other);
        return tnt;
    }

    /** «Fabric+Sodium-1.20.1» → сборка Fabric+Sodium версии 1.20.1. */
    static TntVersions.Entry entryForInstance(TntVersions.Data data, String instance) {
        TntLoader loader = TntLoader.VANILLA;
        String mc = instance;
        int bestLen = -1;
        for (TntLoader l : TntLoader.values()) {
            if (l == TntLoader.VANILLA) continue;
            String prefix = l.title + "-";
            if (instance.startsWith(prefix) && prefix.length() > bestLen) {
                bestLen = prefix.length();
                loader = l;
                mc = instance.substring(prefix.length());
            }
        }
        for (TntVersions.McVersion v : data.versions)
            if (v.id.equals(mc)) return new TntVersions.Entry(v, loader);
        // нет списка версий (нет интернета и кэша) — установленная сборка всё равно должна запускаться
        if (new File(Tools.DIR_HOME_VERSION, mc + "/" + mc + ".json").exists() || loader != TntLoader.VANILLA)
            return new TntVersions.Entry(new TntVersions.McVersion(mc, null, "release", ""), loader);
        return null;
    }

    /** Версия из каталога по имени («Fabric 1.20.1»), если её выбрали, но ещё не запускали. */
    public static TntItem findInCatalog(TntVersions.Data data, String name) {
        for (int cat = TntVersions.T_RELEASE; cat <= TntVersions.T_APRIL; cat++)
            for (TntVersions.Entry e : TntVersions.build(data, cat))
                if (e.name().equals(name)) {
                    List<TntVersions.Entry> one = new ArrayList<>();
                    one.add(e);
                    return fromEntries(one).get(0);
                }
        return null;
    }

    /** Версия Minecraft для новостей и каталога модов. */
    public String mcVersion() {
        if (entry != null) return entry.mc.id;
        String id = mVersionId;
        try {
            JSONObject j = new JSONObject(Tools.read(Tools.DIR_HOME_VERSION + "/" + id + "/" + id + ".json"));
            if (j.has("inheritsFrom")) return j.getString("inheritsFrom");
        } catch (Exception ignored) {}
        Matcher m = Pattern.compile("(1\\.\\d+(?:\\.\\d+)?)").matcher(id);
        return m.find() ? m.group(1) : id;
    }

    public ModTarget modTarget(Context c) {
        if (entry != null) {
            if (!entry.loader.supportsMods()) return null;
            return new ModTarget(entry.name(), entry.mc.id, entry.loader.modLoaders,
                    TntLauncher.modsDir(c, entry).getAbsolutePath());
        }
        LauncherProfiles.load();
        MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(profileKey);
        if (p == null) return null;
        String id = mVersionId.toLowerCase(Locale.ROOT);
        String[] loaders;
        if (id.contains("quilt")) loaders = new String[]{"quilt", "fabric"};
        else if (id.contains("fabric")) loaders = new String[]{"fabric"};
        else if (id.contains("neoforge")) loaders = new String[]{"neoforge"};
        else if (id.contains("forge")) loaders = new String[]{"forge"};
        else if (id.contains("liteloader")) loaders = new String[]{"liteloader"};
        else return null;
        return new ModTarget(name, mcVersion(), loaders, new File(Tools.getGameDirPath(p), "mods").getAbsolutePath());
    }
}
