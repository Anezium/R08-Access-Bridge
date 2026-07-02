package com.anezium.r08accessbridge;

import android.content.Intent;
import android.util.Log;

/**
 * NewPipe (Rokid build) ships its own single-axis focus engine that already handles list
 * scrolling, the category header, the search keyboard and the player action rail. The generic
 * accessibility walker fights that model (it flattens the fixed header into the video list and
 * wraps to the top on scroll), so instead of driving nodes we forward the ring command to NewPipe
 * as a targeted broadcast and let its own navigator replay it as a touchpad swipe/select.
 */
final class NewPipeCompatibility {
    private static final String TAG = "R08NewPipe";
    private static final String PACKAGE = "com.anezium.rokid.newpipe";
    private static final String ACTION_RING_NAV = "com.anezium.rokid.newpipe.action.RING_NAV";
    private static final String EXTRA_NAV = "nav";
    private static final int NAV_NEXT = 1;
    private static final int NAV_PREV = 2;
    private static final int NAV_SELECT = 3;

    private final RingControlAccessibilityService service;

    NewPipeCompatibility(RingControlAccessibilityService service) {
        this.service = service;
    }

    boolean move(boolean forward) {
        return isActive() && send(forward ? NAV_NEXT : NAV_PREV);
    }

    boolean activate() {
        return isActive() && send(NAV_SELECT);
    }

    private boolean isActive() {
        return AccessibilityWindowRoots.isPackageActive(service, PACKAGE);
    }

    private boolean send(int nav) {
        try {
            Intent intent = new Intent(ACTION_RING_NAV)
                    .setPackage(PACKAGE)
                    .putExtra(EXTRA_NAV, nav);
            service.sendBroadcast(intent);
            Log.d(TAG, "Forwarded ring nav=" + nav + " to NewPipe");
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "NewPipe ring forward failed", exception);
            return false;
        }
    }
}
