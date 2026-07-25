package com.anezium.r08accessbridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RingInputPresenceTest {
    @Test
    public void isRingNameMatchesR08NamesCaseInsensitively() {
        assertTrue(RingInputPresence.isRingName("R08"));
        assertTrue(RingInputPresence.isRingName("r08 ring"));
        assertTrue(RingInputPresence.isRingName("QRING-R08X"));
    }

    @Test
    public void isRingNameRejectsNonR08Names() {
        assertFalse(RingInputPresence.isRingName(null));
        assertFalse(RingInputPresence.isRingName(""));
        assertFalse(RingInputPresence.isRingName("QRing"));
        assertFalse(RingInputPresence.isRingName("D06"));
    }
}
