package ru.tubetv.app;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class VideoActions {
    static void show(Activity activity, VideoItem item, Runnable openChannel) {
        show(activity, item, openChannel, null);
    }

    static void show(Activity activity, VideoItem item, Runnable openChannel,
                     Runnable onFilmsChanged) {
        boolean subscribed = SubscriptionStore.contains(activity, item);
        String name = item.channelName.isEmpty() ? "Канал " + item.channelId : item.channelName;
        boolean hasChannel = !item.channelId.isEmpty();
        boolean savedFilm = !BuildConfig.MOBILE_VARIANT && SavedFilmsStore.contains(activity, item);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(18 * density);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackgroundColor(Color.rgb(11, 15, 20));
        TextView title = new TextView(activity);
        title.setText(item.title); title.setTextColor(Color.WHITE); title.setTextSize(20);
        title.setMaxLines(3); title.setPadding(0, 0, 0, padding);
        panel.addView(title);
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        labels.add("Смотреть"); actions.add(() -> play(activity, item));
        if (hasChannel) {
            labels.add((subscribed ? "Отписаться от: " : "Подписаться на: ") + name);
            actions.add(() -> {
                try {
                    SubscriptionStore.set(activity, item, !subscribed);
                    Toast.makeText(activity, subscribed ? "Подписка удалена" : "Канал добавлен в подписки",
                            Toast.LENGTH_SHORT).show();
                } catch (Exception error) {
                    Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            labels.add("Видео канала"); actions.add(openChannel);
        }
        if (!BuildConfig.MOBILE_VARIANT) {
            labels.add(savedFilm ? "Удалить из Фильмов" : "Добавить в Фильмы");
            actions.add(() -> {
                try {
                    SavedFilmsStore.set(activity, item, !savedFilm);
                    Toast.makeText(activity, savedFilm ? "Удалено из Фильмов" : "Добавлено в Фильмы",
                            Toast.LENGTH_SHORT).show();
                    if (onFilmsChanged != null) onFilmsChanged.run();
                } catch (Exception error) {
                    Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
        if (!FavoriteStore.contains(activity, item)) {
            labels.add("В избранное"); actions.add(() -> setFavorite(activity, item, true));
        } else {
            labels.add("Убрать из избранного"); actions.add(() -> setFavorite(activity, item, false));
        }
        labels.add("Назад"); actions.add(() -> { });
        if (!BuildConfig.MOBILE_VARIANT) {
            String[] menuLabels = new String[labels.size()];
            for (int i = 0; i < labels.size(); i++) {
                String label = labels.get(i);
                String icon = label.equals("Смотреть") ? "▶"
                        : label.startsWith("Подписаться") || label.startsWith("Отписаться") ? "♙"
                        : label.equals("Видео канала") ? "▣"
                        : label.contains("Фильмов") || label.contains("Фильмы") ? "▤"
                        : label.contains("избранн") ? "♥" : "←";
                menuLabels[i] = icon + "    " + label;
            }
            SideSelectionPanel choices = new SideSelectionPanel(activity, item.title,
                    menuLabels, -1, index -> {
                        dialog.dismiss();
                        actions.get(index).run();
                    });
            if (item.thumbnail != null && !item.thumbnail.isEmpty()) {
                FrameLayout preview = new FrameLayout(activity);
                ImageView cover = new ImageView(activity);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                int previewHeight = Math.round(135 * density);
                preview.addView(cover, new FrameLayout.LayoutParams(-1, previewHeight));
                TextView source = new TextView(activity);
                source.setText(item.source);
                source.setTextColor(Color.WHITE);
                source.setTextSize(12);
                source.setBackgroundColor(0xd9001024);
                source.setPadding(Math.round(7 * density), Math.round(3 * density),
                        Math.round(7 * density), Math.round(3 * density));
                FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2,
                        Gravity.TOP | Gravity.START);
                badge.setMargins(Math.round(8 * density), Math.round(8 * density), 0, 0);
                preview.addView(source, badge);
                choices.setHeader(preview);
                ((TubeApplication) activity.getApplication()).imageLoader().load(
                        item.source, item.thumbnail, cover,
                        activity.getResources().getDisplayMetrics().widthPixels * 38 / 100,
                        previewHeight);
            }
            dialog.setContentView(choices);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams params = window.getAttributes();
                params.dimAmount = .48f;
                params.gravity = Gravity.END;
                params.width = Math.max(Math.round(380 * density),
                        activity.getResources().getDisplayMetrics().widthPixels * 38 / 100);
                params.height = -1;
                window.setAttributes(params);
            }
            dialog.show();
            if (TvAppearancePreferences.animations(activity)) {
                choices.setTranslationX(Math.round(380 * density));
                choices.setAlpha(.82f);
                choices.animate().translationX(0).alpha(1f).setDuration(340)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
            }
            return;
        }
        Button first = null;
        for (int i = 0; i < labels.size(); i++) {
            final int which = i;
            Button action = new Button(activity);
            action.setText(labels.get(i)); action.setAllCaps(false); action.setTextColor(Color.WHITE);
            action.setBackgroundResource(R.drawable.navigation_item);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.round(64 * density));
            params.bottomMargin = Math.round(6 * density); panel.addView(action, params);
            if (first == null) first = action;
            action.setOnClickListener(v -> {
                dialog.dismiss();
                actions.get(which).run();
                });
        }
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = .48f;
            if (BuildConfig.MOBILE_VARIANT) {
                params.gravity = Gravity.BOTTOM;
                params.width = -1;
                params.height = -2;
            } else {
                params.gravity = Gravity.END;
                params.width = Math.max(Math.round(290 * density),
                        activity.getResources().getDisplayMetrics().widthPixels * 30 / 100);
                params.height = -1;
            }
            window.setAttributes(params);
        }
        dialog.show();
        if (first != null) first.requestFocus();
    }

    static void openChannel(Activity activity, VideoItem item) {
        try { activity.startActivity(new Intent(activity,
                BuildConfig.MOBILE_VARIANT ? MobileBrowseActivity.class : BrowseActivity.class)
                .putExtra("channel", VideoJson.encode(item).toString())); }
        catch (Exception error) { Toast.makeText(activity, "Не удалось открыть канал", Toast.LENGTH_SHORT).show(); }
    }

    private static void setFavorite(Activity activity, VideoItem item, boolean enabled) {
        try { FavoriteStore.set(activity, item, enabled); }
        catch (Exception error) {
            Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static void play(Activity activity, VideoItem item) {
        play(activity, item, false);
    }

    static void play(Activity activity, VideoItem item, boolean shortsMode) {
        WatchProgressStore.Progress watched = WatchProgressStore.get(activity, item);
        long duration = watched.durationMs > 0 ? watched.durationMs : item.durationMs;
        long position = item.isLive || (duration > 0 && watched.positionMs >= duration * .95)
                ? 0 : watched.positionMs;
        StateStore.savePlayer(activity, item, position, false, 0, false, false);
        Intent intent = new Intent(activity,
                BuildConfig.MOBILE_VARIANT ? MobilePlayerActivity.class : PlayerActivity.class);
        intent.putExtra("source", item.source).putExtra("title", item.title)
                .putExtra("subtitle", item.subtitle).putExtra("thumbnail", item.thumbnail)
                .putExtra("resolver_url", item.playUrl).putExtra("page_url", item.pageUrl)
                .putExtra("duration_ms", duration).putExtra("max_width", item.maxWidth)
                .putExtra("max_height", item.maxHeight).putExtra("resume_position", position)
                .putExtra("channel_id", item.channelId).putExtra("channel_name", item.channelName)
                .putExtra("channel_url", item.channelUrl).putExtra("live", item.isLive);
        intent.putExtra("views", item.views).putExtra("published_at", item.publishedAtMs);
        if (shortsMode && BuildConfig.MOBILE_VARIANT) intent.putExtra("shorts_mode", true);
        activity.startActivity(intent);
    }
}
