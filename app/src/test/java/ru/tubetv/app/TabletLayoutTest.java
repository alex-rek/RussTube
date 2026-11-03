package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TabletLayoutTest {
    @Test public void tabletThresholdUsesSmallestWidth() {
        assertFalse(TabletLayout.isTablet(599));
        assertTrue(TabletLayout.isTablet(600));
    }

    @Test public void phoneColumnsStayAsBefore() {
        assertEquals(1, TabletLayout.columns(420, false, false));
        assertEquals(2, TabletLayout.columns(900, false, true));
        assertEquals(3, TabletLayout.columns(1100, false, true));
    }

    @Test public void tabletGridRespondsToAvailableWidthAndRail() {
        assertEquals(2, TabletLayout.columns(600, true, false));
        assertEquals(3, TabletLayout.columns(960, true, true));
        assertEquals(4, TabletLayout.columns(1400, true, true));
    }
}
