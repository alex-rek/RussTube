package ru.tubetv.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class RecommendationTopicsStoreTest {
    @Test public void customTopicsKeepOrderAndNormalizeSpaces() {
        assertEquals(Arrays.asList("Космос", "История России"),
                RecommendationTopicsStore.withAdded(
                        Collections.singletonList("Космос"), "  История   России  "));
    }

    @Test public void duplicateAndBuiltInTopicsAreRejected() {
        for (String topic : Arrays.asList("космос", "НАУКА")) {
            try {
                RecommendationTopicsStore.withAdded(Collections.singletonList("Космос"), topic);
                fail("Expected duplicate topic rejection: " + topic);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void damagedStorageAndDuplicateEntriesDoNotHideValidTopics() {
        assertEquals(Collections.emptyList(), RecommendationTopicsStore.decode("broken"));
        assertEquals(Arrays.asList("Космос", "Кулинария"),
                RecommendationTopicsStore.decode("[\"Космос\",\"космос\",\"Наука\",\"Кулинария\"]"));
    }
}
