package ru.tubetv.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.provider.BaseColumns;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mirrors bounded TV catalog rows into the Android TV home-screen provider. */
@SuppressLint("NewApi") // Every public provider entry point checks supported() before API 26 calls.
final class TvHomeChannels {
    static final String RECOMMENDATIONS = "recommendations";
    static final String FILMS = "films";
    static final String TV = "tv";
    private static final String[] SECTIONS = {RECOMMENDATIONS, FILMS, TV};
    private static final String[] LABELS = {"RussTube · Рекомендации", "RussTube · Фильмы", "RussTube · ТВ"};
    private static final String PREFS = "tv_home_channels";
    private static final int MAX_PROGRAMS = 20;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    static boolean supported() {
        return !BuildConfig.MOBILE_VARIANT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    static void initialize(Context context) {
        initialize(context, null);
    }

    static void initialize(Context context, Runnable finished) {
        if (!supported()) return;
        Context app = context.getApplicationContext();
        TvHomeRefreshJob.schedule(app);
        IO.execute(() -> {
            try {
                ensureChannels(app);
                publish(app, RECOMMENDATIONS, loadSnapshot(app, RECOMMENDATIONS));
                List<VideoItem> films = new ArrayList<>(SavedFilmsStore.load(app));
                films.addAll(loadSnapshot(app, FILMS));
                publish(app, FILMS, films);
                publish(app, TV, savedTv(app));
            } catch (RuntimeException error) {
                Log.w("RussTubeTvHome", "TV home initialization failed", error);
            } finally {
                if (finished != null) finished.run();
            }
        });
    }

    static void publishAsync(Context context, String section, List<VideoItem> items) {
        if (!supported() || !known(section)) return;
        Context app = context.getApplicationContext();
        List<VideoItem> snapshot = new ArrayList<>(items);
        IO.execute(() -> {
            try { publish(app, section, snapshot); }
            catch (RuntimeException error) { Log.w("RussTubeTvHome", "Publish " + section, error); }
        });
    }

    static void publishTvCatalogAsync(Context context) {
        if (!supported()) return;
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try { publish(app, TV, savedTv(app)); }
            catch (RuntimeException error) { Log.w("RussTubeTvHome", "Publish TV", error); }
        });
    }

    static void refreshFromSources(Context context) {
        if (!supported() || Thread.currentThread().isInterrupted()) return;
        FeedRepository repository = new FeedRepository();
        List<VideoItem> history = new ArrayList<>();
        for (WatchHistoryStore.Entry entry : WatchHistoryStore.load(context)) history.add(entry.item);
        List<VideoItem> subscriptions = new ArrayList<>();
        if (context.getSharedPreferences("browse", 0)
                .getBoolean("recommend_subscriptions", true)) {
            int count = 0;
            for (VideoItem channel : SubscriptionStore.load(context)) {
                if (count++ >= 8 || Thread.currentThread().isInterrupted()) break;
                if (!MobileBrowsePreferences.sourceEnabled(context, channel.source)) continue;
                try { subscriptions.addAll(repository.channel(channel)); }
                catch (Exception error) { Log.w("RussTubeTvHome", "Subscription refresh", error); }
            }
        }
        List<VideoItem> candidates = new ArrayList<>();
        boolean byHistory = context.getSharedPreferences("browse", 0)
                .getBoolean("recommendations", true);
        if (byHistory) {
            List<String> queries = RecommendationEngine.queries(history);
            for (int q = 0; q < Math.min(2, queries.size()); q++) {
                for (String source : FeedRepository.SOURCES) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (!MobileBrowsePreferences.sourceEnabled(context, source)) continue;
                    try { candidates.addAll(repository.search(source, queries.get(q))); }
                    catch (Exception error) { Log.w("RussTubeTvHome", "Recommendation refresh", error); }
                }
            }
        }
        if (!subscriptions.isEmpty() || !candidates.isEmpty())
            publish(context, RECOMMENDATIONS,
                    HomeFeed.combine(subscriptions, candidates, history, byHistory));

        List<VideoItem> films = new ArrayList<>(SavedFilmsStore.load(context));
        if (MobileBrowsePreferences.sourceEnabled(context, "OK")) try {
            for (VideoItem item : OkClient.newFilmsCatalog())
                if (FilmCatalog.includes(item, true)) films.add(item);
        } catch (Exception error) { Log.w("RussTubeTvHome", "OK films", error); }
        if (MobileBrowsePreferences.sourceEnabled(context, "VK VIDEO")) try {
            for (VideoItem item : repository.search("VK VIDEO", "фильмы и сериалы"))
                if (FilmCatalog.includes(item)) films.add(item);
        } catch (Exception error) { Log.w("RussTubeTvHome", "VK films", error); }
        if (MobileBrowsePreferences.sourceEnabled(context, "RUTUBE")) try {
            for (VideoItem item : RutubeTvCatalog.movies())
                if (FilmCatalog.includes(item, true)) films.add(item);
        } catch (Exception error) { Log.w("RussTubeTvHome", "RUTUBE films", error); }
        if (!Thread.currentThread().isInterrupted()) {
            if (!films.isEmpty()) publish(context, FILMS, films);
            publish(context, TV, savedTv(context));
        }
    }

    static void requestVisible(Activity activity, String section) {
        if (!supported() || !known(section)) return;
        IO.execute(() -> {
            try {
                Long channel = ensureChannels(activity).get(section);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || channel == null) return;
                    try {
                        Intent request = new Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE);
                        request.putExtra(TvContract.EXTRA_CHANNEL_ID, channel);
                        activity.startActivityForResult(request, 8101);
                    } catch (RuntimeException error) {
                        Toast.makeText(activity, "Главный экран не поддерживает каналы",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (RuntimeException error) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Не удалось добавить канал на главный экран", Toast.LENGTH_LONG).show());
            }
        });
    }

    static VideoItem resolve(Context context, Uri uri) {
        if (uri == null || !"russtube".equals(uri.getScheme())
                || !"home".equals(uri.getHost()) || uri.getPathSegments().size() != 2) return null;
        String section = uri.getPathSegments().get(0);
        String id = uri.getPathSegments().get(1);
        if (!known(section) || !id.matches("[0-9a-f]{32}")) return null;
        try {
            JSONArray saved = new JSONArray(prefs(context).getString("items_" + section, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject object = saved.optJSONObject(i);
                if (object == null) continue;
                VideoItem item = VideoJson.decode(object);
                if (id.equals(id(item))) return item;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static List<VideoItem> loadSnapshot(Context context, String section) {
        List<VideoItem> result = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(prefs(context).getString("items_" + section, "[]"));
            for (int i = 0; i < Math.min(saved.length(), MAX_PROGRAMS); i++) {
                JSONObject value = saved.optJSONObject(i);
                if (value != null) result.add(VideoJson.decode(value));
            }
        } catch (Exception ignored) { }
        return result;
    }

    static void suppress(Context context, long programId) {
        if (!supported() || programId < 0) return;
        SharedPreferences p = prefs(context);
        String id = p.getString("program_" + programId, null);
        if (id != null) p.edit().putBoolean("hidden_" + id, true).apply();
        try { context.getContentResolver().delete(TvContract.buildPreviewProgramUri(programId), null, null); }
        catch (RuntimeException ignored) { }
    }

    private static synchronized void publish(Context context, String section, List<VideoItem> items) {
        if (!supported()) return;
        Map<String, Long> channels = ensureChannels(context);
        Long channelId = channels.get(section);
        if (channelId == null) return;
        SharedPreferences p = prefs(context);
        LinkedHashMap<String, VideoItem> selected = new LinkedHashMap<>();
        Set<String> watched = new HashSet<>();
        if (RECOMMENDATIONS.equals(section))
            for (WatchHistoryStore.Entry entry : WatchHistoryStore.load(context))
                watched.add(entry.item.stableKey());
        Set<String> unavailable = UnavailableVideoStore.keys(context);
        for (VideoItem item : items) {
            if (selected.size() >= MAX_PROGRAMS) break;
            if (item == null || item.pageUrl == null || item.pageUrl.isEmpty()
                    || item.title == null || item.title.isEmpty()) continue;
            if (RECOMMENDATIONS.equals(section) && item.isLive) continue;
            if (!MobileBrowsePreferences.sourceEnabled(context, item.source)
                    || watched.contains(item.stableKey())
                    || unavailable.contains(item.stableKey())) continue;
            String key = section + ':' + id(item);
            if (!p.getBoolean("hidden_" + key, false)) selected.put(key, item);
        }
        Map<String, ProgramRow> existing = programs(context, channelId);
        JSONArray snapshot = new JSONArray();
        SharedPreferences.Editor editor = p.edit();
        int weight = MAX_PROGRAMS;
        for (Map.Entry<String, VideoItem> entry : selected.entrySet()) {
            String key = entry.getKey();
            VideoItem item = entry.getValue();
            try {
                // Persist a page identity, never a signed or expiring media URL.
                VideoItem safe = new VideoItem(item.source, item.title, item.subtitle,
                        item.thumbnail, item.pageUrl, item.pageUrl, item.durationMs)
                        .withChannel(item.channelId, item.channelName, item.channelUrl, item.isLive)
                        .withMetrics(item.views, item.publishedAtMs);
                snapshot.put(VideoJson.encode(safe));
                ProgramRow old = existing.remove(key);
                if (old != null && !old.browsable) {
                    editor.putBoolean("hidden_" + key, true);
                    continue;
                }
                ContentValues values = programValues(context, channelId, section, key, safe, weight--);
                if (old == null) {
                    Uri created = context.getContentResolver().insert(TvContract.PreviewPrograms.CONTENT_URI, values);
                    if (created != null) editor.putString("program_" + ContentUris.parseId(created), key);
                } else if (!old.matches(values)) {
                    context.getContentResolver().update(TvContract.buildPreviewProgramUri(old.id), values, null, null);
                }
            } catch (Exception error) { Log.w("RussTubeTvHome", "Skipping preview program", error); }
        }
        // Remove stale cards, while keeping the user's hidden-card decisions in preferences.
        for (ProgramRow old : existing.values()) {
            try { context.getContentResolver().delete(TvContract.buildPreviewProgramUri(old.id), null, null); }
            catch (RuntimeException error) { Log.w("RussTubeTvHome", "Remove stale card", error); }
        }
        editor.putString("items_" + section, snapshot.toString()).apply();
    }

    private static ContentValues programValues(Context context, long channelId, String section,
                                                String key, VideoItem item, int weight) {
        Intent open = new Intent(context, TvHomeEntryActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("russtube://home/" + section + '/' + id(item)));
        ContentValues values = new ContentValues();
        values.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId);
        values.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, key);
        values.put(TvContract.PreviewPrograms.COLUMN_TYPE, item.isLive
                ? TvContract.PreviewPrograms.TYPE_CHANNEL
                : FILMS.equals(section) ? TvContract.PreviewPrograms.TYPE_MOVIE
                : TvContract.PreviewPrograms.TYPE_CLIP);
        values.put(TvContract.PreviewPrograms.COLUMN_TITLE, item.title);
        values.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, item.subtitle);
        values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                item.thumbnail != null && item.thumbnail.startsWith("https://")
                        ? item.thumbnail : "android.resource://" + context.getPackageName()
                        + "/" + R.mipmap.ic_launcher);
        values.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME));
        values.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, weight);
        values.put(TvContract.PreviewPrograms.COLUMN_LIVE, item.isLive ? 1 : 0);
        return values;
    }

    private static synchronized Map<String, Long> ensureChannels(Context context) {
        Map<String, Long> found = new HashMap<>();
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(TvContract.Channels.CONTENT_URI,
                new String[]{BaseColumns._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID},
                null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name != null && name.startsWith("russtube:")) found.put(name.substring(9), cursor.getLong(0));
            }
        }
        for (int i = 0; i < SECTIONS.length; i++) {
            String section = SECTIONS[i];
            if (found.containsKey(section)) continue;
            Intent open = new Intent(context, TvHomeEntryActivity.class)
                    .setAction(Intent.ACTION_VIEW).setData(Uri.parse("russtube://home/" + section));
            ContentValues values = new ContentValues();
            values.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
            values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, LABELS[i]);
            values.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, "russtube:" + section);
            values.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME));
            Uri created = resolver.insert(TvContract.Channels.CONTENT_URI, values);
            if (created == null) continue;
            long id = ContentUris.parseId(created);
            found.put(section, id);
            try (OutputStream out = resolver.openOutputStream(TvContract.buildChannelLogoUri(id))) {
                Bitmap logo = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher);
                if (out != null && logo != null) logo.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception error) { Log.w("RussTubeTvHome", "Channel logo", error); }
        }
        return found;
    }

    private static Map<String, ProgramRow> programs(Context context, long channelId) {
        Map<String, ProgramRow> result = new HashMap<>();
        Uri uri = TvContract.buildPreviewProgramsUriForChannel(channelId);
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{
                BaseColumns._ID, TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContract.PreviewPrograms.COLUMN_BROWSABLE, TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_WEIGHT},
                null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                String key = cursor.getString(1);
                if (key != null) result.put(key, new ProgramRow(cursor.getLong(0),
                        cursor.getInt(2) != 0, cursor.getString(3), cursor.getString(4), cursor.getInt(5)));
            }
        }
        return result;
    }

    private static List<VideoItem> savedTv(Context context) {
        List<VideoItem> result = new ArrayList<>();
        for (String source : new String[]{"RUTUBE", "VK VIDEO", "OK"})
            for (TvChannelStore.Entry entry : TvChannelStore.visible(context, source))
                result.add(entry.video());
        return result;
    }

    private static boolean known(String section) {
        return RECOMMENDATIONS.equals(section) || FILMS.equals(section) || TV.equals(section);
    }

    private static String id(VideoItem item) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(item.stableKey().getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(32);
            for (int i = 0; i < 16; i++) text.append(String.format(java.util.Locale.ROOT, "%02x", hash[i] & 255));
            return text.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static final class ProgramRow {
        final long id;
        final boolean browsable;
        final String title, image;
        final int weight;
        ProgramRow(long id, boolean browsable, String title, String image, int weight) {
            this.id = id; this.browsable = browsable;
            this.title = title; this.image = image; this.weight = weight;
        }
        boolean matches(ContentValues values) {
            return java.util.Objects.equals(title, values.getAsString(TvContract.PreviewPrograms.COLUMN_TITLE))
                    && java.util.Objects.equals(image, values.getAsString(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI))
                    && weight == values.getAsInteger(TvContract.PreviewPrograms.COLUMN_WEIGHT);
        }
    }

    private TvHomeChannels() { }
}
