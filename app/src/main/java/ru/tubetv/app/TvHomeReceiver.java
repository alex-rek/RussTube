package ru.tubetv.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;

/** Initializes the default channel and remembers cards hidden by the viewer. */
public final class TvHomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!TvHomeChannels.supported() || intent == null) return;
        if (TvContract.ACTION_INITIALIZE_PROGRAMS.equals(intent.getAction())) {
            PendingResult pending = goAsync();
            TvHomeChannels.initialize(context, pending::finish);
        } else if (TvContract.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED.equals(intent.getAction())) {
            long id = intent.getLongExtra(TvContract.EXTRA_PREVIEW_PROGRAM_ID, -1L);
            if (id < 0 && intent.getData() != null) try {
                id = android.content.ContentUris.parseId(intent.getData());
            } catch (RuntimeException ignored) { }
            TvHomeChannels.suppress(context, id);
        }
    }
}
