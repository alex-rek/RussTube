package ru.tubetv.app;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public final class VideoCommentsTest {
    @Test public void rutubeKeepsPublicCommentCountAndSkipsDeletedEntries() throws Exception {
        JSONObject response = new JSONObject("{\"comments_count\":1042,\"results\":["
                + "{\"text\":\"Первый комментарий\",\"user\":{\"name\":\"Автор\"}},"
                + "{\"text\":\"Удалён\",\"is_deleted\":true,\"user\":{\"name\":\"Другой\"}}]}");
        VideoComments.Page page = VideoComments.parse("RUTUBE", response);
        assertEquals(1042, page.total);
        assertEquals(1, page.entries.size());
        assertEquals("Автор", page.entries.get(0).author);
    }

    @Test public void peertubeUsesCommentThreadResponse() throws Exception {
        JSONObject response = new JSONObject("{\"totalNotDeletedComments\":2,\"data\":["
                + "{\"text\":\"Hello\",\"account\":{\"displayName\":\"Alice\"}}]}");
        VideoComments.Page page = VideoComments.parse("PEERTUBE", response);
        assertEquals(2, page.total);
        assertEquals("Hello", page.entries.get(0).text);
        assertEquals("Alice", page.entries.get(0).author);
    }
}
