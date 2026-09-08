package com.lanke.nastools;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/** 纯代码搭界面的小工具集，避免堆一堆 XML */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int col(Context c, int res) {
        return ContextCompat.getColor(c, res);
    }

    public static final int TEXT = 0xFF212121;
    public static final int TEXT2 = 0xFF6B6B6B;

    public static void toast(Context c, String s) {
        if (c != null && s != null) Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    public static LinearLayout vbox(Context c, int padDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, padDp);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s == null ? "" : s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static class Card {
        public MaterialCardView view;
        public LinearLayout body;
    }

    public static Card card(Context c, String title) {
        MaterialCardView cv = new MaterialCardView(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(c, 10);
        lp.setMargins(m, m, m, 0);
        cv.setLayoutParams(lp);
        cv.setRadius(dp(c, 12));
        cv.setCardElevation(dp(c, 2));
        cv.setUseCompatPadding(true);
        cv.setContentPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cv.addView(body);

        if (title != null && !title.isEmpty()) {
            TextView t = text(c, title, 15f, col(c, R.color.brand), true);
            body.addView(t);
            View div = new View(c);
            div.setBackgroundColor(0x1E000000);
            div.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
            LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) div.getLayoutParams();
            dlp.setMargins(0, dp(c, 8), 0, dp(c, 4));
            div.setLayoutParams(dlp);
            body.addView(div);
        }
        Card r = new Card();
        r.view = cv;
        r.body = body;
        return r;
    }

    /** 键 = 值 一行 */
    public static void row(Context c, LinearLayout parent, String k, String v) {
        row(c, parent, k, v, TEXT);
    }

    public static void row(Context c, LinearLayout parent, String k, String v, int vColor) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int p = dp(c, 4);
        r.setPadding(0, p, 0, p);

        TextView kt = text(c, k, 13.5f, TEXT2, false);
        kt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView vt = text(c, v == null || v.isEmpty() ? "-" : v, 13.5f, vColor, false);
        vt.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMarginStart(dp(c, 10));
        vt.setLayoutParams(vlp);

        r.addView(kt);
        r.addView(vt);
        parent.addView(r);
    }

    public static void note(Context c, LinearLayout parent, String s) {
        TextView t = text(c, s, 12f, TEXT2, false);
        t.setLineSpacing(dp(c, 2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 8), 0, 0);
        t.setLayoutParams(lp);
        parent.addView(t);
    }

    public static MaterialButton button(Context c, String label, boolean primary) {
        MaterialButton b = new MaterialButton(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setCornerRadius(dp(c, 8));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        if (primary) {
            b.setBackgroundColor(col(c, R.color.brand));
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setStrokeColor(ColorStateList.valueOf(col(c, R.color.brand)));
            b.setStrokeWidth(Math.max(1, dp(c, 1)));
            b.setTextColor(col(c, R.color.brand));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 6), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** 一行里平分宽度的按钮组 */
    public static LinearLayout buttonRow(Context c, int count) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 10), 0, 0);
        l.setLayoutParams(lp);
        l.setWeightSum(count);
        return l;
    }

    public static void addToRow(Context c, LinearLayout row, MaterialButton b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int h = dp(c, 4);
        lp.setMargins(h, 0, h, 0);
        b.setLayoutParams(lp);
        row.addView(b);
    }

    public static LinearLayout errorBox(Context c, String msg) {
        LinearLayout l = vbox(c, 14);
        l.setBackgroundColor(0xFFFFEBEE);
        TextView t = text(c, "连不上 NAS\n\n" + msg, 14f, 0xFFC62828, true);
        l.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 10), dp(c, 20), dp(c, 10), 0);
        l.setLayoutParams(lp);
        return l;
    }
}
