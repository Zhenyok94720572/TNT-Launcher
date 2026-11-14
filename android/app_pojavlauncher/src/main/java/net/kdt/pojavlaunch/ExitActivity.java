package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.shareLog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

@Keep
public class ExitActivity extends AppCompatActivity {

    @SuppressLint("StringFormatInvalid") //invalid on some translations but valid on most, cant fix that atm
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int code = -1;
        boolean isSignal = false;
        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            code = extras.getInt("code",-1);
            isSignal = extras.getBoolean("isSignal", false);
        }

        // TNT: обычный выход из игры — сразу назад в лаунчер, без предложения поделиться логом.
        // На телефонах Minecraft часто завершается с ненулевым кодом даже после «Выйти из игры»,
        // поэтому смотрим в лог: «Stopping!» без отчёта о сбое после него — это нормальный выход.
        if ((!isSignal && code == 0) || isNormalQuit()) {
            backToLauncher();
            return;
        }

        int message = isSignal ? R.string.mcn_signal_title : R.string.mcn_exit_title;

        new AlertDialog.Builder(this)
                .setMessage(getString(message,code))
                .setPositiveButton(R.string.main_share_logs, (dialog, which) -> shareLog(this))
                .setNegativeButton(android.R.string.ok, null)
                .setOnDismissListener(dialog -> backToLauncher())
                .show();
    }

    /** Игрок сам вышел из игры (в логе есть «Stopping!», а после него нет отчёта о сбое). */
    public static boolean isNormalQuit() {
        if (Tools.DIR_GAME_HOME == null) return false;
        java.io.File log = new java.io.File(Tools.DIR_GAME_HOME, "latestlog.txt");
        if (!log.isFile()) return false;
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(log, "r")) {
            long len = f.length();
            int size = (int) Math.min(len, 256 * 1024);
            byte[] buf = new byte[size];
            f.seek(len - size);
            f.readFully(buf);
            String tail = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            int stop = tail.lastIndexOf("Stopping!");
            if (stop < 0) return false;
            String after = tail.substring(stop);
            return !after.contains("Crash Report") && !after.contains("Game crashed")
                    && !after.contains("Exception in thread \"main\"") && !after.contains("A fatal error");
        } catch (Exception e) {
            return false;
        }
    }

    private void backToLauncher() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        }
        finish();
    }

    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        Intent i = new Intent(ctx,ExitActivity.class);
        i.putExtra("code",code);
        i.putExtra("isSignal", isSignal);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

}
