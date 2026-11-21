package ru.tubetv.app;

import android.content.Context;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TvHomePreferences {
    static final int[] COUNTS = {10, 20, 30};
    private static final String PREFS = "browse";
    private static final String KEY = "tv_home_row_count";
    private static final String SECTION_PREFIX = "tv_home_section_";
    private static final String MODE_KEY = "tv_home_mode";
    private static final String TOPICS_KEY = "tv_home_topics";
    static final String NORMAL = "normal";
    static final String THEMATIC = "thematic";
    private static final List<String> DEFAULT_TOPICS = Arrays.asList("Новости", "Наука",
            "Спорт", "Кулинария", "Рукоделие", "Оружие", "Технологии", "Музыка",
            "Путешествия", "Игры", "История", "Автомобили");

    static String mode(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MODE_KEY, NORMAL);
        return THEMATIC.equals(value) ? THEMATIC : NORMAL;
    }

    static void setMode(Context context, String mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(MODE_KEY, THEMATIC.equals(mode) ? THEMATIC : NORMAL).apply();
    }

    static List<String> availableTopics(Context context) {
        Set<String> topics = new LinkedHashSet<>(DEFAULT_TOPICS);
        topics.addAll(RecommendationTopicsStore.load(context));
        return new ArrayList<>(topics);
    }

    static List<String> topics(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(TOPICS_KEY, "[\"Новости\",\"Наука\",\"Спорт\"]");
        List<String> available = availableTopics(context);
        List<String> topics = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(saved);
            for (int i = 0; i < values.length() && topics.size() < 5; i++) {
                String value = values.optString(i);
                if (available.contains(value) && !topics.contains(value)) topics.add(value);
            }
        } catch (Exception ignored) { }
        if (topics.isEmpty()) topics.add("Новости");
        return topics;
    }

    static void setTopics(Context context, List<String> selected) {
        JSONArray values = new JSONArray();
        List<String> available = availableTopics(context);
        Set<String> seen = new LinkedHashSet<>();
        for (String topic : selected) {
            if (values.length() >= 5) break;
            if (available.contains(topic) && seen.add(topic))
                values.put(topic);
        }
        if (values.length() == 0) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(TOPICS_KEY, values.toString()).apply();
    }

    static int count(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 20);
        return value == 10 || value == 20 || value == 30 ? value : 20;
    }

    static void setCount(Context context, int value) {
        if (value != 10 && value != 20 && value != 30) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, value).apply();
    }

    static boolean sectionEnabled(Context context, String section) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(SECTION_PREFIX + section, true);
    }

    static void setSectionEnabled(Context context, String section, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(SECTION_PREFIX + section, enabled).apply();
    }

    private TvHomePreferences() { }
}
