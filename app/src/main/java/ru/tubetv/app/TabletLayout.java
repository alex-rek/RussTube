package ru.tubetv.app;

/** Shared sizing rules for the mobile tablet layout. Values are density-independent pixels. */
final class TabletLayout {
    static final int MIN_SMALLEST_WIDTH_DP = 600;
    static final int RAIL_WIDTH_DP = 76;
    private static final int CARD_MIN_WIDTH_DP = 250;
    private static final int CARD_GAP_DP = 14;

    static boolean isTablet(int smallestWidthDp) {
        return smallestWidthDp >= MIN_SMALLEST_WIDTH_DP;
    }

    static int columns(int widthDp, boolean tablet, boolean landscape) {
        if (!tablet) return widthDp >= 1000 ? 3 : landscape || widthDp >= 600 ? 2 : 1;
        int usable = Math.max(0, widthDp - (landscape ? RAIL_WIDTH_DP : 0) - 28);
        return Math.max(2, Math.min(4,
                (usable + CARD_GAP_DP) / (CARD_MIN_WIDTH_DP + CARD_GAP_DP)));
    }

    private TabletLayout() { }
}
