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
    private static int droppedClicks;
    private static int chainedClicks;
    private static int instantDetonations;

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
    public static int heldClicks() {
        return heldClicks;
    }

    /** Repeat clicks with the same item on an exploding anchor, folded into the one already waiting. */
    public static int mergedClicks() {
        return mergedClicks;
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
