package ru.tubetv.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.PathParser;

final class TvIcons {
    static Drawable icon(String name, int color, int size) {
        String data;
        switch (name) {
            case "home": data = "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"; break;
            case "popular": data = "M13,2c1,5 -4,6 -2,10c-4,-1 -3,-4 -3,-4c-7,8 -2,14 4,14c8,0 11,-12 1,-20z"; break;
            case "films": data = "M2,4h20v16H2z M6,4v16 M18,4v16 M2,9h4 M2,15h4 M18,9h4 M18,15h4 M9,8l7,4 -7,4z"; break;
            case "recommend": data = "M12,2l3,6 7,1 -5,5 1,7 -6,-3 -6,3 1,-7 -5,-5 7,-1z"; break;
            case "live": data = "M3,6h18v14H3z M5,8v10h14V8z M8,1l4,4 4,-4 1,1 -5,5 -5,-5z M10,10l5,3 -5,3z"; break;
            case "subscriptions": data = "M5,2h14v2H5z M3,6h18v2H3z M2,10h20v12H2z M10,12v8l6,-4z"; break;
            case "channels": data = "M4,3h17v15H4z M6,5v11h13V5z M1,6h2v15h15v2H1z M10,7l5,3 -5,3z"; break;
            case "history": data = "M12,3a9,9 0,1 1,-8,13l2,-1a7,7 0,1 0,1,-9L10,9H2V1l3,3a9,9 0,0 1,7,-1z M11,7h2v5l4,2 -1,2 -5,-3z"; break;
            case "favorite": data = "M12,21L3,12C-4,4 6,-1 12,6C18,-1 28,4 21,12z"; break;
            case "search": data = "M10,2a8,8 0,1 0,5,14l6,6 2,-2 -6,-6A8,8 0,0 0,10,2z M10,4a6,6 0,1 1,0,12a6,6 0,1 1,0,-12z"; break;
            case "settings": data = "M9,2h6l1,4 4,1 3,5 -3,5 -4,1 -1,4H9l-1,-4 -4,-1 -3,-5 3,-5 4,-1z M12,8a4,4 0,1 0,0,8a4,4 0,1 0,0,-8z"; break;
            default: data = "M3,5h18v2H3z M3,11h18v2H3z M3,17h18v2H3z";
        }
        Path path = PathParser.createPathFromPathData(data);
        path.setFillType(Path.FillType.EVEN_ODD);
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            { paint.setColor(color); setBounds(0, 0, size, size); }
            @Override public void draw(Canvas canvas) {
                int saved = canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                canvas.drawPath(path, paint); canvas.restoreToCount(saved);
            }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            @Override public int getIntrinsicWidth() { return size; }
            @Override public int getIntrinsicHeight() { return size; }
        };
    }
    private TvIcons() { }
}
