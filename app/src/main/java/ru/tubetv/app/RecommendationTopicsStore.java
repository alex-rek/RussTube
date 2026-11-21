package ru.tubetv.app;

import android.content.Context;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** User-defined TV recommendation search buttons, stored in display order. */
final class RecommendationTopicsStore {
    private static final String PREFS = "recommendation_topics";
    private static final String KEY = "topics";
    private static final int MAX_TOPICS = 12;
    private static final int MAX_LENGTH = 48;
    private static final String[] BUILT_IN = {"Наука", "Путешествия", "Технологии"};

    static List<String> load(Context context) {
        return decode(context.getSharedPreferences(PREFS, 0).getString(KEY, "[]"));
    }

    static void add(Context context, String topic) {
        List<String> topics = withAdded(load(context), topic);
        save(context, topics);
    }

    static void remove(Context context, String topic) {
        String key = normalized(topic).toLowerCase(Locale.ROOT);
        List<String> topics = new ArrayList<>(load(context));
        for (int i = topics.size() - 1; i >= 0; i--)
            if (topics.get(i).toLowerCase(Locale.ROOT).equals(key)) topics.remove(i);
        save(context, topics);
    }

    static List<String> withAdded(List<String> existing, String topic) {
        String value = normalized(topic);
        if (value.length() < 2 || value.length() > MAX_LENGTH)
            throw new IllegalArgumentException("Введите тему длиной от 2 до 48 символов");
        if (existing.size() >= MAX_TOPICS)
            throw new IllegalArgumentException("Можно сохранить не более 12 тем");
        String key = value.toLowerCase(Locale.ROOT);
        for (String builtIn : BUILT_IN)
            if (builtIn.toLowerCase(Locale.ROOT).equals(key))
                throw new IllegalArgumentException("Такая тема уже есть");
        for (String saved : existing)
            if (saved.toLowerCase(Locale.ROOT).equals(key))
                throw new IllegalArgumentException("Такая тема уже есть");
        List<String> result = new ArrayList<>(existing);
        result.add(value);
        return result;
    }

    static List<String> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(encoded);
            for (int i = 0; i < values.length() && result.size() < MAX_TOPICS; i++) {
                String topic = values.optString(i, "");
                try { result = withAdded(result, topic); }
                catch (IllegalArgumentException ignored) { /* Ignore malformed or duplicate entries. */ }
            }
        } catch (Exception ignored) { /* A damaged preference starts with an empty list. */ }
        return result;
    }

    private static String normalized(String topic) {
        return topic == null ? "" : topic.trim().replaceAll("\\s+", " ");
    }

    private static void save(Context context, List<String> topics) {
        JSONArray values = new JSONArray();
        for (String topic : topics) values.put(topic);
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, values.toString()).apply();
    }

    private RecommendationTopicsStore() { }
}
