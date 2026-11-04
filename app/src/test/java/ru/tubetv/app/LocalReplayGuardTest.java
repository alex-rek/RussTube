package ru.tubetv.app;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LocalReplayGuardTest {
    @Test public void countersRejectReplayAndRemainIndependentPerPeer() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        assertEquals(1, LocalReplayGuard.nextOutgoing(prefs, first));
        assertEquals(2, LocalReplayGuard.nextOutgoing(prefs, first));
        assertEquals(1, LocalReplayGuard.nextOutgoing(prefs, second));
        assertTrue(LocalReplayGuard.acceptIncoming(prefs, first, 4));
        assertFalse(LocalReplayGuard.acceptIncoming(prefs, first, 4));
        assertFalse(LocalReplayGuard.acceptIncoming(prefs, first, 3));
        assertFalse(LocalReplayGuard.acceptIncoming(prefs, first, 0));
        assertTrue(LocalReplayGuard.acceptIncoming(prefs, second, 1));
        assertTrue(LocalReplayGuard.acceptIncoming(prefs, first, 5));
    }

    @Test public void invalidPeerCannotAdvanceCounters() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        assertFalse(LocalReplayGuard.acceptIncoming(prefs, "invalid", 1));
        try {
            LocalReplayGuard.nextOutgoing(prefs, "invalid");
            throw new AssertionError("Invalid peer was accepted");
        } catch (Exception expected) {
            assertEquals("Неизвестный адресат", expected.getMessage());
        }
    }
}
