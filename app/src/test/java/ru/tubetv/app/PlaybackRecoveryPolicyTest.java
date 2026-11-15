package ru.tubetv.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackRecoveryPolicyTest {
    @Test public void refreshesVodOnlyOnceAfterPlaybackHasStarted() {
        assertTrue(PlaybackRecoveryPolicy.shouldRefreshVod(false, true, false, 2004));
        assertFalse(PlaybackRecoveryPolicy.shouldRefreshVod(false, true, true, 2004));
        assertFalse(PlaybackRecoveryPolicy.shouldRefreshVod(false, false, false, 2004));
    }

    @Test public void leavesLiveAndDecoderFailuresToTheirExistingHandlers() {
        assertFalse(PlaybackRecoveryPolicy.shouldRefreshVod(true, true, false, 2004));
        assertFalse(PlaybackRecoveryPolicy.shouldRefreshVod(false, true, false, 4003));
    }
}
