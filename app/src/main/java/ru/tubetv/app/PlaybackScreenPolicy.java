package ru.tubetv.app;

/** Keeps Android TV out of ambient mode only during foreground playback or buffering. */
final class PlaybackScreenPolicy {
    static boolean shouldKeepAwake(boolean started, boolean focused, boolean playWhenReady,
                                   int playbackState, boolean failed) {
        return started && focused && playWhenReady && !failed
                && (playbackState == 2 || playbackState == 3);
    }

    private PlaybackScreenPolicy() { }
}
