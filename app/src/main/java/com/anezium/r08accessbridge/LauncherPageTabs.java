package com.anezium.r08accessbridge;

import android.graphics.Rect;
import android.graphics.PointF;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** Reads visible launcher pages; scrolling ViewPager2's descendants bypasses its lifecycle. */
final class LauncherPageTabs {
    static final int UNKNOWN = -1;
    static final int MESSAGES = 0;
    static final int HOME = 1;
    static final int APPS = 2;
    private static final String ID_PREFIX = "com.rokid.os.sprite.launcher:id/";

    static Snapshot read(AccessibilityNodeInfo root, Rect viewport, boolean rtl) {
        Inspection inspection = new Inspection(viewport);
        inspect(root, inspection, null, -1, false);
        int page = inspection.complete && Integer.bitCount(inspection.pages) == 1
                ? Integer.numberOfTrailingZeros(inspection.pages) : UNKNOWN;
        return new Snapshot(page, inspection.indicator, rtl, inspection.mainLauncher,
                root != null && inspection.complete,
                page != UNKNOWN && (inspection.alignedPages & (1 << page)) != 0);
    }

    static boolean isVisibleCarousel(AccessibilityNodeInfo node, Rect viewport) {
        Rect bounds = visibleBounds(node, viewport);
        return !bounds.isEmpty() && bounds.width() >= viewport.width() / 2;
    }

    private static Rect visibleBounds(AccessibilityNodeInfo node, Rect viewport) {
        Rect bounds = new Rect();
        if (node != null && node.isVisibleToUser()) {
            node.getBoundsInScreen(bounds);
            if (!bounds.intersect(viewport)) bounds.setEmpty();
        }
        return bounds;
    }

    private static void inspect(AccessibilityNodeInfo node, Inspection inspection,
            Rect pagerBounds, int pagerDepth, boolean pageAligned) {
        if (node == null) return;
        if (inspection.remaining-- <= 0 || SystemClock.uptimeMillis() > inspection.deadline) {
            inspection.complete = false;
            return;
        }
        String id = node.getViewIdResourceName();
        Rect visible = visibleBounds(node, inspection.viewport);
        if ((ID_PREFIX + "viewpager").equals(id)) {
            inspection.mainLauncher = true;
            pagerBounds = visible;
            pagerDepth = 0;
            pageAligned = false;
        } else if ((ID_PREFIX + "indicator").equals(id)
                || (ID_PREFIX + "app_recycler").equals(id)
                || (ID_PREFIX + "main_focus_audio_layout").equals(id)) {
            inspection.mainLauncher = true;
        }
        // ViewPager2 -> inner RecyclerView -> page FrameLayout. A partially visible page
        // can expose its title or carousel before its horizontal transition has finished.
        if (pagerDepth == 2 && pagerBounds != null && !pagerBounds.isEmpty()) {
            pageAligned = !visible.isEmpty()
                    && Math.abs(visible.left - pagerBounds.left) <= 2
                    && Math.abs(visible.right - pagerBounds.right) <= 2;
        }
        if (!visible.isEmpty() && id != null && id.startsWith(ID_PREFIX)) {
            int marker = UNKNOWN;
            switch (id.substring(ID_PREFIX.length())) {
                case "indicator":
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (node.isEnabled() && inspection.viewport.contains(bounds)
                            && bounds.width() >= 3 * bounds.height()) {
                        inspection.indicator.set(bounds);
                    }
                    break;
                case "app_recycler":
                    if (visible.width() >= inspection.viewport.width() / 2) {
                        marker = APPS;
                    }
                    break;
                case "no_message_tip":
                case "today_message":
                case "recycler_view": marker = MESSAGES; break;
                case "no_plan_tips":
                case "permanentWidgetRv":
                case "overlayWidgetRv":
                case "plan_lay":
                case "journey_lay":
                case "connect_app_name": marker = HOME; break;
                default: break;
            }
            if (marker != UNKNOWN) {
                inspection.pages |= 1 << marker;
                if (pageAligned) inspection.alignedPages |= 1 << marker;
                // The container identifies its page. Its app rows / widget contents
                // cannot identify another ViewPager page, and fetching them can exhaust
                // the tree budget while media widgets are updating. Still inspect the
                // container's siblings so conflicting pages and the tabs are checked.
                return;
            }
        }
        for (int i = 0; i < node.getChildCount() && inspection.complete; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                inspection.complete = false;
                break;
            }
            try {
                inspect(child, inspection, pagerBounds, pagerDepth < 0 ? -1 : pagerDepth + 1,
                        pageAligned);
            } finally {
                child.recycle();
            }
        }
    }

    static final class Snapshot {
        final int page;
        final boolean mainLauncher;
        final boolean complete;
        final boolean aligned;
        private final Rect indicator;
        private final boolean rtl;

        Snapshot(int page, Rect indicator, boolean rtl, boolean mainLauncher,
                boolean complete, boolean aligned) {
            this.page = page;
            this.indicator = new Rect(indicator);
            this.rtl = rtl;
            this.mainLauncher = mainLauncher;
            this.complete = complete;
            this.aligned = aligned;
        }

        PointF tab(int target) {
            if (target < MESSAGES || target > APPS || indicator.isEmpty()) return null;
            int physicalIndex = rtl ? APPS - target : target;
            float iconSize = indicator.height();
            float spacing = (indicator.width() - iconSize) / 2f;
            return new PointF(indicator.left + iconSize / 2f + physicalIndex * spacing,
                    indicator.exactCenterY());
        }
    }

    private static final class Inspection {
        final Rect viewport;
        final Rect indicator = new Rect();
        final long deadline = SystemClock.uptimeMillis() + 150L;
        int remaining = 90;
        int pages;
        int alignedPages;
        boolean mainLauncher;
        boolean complete = true;

        Inspection(Rect viewport) {
            this.viewport = viewport;
        }
    }
}
