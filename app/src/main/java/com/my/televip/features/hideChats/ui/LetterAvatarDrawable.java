package com.my.televip.features.hideChats.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * Fallback avatar (colored circle with initials) used when Telegram's avatar views are unavailable.
 */
public class LetterAvatarDrawable extends Drawable {

    private static final int[] COLORS = {
            0xFFE56555, 0xFFF28C48, 0xFF8E85EE, 0xFF76C84D, 0xFF5FBED5, 0xFF549CDD, 0xFFF2749A
    };

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();
    private String letters = "";

    public LetterAvatarDrawable() {
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setInfo(long id, String title) {
        circlePaint.setColor(COLORS[(int) (Math.abs(id) % COLORS.length)]);
        letters = initials(title);
        invalidateSelf();
    }

    private static String initials(String title) {
        if (title == null) return "";
        String[] words = title.trim().split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            builder.appendCodePoint(word.codePointAt(0));
            if (builder.length() >= 2 || words.length == 1) break;
        }
        return builder.toString().toUpperCase();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, circlePaint);
        if (letters.isEmpty()) return;
        textPaint.setTextSize(radius * 0.8f);
        textPaint.getTextBounds(letters, 0, letters.length(), textBounds);
        canvas.drawText(letters, bounds.exactCenterX(), bounds.exactCenterY() - textBounds.exactCenterY(), textPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        circlePaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        circlePaint.setColorFilter(colorFilter);
        textPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
