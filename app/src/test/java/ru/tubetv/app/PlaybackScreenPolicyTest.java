package ru.tubetv.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackScreenPolicyTest {
    @Test public void keepsScreenOnForForegroundBufferingAndPlayback() {
        assertTrue(PlaybackScreenPolicy.shouldKeepAwake(true, true, true, 2, false));
        assertTrue(PlaybackScreenPolicy.shouldKeepAwake(true, true, true, 3, false));
    }

    @Test public void releasesScreenForPauseBackgroundFocusLossEndAndFailure() {
        assertFalse(PlaybackScreenPolicy.shouldKeepAwake(true, true, false, 3, false));
        assertFalse(PlaybackScreenPolicy.shouldKeepAwake(false, true, true, 3, false));
        assertFalse(PlaybackScreenPolicy.shouldKeepAwake(true, false, true, 3, false));
        assertFalse(PlaybackScreenPolicy.shouldKeepAwake(true, true, true, 4, false));
        assertFalse(PlaybackScreenPolicy.shouldKeepAwake(true, true, true, 3, true));
    }
}
