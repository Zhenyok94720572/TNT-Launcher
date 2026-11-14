package net.kdt.pojavlaunch.tnt;

import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** «Что нового» с официальной ленты патч-ноутов Mojang + автоперевод. */
public final class TntNews {
    private static final String TAG = "TntNews";
    private static final String NOTES_URL = "https://launchercontent.mojang.com/v2/javaPatchNotes.json";
    private static final String CONTENT_BASE = "https://launchercontent.mojang.com/v2/";

    public static final class Note {
        public final String version, title, contentPath, shortText;
        Note(String version, String title, String contentPath, String shortText) {
            this.version = version; this.title = title; this.contentPath = contentPath; this.shortText = shortText;
        }
    }

    private static List<Note> sNotes;
    private static final Map<String, String> sTextCache = new HashMap<>();
    private static final Map<String, String> sTranslated = new HashMap<>();

    private TntNews() {}

    public static synchronized List<Note> notes() {
        if (sNotes != null) return sNotes;
        List<Note> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(TntVersions.get(NOTES_URL)).getJSONArray("entries");
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                out.add(new Note(e.optString("version"), e.optString("title"), e.optString("contentPath"), e.optString("shortText")));
            }
            sNotes = out;
        } catch (Exception e) {
            Log.w(TAG, "notes", e);
        }
        return out;
    }

    /** Ближайшая заметка: точное совпадение, иначе последняя (для сборок/старых версий её нет). */
    public static Note find(String version) {
        for (Note n : notes()) if (n.version.equals(version)) return n;
        return null;
    }



    public static String text(Note n) {
        if (isLocal(n)) return n.shortText;
        synchronized (sTextCache) {
            String c = sTextCache.get(n.contentPath);
            if (c != null) return c;
        }
        String text;
        try {
            JSONObject body = new JSONObject(TntVersions.get(CONTENT_BASE + n.contentPath));
            text = htmlToText(body.optString("body"));
        } catch (Exception e) {
            text = n.shortText;
        }
        synchronized (sTextCache) { sTextCache.put(n.contentPath, text); }
        return text;
    }

    // ---------- Свои описания версий (если официальных патч-ноутов нет или они не загрузились) ----------

    private static JSONObject sLocal;

    private static synchronized JSONObject localDb(android.content.Context c) {
        if (sLocal == null) {
            try (java.io.InputStream is = c.getAssets().open("tnt_versions.json")) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                sLocal = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "tnt_versions.json", e);
                sLocal = new JSONObject();
            }
        }
        return sLocal;
    }

    /** Язык описания: русский — для ru, иначе английский (и при необходимости автоперевод). */
    public static boolean localIsRussian() { return "ru".equals(targetLanguage()); }

    /** Описание версии от TNT Launcher. type — тип из манифеста Mojang (release / snapshot / old_beta / old_alpha). */
    public static Note local(android.content.Context c, String id, String type) {
        JSONObject db = localDb(c);
        String sfx = localIsRussian() ? "_ru" : "_en";
        String lang = localIsRussian() ? "ru" : "en";
        JSONObject e = db.optJSONObject(id);
        if (e != null) return new Note(id, e.optString("title" + sfx, id), "local:" + id, e.optString(lang));

        if (id.matches("^\\d+\\.\\d+(\\.\\d+)?$")) {
            // патч: берём ближайшее описанное обновление той же линейки (1.20.1 → 1.20, 1.21.10 → 1.21.9)
            String line = id.split("\\.")[0] + "." + id.split("\\.")[1];
            String best = null;
            for (java.util.Iterator<String> it = db.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!(k.equals(line) || k.startsWith(line + "."))) continue;
                if (TntVersions.atLeast(id, k) && (best == null || TntVersions.atLeast(k, best))) best = k;
            }
            if (best != null) {
                JSONObject b = db.optJSONObject(best);
                String head = localIsRussian()
                        ? "Версия " + id + " входит в обновление " + best + ": исправления и доработки.\n\n"
                        : "Version " + id + " belongs to the " + best + " update: fixes and refinements.\n\n";
                return new Note(id, "Minecraft " + id + " · " + b.optString("title" + sfx, best), "local:" + id,
                        head + b.optString(lang));
            }
            return generic(db, "_release", id, id, sfx, lang);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)-(pre|rc)").matcher(id);
        if (m.find()) return generic(db, "_pre", id, m.group(1), sfx, lang);
        if ("old_beta".equals(type) || id.startsWith("b1.")) return generic(db, "_beta", id, id, sfx, lang);
        if ("old_alpha".equals(type)) {
            if (id.startsWith("a")) return generic(db, "_alpha", id, id, sfx, lang);
            return generic(db, "_old", id, id, sfx, lang);
        }
        if ("snapshot".equals(type) || id.contains("w") || id.contains("snapshot")) return generic(db, "_snapshot", id, id, sfx, lang);
        return generic(db, "_release", id, id, sfx, lang);
    }

    private static Note generic(JSONObject db, String key, String id, String arg, String sfx, String lang) {
        JSONObject g = db.optJSONObject(key);
        if (g == null) return null;
        return new Note(id, String.format(g.optString("title" + sfx, "%s"), id), "local:" + id,
                g.optString(lang).replace("%s", arg));
    }

    public static boolean isLocal(Note n) { return n.contentPath.startsWith("local:"); }
    @SuppressWarnings("deprecation")
    static String htmlToText(String html) {
        // списки превращаем в «• пункт», остальное отдаёт Html
        html = html.replace("<li>", "<br>• ").replace("</li>", "");
        String t = Html.fromHtml(html).toString();
        return t.replaceAll("\n{3,}", "\n\n").trim();
    }

    public static String targetLanguage() {
        String l = Locale.getDefault().getLanguage();
        return l == null || l.isEmpty() ? "ru" : l;
    }

    /** Перевод на язык устройства; null — если перевести не удалось. */
    public static String translate(String text) {
        String lang = targetLanguage();
        if ("en".equals(lang)) return text;
        String key = lang + ":" + text.hashCode();
        synchronized (sTranslated) {
            String c = sTranslated.get(key);
            if (c != null) return c;
        }
        // Режем по абзацам на куски ≤1500 символов
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String p : text.split("\n")) {
            if (cur.length() + p.length() + 1 > 1500 && cur.length() > 0) { chunks.add(cur.toString()); cur.setLength(0); }
            cur.append(p).append('\n');
        }
        if (cur.length() > 0) chunks.add(cur.toString());

        String[] results = new String[chunks.size()];
        Thread[] th = new Thread[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            final int idx = i;
            th[i] = new Thread(() -> results[idx] = translateChunk(chunks.get(idx), lang));
            th[i].start();
            if (i % 2 == 1) join(th, i - 1, i); // не больше двух запросов разом — иначе Google режет
        }
        join(th, 0, th.length - 1);
        StringBuilder sb = new StringBuilder();
        for (String r : results) {
            if (r == null) return null;
            sb.append(r);
        }
        String out = sb.toString().trim();
        synchronized (sTranslated) { sTranslated.put(key, out); }
        return out;
    }

    private static void join(Thread[] th, int from, int to) {
        for (int i = Math.max(0, from); i <= to && i < th.length; i++) {
            try { th[i].join(); } catch (InterruptedException ignored) {}
        }
    }

    private static String translateChunk(String chunk, String lang) {
        try {
            String body = "q=" + URLEncoder.encode(chunk, "UTF-8");
            String r = post("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + lang + "&dt=t", body);
            JSONArray parts = new JSONArray(r).getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONArray(i).optString(0));
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "google translate failed, trying MyMemory", e);
        }
        // Запасной переводчик — кусками по 450 символов
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : chunk.split("\n")) {
                if (line.trim().isEmpty()) { sb.append('\n'); continue; }
                for (int s = 0; s < line.length(); s += 450) {
                    String part = line.substring(s, Math.min(line.length(), s + 450));
                    String r = TntVersions.get("https://api.mymemory.translated.net/get?langpair=en%7C" + lang
                            + "&q=" + URLEncoder.encode(part, "UTF-8"));
                    JSONObject j = new JSONObject(r);
                    if (j.optInt("responseStatus") != 200) return null;
                    sb.append(j.getJSONObject("responseData").getString("translatedText"));
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String post(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        try (InputStream is = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
