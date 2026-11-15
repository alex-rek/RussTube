package ru.tubetv.app;

/** One fresh stream lookup for a VOD that has already played and then lost its source. */
final class PlaybackRecoveryPolicy {
    static boolean shouldRefreshVod(boolean live, boolean reachedReady,
            boolean refreshAttempted, int errorCode) {
        return !live && reachedReady && !refreshAttempted
                && errorCode >= 2000 && errorCode < 3000;
    }

    private PlaybackRecoveryPolicy() { }
}
