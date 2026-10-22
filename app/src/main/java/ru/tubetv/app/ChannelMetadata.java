package ru.tubetv.app;

import org.json.JSONObject;

final class ChannelMetadata {
    static VideoItem attach(VideoItem video, JSONObject owner, boolean live) {
        if (owner == null) return video.withChannel("", "", "", live);
        String id = owner.optString("id");
        String name = owner.optString("name", owner.optString("title"));
        String url = owner.optString("shareLink", owner.optString("url", owner.optString("link")));
        // A display name alone is not a stable channel identity.
        return video.withChannel(id, name, url, live);
    }

    static boolean vkLive(JSONObject video) {
        if (video.optInt("upcoming") == 1) return false;
        String status = video.optString("live_status");
        if (!status.isEmpty()) return "started".equals(status);
        return video.optInt("live") == 1 || video.optInt("live") == 2;
    }
}
