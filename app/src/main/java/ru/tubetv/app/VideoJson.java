package ru.tubetv.app;

import org.json.JSONObject;

/** Persistent video identity; never stores resolved, expiring stream URLs. */
final class VideoJson {
    static JSONObject encode(VideoItem v) throws Exception {
        return new JSONObject().put("source", v.source).put("title", v.title)
                .put("subtitle", v.subtitle).put("thumbnail", v.thumbnail)
                .put("play", v.playUrl).put("page", v.pageUrl).put("duration", v.durationMs)
                .put("width", v.maxWidth).put("height", v.maxHeight)
                .put("channelId", v.channelId).put("channelName", v.channelName)
                .put("channelUrl", v.channelUrl).put("live", v.isLive)
                .put("views", v.views).put("publishedAtMs", v.publishedAtMs);
    }

    static VideoItem decode(JSONObject o) {
        return new VideoItem(o.optString("source"), o.optString("title"), o.optString("subtitle"),
                o.optString("thumbnail"), o.optString("play"), o.optString("page"),
                o.optLong("duration")).withQuality(o.optInt("width"), o.optInt("height"))
                .withChannel(o.optString("channelId"), o.optString("channelName"),
                        o.optString("channelUrl"), o.optBoolean("live"))
                .withMetrics(o.optLong("views", -1), o.optLong("publishedAtMs"));
    }
}
