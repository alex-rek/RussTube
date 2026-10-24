package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.TimeUnit;

/** Settings shown in the mobile card area. TV settings keep their own UI. */
@SuppressLint("ViewConstructor")
final class MobileSettingsPanel extends ScrollView {
    private final Activity activity;
    private final LinearLayout content;

    MobileSettingsPanel(Activity activity, Runnable openNetwork, Runnable historyChanged) {
        super(activity);
        this.activity = activity;
        setFillViewport(true);
        setBackgroundColor(MobileAuroraTheme.BACKGROUND);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(30));
        addView(content);

        section("Общий");
        description("Видеоплощадки для поиска и каталога");
        for (String source : FeedRepository.SOURCES) {
            CheckBox box = check(source, MobileBrowsePreferences.sourceEnabled(activity, source));
            box.setOnCheckedChangeListener((button, checked) -> {
                if (!MobileBrowsePreferences.setSourceEnabled(activity, source, checked)) {
                    box.setChecked(true);
                    Toast.makeText(activity, "Оставьте хотя бы одну площадку",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        description("Рекомендации");
        CheckBox history = check("По истории просмотра",
                MobileBrowsePreferences.recommendHistory(activity));
        history.setOnCheckedChangeListener((button, checked) ->
                MobileBrowsePreferences.setRecommendHistory(activity, checked));
        CheckBox subscriptions = check("По подпискам",
                MobileBrowsePreferences.recommendSubscriptions(activity));
        subscriptions.setOnCheckedChangeListener((button, checked) ->
                MobileBrowsePreferences.setRecommendSubscriptions(activity, checked));

        section("История");
        description("Удалить просмотры за выбранный период");
        for (int days : new int[]{1, 2, 3, 7}) {
            String label = days == 1 ? "За 1 день" : "За " + days + " дня";
            if (days == 7) label = "За 7 дней";
            deleteRow(label, days, historyChanged);
        }
        deleteRow("Всю историю", 0, historyChanged);

        section("Сеть");
        row("Прокси  ›", openNetwork);
    }

    private void deleteRow(String label, int days, Runnable changed) {
        row(label, () -> new AlertDialog.Builder(activity)
                .setTitle("Удалить историю?")
                .setMessage(days == 0 ? "Будет удалена вся история просмотра."
                        : "Будет удалена история за последние " + days + " дн.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    long cutoff = days == 0 ? 0
                            : System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
                    int removed = WatchHistoryStore.deleteSince(activity, cutoff);
                    Toast.makeText(activity, "Удалено записей: " + removed,
                            Toast.LENGTH_SHORT).show();
                    changed.run();
                }).show());
    }

    private void section(String title) {
        TextView label = text(title, 21, MobileAuroraTheme.TEXT);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(12);
        content.addView(label, params);
    }

    private void description(String value) {
        TextView label = text(value, 14, MobileAuroraTheme.MUTED);
        label.setPadding(dp(6), 0, dp(6), dp(8));
        content.addView(label);
    }

    private CheckBox check(String title, boolean enabled) {
        CheckBox box = new CheckBox(activity);
        box.setText(title);
        box.setTextSize(15);
        box.setTextColor(MobileAuroraTheme.TEXT);
        box.setButtonTintList(ColorStateList.valueOf(MobileAuroraTheme.TEAL));
        box.setPadding(dp(10), 0, dp(10), 0);
        box.setBackground(MobileAuroraTheme.round(MobileAuroraTheme.SURFACE, dp(12)));
        box.setChecked(enabled);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.bottomMargin = dp(8);
        content.addView(box, params);
        return box;
    }

    private void row(String title, Runnable action) {
        TextView row = text(title, 16, MobileAuroraTheme.TEXT);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setBackground(MobileAuroraTheme.round(MobileAuroraTheme.SURFACE, dp(12)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.bottomMargin = dp(8);
        content.addView(row, params);
        row.setOnClickListener(view -> action.run());
    }

    private TextView text(String value, int size, int color) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
