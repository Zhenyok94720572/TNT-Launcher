package net.kdt.pojavlaunch.tnt;

import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Список версий как в TNT Launcher для ПК: ванилла + сборки (Fabric, Forge, OptiFine...) вперемешку. */
public final class TntVersions {
    private static final String TAG = "TntVersions";
    public static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public static final int T_RELEASE = 0, T_SNAPSHOT = 1, T_BETA = 2, T_ALPHA = 3, T_APRIL = 4;

    /** Одна запись Mojang. */
    public static final class McVersion {
        public final String id, url, type, time;
        McVersion(String id, String url, String type, String time) {
            this.id = id; this.url = url; this.type = type; this.time = time;
        }
    }

    /** Пункт списка «версия + сборка». */
    public static final class Entry {
        public final McVersion mc;
        public final TntLoader loader;
        public Entry(McVersion mc, TntLoader loader) { this.mc = mc; this.loader = loader; }
        public String name() { return loader == TntLoader.VANILLA ? mc.id : loader.title + " " + mc.id; }
        @Override public String toString() { return name(); }
    }

    public static final class Data {
        public final List<McVersion> versions = new ArrayList<>();
        @SuppressWarnings("unchecked")
        public final Set<String>[] support = new Set[TntLoader.values().length];
        public boolean offline;
        Data() { for (int i = 0; i < support.length; i++) support[i] = Collections.synchronizedSet(new HashSet<>()); }
    }

    private static final Set<String> KNOWN_APRIL = new HashSet<>(Arrays.asList(
            "15w14a", "1.RV-Pre1", "3D Shareware v1.34", "20w14infinite",
            "22w13oneblockatatime", "23w13a_or_b", "24w14potato", "25w14craftmine"));
    private static final Pattern NORMAL_SNAPSHOT = Pattern.compile("^\\d\\dw\\d\\d[a-z]$");

    private TntVersions() {}

    public static boolean isAprilFools(McVersion v) {
        if (KNOWN_APRIL.contains(v.id)) return true;
        if (!"snapshot".equals(v.type) || v.time == null || v.time.length() < 10) return false;
        String md = v.time.substring(5, 10);
        if (md.compareTo("03-28") < 0 || md.compareTo("04-02") > 0) return false;
        String l = v.id.toLowerCase();
        boolean normal = NORMAL_SNAPSHOT.matcher(l).matches()
                || l.contains("pre") || l.contains("rc") || l.contains("snapshot");
        return !normal;
    }

    public static int category(McVersion v) {
        switch (v.type) {
            case "release": return T_RELEASE;
            case "old_beta": return T_BETA;
            case "old_alpha": return T_ALPHA;
            default: return isAprilFools(v) ? T_APRIL : T_SNAPSHOT;
        }
    }

    /** Сравнение версий вида 1.12.2 >= 1.7.10. */
    public static boolean atLeast(String v, String min) {
        String[] a = v.split("[.\\-]"), b = min.split("[.\\-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parse(a[i]) : 0, y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return true;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    /** Список для выбранной категории: для релизов — со сборками. */
    public static List<Entry> build(Data d, int category) {
        List<Entry> out = new ArrayList<>();
        for (McVersion v : d.versions) {
            if (category(v) != category) continue;
            for (TntLoader l : TntLoader.values()) {
                if (l != TntLoader.VANILLA && (category != T_RELEASE || !d.support[l.ordinal()].contains(v.id))) continue;
                out.add(new Entry(v, l));
            }
        }
        return out;
    }

    /** Загружает манифест Mojang и узнаёт, для каких версий есть сборки (всё параллельно). */
    public static Data load() { return load(true); }

    /** online=false — без сети: только сохранённый манифест, без списков сборок (установленные сборки всё равно запускаются). */
    public static Data load(boolean online) {
        Data d = new Data();
        CountDownLatch latch = new CountDownLatch(online ? 6 : 0);
        if (online) {
        run(latch, () -> {
            for (JSONObject v : arr(get("https://meta.fabricmc.net/v2/versions/game")))
                if (v.optBoolean("stable")) d.support[TntLoader.FABRIC.ordinal()].add(v.getString("version"));
        });
        run(latch, () -> {
            for (JSONObject v : arr(get("https://meta.quiltmc.org/v3/versions/game")))
                if (v.optBoolean("stable")) d.support[TntLoader.QUILT.ordinal()].add(v.getString("version"));
        });
        run(latch, () -> {
            for (JSONObject v : arr(get("https://api.modrinth.com/v2/project/AANobbMI/version?loaders=%5B%22fabric%22%5D"))) {
                JSONArray g = v.getJSONArray("game_versions");
                for (int i = 0; i < g.length(); i++) d.support[TntLoader.FABRIC_SODIUM.ordinal()].add(g.getString(i));
            }
        });
        run(latch, () -> {
            JSONObject promos = new JSONObject(get("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"))
                    .getJSONObject("promos");
            for (Iterator<String> it = promos.keys(); it.hasNext(); ) {
                String k = it.next();
                String mc = k.substring(0, k.lastIndexOf('-'));
                if (atLeast(mc, "1.7.10")) d.support[TntLoader.FORGE.ordinal()].add(mc);
            }
        });
        run(latch, () -> {
            String page = get("https://optifine.net/downloads");
            Matcher m = Pattern.compile("adloadx\\?f=OptiFine_([0-9.]{3,10})_HD_U_").matcher(page);
            while (m.find()) d.support[TntLoader.OPTIFINE.ordinal()].add(m.group(1));
        });
        run(latch, () -> {
            JSONObject vs = new JSONObject(get("https://dl.liteloader.com/versions/versions.json")).getJSONObject("versions");
            for (Iterator<String> it = vs.keys(); it.hasNext(); ) {
                String k = it.next();
                if (vs.getJSONObject(k).has("artefacts") && atLeast(k, "1.6")) d.support[TntLoader.LITELOADER.ordinal()].add(k);
            }
        });

        } // online

        File cache = new File(Tools.DIR_DATA, "tnt_version_manifest.json");
        String manifest;
        try {
            if (!online) throw new Exception("offline");
            manifest = get(MANIFEST_URL);
            Tools.write(cache.getAbsolutePath(), manifest);
        } catch (Exception e) {
            d.offline = true;
            try { manifest = Tools.read(cache.getAbsolutePath()); } catch (Exception e2) { manifest = "{\"versions\":[]}"; }
        }
        try {
            JSONArray a = new JSONObject(manifest).getJSONArray("versions");
            for (int i = 0; i < a.length(); i++) {
                JSONObject v = a.getJSONObject(i);
                d.versions.add(new McVersion(v.getString("id"), v.getString("url"), v.getString("type"), v.optString("releaseTime")));
            }
        } catch (Exception e) { Log.e(TAG, "manifest", e); }

        try { latch.await(); } catch (InterruptedException ignored) {}
        // Sodium ставится поверх Fabric, OptiFine — поверх Forge
        d.support[TntLoader.FABRIC_SODIUM.ordinal()].retainAll(new HashSet<>(d.support[TntLoader.FABRIC.ordinal()]));
        for (String v : d.support[TntLoader.FORGE.ordinal()].toArray(new String[0]))
            if (d.support[TntLoader.OPTIFINE.ordinal()].contains(v)) d.support[TntLoader.FORGE_OPTIFINE.ordinal()].add(v);
        return d;
    }

    interface Job { void run() throws Exception; }

    private static void run(CountDownLatch latch, Job job) {
        new Thread(() -> {
            try { job.run(); } catch (Exception e) { Log.w(TAG, "support source failed", e); }
            latch.countDown();
        }, "tnt-support").start();
    }

    static String get(String url) throws Exception {
        return DownloadUtils.downloadString(url);
    }

    static List<JSONObject> arr(String json) throws Exception {
        JSONArray a = new JSONArray(json);
        List<JSONObject> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        return out;
    }
}
