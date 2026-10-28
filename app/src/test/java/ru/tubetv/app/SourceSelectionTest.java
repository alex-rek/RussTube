package ru.tubetv.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class SourceSelectionTest {
    @Test public void absentPeerTubePreferenceDefaultsOffWithoutChangingOtherSources() {
        MemoryPreferences prefs = new MemoryPreferences();
        assertFalse(MobileBrowsePreferences.sourceEnabled(prefs, PeerTubeClient.SOURCE));
        assertTrue(MobileBrowsePreferences.sourceEnabled(prefs, "RUTUBE"));
        assertTrue(MobileBrowsePreferences.defaultSourceEnabled("RUTUBE"));
        assertTrue(MobileBrowsePreferences.defaultSourceEnabled("VK VIDEO"));
        assertTrue(MobileBrowsePreferences.defaultSourceEnabled("OK"));
        assertTrue(MobileBrowsePreferences.defaultSourceEnabled("ДЗЕН"));
        assertFalse(MobileBrowsePreferences.defaultSourceEnabled(PeerTubeClient.SOURCE));
    }

    @Test public void explicitChoiceSurvivesAndEmptyDraftIsRejected() {
        MemoryPreferences prefs = new MemoryPreferences();
        boolean[] selected = {true, true, true, true, true};
        assertTrue(MobileBrowsePreferences.setSources(prefs, selected));
        assertTrue(MobileBrowsePreferences.sourceEnabled(prefs, PeerTubeClient.SOURCE));
        selected[4] = false;
        assertTrue(MobileBrowsePreferences.setSources(prefs, selected));
        assertFalse(MobileBrowsePreferences.sourceEnabled(prefs, PeerTubeClient.SOURCE));
        assertFalse(MobileBrowsePreferences.setSources(prefs, new boolean[5]));
        assertTrue(MobileBrowsePreferences.sourceEnabled(prefs, "RUTUBE"));
    }

    @Test public void searchAndLiveUseOnlyExplicitlyEnabledSources() {
        boolean[] enabled = {true, false, true, true, false};
        assertArrayEquals(new String[]{"RUTUBE", "OK", "ДЗЕН"},
                MobileBrowsePreferences.selectedSources(enabled, false));
        assertArrayEquals(new String[]{"RUTUBE"},
                MobileBrowsePreferences.selectedSources(enabled, true));
        enabled[0] = false;
        assertArrayEquals(new String[0], MobileBrowsePreferences.selectedSources(enabled, true));
        enabled[4] = true;
        assertArrayEquals(new String[]{PeerTubeClient.SOURCE},
                MobileBrowsePreferences.selectedSources(enabled, true));
    }
}
