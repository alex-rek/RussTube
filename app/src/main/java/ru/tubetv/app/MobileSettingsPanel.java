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

    MobileSettingsPanel(Activity activity, Runnable openNetwork, Runnable historyChanged,
                        Runnable cacheSizeChanged, Runnable clearVideoCache,
                        Runnable saveLibrary, Runnable loadLibrary) {
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

        section("Подписки и Избранное");
        row("Сохранить в файл", saveLibrary);
        row("Загрузить из файла", loadLibrary);
        description("При загрузке новые записи добавляются к сохранённым, повторы пропускаются.");

        section("Shorts");
        CheckBox autoplay = check("Автовоспроизведение",
                MobileBrowsePreferences.shortsAutoplay(activity));
        autoplay.setOnCheckedChangeListener((button, checked) ->
                MobileBrowsePreferences.setShortsAutoplay(activity, checked));
        CheckBox showInfo = check("Показывать информацию поверх видео",
                MobileBrowsePreferences.shortsShowInfo(activity));
        showInfo.setOnCheckedChangeListener((button, checked) ->
                MobileBrowsePreferences.setShortsShowInfo(activity, checked));
        CheckBox loop = check("Повторять по кругу",
                MobileBrowsePreferences.shortsLoop(activity));
        loop.setOnCheckedChangeListener((button, checked) ->
                MobileBrowsePreferences.setShortsLoop(activity, checked));
        description("Если повтор выключен, Shorts остановится после одного просмотра.");
        description("Предзагрузка");
        row("Предзагрузка следующих: " + MobileBrowsePreferences.shortsPreloadCount(activity),
                () -> choosePreload());
        description("Видео заранее загружается для следующего ролика; для остальных готовятся ссылки.");
        row("Режим предзагрузки: " + preloadModeLabel(), () -> choosePreloadMode());
        CheckBox wifiOnly = check("Предзагрузка только через Wi-Fi",
                MobileBrowsePreferences.shortsWifiOnly(activity));
        wifiOnly.setOnCheckedChangeListener((button, checked) ->
                MobileBrowsePreferences.setShortsWifiOnly(activity, checked));
        row("Размер видеокэша: " + MobileBrowsePreferences.shortsCacheMb(activity) + " МБ",
                () -> chooseCacheSize(cacheSizeChanged));
        row("Очистить видеокэш", () -> new AlertDialog.Builder(activity)
                .setTitle("Очистить видеокэш?")
                .setMessage("Удалятся сохранённые видеосегменты; история просмотров останется.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить", (dialog, which) -> clearVideoCache.run()).show());
        row("Формат окна: " + frameLabel(MobileBrowsePreferences.shortsFrameRatio(activity)),
                () -> chooseFrame());
        CheckBox fill = check("Заполнять окно видео (обрезать края)",
                MobileBrowsePreferences.shortsFill(activity));
        fill.setOnCheckedChangeListener((button, checked) ->
                MobileBrowsePreferences.setShortsFill(activity, checked));

        section("Плеер");
        row("Язык субтитров: " + CaptionPreferences.LANGUAGES[CaptionPreferences.language(activity)],
                () -> chooseCaption("Язык субтитров", CaptionPreferences.LANGUAGES,
                        CaptionPreferences.language(activity), CaptionPreferences::setLanguage));
        row("Размер субтитров: " + CaptionPreferences.SIZES[CaptionPreferences.size(activity)],
                () -> chooseCaption("Размер субтитров", CaptionPreferences.SIZES,
                        CaptionPreferences.size(activity), CaptionPreferences::setSize));
        row("Цвет субтитров: " + CaptionPreferences.COLORS[CaptionPreferences.color(activity)],
                () -> chooseCaption("Цвет субтитров", CaptionPreferences.COLORS,
                        CaptionPreferences.color(activity), CaptionPreferences::setColor));

        section("История");
        description("Удалить просмотры за выбранный период");
        for (int days : new int[]{1, 2, 3, 7}) {
            String label = days == 1 ? "За 1 день" : "За " + days + " дня";
            if (days == 7) label = "За 7 дней";
            deleteRow(label, days, historyChanged);
        }
        deleteRow("Всю историю", 0, historyChanged);
        row("Вернуть недоступные видео (" + UnavailableVideoStore.count(activity) + ")", () ->
                new AlertDialog.Builder(activity).setTitle("Вернуть видео в рекомендации?")
                        .setMessage("Ранее неработающие видео снова появятся в ленте и Shorts.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Вернуть", (dialog, which) -> {
                            UnavailableVideoStore.clear(activity);
                            historyChanged.run();
                        }).show());

        section("Сеть");
        row("Прокси  ›", openNetwork);
        section("О программе");
        description("RussTube " + BuildConfig.VERSION_NAME);
        description("Artic Fox app, California. 2026.");
    }

    private interface CaptionSetter { void set(Activity activity, int value); }

    private void chooseCaption(String title, String[] choices, int selected, CaptionSetter setter) {
        new AlertDialog.Builder(activity).setTitle(title)
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    setter.set(activity, which);
                    dialog.dismiss();
                    for (int i = 0; i < content.getChildCount(); i++) {
                        if (content.getChildAt(i) instanceof TextView) {
                            TextView view = (TextView) content.getChildAt(i);
                            if (view.getText().toString().startsWith(title + ":"))
                                view.setText(title + ": " + choices[which]);
                        }
                    }
                }).setNegativeButton("Отмена", null).show();
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

    private void choosePreload() {
        String[] choices = {"0 — выключено", "1 видео", "2 видео", "3 видео", "4 видео", "5 видео"};
        new AlertDialog.Builder(activity).setTitle("Предзагрузка Shorts")
                .setSingleChoiceItems(choices, MobileBrowsePreferences.shortsPreloadCount(activity),
                        (dialog, which) -> {
                            MobileBrowsePreferences.setShortsPreloadCount(activity, which);
                            dialog.dismiss();
                            refreshShortsRows();
                        }).setNegativeButton("Отмена", null).show();
    }

    private String preloadModeLabel() {
        return new String[]{"Экономия трафика", "Баланс", "Максимальная скорость"}[
                MobileBrowsePreferences.shortsPreloadMode(activity)];
    }

    private void choosePreloadMode() {
        String[] choices = {"Экономия трафика", "Баланс", "Максимальная скорость"};
        new AlertDialog.Builder(activity).setTitle("Режим предзагрузки Shorts")
                .setSingleChoiceItems(choices, MobileBrowsePreferences.shortsPreloadMode(activity),
                        (dialog, which) -> {
                            MobileBrowsePreferences.setShortsPreloadMode(activity, which);
                            dialog.dismiss();
                            refreshShortsRows();
                        }).setNegativeButton("Отмена", null).show();
    }

    private void chooseCacheSize(Runnable changed) {
        int[] sizes = {32, 64, 128, 256};
        String[] choices = {"32 МБ", "64 МБ", "128 МБ", "256 МБ"};
        int selected = 1;
        for (int i = 0; i < sizes.length; i++)
            if (sizes[i] == MobileBrowsePreferences.shortsCacheMb(activity)) selected = i;
        new AlertDialog.Builder(activity).setTitle("Размер видеокэша")
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    MobileBrowsePreferences.setShortsCacheMb(activity, sizes[which]);
                    changed.run();
                    dialog.dismiss();
                    refreshShortsRows();
                }).setNegativeButton("Отмена", null).show();
    }

    private void chooseFrame() {
        String[] choices = {"9:16", "4:5", "По высоте экрана"};
        new AlertDialog.Builder(activity).setTitle("Формат окна Shorts")
                .setSingleChoiceItems(choices, MobileBrowsePreferences.shortsFrameRatio(activity),
                        (dialog, which) -> {
                            MobileBrowsePreferences.setShortsFrameRatio(activity, which);
                            dialog.dismiss();
                            refreshShortsRows();
                        }).setNegativeButton("Отмена", null).show();
    }

    private String frameLabel(int ratio) {
        return ratio == 1 ? "4:5" : ratio == 2 ? "По высоте экрана" : "9:16";
    }

    private void refreshShortsRows() {
        // Settings are rebuilt when the user returns to this page.
        for (int i = 0; i < content.getChildCount(); i++) {
            if (!(content.getChildAt(i) instanceof TextView)) continue;
            TextView view = (TextView) content.getChildAt(i);
            String label = view.getText().toString();
            if (label.startsWith("Предзагрузка следующих:"))
                view.setText("Предзагрузка следующих: " + MobileBrowsePreferences.shortsPreloadCount(activity));
            else if (label.startsWith("Режим предзагрузки:"))
                view.setText("Режим предзагрузки: " + preloadModeLabel());
            else if (label.startsWith("Размер видеокэша:"))
                view.setText("Размер видеокэша: " + MobileBrowsePreferences.shortsCacheMb(activity) + " МБ");
            else if (label.startsWith("Формат окна:"))
                view.setText("Формат окна: " + frameLabel(MobileBrowsePreferences.shortsFrameRatio(activity)));
        }
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
