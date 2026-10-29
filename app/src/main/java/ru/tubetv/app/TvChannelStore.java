package ru.tubetv.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Permanent TV channel identities; resolved media URLs and credentials never enter this store. */
final class TvChannelStore {
    private static final String PREFS = "tv_channel_catalog";
    private static final int LIMIT = 1000;
    private static final Pattern RUTUBE = Pattern.compile("/(?:live/)?video/([0-9a-f]{32})(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VK = Pattern.compile(
            "(?:^|/)video(-?[0-9]+_[0-9]+)(?:_([A-Za-z0-9_-]+))?(?:/|$)");
    private static final Pattern OK = Pattern.compile("/video/([0-9]+)(?:/|$)");

    enum Status {
        UNKNOWN("Не удалось проверить"), AVAILABLE("В эфире"),
        PAUSED("Эфир приостановлен"), UNAVAILABLE("Недоступен");
        final String label;
        Status(String label) { this.label = label; }
    }

    static Status failureStatus(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.contains("приостанов") || lower.contains("не в эфире")
                || lower.contains("не отдал поток") || lower.contains("offline"))
            return Status.PAUSED;
        if (lower.contains("недоступ") || lower.contains("not found"))
            return Status.UNAVAILABLE;
        return Status.UNKNOWN;
    }

    static final class Entry {
        String source, id, pageUrl, title, logoUrl, channelId, channelName, channelUrl;
        int order;
        boolean favorite, hidden, customTitle;
        long lastSuccessMs;
        Status status = Status.UNKNOWN;

        String key() { return source + "\n" + id; }
        VideoItem video() {
            return new VideoItem(source, title, "", logoUrl, pageUrl, pageUrl)
                    .withChannel(channelId, channelName, channelUrl, true);
        }
    }

    static Entry fromVideo(VideoItem item) {
        Link link = link(item.pageUrl);
        if (link == null || !link.source.equals(item.source)) return null;
        Entry entry = new Entry();
        entry.source = link.source;
        entry.id = link.id;
        entry.pageUrl = link.pageUrl;
        entry.title = clean(item.title, 180);
        if (entry.title.isEmpty()) entry.title = "Телеканал";
        entry.logoUrl = safeImage(item.thumbnail);
        entry.channelId = clean(item.channelId, 100);
        entry.channelName = clean(item.channelName, 180);
        entry.channelUrl = safePage(item.channelUrl);
        return entry;
    }

    static final class Link {
        final String source, id, pageUrl;
        Link(String source, String id, String pageUrl) {
            this.source = source; this.id = id; this.pageUrl = pageUrl;
        }
    }

    static Link link(String address) {
        if (address == null || address.length() > 2048) return null;
        try {
            URI uri = new URI(address.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) return null;
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String source;
            Pattern pattern;
            if (host.equals("rutube.ru") || host.equals("www.rutube.ru")) {
                source = "RUTUBE"; pattern = RUTUBE;
            } else if (host.equals("vkvideo.ru") || host.equals("www.vkvideo.ru")
                    || host.equals("vk.com") || host.equals("www.vk.com")) {
                source = "VK VIDEO"; pattern = VK;
            } else if (host.equals("ok.ru") || host.equals("www.ok.ru")) {
                source = "OK"; pattern = OK;
            } else return null;
            Matcher matcher = pattern.matcher(path);
            if (!matcher.find()) return null;
            String id = matcher.group(1).toLowerCase(Locale.ROOT);
            String page = source.equals("RUTUBE")
                    ? "https://rutube.ru" + (path.startsWith("/live/") ? "/live/video/" : "/video/") + id + "/"
                    : source.equals("VK VIDEO") ? "https://vkvideo.ru/video" + id
                    + (matcher.group(2) == null ? "" : "_" + matcher.group(2))
                    : "https://ok.ru/video/" + id;
            return new Link(source, id, page);
        } catch (Exception ignored) { return null; }
    }

    static synchronized List<Entry> load(Context context) {
        return decode(context.getSharedPreferences(PREFS, 0).getString("entries", "[]"));
    }

    static synchronized boolean initialDiscoveryDone(Context context, String source) {
        return context.getSharedPreferences(PREFS, 0).getBoolean(discoveryKey(source), false);
    }

    static synchronized void markInitialDiscovery(Context context, String source) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(discoveryKey(source), true).commit();
    }

    private static String discoveryKey(String source) {
        // The first catalog version rejected keyed VK video URLs. Retry discovery once on upgrade.
        return "VK VIDEO".equals(source) ? "discovered_VK VIDEO_keyed_v2" : "discovered_" + source;
    }

    static synchronized int merge(Context context, List<VideoItem> found) {
        List<Entry> entries = load(context);
        int changed = mergeEntries(entries, found);
        if (changed > 0) save(context, entries);
        return changed;
    }

    static int mergeEntries(List<Entry> entries, List<VideoItem> found) {
        int changed = 0;
        for (VideoItem item : found) {
            if (!item.isLive) continue;
            Entry incoming = fromVideo(item);
            if (incoming == null) continue;
            Entry existing = find(entries, incoming.source, incoming.id);
            if (existing == null && !incoming.channelId.isEmpty()) {
                Entry only = null;
                int matches = 0;
                for (Entry entry : entries) if (entry.source.equals(incoming.source)
                        && incoming.channelId.equals(entry.channelId)) { only = entry; matches++; }
                if (matches == 1) existing = only;
            }
            if (existing != null) {
                if (!existing.id.equals(incoming.id)) {
                    existing.id = incoming.id;
                    existing.pageUrl = incoming.pageUrl;
                    existing.status = Status.UNKNOWN;
                    changed++;
                }
                if (!existing.pageUrl.equals(incoming.pageUrl)) {
                    existing.pageUrl = incoming.pageUrl; changed++;
                }
                if (!existing.customTitle && !incoming.title.isEmpty()
                        && !incoming.title.equals(existing.title)) {
                    existing.title = incoming.title; changed++;
                }
                if (!incoming.logoUrl.isEmpty() && !incoming.logoUrl.equals(existing.logoUrl)) {
                    existing.logoUrl = incoming.logoUrl; changed++;
                }
                if (!incoming.channelName.isEmpty() && !incoming.channelName.equals(existing.channelName)) {
                    existing.channelName = incoming.channelName; changed++;
                }
                if (!incoming.channelUrl.isEmpty() && !incoming.channelUrl.equals(existing.channelUrl)) {
                    existing.channelUrl = incoming.channelUrl; changed++;
                }
                if (!incoming.channelId.isEmpty() && !incoming.channelId.equals(existing.channelId)) {
                    existing.channelId = incoming.channelId; changed++;
                }
            } else if (entries.size() < LIMIT) {
                incoming.order = nextOrder(entries);
                entries.add(incoming);
                changed++;
            }
        }
        return changed;
    }

    static synchronized Entry find(Context context, VideoItem item) {
        Link link = link(item.pageUrl);
        return link == null ? null : find(load(context), link.source, link.id);
    }

    private static Entry find(List<Entry> entries, String source, String id) {
        for (Entry entry : entries)
            if (entry.source.equals(source) && entry.id.equals(id)) return entry;
        return null;
    }

    static synchronized List<Entry> visible(Context context, String source) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : load(context))
            if (entry.source.equals(source) && !entry.hidden) result.add(entry);
        result.sort(Comparator.comparingInt(value -> value.order));
        return result;
    }

    static synchronized int hiddenCount(Context context, String source) {
        int count = 0;
        for (Entry entry : load(context)) if (entry.source.equals(source) && entry.hidden) count++;
        return count;
    }

    static synchronized List<Entry> hiddenEntries(Context context, String source) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : load(context))
            if (entry.source.equals(source) && entry.hidden) result.add(entry);
        result.sort(Comparator.comparingInt(value -> value.order));
        return result;
    }

    static synchronized List<VideoItem> favorites(Context context) {
        List<VideoItem> result = new ArrayList<>();
        for (Entry entry : load(context)) if (entry.favorite) result.add(entry.video());
        return result;
    }

    static synchronized void addLink(Context context, String address) throws Exception {
        Link link = link(address);
        if (link == null) throw new Exception("Поддерживаются постоянные ссылки RUTUBE, VK Видео и OK.ru");
        List<Entry> entries = load(context);
        if (find(entries, link.source, link.id) != null) return;
        if (entries.size() >= LIMIT) throw new Exception("Каталог заполнен");
        Entry entry = new Entry();
        entry.source = link.source; entry.id = link.id; entry.pageUrl = link.pageUrl;
        entry.title = "Телеканал " + link.id;
        entry.logoUrl = ""; entry.channelId = ""; entry.channelName = ""; entry.channelUrl = "";
        entry.order = nextOrder(entries);
        entries.add(entry);
        save(context, entries);
    }

    static synchronized void replaceLink(Context context, Entry target, String address) throws Exception {
        Link link = link(address);
        if (link == null || !target.source.equals(link.source))
            throw new Exception("Нужна ссылка на эфир той же платформы");
        List<Entry> entries = load(context);
        Entry duplicate = find(entries, link.source, link.id);
        if (duplicate != null && !duplicate.id.equals(target.id))
            throw new Exception("Эта трансляция уже есть в каталоге");
        Entry entry = find(entries, target.source, target.id);
        if (entry == null) return;
        entry.id = link.id; entry.pageUrl = link.pageUrl; entry.status = Status.UNKNOWN;
        save(context, entries);
    }

    static synchronized void rename(Context context, Entry target, String title) throws Exception {
        String value = clean(title, 180);
        if (value.isEmpty()) throw new Exception("Введите название телеканала");
        List<Entry> entries = load(context);
        Entry entry = find(entries, target.source, target.id);
        if (entry == null) return;
        entry.title = value; entry.customTitle = true;
        save(context, entries);
    }

    static synchronized void favorite(Context context, Entry target, boolean enabled) {
        List<Entry> entries = load(context);
        Entry entry = find(entries, target.source, target.id);
        if (entry != null) { entry.favorite = enabled; save(context, entries); }
    }

    static synchronized void hidden(Context context, Entry target, boolean hidden) {
        List<Entry> entries = load(context);
        Entry entry = find(entries, target.source, target.id);
        if (entry != null) { entry.hidden = hidden; save(context, entries); }
    }

    static synchronized void restoreHidden(Context context, String source) {
        List<Entry> entries = load(context);
        for (Entry entry : entries) if (entry.source.equals(source)) entry.hidden = false;
        save(context, entries);
    }

    static synchronized void move(Context context, Entry target, int direction) {
        List<Entry> entries = load(context);
        List<Entry> group = new ArrayList<>();
        for (Entry entry : entries)
            if (entry.source.equals(target.source) && !entry.hidden) group.add(entry);
        group.sort(Comparator.comparingInt(value -> value.order));
        for (int i = 0; i < group.size(); i++) if (group.get(i).id.equals(target.id)) {
            int neighbor = i + (direction < 0 ? -1 : 1);
            if (neighbor < 0 || neighbor >= group.size()) return;
            int order = group.get(i).order;
            group.get(i).order = group.get(neighbor).order;
            group.get(neighbor).order = order;
            save(context, entries);
            return;
        }
    }

    static synchronized void playbackStatus(Context context, VideoItem item, Status status) {
        Link link = link(item.pageUrl);
        if (link == null) return;
        List<Entry> entries = load(context);
        Entry entry = find(entries, link.source, link.id);
        if (entry == null) return;
        entry.status = status;
        if (status == Status.AVAILABLE) entry.lastSuccessMs = System.currentTimeMillis();
        save(context, entries);
    }

    static synchronized String exportJson(Context context) throws Exception {
        JSONObject root = new JSONObject().put("schema", 1);
        JSONArray data = new JSONArray();
        for (Entry entry : load(context)) data.put(encode(entry));
        return root.put("channels", data).toString(2);
    }

    static synchronized int importJson(Context context, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.optInt("schema") != 1 || root.optJSONArray("channels") == null)
            throw new Exception("Неподдерживаемый формат каталога");
        List<Entry> entries = load(context);
        JSONArray data = root.getJSONArray("channels");
        int added = 0;
        boolean changed = false;
        for (int i = 0; i < data.length(); i++) {
            Entry incoming = decodeEntry(data.optJSONObject(i));
            if (incoming == null) continue;
            Entry existing = find(entries, incoming.source, incoming.id);
            if (existing == null) {
                if (entries.size() >= LIMIT) continue;
                entries.add(incoming);
                added++;
            } else {
                existing.pageUrl = incoming.pageUrl;
                existing.title = incoming.title;
                existing.logoUrl = incoming.logoUrl;
                existing.channelId = incoming.channelId;
                existing.channelName = incoming.channelName;
                existing.channelUrl = incoming.channelUrl;
                existing.order = incoming.order;
                existing.favorite = incoming.favorite;
                existing.hidden = incoming.hidden;
                existing.customTitle = incoming.customTitle;
                existing.lastSuccessMs = incoming.lastSuccessMs;
                existing.status = incoming.status;
            }
            changed = true;
        }
        if (changed) save(context, entries);
        return added;
    }

    private static int nextOrder(List<Entry> entries) {
        int max = -1;
        for (Entry entry : entries) max = Math.max(max, entry.order);
        return max + 1;
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray data = new JSONArray();
        try { for (Entry entry : entries) data.put(encode(entry)); }
        catch (Exception error) { throw new IllegalStateException(error); }
        if (!context.getSharedPreferences(PREFS, 0).edit()
                .putString("entries", data.toString()).commit())
            throw new IllegalStateException("Не удалось сохранить каталог телеканалов");
    }

    private static JSONObject encode(Entry entry) throws Exception {
        return new JSONObject().put("source", entry.source).put("id", entry.id)
                .put("page", entry.pageUrl).put("title", entry.title)
                .put("logo", entry.logoUrl).put("order", entry.order)
                .put("favorite", entry.favorite).put("hidden", entry.hidden)
                .put("customTitle", entry.customTitle)
                .put("channelId", entry.channelId).put("channelName", entry.channelName)
                .put("channelUrl", entry.channelUrl)
                .put("lastSuccessMs", entry.lastSuccessMs)
                .put("status", entry.status.name());
    }

    private static List<Entry> decode(String json) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            JSONArray data = new JSONArray(json);
            for (int i = 0; i < data.length() && entries.size() < LIMIT; i++) {
                Entry entry = decodeEntry(data.optJSONObject(i));
                if (entry != null && seen.add(entry.key())) entries.add(entry);
            }
        } catch (Exception ignored) { }
        return entries;
    }

    private static Entry decodeEntry(JSONObject value) {
        if (value == null) return null;
        Link link = link(value.optString("page"));
        if (link == null || !link.source.equals(value.optString("source"))
                || !link.id.equals(value.optString("id"))) return null;
        Entry entry = new Entry();
        entry.source = link.source; entry.id = link.id; entry.pageUrl = link.pageUrl;
        entry.title = clean(value.optString("title"), 180);
        if (entry.title.isEmpty()) entry.title = "Телеканал " + entry.id;
        entry.logoUrl = safeImage(value.optString("logo"));
        entry.order = Math.max(0, value.optInt("order"));
        entry.favorite = value.optBoolean("favorite");
        entry.hidden = value.optBoolean("hidden");
        entry.customTitle = value.optBoolean("customTitle");
        entry.channelId = clean(value.optString("channelId"), 100);
        entry.channelName = clean(value.optString("channelName"), 180);
        entry.channelUrl = safePage(value.optString("channelUrl"));
        entry.lastSuccessMs = Math.max(0, value.optLong("lastSuccessMs"));
        try { entry.status = Status.valueOf(value.optString("status", "UNKNOWN")); }
        catch (Exception ignored) { entry.status = Status.UNKNOWN; }
        return entry;
    }

    private static String clean(String text, int limit) {
        String value = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").trim();
        return value.substring(0, Math.min(limit, value.length()));
    }

    private static String safeImage(String address) {
        return safePage(address);
    }

    private static String safePage(String address) {
        if (address == null || address.length() > 2048) return "";
        try {
            URI uri = new URI(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getHost() == null) return "";
            return new URI("https", null, uri.getHost(), -1, uri.getPath(), null, null).toString();
        } catch (Exception ignored) { return ""; }
    }

    private TvChannelStore() { }
}
