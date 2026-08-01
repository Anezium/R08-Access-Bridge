package com.anezium.r08healthtest.gesture;

import static org.junit.Assert.assertEquals;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class GestureLogicTest {
    @Test public void mapsAllPrimaryR08HidKeyFamilies() {
        assertEquals(GestureLogic.Kind.FORWARD, GestureLogic.classify(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertEquals(GestureLogic.Kind.BACKWARD, GestureLogic.classify(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertEquals(GestureLogic.Kind.TAP, GestureLogic.classify(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertEquals(GestureLogic.Kind.BACK, GestureLogic.classify(KeyEvent.KEYCODE_ESCAPE));
    }

    @Test public void bounceIsIgnoredAndDoubleTapResolvesOnce() {
        List<Integer> resolved = new ArrayList<>();
        TapSequenceRecognizer recognizer = new TapSequenceRecognizer(
                new Handler(Looper.getMainLooper()), resolved::add);
        recognizer.onTap(1_000L);
        recognizer.onTap(1_050L);
        recognizer.onTap(1_200L);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(351));
        assertEquals(List.of(2), resolved);
    }

    @Test public void tapSwipeComboConsumesPendingTap() {
        TapSequenceRecognizer recognizer = new TapSequenceRecognizer(
                new Handler(Looper.getMainLooper()), ignored -> {});
        recognizer.onTap(1_000L);
        assertEquals(1, recognizer.takeForCombo(1_400L));
        assertEquals(0, recognizer.pendingCount());
    }
}
