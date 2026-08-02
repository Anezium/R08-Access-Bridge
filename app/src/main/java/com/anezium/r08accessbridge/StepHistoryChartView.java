package com.anezium.r08accessbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.AttributeSet;
import android.view.View;

import com.anezium.ringhealth.StepDay;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class StepHistoryChartView extends View {
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("d MMM", Locale.US);
    private final Paint grid = paint(Color.rgb(52, 69, 61));
    private final Paint bar = paint(Color.rgb(102, 242, 165));
    private final Paint text = paint(Color.rgb(161, 183, 172));
    private final Paint value = paint(Color.rgb(248, 250, 249));
    private final Map<String, Integer> stepsByDate = new HashMap<>();
    private int days = 7;

    StepHistoryChartView(Context context) { super(context); initialize(); }
    StepHistoryChartView(Context context, AttributeSet attrs) { super(context, attrs); initialize(); }

    void setData(List<StepDay> history, int days) {
        if (days != 7 && days != 30) throw new IllegalArgumentException("Step range must be 7 or 30 days");
        this.days = days;
        stepsByDate.clear();
        for (StepDay day : history) stepsByDate.put(day.localDate(), Math.max(0, day.steps()));
        invalidate();
    }

    int getDays() { return days; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(38), right = getWidth() - dp(8);
        float top = dp(24), bottom = getHeight() - dp(24);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1L);
        int maximum = 0;
        for (int index = 0; index < days; index++) {
            maximum = Math.max(maximum, stepsByDate.getOrDefault(start.plusDays(index).toString(), 0));
        }
        int axisMaximum = Math.max(1_000, roundAxisMaximum(maximum));

        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Daily steps", left, dp(15), value);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(days + " days", right, dp(15), text);

        for (int index = 0; index <= 3; index++) {
            float y = top + (bottom - top) * index / 3f;
            canvas.drawLine(left, y, right, y, grid);
        }
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(Integer.toString(axisMaximum), left - dp(4), top + dp(4), text);
        canvas.drawText("0", left - dp(4), bottom, text);

        float slot = (right - left) / days;
        float barWidth = Math.max(dp(2), slot * (days == 7 ? 0.58f : 0.52f));
        for (int index = 0; index < days; index++) {
            LocalDate date = start.plusDays(index);
            int steps = stepsByDate.getOrDefault(date.toString(), 0);
            float center = left + slot * (index + 0.5f);
            float height = (bottom - top) * Math.min(1f, steps / (float) axisMaximum);
            if (steps > 0) {
                canvas.drawRoundRect(center - barWidth / 2f, bottom - height,
                        center + barWidth / 2f, bottom, dp(1.5f), dp(1.5f), bar);
            }
        }

        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(DAY_LABEL.format(start), left, getHeight() - dp(6), text);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(DAY_LABEL.format(start.plusDays((days - 1L) / 2L)),
                (left + right) / 2f, getHeight() - dp(6), text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(DAY_LABEL.format(end), right, getHeight() - dp(6), text);
    }

    private void initialize() {
        StateListDrawable outlines = new StateListDrawable();
        outlines.addState(new int[]{android.R.attr.state_focused}, outline(true));
        outlines.addState(new int[]{android.R.attr.state_pressed}, outline(true));
        outlines.addState(new int[]{}, outline(false));
        setBackground(outlines);
        grid.setStrokeWidth(dp(1));
        bar.setStyle(Paint.Style.FILL);
        text.setTextSize(dp(9));
        value.setTextSize(dp(11));
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    private GradientDrawable outline(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? Color.rgb(18, 24, 21) : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(Math.max(1, Math.round(dp(focused ? 3 : 1))),
                focused ? Color.rgb(102, 242, 165) : Color.rgb(117, 142, 130));
        return drawable;
    }

    private static int roundAxisMaximum(int maximum) {
        int magnitude = maximum >= 10_000 ? 5_000 : maximum >= 5_000 ? 2_000 : 1_000;
        return ((maximum + magnitude - 1) / magnitude) * magnitude;
    }

    private static Paint paint(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        return paint;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
