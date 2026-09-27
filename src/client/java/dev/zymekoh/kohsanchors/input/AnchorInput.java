package dev.zymekoh.kohsanchors.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.mixin.KeyMappingAccessor;
import dev.zymekoh.kohsanchors.mixin.MinecraftUseInvoker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.HitResult;

/**
 * Makes a fast anchor burst land the way it was pressed.
 *
 * <h2>What Vanilla does</h2>
 *
 * <p>Presses are only counted between ticks. When a tick handles them, {@code handleKeybinds}
 * first applies every hotbar key (each at most once, in slot order 1 to 9) and only afterwards
 * runs every use press, all against the crosshair target that was read before any of them. So
 * "use, 2, use" within one tick becomes "2, use, use": the anchor you meant to place is never
 * placed, and the glowstone press aims at the block the anchor was going to sit on. The same
 * code runs on 1.21.11 and on every 26.x release; this was checked against their bytecode.</p>
 *
 * <h2>What this changes</h2>
 *
 * <ul>
 *   <li><b>Order.</b> When, and only when, applying the presses in pressed order would put a
 *   different item in the hand for at least one use, the burst is applied in pressed order.</li>
 *   <li><b>Target.</b> Before the second and later uses of a tick, the crosshair is read again
 *   with Vanilla's own raycast, so a glowstone press aims at the anchor that the previous press
 *   just placed.</li>
 * </ul>
 *
 * <h2>What it never does</h2>
 *
 * <p>Every action is one press that Minecraft already counted: a press is consumed from
 * Vanilla's own counter before it is applied, and anything this class does not apply stays in
 * that counter for Vanilla. No press is created, repeated, delayed to another tick or moved to
 * another input frame, no slot is chosen that the player did not press, the repeat delay for a
 * held key is untouched, and no packet is written here: Vanilla's {@code startUseItem} sends
 * exactly what it would send for the same press.</p>
 */
public final class AnchorInput {
    private static final InputJournal JOURNAL = new InputJournal();
    private static final int[] BURST = new int[InputJournal.CAPACITY];

    private static boolean hotbarPointReached;
    private static int usesThisPass;

    private AnchorInput() {
    }

    /** Called for every key or mouse press Minecraft counts, in the order they happen. */
    public static void onKeyClicked(InputConstants.Key key) {
        if (!AnchorsConfig.settings().inputOrder) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || Mc.screen(minecraft) != null || Mc.overlay(minecraft) != null) {
            return;
        }
        long now = System.nanoTime();
        Options options = minecraft.options;
        if (key.equals(keyOf(options.keyUse))) {
            JOURNAL.record(InputJournal.USE, now);
        }
        KeyMapping[] slots = options.keyHotbarSlots;
        for (int slot = 0; slot < slots.length; slot++) {
            if (key.equals(keyOf(slots[slot]))) {
                JOURNAL.record(slot, now);
            }
        }
    }

    /** Start of {@code handleKeybinds}. */
    public static void beginPass() {
        hotbarPointReached = false;
        usesThisPass = 0;
    }

    /**
     * Where Vanilla is about to apply the hotbar keys. The burst is claimed here, before Vanilla
     * has consumed any of its presses, or left entirely alone.
     */
    public static void atHotbarPoint(Minecraft minecraft) {
        if (hotbarPointReached) {
            return;
        }
        hotbarPointReached = true;

        int count = JOURNAL.drainInto(BURST, System.nanoTime());
        if (count < 2 || !AnchorsConfig.settings().inputOrder) {
            return;
        }
        if (!AnchorContext.ready(minecraft) || minecraft.player == null) {
            return;
        }
        Options options = minecraft.options;
        // Creative hotbar saving uses the number keys for something else entirely.
        if (options.keySaveHotbarActivator.isDown() || options.keyLoadHotbarActivator.isDown()) {
            return;
        }
        // Inventory, swap, drop, attack and pick run between the hotbar keys and the use presses.
        // With any of them in the same tick the burst is not only hotbar and use, so Vanilla keeps
        // its own order for all of it.
        if (pending(options.keyInventory) || pending(options.keySwapOffhand) || pending(options.keyDrop)
                || pending(options.keyAttack) || pending(options.keyPickItem)) {
            return;
        }
        if (!orderMatters(BURST, count, minecraft.player.getInventory().getSelectedSlot())) {
            return;
        }
        if (!isAnchorBurst(minecraft, BURST, count)) {
            return;
        }
        replay(minecraft, BURST, count);
    }

    /** Before each use press Vanilla runs from its own loop in {@code handleKeybinds}. */
    public static void beforeQueuedUse(Minecraft minecraft) {
        refreshTargetBeforeUse(minecraft);
    }

    private static void replay(Minecraft minecraft, int[] burst, int count) {
        Options options = minecraft.options;
        KeyMapping[] slots = options.keyHotbarSlots;
        for (int index = 0; index < count; index++) {
            // A use that opened a screen, started eating or started breaking ends the burst; the
            // presses still counted are Vanilla's to handle, as they would have been.
            if (!AnchorContext.ready(minecraft) || minecraft.player == null) {
                break;
            }
            int action = burst[index];
            if (action == InputJournal.USE) {
                if (!options.keyUse.consumeClick()) {
                    continue;
                }
                refreshTargetBeforeUse(minecraft);
                ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem();
            } else if (slots[action].consumeClick()) {
                // Exactly what Vanilla does for a number key outside spectator and creative saving.
                minecraft.player.getInventory().setSelectedSlot(action);
            }
        }
        AnchorStats.orderedBurst();
    }

    private static void refreshTargetBeforeUse(Minecraft minecraft) {
        if (usesThisPass++ == 0) {
            // The first use of a tick aims with the raycast Vanilla just made for this tick.
            return;
        }
        if (!AnchorsConfig.settings().freshTarget
                || !AnchorContext.ready(minecraft)
                || !AnchorContext.inAnchorPlay(minecraft)) {
            return;
        }
        HitResult before = minecraft.hitResult;
        Mc.pick(minecraft);
        if (AnchorContext.differentTarget(before, minecraft.hitResult)) {
            AnchorStats.retargetedUse();
        }
    }

    /**
     * Whether pressed order puts a different main-hand slot behind any use than Vanilla's order.
     * Vanilla applies each pressed number key once, in slot order, before the first use.
     */
    static boolean orderMatters(int[] burst, int count, int selectedSlot) {
        int pressedSlots = 0;
        boolean anyUse = false;
        for (int index = 0; index < count; index++) {
            if (burst[index] == InputJournal.USE) {
                anyUse = true;
            } else {
                pressedSlots |= 1 << burst[index];
            }
        }
        if (!anyUse || pressedSlots == 0) {
            return false;
        }
        int vanillaSlot = 31 - Integer.numberOfLeadingZeros(pressedSlots);
        int slot = selectedSlot;
        for (int index = 0; index < count; index++) {
            if (burst[index] == InputJournal.USE) {
                if (slot != vanillaSlot) {
                    return true;
                }
            } else {
                slot = burst[index];
            }
        }
        return false;
    }

    private static boolean isAnchorBurst(Minecraft minecraft, int[] burst, int count) {
        if (AnchorContext.targetsMenuBlock(minecraft)) {
            return false;
        }
        if (AnchorContext.inAnchorPlay(minecraft)) {
            return true;
        }
        Inventory inventory = minecraft.player.getInventory();
        for (int index = 0; index < count; index++) {
            if (burst[index] != InputJournal.USE && AnchorContext.isAnchorItem(inventory.getItem(burst[index]))) {
                return true;
            }
        }
        return false;
    }

    private static boolean pending(KeyMapping mapping) {
        return ((KeyMappingAccessor) mapping).kohsAnchors$clickCount() > 0;
    }

    private static InputConstants.Key keyOf(KeyMapping mapping) {
        return ((KeyMappingAccessor) mapping).kohsAnchors$key();
    }
}
