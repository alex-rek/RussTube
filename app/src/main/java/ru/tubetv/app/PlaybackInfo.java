package ru.tubetv.app;

import androidx.media3.common.MediaItem;
import java.util.Collections;
import java.util.List;

final class PlaybackInfo {
    final String streamUrl;
    final String streamMimeType;
    final int maxWidth;
    final int maxHeight;
    final List<MediaItem.SubtitleConfiguration> subtitles;

    PlaybackInfo(String streamUrl, int maxWidth, int maxHeight) {
        this(streamUrl, null, maxWidth, maxHeight);
    }

    PlaybackInfo(String streamUrl, String streamMimeType, int maxWidth, int maxHeight) {
        this(streamUrl, streamMimeType, maxWidth, maxHeight, Collections.emptyList());
    }

    PlaybackInfo(String streamUrl, String streamMimeType, int maxWidth, int maxHeight,
                 List<MediaItem.SubtitleConfiguration> subtitles) {
        this.streamUrl = streamUrl;
        this.streamMimeType = streamMimeType;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.subtitles = subtitles;
    }

    MediaCapabilities capabilities() { return new MediaCapabilities(maxWidth, maxHeight); }
}
