package ru.tubetv.app;

import java.util.ArrayList;
import java.util.List;

final class FeedRepository {
    static final String[] SOURCES = {"RUTUBE", "VK VIDEO", PeerTubeClient.SOURCE, "OK", "ДЗЕН"};

    List<VideoItem> live(String source, String query) throws Exception {
        return PeerTubeClient.SOURCE.equals(source) ? PeerTubeFeedClient.live(query)
                : "VK VIDEO".equals(source) ? new VkWebClient().searchLive(query)
                : liveOnly(search(source, query));
    }

    List<VideoItem> search(String source, String query) throws Exception {
        SearchClient client = new SearchClient();
        switch (source) {
            case "RUTUBE": return client.searchRutube(query, 0);
            case "VK VIDEO": return new VkWebClient().search(query, 0, 480);
            case "OK": return client.searchOk(query, 0);
            case PeerTubeClient.SOURCE: return PeerTubeFeedClient.search(query);
            case "ДЗЕН":
                List<VideoItem> items = new ArrayList<>();
                client.searchDzenPages(query, page -> {
                    items.addAll(page);
                    return !Thread.currentThread().isInterrupted();
                });
                return items;
            default: throw new Exception("Неизвестная платформа");
        }
    }

    List<VideoItem> channel(VideoItem channel) throws Exception {
        List<VideoItem> candidates;
        switch (channel.source) {
            case "RUTUBE":
                candidates = new SearchClient().channelRutube(channel.channelId);
                break;
            case "VK VIDEO":
                candidates = VkWebClient.channelVideos(channel.channelId);
                break;
            case "OK":
                candidates = OkClient.channelVideos(channel);
                break;
            case PeerTubeClient.SOURCE:
                candidates = PeerTubeFeedClient.channelVideos(channel);
                break;
            default:
                if (channel.channelName.isEmpty()) throw new Exception("Неизвестно название канала");
                candidates = search(channel.source, channel.channelName);
        }
        List<VideoItem> exact = new ArrayList<>();
        for (VideoItem item : candidates) {
            if (channel.channelKey().equals(item.channelKey())) exact.add(item);
        }
        return exact;
    }

    static List<VideoItem> liveOnly(List<VideoItem> candidates) {
        List<VideoItem> result = new ArrayList<>();
        for (VideoItem item : candidates) if (item.isLive) result.add(item);
        return result;
    }
}
