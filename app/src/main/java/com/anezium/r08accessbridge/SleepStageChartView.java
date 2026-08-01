package com.anezium.r08accessbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.anezium.ringhealth.SleepSession;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class SleepStageChartView extends View {
    private static final SleepSession.Stage[] STAGE_ORDER = {
            SleepSession.Stage.AWAKE,
            SleepSession.Stage.REM,
            SleepSession.Stage.LIGHT,
            SleepSession.Stage.DEEP
    };

    private final Paint grid = paint(Color.rgb(52, 69, 61), 1f);
    private final Paint line = paint(Color.rgb(102, 242, 165), 3f);
    private final Paint text = paint(Color.rgb(161, 183, 172), 1f);
    private final Paint empty = paint(Color.rgb(197, 218, 208), 1f);
    private SleepSession session;

    SleepStageChartView(Context context) {
        super(context);
        initialize();
    }

    SleepStageChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    void setSession(SleepSession session) {
        this.session = session;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (session == null) return;

        float left = dp(48);
        float right = getWidth() - dp(8);
        float top = dp(15);
        float bottom = getHeight() - dp(25);
        drawTimeAxis(canvas, left, right);

        if (!session.stages().isEmpty()) {
            drawStageGrid(canvas, left, right, top, bottom);
            drawStages(canvas, session.stages(), left, right, top, bottom);
        } else if (!session.sleepIntervals().isEmpty()) {
            drawIntervalGrid(canvas, left, right, top, bottom);
            drawIntervals(canvas, session.sleepIntervals(), left, right, top, bottom);
        } else {
            canvas.drawText("No stage timeline", left + dp(12),
                    (top + bottom) / 2f, empty);
        }
    }

    private void drawStageGrid(Canvas canvas, float left, float right,
                               float top, float bottom) {
        for (int index = 0; index < STAGE_ORDER.length; index++) {
            float y = stageY(index, top, bottom, STAGE_ORDER.length);
            canvas.drawLine(left, y, right, y, grid);
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(shortStageLabel(STAGE_ORDER[index]), left - dp(5), y + dp(3), text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawStages(Canvas canvas, List<SleepSession.Segment> stages,
                            float left, float right, float top, float bottom) {
        float previousY = Float.NaN;
        float previousEndX = Float.NaN;
        for (SleepSession.Segment segment : stages) {
            float startX = timelineX(segment.startEpochMs(), left, right);
            float endX = timelineX(segment.endEpochMs(), left, right);
            float y = stageY(stageBand(segment.stage()), top, bottom, STAGE_ORDER.length);
            if (!Float.isNaN(previousY) && Math.abs(startX - previousEndX) <= dp(2)) {
                canvas.drawLine(startX, previousY, startX, y, line);
            }
            canvas.drawLine(startX, y, Math.max(startX + dp(1), endX), y, line);
            previousY = y;
            previousEndX = endX;
        }
    }

    private void drawIntervalGrid(Canvas canvas, float left, float right,
                                  float top, float bottom) {
        String[] labels = {"Awake", "Sleep"};
        for (int index = 0; index < labels.length; index++) {
            float y = stageY(index, top, bottom, labels.length);
            canvas.drawLine(left, y, right, y, grid);
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(labels[index], left - dp(5), y + dp(3), text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawIntervals(Canvas canvas, List<SleepSession.Interval> intervals,
                               float left, float right, float top, float bottom) {
        float awakeY = stageY(0, top, bottom, 2);
        float sleepY = stageY(1, top, bottom, 2);
        long cursor = session.startEpochMs();
        for (SleepSession.Interval interval : intervals) {
            if (interval.startEpochMs() > cursor) {
                canvas.drawLine(timelineX(cursor, left, right), awakeY,
                        timelineX(interval.startEpochMs(), left, right), awakeY, line);
            }
            float startX = timelineX(interval.startEpochMs(), left, right);
            float endX = timelineX(interval.endEpochMs(), left, right);
            canvas.drawLine(startX, awakeY, startX, sleepY, line);
            canvas.drawLine(startX, sleepY, Math.max(startX + dp(1), endX), sleepY, line);
            cursor = Math.max(cursor, interval.endEpochMs());
        }
        if (cursor < session.endEpochMs()) {
            float startX = timelineX(cursor, left, right);
            canvas.drawLine(startX, sleepY, startX, awakeY, line);
            canvas.drawLine(startX, awakeY, right, awakeY, line);
        }
    }

    private void drawTimeAxis(Canvas canvas, float left, float right) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.US);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(format.format(new Date(session.startEpochMs())),
                left, getHeight() - dp(7), text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(format.format(new Date(session.endEpochMs())),
                right, getHeight() - dp(7), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private float timelineX(long epochMs, float left, float right) {
        return left + (right - left) * timelineFraction(
                epochMs, session.startEpochMs(), session.endEpochMs());
    }

    static float timelineFraction(long epochMs, long startEpochMs, long endEpochMs) {
        if (endEpochMs <= startEpochMs) return 0f;
        float fraction = (epochMs - startEpochMs) / (float) (endEpochMs - startEpochMs);
        return Math.max(0f, Math.min(1f, fraction));
    }

    static int stageBand(SleepSession.Stage stage) {
        return switch (stage) {
            case AWAKE -> 0;
            case REM -> 1;
            case LIGHT, UNKNOWN -> 2;
            case DEEP -> 3;
        };
    }

    private static String shortStageLabel(SleepSession.Stage stage) {
        return switch (stage) {
            case AWAKE -> "Awake";
            case REM -> "REM";
            case LIGHT -> "Light";
            case DEEP -> "Deep";
            case UNKNOWN -> "Other";
        };
    }

    private static float stageY(int index, float top, float bottom, int count) {
        if (count <= 1) return (top + bottom) / 2f;
        return top + (bottom - top) * index / (count - 1f);
    }

    private void initialize() {
        text.setTextSize(dp(9));
        empty.setTextSize(dp(11));
        line.setStrokeCap(Paint.Cap.SQUARE);
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
