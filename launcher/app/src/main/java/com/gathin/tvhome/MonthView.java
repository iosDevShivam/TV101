package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * The screensaver's month: "November  2026", the weekday initials and the days, drawn in one pass by one view
 * instead of 50 TextViews. Past days are faint, weekends dimmer than weekdays, today the only colour. It draws
 * only when the day changes (refresh(), called by ClockScene once a minute, returns at once otherwise).
 */
public final class MonthView extends View {
    private static final int ACCENT = 0xFFFF6B6B;
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG), year = new Paint(Paint.ANTI_ALIAS_FLAG),
            weekday = new Paint(Paint.ANTI_ALIAS_FLAG), day = new Paint(Paint.ANTI_ALIAS_FLAG),
            todayDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dot = new RectF();
    private final float d;
    private final float cell, row, header, weekdays;
    private LocalDate shown;

    public MonthView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        d = getResources().getDisplayMetrics().density;
        float sp = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 1,
                getResources().getDisplayMetrics());
        cell = 29 * d;
        row = 34 * d;
        header = 30 * d;
        weekdays = 26 * d;
        Typeface regular = getResources().getFont(R.font.outfit_regular);
        Typeface medium = getResources().getFont(R.font.outfit_medium);
        title.setTypeface(medium);
        title.setTextSize(14 * sp);
        title.setColor(0xBFFFFFFF);
        year.setTypeface(regular);
        year.setTextSize(14 * sp);
        year.setColor(0x59FFFFFF);
        year.setTextAlign(Paint.Align.RIGHT);
        weekday.setTypeface(medium);
        weekday.setTextSize(9 * sp);
        weekday.setColor(0x52FFFFFF);
        weekday.setTextAlign(Paint.Align.CENTER);
        day.setTypeface(regular);
        day.setTextSize(13 * sp);
        day.setTextAlign(Paint.Align.CENTER);
        todayDot.setColor(0x1FFFFFFF);
        shown = LocalDate.now();
    }

    /** Redraws only when the date has moved on since the last draw. */
    void refresh() {
        if (!LocalDate.now().equals(shown)) {
            shown = LocalDate.now();
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension((int) (7 * cell), (int) (header + weekdays + 6 * row));
    }

    @Override
    protected void onDraw(Canvas c) {
        LocalDate today = shown;
        LocalDate first = today.withDayOfMonth(1);
        float w = 7 * cell;
        float base = -title.ascent();
        c.drawText(today.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()), 2 * d, base, title);
        c.drawText(String.valueOf(today.getYear()), w - 2 * d, base, year);
        // Weeks start on Sunday, like the calendar the screensaver is modelled on.
        float wy = header + -weekday.ascent();
        for (int i = 0; i < 7; i++) {
            DayOfWeek dow = DayOfWeek.SUNDAY.plus(i);
            String initial = dow.getDisplayName(TextStyle.NARROW, Locale.getDefault());
            c.drawText(initial, i * cell + cell / 2, wy, weekday);
        }
        int lead = first.getDayOfWeek().getValue() % 7;   // Sunday 0 ... Saturday 6
        int days = today.lengthOfMonth();
        float top = header + weekdays;
        float centre = (day.descent() + day.ascent()) / 2;
        for (int n = 1; n <= days; n++) {
            int slot = lead + n - 1;
            int col = slot % 7, line = slot / 7;
            float cx = col * cell + cell / 2, cy = top + line * row + row / 2;
            boolean weekend = col == 0 || col == 6;
            if (n == today.getDayOfMonth()) {
                dot.set(cx - 12 * d, cy - 12 * d, cx + 12 * d, cy + 12 * d);
                c.drawOval(dot, todayDot);
                day.setColor(ACCENT);
                day.setFakeBoldText(true);
            } else {
                day.setFakeBoldText(false);
                day.setColor(n < today.getDayOfMonth() ? 0x38FFFFFF : weekend ? 0x6BFFFFFF : 0xA6FFFFFF);
            }
            c.drawText(String.valueOf(n), cx, cy - centre, day);
        }
        day.setFakeBoldText(false);
    }
}
