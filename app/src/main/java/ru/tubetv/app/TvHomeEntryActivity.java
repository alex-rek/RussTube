package ru.tubetv.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Narrow exported entry point for Android TV preview cards. */
public final class TvHomeEntryActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (BuildConfig.MOBILE_VARIANT) { finish(); return; }
        VideoItem item = TvHomeChannels.resolve(this, getIntent().getData());
        if (item != null) VideoActions.play(this, item);
        else {
            Intent browse = new Intent(this, BrowseActivity.class);
            if (getIntent().getData() != null
                    && "russtube".equals(getIntent().getData().getScheme())
                    && "home".equals(getIntent().getData().getHost())
                    && getIntent().getData().getPathSegments().size() == 1)
                browse.putExtra("open_section", getIntent().getData().getPathSegments().get(0));
            startActivity(browse);
        }
        finish();
    }
}
