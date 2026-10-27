package ru.tubetv.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class MobileLibraryTransferTest {
    @Test public void readsSubscriptionsAndFavoritesWithoutStreamUrls() throws Exception {
        String backup = "{\"format\":\"russtube-library\",\"version\":1,"
                + "\"subscriptions\":[{\"source\":\"VK VIDEO\",\"channelId\":\"42\","
                + "\"title\":\"Канал\",\"play\":\"https://vkvideo.ru/video1_2\"}],"
                + "\"favorites\":[{\"source\":\"RUTUBE\",\"title\":\"Видео\","
                + "\"play\":\"https://rutube.ru/video/abc\"}]}";
        MobileLibraryTransfer.ImportData data = MobileLibraryTransfer.read(
                new ByteArrayInputStream(backup.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, data.channels.size());
        assertEquals("42", data.channels.get(0).channelId);
        assertEquals(1, data.favorites.size());
        assertEquals("https://rutube.ru/video/abc", data.favorites.get(0).playUrl);
    }

    @Test(expected = Exception.class) public void rejectsUnknownBackupFormat() throws Exception {
        MobileLibraryTransfer.read(new ByteArrayInputStream(
                "{\"format\":\"other\",\"version\":1,\"subscriptions\":[],\"favorites\":[]}"
                        .getBytes(StandardCharsets.UTF_8)));
    }
}
