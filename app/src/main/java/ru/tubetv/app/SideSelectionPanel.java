package ru.tubetv.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Focusable right-side list shared by settings with fixed choices. */
final class SideSelectionPanel extends LinearLayout {
    interface Selection { void select(int position); }

    private final ScrollView scroll;
    private final LinearLayout rows;
    private final TextView[] choices;

    SideSelectionPanel(Context context, String title, String[] labels, int selected,
                       Selection onSelect) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(0xff171a21);
        setElevation(dp(24));
        setPadding(dp(14), dp(16), dp(14), dp(16));
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextSize(20 * TvScalePreferences.get(context, TvScalePreferences.INTERFACE));
        heading.setTextColor(Color.WHITE);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMaxLines(2);
        addView(heading, new LayoutParams(-1, dp(86)));
        scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        scroll.addView(rows);
        addView(scroll, new LayoutParams(-1, 0, 1));
        choices = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView choice = new TextView(context);
            choice.setText(labels[i] + (i == selected ? "     ✓" : ""));
            choice.setTextColor(Color.WHITE);
            choice.setTextSize(19 * TvScalePreferences.get(context, TvScalePreferences.INTERFACE));
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setPadding(dp(22), 0, dp(12), 0);
            choice.setFocusable(true);
            choice.setBackground(background(false));
            choice.setOnFocusChangeListener((v, focused) -> {
                choice.setBackground(background(focused));
                choice.setTextColor(focused ? Color.BLACK : Color.WHITE);
            });
            choice.setOnClickListener(v -> onSelect.select(index));
            rows.addView(choice, new LayoutParams(-1, dp(52)));
            choices[i] = choice;
        }
        post(() -> {
            int position = Math.max(0, Math.min(choices.length - 1, selected));
            choices[position].requestFocus();
            scroll.smoothScrollTo(0, Math.max(0, choices[position].getTop() - dp(80)));
        });
    }

    private GradientDrawable background(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? Color.WHITE : 0xff202732);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density
                * TvScalePreferences.get(getContext(), TvScalePreferences.INTERFACE));
    }
}
