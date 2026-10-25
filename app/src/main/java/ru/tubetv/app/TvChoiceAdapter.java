package ru.tubetv.app;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

/** Scales TV dialog choices without changing Android's global font preference. */
final class TvChoiceAdapter extends ArrayAdapter<String> {
    private final float scale;

    TvChoiceAdapter(Context context, String[] values) {
        super(context, android.R.layout.select_dialog_singlechoice, values);
        scale = TvScalePreferences.get(context, TvScalePreferences.INTERFACE);
    }

    @Override public View getView(int position, View reused, ViewGroup parent) {
        View view = super.getView(position, reused, parent);
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            label.setTextSize(18 * scale);
            label.setTextColor(Color.WHITE);
            label.setMinHeight(Math.round(52 * scale * getContext().getResources()
                    .getDisplayMetrics().density));
        }
        return view;
    }
}
