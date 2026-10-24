package ru.tubetv.app;

import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

/** Routes headset play/pause presses to the current mobile playback owner. */
final class HeadsetMediaControls {
    private final MediaSession session;

    HeadsetMediaControls(Context context, Runnable play, Runnable pause, Runnable toggle) {
        session = new MediaSession(context, "RussTube playback");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play.run(); }
            @Override public void onPause() { pause.run(); }
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent event = intent == null ? null
                        : intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null) return false;
                int code = event.getKeyCode();
                if (code != KeyEvent.KEYCODE_HEADSETHOOK
                        && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        && code != KeyEvent.KEYCODE_MEDIA_PLAY
                        && code != KeyEvent.KEYCODE_MEDIA_PAUSE) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    if (code == KeyEvent.KEYCODE_MEDIA_PLAY) play.run();
                    else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) pause.run();
                    else toggle.run();
                }
                return true;
            }
        });
        update(false, 0);
    }

    void setActive(boolean active) { session.setActive(active); }

    void update(boolean playing, long positionMs) {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        Math.max(0, positionMs), playing ? 1f : 0f)
                .build());
    }

    void release() { session.setActive(false); session.release(); }
}
