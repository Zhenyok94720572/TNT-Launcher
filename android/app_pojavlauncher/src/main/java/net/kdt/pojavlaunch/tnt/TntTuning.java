package net.kdt.pojavlaunch.tnt;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Оптимизация TNT Launcher:
 *  1) стартовые настройки игры под мощность телефона (дальность прорисовки, частицы, тени, FPS...);
 *  2) автоматический выбор рендера по версии игры;
 *  3) аккуратные флаги JVM (без опасных для мобильных сборок OpenJDK);
 *  4) обход серой кнопки «Сетевая игра» 1.16.4/1.16.5 для офлайн-ника.
 */
public final class TntTuning {
    private static final String TAG = "TntTuning";

    /** 0 — слабое устройство, 1 — среднее, 2 — мощное. */
    public static int deviceTier(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long ramMb = mi.totalMem / (1024 * 1024);
        int cores = Runtime.getRuntime().availableProcessors();
        if (ramMb < 3500 || cores <= 4) return 0;
        if (ramMb < 7000) return 1;
        return 2;
    }

    public static String tierName(int tier) {
        return TntText.s(tier == 0 ? net.kdt.pojavlaunch.R.string.tnt_tier_low : tier == 1 ? net.kdt.pojavlaunch.R.string.tnt_tier_mid : net.kdt.pojavlaunch.R.string.tnt_tier_high);
    }

    private static int refreshRate(Context c) {
        try {
            Display d = ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            return Math.round(d.getRefreshRate());
        } catch (Exception e) {
            return 60;
        }
    }

    /**
     * Пишет options.txt при первом запуске сборки. Если игрок уже менял настройки — ничего не трогаем.
     * Незнакомые старым версиям ключи игра просто пропускает.
     */
    public static void prepareOptions(Context c, File gameDir, String mc) {
        File opts = new File(gameDir, "options.txt");
        if (opts.exists()) return;
        int tier = deviceTier(c);
        int rd = tier == 0 ? 6 : tier == 1 ? 8 : 10;
        int fps = Math.min(refreshRate(c), tier == 0 ? 60 : 120);
        boolean modern = TntVersions.atLeast(mc, "1.13");
        List<String> l = new ArrayList<>();
        l.add("renderDistance:" + rd);
        l.add("simulationDistance:" + Math.min(rd, tier == 2 ? 8 : 6));
        l.add("maxFps:" + Math.max(30, fps));
        l.add("enableVsync:false");
        l.add("entityShadows:false");
        l.add("particles:" + (tier == 0 ? 2 : 1));
        l.add("mipmapLevels:" + (tier == 2 ? 2 : 0));
        l.add("entityDistanceScaling:" + (tier == 0 ? "0.75" : "1.0"));
        if (modern) l.add("biomeBlendRadius:" + (tier == 2 ? 1 : 0));
        if (TntVersions.atLeast(mc, "1.16")) l.add("graphicsMode:0");
        else l.add("fancyGraphics:false");
        if (tier == 0) {
            l.add(TntVersions.atLeast(mc, "1.19") ? "renderClouds:\"false\"" : "renderClouds:false");
            l.add(TntVersions.atLeast(mc, "1.19") ? "ao:false" : "ao:0");
        }
        l.add("useVbo:true");
        l.add("lang:" + gameLanguage(mc));
        // Подсказки и обучение на телефоне только мешают
        l.add("tutorialStep:none");
        l.add("skipMultiplayerWarning:true");
        l.add("onboardAccessibility:false");
        try {
            gameDir.mkdirs();
            StringBuilder sb = new StringBuilder();
            for (String s : l) sb.append(s).append('\n');
            Tools.write(opts.getAbsolutePath(), sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "options.txt", e);
        }
    }

    /** Язык игры = язык телефона (ru_ru; до 1.11 — ru_RU). */
    static String gameLanguage(String mc) {
        Locale loc = Locale.getDefault();
        String lang = loc.getLanguage().toLowerCase(Locale.ROOT);
        String country = loc.getCountry();
        if (country == null || country.isEmpty()) country = "ru".equals(lang) ? "RU" : "US";
        if ("en".equals(lang)) country = "US";
        return TntVersions.atLeast(mc, "1.11") ? lang + "_" + country.toLowerCase(Locale.ROOT) : lang + "_" + country.toUpperCase(Locale.ROOT);
    }

    /**
     * Рендер под версию: старым версиям (до 1.17, OpenGL 2.x) быстрее Krypton/GL4ES,
     * новым — MobileGlues (null = рендер по умолчанию из настроек).
     */
    public static String pickRenderer(Context c, String mc) {
        if (!TntVersions.atLeast(mc, "1.17") && Tools.checkRendererCompatible(c, "opengles2")) return "opengles2";
        return null;
    }

    /** Дополнительные аргументы JVM (вызывается из Tools.launchMinecraft). */
    public static void addJvmArgs(List<String> args, MinecraftAccount account, int javaVersion) {
        // Неизвестный флаг не должен ронять старые/урезанные мобильные JVM
        args.add("-XX:+IgnoreUnrecognizedVMOptions");
        // Моды любят звать System.gc() — на телефоне это фризы по полсекунды
        args.add("-XX:+DisableExplicitGC");
        args.add("-XX:+ParallelRefProcEnabled");
        args.add("-XX:MaxGCPauseMillis=40");
        if (javaVersion >= 17) args.add("-XX:+UseStringDeduplication");
        // Forge 1.17+ иначе пытается открыть собственное окно загрузки
        args.add("-Dfml.earlyprogresswindow=false");
        args.add("-Dfml.earlyWindowControl=false");
        if (account != null && account.isLocal()) {
            // 1.16.4/1.16.5: без этого у офлайн-ника серая кнопка «Сетевая игра»
            args.add("-Dminecraft.api.env=custom");
            args.add("-Dminecraft.api.auth.host=https://invalid.invalid");
            args.add("-Dminecraft.api.account.host=https://invalid.invalid");
            args.add("-Dminecraft.api.session.host=https://invalid.invalid");
            args.add("-Dminecraft.api.services.host=https://invalid.invalid");
        }
    }
}
