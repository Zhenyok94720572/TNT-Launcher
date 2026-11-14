package net.kdt.pojavlaunch.tnt;

import android.content.Context;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.R;

/** Окно «О разработчике». */
public final class TntAbout {
    public static final String GITHUB = "https://github.com/Zhenyok94720572";
    public static final String ENGINE = "https://github.com/AngelAuraMC/Amethyst-Android";

    private TntAbout() {}

    @SuppressWarnings("deprecation")
    public static void show(Context c) {
        String html = "<b>TNT Launcher</b><br><br>"
                + "<b>Zhenyok</b> — " + c.getString(R.string.tnt_about_dev)
                + " · <a href=\"" + GITHUB + "\">GitHub</a><br>"
                + "<b>Molly</b> — " + c.getString(R.string.tnt_about_molly) + "<br><br>"
                + "<a href=\"" + ENGINE + "\">" + c.getString(R.string.tnt_about_based) + "</a> (LGPL-3.0)";
        TextView tv = new TextView(c);
        int pad = (int) (22 * c.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);
        tv.setTextSize(15);
        tv.setTextColor(0xFFFFFFFF);
        tv.setLinkTextColor(0xFFFF8024);
        tv.setText(Html.fromHtml(html));
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        new AlertDialog.Builder(c)
                .setTitle(R.string.tnt_about_title)
                .setView(tv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
