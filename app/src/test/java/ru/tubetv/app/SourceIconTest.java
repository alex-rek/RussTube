package ru.tubetv.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SourceIconTest {
    @Test public void vkLiveUsesItsOwnArtwork() {
        VideoItem recorded = new VideoItem("VK VIDEO", "Запись", "", "", "", "")
                .withChannel("channel", "Автор", "", false);
        VideoItem live = recorded.withChannel("channel", "Автор", "", true);

        assertEquals(R.drawable.source_vk_video, SourceIcon.drawable(recorded));
        assertEquals(R.drawable.source_vk_video_live, SourceIcon.drawable(live));
    }

    @Test public void otherLiveSourcesKeepTheirPlatformArtwork() {
        VideoItem live = new VideoItem("RUTUBE", "Эфир", "", "", "", "")
                .withChannel("channel", "Автор", "", true);
        assertEquals(R.drawable.source_rutube, SourceIcon.drawable(live));
    }
}
