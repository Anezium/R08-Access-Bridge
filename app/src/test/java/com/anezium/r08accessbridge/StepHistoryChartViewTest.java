package com.anezium.r08accessbridge;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import com.anezium.ringhealth.StepDay;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.LocalDate;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public final class StepHistoryChartViewTest {
    @Test public void chartSupportsSevenAndThirtyDayWidths() {
        Context context = RuntimeEnvironment.getApplication();
        StepHistoryChartView chart = new StepHistoryChartView(context);
        StepDay today = new StepDay(1L, "R08", LocalDate.now().toString(),
                12_345, 0, 0, 0, 0, System.currentTimeMillis());

        chart.setData(List.of(today), 7);
        assertEquals(7, chart.getDays());
        chart.setData(List.of(today), 30);
        assertEquals(30, chart.getDays());
    }

    @Test(expected = IllegalArgumentException.class)
    public void chartRejectsSessionLikeArbitraryWidths() {
        StepHistoryChartView chart = new StepHistoryChartView(RuntimeEnvironment.getApplication());
        chart.setData(List.of(), 14);
    }
}
