package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Toast;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class VideoActions {
    static void show(Activity activity, VideoItem item, Runnable openChannel) {
        boolean subscribed = SubscriptionStore.contains(activity, item);
        String name = item.channelName.isEmpty() ? "Канал " + item.channelId : item.channelName;
        if (item.channelId.isEmpty()) {
            new AlertDialog.Builder(activity).setTitle(item.title)
                    .setMessage(item.source + " · Платформа не передала данные канала")
                    .setPositiveButton("Смотреть", (d, w) -> play(activity, item))
                    .setNegativeButton("Назад", null).show();
            return;
        }
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
        String[] labels = {"Смотреть", (subscribed ? "Отписаться от: " : "Подписаться на: ") + name,
                "Видео канала", "Назад"};
        Button first = null;
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            Button action = new Button(activity);
            action.setText(labels[i]); action.setAllCaps(false); action.setTextColor(Color.WHITE);
            action.setBackgroundResource(R.drawable.navigation_item);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.round(64 * density));
            params.bottomMargin = Math.round(6 * density); panel.addView(action, params);
            if (first == null) first = action;
            action.setOnClickListener(v -> {
                dialog.dismiss();
                if (which == 0) play(activity, item);
                else if (which == 2) openChannel.run();
                else if (which == 1) try {
                        SubscriptionStore.set(activity, item, !subscribed);
                        Toast.makeText(activity, subscribed ? "Подписка удалена" : "Канал добавлен в подписки",
                                Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
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

    static void play(Activity activity, VideoItem item) {
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
        activity.startActivity(intent);
    }
}
