package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public final class LauncherPageTabsTest {
    private static final Rect SCREEN = new Rect(0, 0, 480, 640);
    private static final Rect TABS = new Rect(196, 318, 284, 342);

    @Test public void nativeTabsUseMeasuredBoundsAndLogicalPageOrder() {
        LauncherPageTabs.Snapshot page = read(page("no_plan_tips", TABS));
        assertEquals(LauncherPageTabs.HOME, page.page);
        assertTrue(page.aligned);
        assertEquals(272f, page.tab(LauncherPageTabs.APPS).x, 0f);
        assertEquals(208f, page.tab(LauncherPageTabs.MESSAGES).x, 0f);
        assertEquals(330f, page.tab(LauncherPageTabs.HOME).y, 0f);

        page = read(page("no_message_tip", new Rect(100, 400, 276, 448)));
        assertEquals(LauncherPageTabs.MESSAGES, page.page);
        assertEquals(188f, page.tab(LauncherPageTabs.HOME).x, 0f);
    }

    @Test public void rtlReversesPhysicalTabsWithoutChangingLogicalPages() {
        LauncherPageTabs.Snapshot page = LauncherPageTabs.read(page("app_recycler", TABS), SCREEN, true);
        assertEquals(LauncherPageTabs.APPS, page.page);
        assertEquals(208f, page.tab(LauncherPageTabs.APPS).x, 0f);
        assertEquals(272f, page.tab(LauncherPageTabs.MESSAGES).x, 0f);
        assertEquals(240f, page.tab(LauncherPageTabs.HOME).x, 0f);
    }

    @Test public void retainedOffscreenAppsDoNotOverrideVisibleHome() {
        AccessibilityNodeInfo root = page("no_plan_tips", TABS);
        AccessibilityNodeInfo carousel = node("app_recycler");
        carousel.setBoundsInScreen(new Rect(505, 150, 935, 250));
        shadowOf(root).addChild(carousel);
        assertEquals(LauncherPageTabs.HOME, read(root).page);
        assertFalse(LauncherPageTabs.isVisibleCarousel(carousel, SCREEN));

        carousel.setBoundsInScreen(new Rect(400, 150, 830, 250));
        assertFalse(LauncherPageTabs.isVisibleCarousel(carousel, SCREEN));
        assertEquals(LauncherPageTabs.HOME, read(root).page);
    }

    @Test public void conflictingVisiblePagesAreUnknownInsteadOfDependingOnTraversalOrder() {
        AccessibilityNodeInfo root = page("no_plan_tips", TABS);
        shadowOf(root).addChild(node("app_recycler"));
        assertEquals(LauncherPageTabs.UNKNOWN, read(root).page);
    }

    @Test public void missingHiddenClippedOrMalformedIndicatorCannotProduceATap() {
        AccessibilityNodeInfo root = node("no_plan_tips");
        assertNull(read(root).tab(LauncherPageTabs.APPS));
        AccessibilityNodeInfo hidden = node("indicator");
        hidden.setBoundsInScreen(TABS);
        hidden.setVisibleToUser(false);
        shadowOf(root).addChild(hidden);
        assertNull(read(root).tab(LauncherPageTabs.APPS));
        assertNull(read(page("no_plan_tips", new Rect(450, 318, 538, 342))).tab(2));
        assertNull(read(page("no_plan_tips", new Rect(196, 318, 220, 342))).tab(2));
    }

    @Test public void anIncompleteTreeDoesNotTrustAnEarlyPageMarker() {
        AccessibilityNodeInfo root = page("no_plan_tips", TABS);
        for (int i = 0; i < 100; i++) shadowOf(root).addChild(node("decoration"));
        assertEquals(LauncherPageTabs.UNKNOWN, read(root).page);
        assertFalse(read(root).complete);
    }

    @Test public void emptyOrHiddenCarouselIsNotAnActivePage() {
        AccessibilityNodeInfo node = node("app_recycler");
        node.setBoundsInScreen(new Rect());
        assertFalse(LauncherPageTabs.isVisibleCarousel(node, SCREEN));
        assertEquals(LauncherPageTabs.UNKNOWN, read(node).page);
        node.setBoundsInScreen(new Rect(25, 150, 455, 250));
        node.setVisibleToUser(false);
        assertFalse(LauncherPageTabs.isVisibleCarousel(node, SCREEN));
        assertEquals(LauncherPageTabs.UNKNOWN, read(node).page);
        assertEquals(LauncherPageTabs.UNKNOWN, read(null).page);
        assertFalse(read(null).complete);
    }

    @Test public void partiallyVisiblePageIsNotAlignedEvenIfItsCarouselIsMostlyVisible() {
        AccessibilityNodeInfo root = page("app_recycler", TABS, new Rect(50, 20, 480, 318));
        assertEquals(LauncherPageTabs.APPS, read(root).page);
        assertFalse(read(root).aligned);
        assertTrue(read(page("app_recycler", TABS)).aligned);
    }

    @Test public void mainPagerWithoutTabsCannotBeMistakenForAGenericSettingsPage() {
        AccessibilityNodeInfo root = node("viewpager");
        shadowOf(root).addChild(node("recycler_view"));
        LauncherPageTabs.Snapshot main = read(root);
        assertTrue(main.mainLauncher);
        assertNull(main.tab(LauncherPageTabs.APPS));
        AccessibilityNodeInfo settings = node("settings_list");
        shadowOf(settings).addChild(node("recycler_view"));
        assertFalse(read(settings).mainLauncher);
        assertTrue(read(settings).complete);
    }

    private static LauncherPageTabs.Snapshot read(AccessibilityNodeInfo root) {
        return LauncherPageTabs.read(root, SCREEN, false);
    }

    private static AccessibilityNodeInfo page(String contentId, Rect bounds) {
        return page(contentId, bounds, new Rect(0, 20, 480, 318));
    }

    private static AccessibilityNodeInfo page(String contentId, Rect bounds, Rect holderBounds) {
        AccessibilityNodeInfo root = node("main_focus_audio_layout");
        AccessibilityNodeInfo pager = node("viewpager");
        pager.setBoundsInScreen(new Rect(0, 20, 480, 318));
        AccessibilityNodeInfo innerRecycler = node("inner_pager_recycler");
        innerRecycler.setBoundsInScreen(new Rect(0, 20, 480, 318));
        AccessibilityNodeInfo holder = node("page_holder");
        holder.setBoundsInScreen(holderBounds);
        shadowOf(root).addChild(pager);
        shadowOf(pager).addChild(innerRecycler);
        shadowOf(innerRecycler).addChild(holder);
        shadowOf(holder).addChild(node(contentId));
        AccessibilityNodeInfo indicator = node("indicator");
        indicator.setBoundsInScreen(bounds);
        shadowOf(root).addChild(indicator);
        return root;
    }

    private static AccessibilityNodeInfo node(String id) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setViewIdResourceName("com.rokid.os.sprite.launcher:id/" + id);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setBoundsInScreen(new Rect(25, 150, 455, 250));
        return node;
    }
}
