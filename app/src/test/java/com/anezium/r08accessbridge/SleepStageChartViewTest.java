package com.anezium.r08accessbridge;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import com.anezium.ringhealth.SleepSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SleepStageChartViewTest {
    @Test public void rendersStageTimelineFromDecodedSegments() {
        Context context = RuntimeEnvironment.getApplication();
        long start = 1_750_000_000_000L;
        SleepSession session = new SleepSession(1L, "R08", SleepSession.Kind.NIGHT,
                start, start + 180L * 60_000L, 165, 60, 55, 50, 15,
                List.of(
                        segment(SleepSession.Stage.LIGHT, start, 60),
                        segment(SleepSession.Stage.DEEP, start + 60L * 60_000L, 55),
                        segment(SleepSession.Stage.REM, start + 115L * 60_000L, 50),
                        segment(SleepSession.Stage.AWAKE, start + 165L * 60_000L, 15)),
                List.of(new SleepSession.Interval(start, start + 165L * 60_000L)));

        SleepStageChartView view = new SleepStageChartView(context);
        view.setSession(session);
        view.layout(0, 0, 444, 150);
        Bitmap bitmap = Bitmap.createBitmap(444, 150, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));

        int graphPixels = 0;
        int graphColor = Color.rgb(102, 242, 165);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == graphColor) graphPixels++;
            }
        }
        assertTrue("Expected the sleep-stage path to be rendered", graphPixels > 100);
    }

    private static SleepSession.Segment segment(SleepSession.Stage stage,
                                                long startEpochMs, int minutes) {
        return new SleepSession.Segment(stage, startEpochMs,
                startEpochMs + minutes * 60_000L, minutes);
    }
}
