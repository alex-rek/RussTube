package ru.tubetv.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** Visual tokens used by the phone and tablet UI only. */
final class MobileAuroraTheme {
    static final int BACKGROUND = Color.rgb(7, 20, 38);
    static final int SURFACE = Color.rgb(16, 36, 59);
    static final int SURFACE_DARK = Color.rgb(8, 27, 46);
    static final int TEAL = Color.rgb(0, 217, 192);
    static final int CYAN = Color.rgb(25, 200, 255);
    static final int VIOLET = Color.rgb(133, 91, 255);
    static final int TEXT = Color.rgb(244, 248, 255);
    static final int MUTED = Color.rgb(174, 193, 218);
    static final int BRAND_SP = 28;
    static final int HEADING_SP = 24;
    static final int CHIP_SP = 13;
    static final int CARD_TITLE_SP = 15;
    static final int CARD_META_SP = 12;
    static final int NAV_LABEL_SP = 11;
    static final int CARD_RADIUS_DP = 15;
    static final int ARTWORK_RADIUS_DP = 14;

    static GradientDrawable round(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    static GradientDrawable stroke(int color, float radius, int border, float borderWidth) {
        GradientDrawable shape = round(color, radius);
        shape.setStroke(Math.max(1, Math.round(borderWidth)), border);
        return shape;
    }

    private MobileAuroraTheme() { }
}
