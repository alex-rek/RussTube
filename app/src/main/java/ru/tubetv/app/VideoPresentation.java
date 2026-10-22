package ru.tubetv.app;

import java.text.NumberFormat;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class VideoPresentation {
    static String channel(VideoItem item) {
        return item.channelName.isEmpty() ? "Канал неизвестен" : item.channelName;
    }

    static String views(VideoItem item) {
        return item.views < 0 ? "" : NumberFormat.getIntegerInstance(new Locale("ru"))
                .format(item.views) + " просмотров";
    }

    static String published(VideoItem item) {
        return item.publishedAtMs <= 0 ? "" : DateFormat.getDateInstance(DateFormat.MEDIUM,
                new Locale("ru")).format(new Date(item.publishedAtMs));
    }

    static long timestamp(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            long number = Long.parseLong(value);
            return number <= 0 ? 0 : number < 100_000_000_000L ? number * 1000L : number;
        } catch (NumberFormatException ignored) { }
        String normalized = value.replaceFirst("Z$", "+0000")
                .replaceFirst("([+-][0-9]{2}):([0-9]{2})$", "$1$2");
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            format.setLenient(false);
            try { Date date = format.parse(normalized); if (date != null) return Math.max(0, date.getTime()); }
            catch (ParseException ignored) { }
        }
        return 0;
    }

    static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000L);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private VideoPresentation() { }
}
