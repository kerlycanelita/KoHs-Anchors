package dev.zymekoh.kohsanchors.input;

/**
 * What the mod actually did this session, counted where it happens. The settings screen shows
 * these numbers; nothing here is estimated.
 */
public final class AnchorStats {
    private static int orderedBursts;
    private static int retargetedUses;
    private static int predictedDetonations;
    private static int confirmedDetonations;
    private static int heldClicks;
    private static int mergedClicks;
    private static int doubleAnchors;
    private static int droppedClicks;
    private static int chainedClicks;
    private static int instantDetonations;
    private static int keptDetonations;
    private static int debouncedAnchors;
    private static int debouncedGlowstone;
    private static int nextTickPresses;
    private static int orderRisks;
    private static int withdrawnDetonations;
    private static int glowstoneGuarded;
    private static long lastCycleMillis = -1L;
    private static long bestCycleMillis = -1L;

    private AnchorStats() {
    }

    /** Bursts whose presses were applied in pressed order because a hotbar-first order could differ. */
    public static int orderedBursts() {
        return orderedBursts;
    }

    /** Extra uses in a tick that landed on a different target once the crosshair was re-read. */
    public static int retargetedUses() {
        return retargetedUses;
    }

    public static int predictedDetonations() {
        return predictedDetonations;
    }

    /** Predictions the server then confirmed with its own explosion at the same block. */
    public static int confirmedDetonations() {
        return confirmedDetonations;
    }

    /** Clicks that waited for the server to remove a detonated anchor instead of hitting it. */
    private static int mirroredStates;

    /** Server states at a chained anchor shown at once instead of after Vanilla's acknowledgement. */
    public static int mirroredStates() {
        return mirroredStates;
    }

    public static void mirroredState() {
        mirroredStates++;
    }

    public static int heldClicks() {
        return heldClicks;
    }

    /** Repeat clicks with the same item on an exploding anchor, folded into the one already waiting. */
    public static int mergedClicks() {
        return mergedClicks;
    }

    public static int doubleAnchors() {
        return doubleAnchors;
    }

    /** Waiting clicks dropped because their anchor was never removed by the server. */
    public static int droppedClicks() {
        return droppedClicks;
    }

    /** Clicks the advanced chain sent without waiting, with their outcome drawn at once. */
    public static int chainedClicks() {
        return chainedClicks;
    }

    /** Detonations sent the moment the click was pressed, by the advanced instant detonation. */
    public static int instantDetonations() {
        return instantDetonations;
    }

    /** Detonation clicks the server acknowledged without exploding the anchor, drawn again at once. */
    public static int keptDetonations() {
        return keptDetonations;
    }

    /** Anchor placements refused by the anchor debounce. */
    public static int debouncedAnchors() {
        return debouncedAnchors;
    }

    /** Glowstone uses refused by the glowstone debounce. */
    public static int debouncedGlowstone() {
        return debouncedGlowstone;
    }

    /** Presses of a burst applied on a later tick, so each tick changes slot at most once, first. */
    public static int nextTickPresses() {
        return nextTickPresses;
    }

    /** Slot changes sent after a click of the same tick; expected to stay 0. */
    public static int orderRisks() {
        return orderRisks;
    }

    static void nextTickPresses(int count) {
        nextTickPresses += count;
    }

    static void orderRisk() {
        orderRisks++;
    }

    /** Detonations shown at a press whose click then went to another block, taken back at once. */
    public static int withdrawnDetonations() {
        return withdrawnDetonations;
    }

    public static void withdrawnDetonation() {
        withdrawnDetonations++;
    }

    /** Glowstone clicks the guard dropped, that would have gone down as a block. */
    public static int glowstoneGuarded() {
        return glowstoneGuarded;
    }

    static void glowstoneGuardedClick() {
        glowstoneGuarded++;
    }

    /** The last and the best anchor cycle of the session, in milliseconds, or -1. */
    public static long lastCycleMillis() {
        return lastCycleMillis;
    }

    public static long bestCycleMillis() {
        return bestCycleMillis;
    }

    static void anchorCycle(long millis) {
        lastCycleMillis = millis;
        if (bestCycleMillis < 0L || millis < bestCycleMillis) {
            bestCycleMillis = millis;
        }
    }

    public static void keptDetonation() {
        keptDetonations++;
    }

    static void anchorDebounced() {
        debouncedAnchors++;
    }

    static void glowstoneDebounced() {
        debouncedGlowstone++;
    }

    static void chainedClick() {
        chainedClicks++;
    }

    static void instantDetonation() {
        instantDetonations++;
    }

    static void heldClick() {
        heldClicks++;
    }

    static void mergedClick() {
        mergedClicks++;
    }

    static void doubleAnchor() {
        doubleAnchors++;
    }

    static void droppedClicks(int count) {
        droppedClicks += count;
    }

    static void orderedBurst() {
        orderedBursts++;
    }

    static void retargetedUse() {
        retargetedUses++;
    }

    public static void predictedDetonation() {
        predictedDetonations++;
    }

    public static void confirmedDetonation() {
        confirmedDetonations++;
    }
}
