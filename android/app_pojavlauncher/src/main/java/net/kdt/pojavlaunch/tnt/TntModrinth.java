package net.kdt.pojavlaunch.tnt;

import android.util.Log;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Каталог модов Modrinth: поиск, установка вместе с обязательными зависимостями, удаление. */
public final class TntModrinth {
    private static final String TAG = "TntModrinth";
    private static final String API = "https://api.modrinth.com/v2/";
    private static final String REGISTRY = ".tnt_mods.txt"; // id \t файл \t название
    private static final Object LOCK = new Object();

    public static final class Mod {
        public String id, title, author, description, iconUrl;
        public long downloads;
    }

    public static final class Installed {
        public final String projectId, file, title;
        Installed(String projectId, String file, String title) { this.projectId = projectId; this.file = file; this.title = title; }
    }

    public static final class SearchResult {
        public final List<Mod> mods = new ArrayList<>();
        public int total;
    }

    public interface Progress { void onStatus(String text); }

    private TntModrinth() {}

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    public static SearchResult search(String query, String mc, String[] loaders, int offset) throws Exception {
        StringBuilder cats = new StringBuilder("[");
        for (int i = 0; i < loaders.length; i++) cats.append(i > 0 ? "," : "").append("\"categories:").append(loaders[i]).append('"');
        cats.append(']');
        String facets = "[" + cats + ",[\"versions:" + mc + "\"],[\"project_type:mod\"]]";
        String url = API + "search?limit=20&offset=" + offset
                + "&index=" + (query.trim().isEmpty() ? "downloads" : "relevance")
                + "&facets=" + enc(facets)
                + (query.trim().isEmpty() ? "" : "&query=" + enc(query.trim()));
        JSONObject r = new JSONObject(TntVersions.get(url));
        SearchResult out = new SearchResult();
        out.total = r.optInt("total_hits");
        JSONArray hits = r.getJSONArray("hits");
        for (int i = 0; i < hits.length(); i++) {
            JSONObject h = hits.getJSONObject(i);
            Mod m = new Mod();
            m.id = h.getString("project_id");
            m.title = h.optString("title");
            m.author = h.optString("author");
            m.description = h.optString("description");
            m.iconUrl = h.optString("icon_url", null);
            m.downloads = h.optLong("downloads");
            out.mods.add(m);
        }
        return out;
    }

    // ---------- реестр установленных ----------

    private static File registry(File modsDir) { return new File(modsDir, REGISTRY); }

    private static Map<String, Installed> readRegistry(File modsDir) {
        Map<String, Installed> map = new LinkedHashMap<>();
        try {
            for (String line : Tools.read(registry(modsDir).getAbsolutePath()).split("\n")) {
                String[] p = line.split("\t");
                if (p.length >= 3 && new File(modsDir, p[1]).exists()) map.put(p[0], new Installed(p[0], p[1], p[2]));
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static void writeRegistry(File modsDir, Map<String, Installed> map) {
        StringBuilder sb = new StringBuilder();
        for (Installed i : map.values()) sb.append(i.projectId).append('\t').append(i.file).append('\t').append(i.title).append('\n');
        try { Tools.write(registry(modsDir).getAbsolutePath(), sb.toString()); } catch (Exception e) { Log.w(TAG, "registry", e); }
    }

    /** Все моды в папке: из каталога (с id) и положенные вручную. */
    public static List<Installed> listInstalled(File modsDir) {
        synchronized (LOCK) {
            Map<String, Installed> reg = readRegistry(modsDir);
            Set<String> known = new HashSet<>();
            List<Installed> out = new ArrayList<>(reg.values());
            for (Installed i : out) known.add(i.file);
            File[] files = modsDir.listFiles();
            if (files != null) for (File f : files) {
                String n = f.getName();
                if (f.isFile() && (n.endsWith(".jar") || n.endsWith(".litemod")) && !known.contains(n))
                    out.add(new Installed("", n, n.replaceAll("\\.(jar|litemod)$", "")));
            }
            return out;
        }
    }

    public static boolean isInstalled(File modsDir, String projectId) {
        synchronized (LOCK) { return readRegistry(modsDir).containsKey(projectId); }
    }

    public static void remove(File modsDir, Installed mod) {
        synchronized (LOCK) {
            new File(modsDir, mod.file).delete();
            Map<String, Installed> reg = readRegistry(modsDir);
            reg.remove(mod.projectId);
            writeRegistry(modsDir, reg);
        }
    }

    /** Ставит мод и (рекурсивно) его обязательные зависимости. */
    public static void install(String projectId, String title, String mc, String[] loaders, File modsDir, Progress p) throws Exception {
        modsDir.mkdirs();
        install(projectId, title, mc, loaders, modsDir, p, new HashSet<>());
    }

    private static void install(String projectId, String title, String mc, String[] loaders, File modsDir, Progress p, Set<String> done) throws Exception {
        if (!done.add(projectId)) return;
        if (isInstalled(modsDir, projectId)) return;
        StringBuilder l = new StringBuilder("[");
        for (int i = 0; i < loaders.length; i++) l.append(i > 0 ? "," : "").append('"').append(loaders[i]).append('"');
        l.append(']');
        JSONArray versions = new JSONArray(TntVersions.get(API + "project/" + projectId + "/version?loaders=" + enc(l.toString())
                + "&game_versions=" + enc("[\"" + mc + "\"]")));
        if (versions.length() == 0) throw new Exception(TntText.s(R.string.tnt_mod_not_for, title == null ? projectId : title, mc));
        // стабильная версия, если есть
        JSONObject ver = versions.getJSONObject(0);
        for (int i = 0; i < versions.length(); i++)
            if ("release".equals(versions.getJSONObject(i).optString("version_type"))) { ver = versions.getJSONObject(i); break; }

        if (title == null || title.isEmpty()) {
            try { title = new JSONObject(TntVersions.get(API + "project/" + projectId)).optString("title", projectId); }
            catch (Exception e) { title = projectId; }
        }
        JSONArray files = ver.getJSONArray("files");
        JSONObject file = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++) if (files.getJSONObject(i).optBoolean("primary")) { file = files.getJSONObject(i); break; }
        String name = file.getString("filename");
        if (p != null) p.onStatus(TntText.s(R.string.tnt_mod_downloading, title));
        DownloadUtils.downloadFile(file.getString("url"), new File(modsDir, name));
        synchronized (LOCK) {
            Map<String, Installed> reg = readRegistry(modsDir);
            reg.put(projectId, new Installed(projectId, name, title));
            writeRegistry(modsDir, reg);
        }

        JSONArray deps = ver.optJSONArray("dependencies");
        if (deps != null) for (int i = 0; i < deps.length(); i++) {
            JSONObject d = deps.getJSONObject(i);
            if (!"required".equals(d.optString("dependency_type"))) continue;
            String dep = d.optString("project_id", "");
            if (dep.isEmpty() || "null".equals(dep)) continue;
            try { install(dep, null, mc, loaders, modsDir, p, done); }
            catch (Exception e) { Log.w(TAG, "dependency " + dep, e); }
        }
    }
}
