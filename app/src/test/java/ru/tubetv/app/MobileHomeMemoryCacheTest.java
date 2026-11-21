package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class MobileHomeMemoryCacheTest {
    @Test public void snapshotKeepsSelectionForSessionWithoutSharingMutableLists() {
        VideoItem video = new VideoItem("RUTUBE", "first", "", "", "", "https://rutube.ru/video/first/");
        List<VideoItem> visible = new ArrayList<>(Collections.singletonList(video));
        Map<String, List<VideoItem>> rows = Collections.singletonMap("RUTUBE", visible);
        MobileHomeMemoryCache.Snapshot snapshot = new MobileHomeMemoryCache.Snapshot(
                "source", visible, Collections.emptyList(), visible, rows, 1_000);
        visible.clear();
        assertEquals(Collections.singletonList(video), snapshot.visible);
        assertEquals(Collections.singletonList(video), snapshot.rows.get("RUTUBE"));
        assertTrue(snapshot.isReady());
        MobileHomeMemoryCache.save("partial", snapshot.candidates,
                Collections.emptyList(), snapshot.visible, snapshot.rows, 0);
        MobileHomeMemoryCache.Snapshot partial = MobileHomeMemoryCache.get("partial");
        assertEquals(Collections.singletonList(video), partial.visible);
        assertFalse(partial.isReady());
        MobileHomeMemoryCache.save("partial", snapshot.candidates,
                Collections.emptyList(), snapshot.visible, snapshot.rows, 1_000);
        assertTrue(MobileHomeMemoryCache.get("partial").isReady());
    }
}
