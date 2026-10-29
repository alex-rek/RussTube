package ru.tubetv.app;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Focusable flat right-side list shared by TV settings and player. */
final class SideSelectionPanel extends LinearLayout {
    interface Selection { void select(int position); }
    interface MultiSelection { void apply(boolean[] selected); }

    private final ScrollView scroll;
    private final LinearLayout rows;
    private final TextView[] choices;
    private final boolean markedChoices;

    SideSelectionPanel(Context context, String title, String[] labels, int selected,
                       Selection onSelect) {
        super(context);
        markedChoices = selected >= 0;
        setOrientation(VERTICAL);
        setBackgroundColor(TvAppearancePreferences.panelSurface(context));
        setElevation(dp(24));
        setPadding(dp(14), dp(16), dp(14), dp(16));
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextSize(20 * TvScalePreferences.get(context, TvScalePreferences.INTERFACE));
        heading.setTextColor(TvAppearancePreferences.text(context));
        heading.setBackgroundColor(TvAppearancePreferences.surface(context));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMaxLines(2);
        heading.setPadding(dp(16), dp(4), dp(8), dp(12));
        addView(heading, new LayoutParams(-1, -2));
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
            choice.setText(selected < 0 ? labels[i]
                    : (i == selected ? "●  " : "○  ") + labels[i]);
            choice.setTextColor(TvAppearancePreferences.text(context));
            choice.setTextSize(19 * TvScalePreferences.get(context, TvScalePreferences.INTERFACE));
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setMinHeight(dp(52));
            choice.setPadding(dp(18), dp(9), dp(12), dp(9));
            choice.setFocusable(true);
            choice.setBackgroundColor(Color.TRANSPARENT);
            choice.setOnFocusChangeListener((v, focused) -> {
                choice.setBackgroundColor(focused ? TvAppearancePreferences.focus(context)
                        : Color.TRANSPARENT);
                choice.setTextColor(TvAppearancePreferences.text(context));
            });
            choice.setOnClickListener(v -> onSelect.select(index));
            LayoutParams params = new LayoutParams(-1, -2);
            params.bottomMargin = dp(3);
            rows.addView(choice, params);
            choices[i] = choice;
        }
        post(() -> {
            int position = Math.max(0, Math.min(choices.length - 1, selected));
            if (choices.length > 0) {
                choices[position].requestFocus();
                scroll.scrollTo(0, Math.max(0, choices[position].getTop() - dp(80)));
            }
        });
    }

    SideSelectionPanel(Context context, String title, String[] labels, boolean[] saved,
                       MultiSelection onApply, Runnable onCancel) {
        this(context, title, labels, 0, ignored -> { });
        if (labels.length != saved.length) throw new IllegalArgumentException("Source count");
        boolean[] draft = saved.clone();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            choices[i].setText((draft[i] ? "☑  " : "☐  ") + labels[i]);
            choices[i].setOnClickListener(v -> {
                draft[index] = !draft[index];
                choices[index].setText((draft[index] ? "☑  " : "☐  ") + labels[index]);
            });
        }
        addAction(context, "Применить", () -> onApply.apply(draft.clone()));
        addAction(context, "Отмена", onCancel);
    }

    private void addAction(Context context, String name, Runnable action) {
        TextView row = new TextView(context);
        row.setText(name);
        row.setTextColor(TvAppearancePreferences.accent(context));
        row.setTextSize(18 * TvScalePreferences.get(context, TvScalePreferences.INTERFACE));
        row.setMinHeight(dp(52));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(8), dp(12), dp(8));
        row.setFocusable(true);
        row.setOnFocusChangeListener((v, focus) -> row.setBackgroundColor(
                focus ? TvAppearancePreferences.focus(context)
                        : Color.TRANSPARENT));
        row.setOnClickListener(v -> action.run());
        rows.addView(row, new LayoutParams(-1, -2));
    }

    void setChoiceLabel(int index, String label) {
        if (index >= 0 && index < choices.length)
            choices[index].setText((markedChoices ? "○  " : "") + label);
    }

    void setHeader(View header) {
        addView(header, 0, new LayoutParams(-1, -2));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density
                * TvScalePreferences.get(getContext(), TvScalePreferences.INTERFACE));
    }
}
