package ru.tubetv.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Horizontally independent rows; only cards near the visible viewport are bound. */
final class TvHomeRows extends ScrollView {
    interface Actions {
        void play(VideoItem item);
        void menu(VideoItem item);
        void navigation();
    }

    private static final String[] TITLES = {
            "Рекомендованные", "Недавно опубликованные", "RUTUBE рекомендует",
            "VK Видео рекомендует", "OK рекомендует", "Дзен рекомендует", "PeerTube"
    };
    private final Activity activity;
    private final Actions actions;
    private final LinearLayout stack;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final int cardWidth;
    private final int cardHeight;
    private final int gap;
    private String selectedSection = TvHomeFeed.RECOMMENDED;

    TvHomeRows(Activity activity, Actions actions) {
        super(activity);
        this.activity = activity;
        this.actions = actions;
        cardWidth = px(Math.round(190 * TvScalePreferences.get(activity, TvScalePreferences.GRID)));
        cardHeight = Math.round(cardWidth * 9f / 16f) + px(112);
        gap = px(14);
        setFillViewport(false);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        stack = new LinearLayout(activity);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setClipChildren(false);
        addView(stack, new ScrollView.LayoutParams(-1, -2));
        int index = 0;
        for (String section : TvHomeFeed.ORDER) {
            Row row = new Row(section, TITLES[index++]);
            rows.put(section, row);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = px(17);
            stack.addView(row.root, rowParams);
        }
        setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) -> refreshVisible());
    }

    void setRows(Map<String, List<VideoItem>> content, boolean loading,
            Set<String> disabledSources) {
        for (Row row : rows.values()) {
            List<VideoItem> next = content.get(row.section);
            row.update(next == null ? new ArrayList<>() : next, loading,
                    disabledSources.contains(row.section));
        }
        post(this::refreshVisible);
    }

    void focusFirst() {
        Row selected = rows.get(selectedSection);
        if (selected != null && !selected.items.isEmpty()) {
            selected.focus(Math.min(selected.selected, selected.items.size() - 1));
            return;
        }
        for (Row row : rows.values()) if (!row.items.isEmpty()) {
            row.focus(Math.min(row.selected, row.items.size() - 1));
            return;
        }
    }

    void restoreSelection() { focusFirst(); }

    boolean hasVideos() {
        for (Row row : rows.values()) if (!row.items.isEmpty()) return true;
        return false;
    }

    private void refreshVisible() {
        if (getVisibility() != VISIBLE || getWidth() == 0) return;
        Rect visible = new Rect();
        for (Row row : rows.values()) {
            if (row.scroll.getGlobalVisibleRect(visible)) row.populate();
            else row.clearInvisible();
        }
    }

    private final class Row {
        final String section;
        final LinearLayout root;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        final TextView empty;
        final List<FrameLayout> slots = new ArrayList<>();
        List<VideoItem> items = new ArrayList<>();
        int selected;

        Row(String section, String title) {
            this.section = section;
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setClipChildren(false);
            TextView heading = text(title, 18, TvAppearancePreferences.text(activity));
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            root.addView(heading, new LinearLayout.LayoutParams(-1, px(32)));
            scroll = new HorizontalScrollView(activity) {
                @Override protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
                    super.onScrollChanged(left, top, oldLeft, oldTop);
                    post(Row.this::populate);
                }
            };
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipChildren(false);
            scroll.setFillViewport(false);
            strip = new LinearLayout(activity);
            strip.setClipChildren(false);
            scroll.addView(strip, new HorizontalScrollView.LayoutParams(-2, -1));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, cardHeight + px(10)));
            empty = text("", 14, TvAppearancePreferences.secondary(activity));
            root.addView(empty, new LinearLayout.LayoutParams(-1, px(34)));
        }

        void update(List<VideoItem> next, boolean loading, boolean disabled) {
            empty.setText(disabled ? "Площадка отключена в настройках поиска"
                    : loading ? "Загрузка видео…" : "Видео пока нет");
            empty.setVisibility(next.isEmpty() ? VISIBLE : GONE);
            scroll.setVisibility(next.isEmpty() ? GONE : VISIBLE);
            if (sameItems(next)) return;
            boolean focused = getFocusedChild() != null;
            String focusedKey = focused && selected < items.size()
                    ? items.get(selected).stableKey() : null;
            items = new ArrayList<>(next);
            strip.removeAllViews();
            slots.clear();
            for (int i = 0; i < items.size(); i++) {
                FrameLayout slot = new FrameLayout(activity);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cardWidth, cardHeight);
                params.rightMargin = gap;
                strip.addView(slot, params);
                slots.add(slot);
                if (focusedKey != null && focusedKey.equals(items.get(i).stableKey())) selected = i;
            }
            selected = Math.max(0, Math.min(selected, items.size() - 1));
            if (focused && !items.isEmpty()) post(() -> focus(selected));
        }

        boolean sameItems(List<VideoItem> next) {
            if (items.size() != next.size()) return false;
            for (int i = 0; i < next.size(); i++)
                if (items.get(i) != next.get(i)) return false;
            return true;
        }

        void populate() {
            if (getVisibility() != VISIBLE || scroll.getWidth() <= 0 || items.isEmpty()) return;
            int start = Math.max(0, scroll.getScrollX() / (cardWidth + gap) - 1);
            int end = Math.min(items.size(),
                    (scroll.getScrollX() + scroll.getWidth()) / (cardWidth + gap) + 2);
            for (int i = 0; i < slots.size(); i++) {
                if (i >= start && i < end) materialize(i);
                else if (slots.get(i).getChildCount() > 0 && !slots.get(i).getChildAt(0).hasFocus())
                    slots.get(i).removeAllViews();
            }
        }

        void clearInvisible() {
            for (FrameLayout slot : slots)
                if (slot.getChildCount() > 0 && !slot.getChildAt(0).hasFocus()) slot.removeAllViews();
        }

        void materialize(int index) {
            if (index < 0 || index >= slots.size() || slots.get(index).getChildCount() != 0) return;
            slots.get(index).addView(card(items.get(index), index),
                    new FrameLayout.LayoutParams(-1, -1));
        }

        void focus(int index) {
            if (index < 0 || index >= items.size()) return;
            selected = index;
            selectedSection = section;
            materialize(index);
            FrameLayout slot = slots.get(index);
            int left = index * (cardWidth + gap);
            scroll.smoothScrollTo(Math.max(0, left - gap), 0);
            smoothScrollTo(0, root.getTop());
            if (slot.getChildCount() > 0) slot.getChildAt(0).requestFocus();
            post(this::populate);
        }

        View card(VideoItem item, int index) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setClipToOutline(true);
            card.setBackground(border(false));
            FrameLayout artwork = new FrameLayout(activity);
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.addView(image, new FrameLayout.LayoutParams(-1, -1));
            ImageView source = new ImageView(activity);
            source.setImageResource(SourceIcon.drawable(item));
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(px(27), px(27),
                    Gravity.TOP | Gravity.LEFT);
            iconParams.setMargins(px(7), px(7), 0, 0);
            artwork.addView(source, iconParams);
            TextView duration = text(VideoPresentation.duration(item.durationMs), 11, Color.WHITE);
            duration.setBackgroundColor(0xe6101216);
            duration.setPadding(px(5), 0, px(5), 0);
            duration.setVisibility(item.durationMs > 0 ? VISIBLE : GONE);
            FrameLayout.LayoutParams durationParams = new FrameLayout.LayoutParams(-2, px(22),
                    Gravity.RIGHT | Gravity.BOTTOM);
            durationParams.setMargins(0, 0, px(7), px(7));
            artwork.addView(duration, durationParams);
            card.addView(artwork, new LinearLayout.LayoutParams(-1,
                    Math.round(cardWidth * 9f / 16f)));
            TextView title = text(item.title, 14, TvAppearancePreferences.text(activity));
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(px(10), px(5), px(10), 0);
            card.addView(title, new LinearLayout.LayoutParams(-1, px(48)));
            TextView channel = text(item.source + " · " + VideoPresentation.channel(item),
                    11, TvAppearancePreferences.secondary(activity));
            channel.setSingleLine(true);
            channel.setEllipsize(TextUtils.TruncateAt.END);
            channel.setPadding(px(10), 0, px(10), 0);
            card.addView(channel, new LinearLayout.LayoutParams(-1, px(23)));
            TextView views = text(VideoPresentation.views(item), 11,
                    TvAppearancePreferences.secondary(activity));
            views.setPadding(px(10), 0, px(10), 0);
            card.addView(views, new LinearLayout.LayoutParams(-1, px(23)));
            ((TubeApplication) activity.getApplication()).imageLoader().load(item.source,
                    item.thumbnail, image, cardWidth, Math.round(cardWidth * 9f / 16f));
            card.setOnFocusChangeListener((view, focused) -> {
                view.setBackground(border(focused));
                view.setElevation(focused ? px(10) : 0);
                if (focused) {
                    selected = index;
                    selectedSection = section;
                    scroll.smoothScrollTo(Math.max(0, index * (cardWidth + gap) - gap), 0);
                    smoothScrollTo(0, root.getTop());
                }
            });
            card.setOnClickListener(view -> actions.play(item));
            card.setOnLongClickListener(view -> { actions.menu(item); return true; });
            card.setOnKeyListener((view, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_MENU) { actions.menu(item); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (index == 0) actions.navigation(); else focus(index - 1);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (index + 1 < items.size()) focus(index + 1);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int direction = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    int rowIndex = TvHomeFeed.ORDER.indexOf(section) + direction;
                    while (rowIndex >= 0 && rowIndex < TvHomeFeed.ORDER.size()) {
                        Row other = rows.get(TvHomeFeed.ORDER.get(rowIndex));
                        if (other != null && !other.items.isEmpty()) {
                            other.focus(Math.min(index, other.items.size() - 1));
                            return true;
                        }
                        rowIndex += direction;
                    }
                    return direction > 0;
                }
                return false;
            });
            return card;
        }

        View getFocusedChild() {
            for (FrameLayout slot : slots) if (slot.getChildCount() > 0
                    && slot.getChildAt(0).hasFocus()) return slot.getChildAt(0);
            return null;
        }
    }

    private GradientDrawable border(boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(focused ? TvAppearancePreferences.focus(activity)
                : TvAppearancePreferences.surface(activity));
        shape.setCornerRadius(px(8));
        shape.setStroke(px(focused ? 3 : 1), focused
                ? TvAppearancePreferences.accent(activity) : Color.TRANSPARENT);
        return shape;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
