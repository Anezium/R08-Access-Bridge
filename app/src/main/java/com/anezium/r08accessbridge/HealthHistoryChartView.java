package com.anezium.r08accessbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.AttributeSet;
import android.view.View;

import com.anezium.ringhealth.HealthSample;
import com.anezium.ringhealth.domain.HealthMetric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

final class HealthHistoryChartView extends View {
    private final Paint grid = paint(Color.rgb(52, 69, 61), 1f);
    private final Paint line = paint(Color.rgb(102, 242, 165), 3f);
    private final Paint dot = paint(Color.rgb(102, 242, 165), 1f);
    private final Paint text = paint(Color.rgb(161, 183, 172), 1f);
    private final Paint empty = paint(Color.rgb(197, 218, 208), 1f);
    private final List<HealthSample> history = new ArrayList<>();
    private final List<HealthSample> samples = new ArrayList<>();
    private HealthMetric metric = HealthMetric.HEART_RATE;
    private HealthChartRange range = HealthChartRange.LAST_12_HOURS;
    private long rangeEndEpochMs = System.currentTimeMillis();
    private boolean fahrenheit;

    HealthHistoryChartView(Context context) { super(context); initialize(); }
    HealthHistoryChartView(Context context, AttributeSet attrs) { super(context, attrs); initialize(); }

    void setData(List<HealthSample> history, HealthMetric metric, boolean fahrenheit) {
        this.metric = metric;
        this.fahrenheit = fahrenheit;
        this.history.clear();
        this.history.addAll(history);
        applyRange(System.currentTimeMillis());
    }

    void setRange(HealthChartRange range) {
        this.range = Objects.requireNonNull(range);
        applyRange(System.currentTimeMillis());
    }

    HealthChartRange getRange() {
        return range;
    }

    HealthChartRange cycleRange() {
        setRange(range.next());
        return range;
    }

    private void applyRange(long endEpochMs) {
        rangeEndEpochMs = endEpochMs;
        samples.clear();
        for (HealthSample sample : history) {
            if (sample.metric() == metric && range.includes(sample.observedAtEpochMs(), endEpochMs)) {
                samples.add(sample);
            }
        }
        samples.sort(Comparator.comparingLong(HealthSample::observedAtEpochMs));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(38), right = getWidth() - dp(8);
        float top = dp(10), bottom = getHeight() - dp(24);
        for (int index = 0; index <= 4; index++) {
            float x = left + (right - left) * index / 4f;
            canvas.drawLine(x, top, x, bottom, grid);
            long time = range.cutoffEpochMs(rangeEndEpochMs)
                    + range.durationMs() * index / 4L;
            text.setTextAlign(index == 0 ? Paint.Align.LEFT
                    : index == 4 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            String label = range.tickLabel(time, Locale.US, TimeZone.getDefault());
            canvas.drawText(label, x, getHeight() - dp(6), text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        for (int index = 0; index <= 3; index++) {
            float y = top + (bottom - top) * index / 3f;
            canvas.drawLine(left, y, right, y, grid);
        }
        if (samples.isEmpty()) {
            canvas.drawText(range.emptyMessage(), left + dp(12), (top + bottom) / 2, empty);
            return;
        }
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (HealthSample sample : samples) {
            double value = displayValue(sample.value());
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (max - min < 0.01) {
            min -= metric == HealthMetric.TEMPERATURE ? 0.5 : 2.0;
            max += metric == HealthMetric.TEMPERATURE ? 0.5 : 2.0;
        } else {
            double padding = (max - min) * 0.12;
            min -= padding;
            max += padding;
        }
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(axisLabel(max), left - dp(4), top + dp(5), text);
        canvas.drawText(axisLabel(min), left - dp(4), bottom, text);
        text.setTextAlign(Paint.Align.LEFT);

        long windowStart = range.cutoffEpochMs(rangeEndEpochMs);
        Path path = new Path();
        for (int index = 0; index < samples.size(); index++) {
            HealthSample sample = samples.get(index);
            float x = left + (right - left)
                    * Math.max(0f, Math.min(1f, (sample.observedAtEpochMs() - windowStart)
                    / (float) range.durationMs()));
            float y = bottom - (float) ((displayValue(sample.value()) - min) / (max - min)) * (bottom - top);
            if (index == 0) path.moveTo(x, y); else path.lineTo(x, y);
            canvas.drawCircle(x, y, dp(2.5f), dot);
        }
        if (samples.size() > 1) canvas.drawPath(path, line);
    }

    private void initialize() {
        StateListDrawable outlines = new StateListDrawable();
        outlines.addState(new int[]{android.R.attr.state_focused}, outline(true));
        outlines.addState(new int[]{android.R.attr.state_pressed}, outline(true));
        outlines.addState(new int[]{}, outline(false));
        setBackground(outlines);
        text.setTextSize(dp(9));
        empty.setTextSize(dp(11));
        line.setStyle(Paint.Style.STROKE);
        dot.setStyle(Paint.Style.FILL);
    }

    private GradientDrawable outline(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? Color.rgb(18, 24, 21) : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(Math.max(1, Math.round(dp(focused ? 3 : 1))),
                focused ? Color.rgb(102, 242, 165) : Color.rgb(117, 142, 130));
        return drawable;
    }

    private double displayValue(double value) {
        return metric == HealthMetric.TEMPERATURE && fahrenheit ? value * 9.0 / 5.0 + 32.0 : value;
    }

    private String axisLabel(double value) {
        return metric == HealthMetric.TEMPERATURE
                ? String.format(Locale.US, "%.1f", value)
                : Long.toString(Math.round(value));
    }

    private static Paint paint(int color, float strokeWidth) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidth);
        return paint;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
