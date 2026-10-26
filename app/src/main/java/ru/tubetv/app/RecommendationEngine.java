package ru.tubetv.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;

/** Small local content-based model. History stays on the device. */
final class RecommendationEngine {
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "это", "как", "для", "про", "что", "или", "при", "его", "все", "всё", "the", "and",
            "with", "видео", "video", "новый", "новое", "смотреть", "полный", "часть", "серия"));

    static List<String> queries(List<VideoItem> history) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(30, history.size()); i++) {
            for (String word : words(history.get(i).title)) {
                Integer previous = weights.get(word);
                weights.put(word, (previous == null ? 0 : previous) + Math.max(1, 8 - i / 4));
            }
        }
        List<String> terms = new ArrayList<>(weights.keySet());
        Collections.sort(terms, (a, b) -> Integer.compare(weights.get(b), weights.get(a)));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(3, terms.size()); i++) result.add(terms.get(i));
        return result;
    }

    static List<VideoItem> rank(List<VideoItem> candidates, List<VideoItem> history) {
        Set<String> watched = new HashSet<>();
        Set<String> topics = new HashSet<>();
        Set<String> channels = new HashSet<>();
        for (VideoItem item : history) {
            watched.add(item.stableKey());
            topics.addAll(words(item.title));
            if (!item.channelId.isEmpty()) channels.add(item.channelKey());
        }
        Map<String, VideoItem> unique = new LinkedHashMap<>();
        for (VideoItem item : candidates) {
            if (!watched.contains(item.stableKey()) && !item.isLive) unique.put(item.stableKey(), item);
        }
        List<VideoItem> result = new ArrayList<>(unique.values());
        Map<VideoItem, Integer> scores = new IdentityHashMap<>();
        for (VideoItem item : result) scores.put(item, score(item, topics, channels));
        Collections.sort(result, (a, b) -> Integer.compare(scores.get(b), scores.get(a)));
        return result.subList(0, Math.min(60, result.size()));
    }

    private static int score(VideoItem item, Set<String> topics, Set<String> channels) {
        int score = channels.contains(item.channelKey()) ? 3 : 0;
        for (String word : words(item.title)) if (topics.contains(word)) score += 2;
        return score;
    }

    private static Set<String> words(String title) {
        Set<String> result = new LinkedHashSet<>();
        for (String word : title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !STOP.contains(word) && !digitsOnly(word)) result.add(word);
        }
        return result;
    }

    private static boolean digitsOnly(String value) {
        for (int i = 0; i < value.length(); i++)
            if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }
}
