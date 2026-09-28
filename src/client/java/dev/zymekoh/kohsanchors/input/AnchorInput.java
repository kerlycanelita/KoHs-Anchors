package dev.zymekoh.kohsanchors.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.mixin.KeyMappingAccessor;
import dev.zymekoh.kohsanchors.mixin.MinecraftUseInvoker;
import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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
 *   <li><b>Order.</b> A burst that mixes number keys and uses is applied in pressed order unless
 *   every way of applying the number keys first gives the same result (see
 *   {@link #needsPressedOrder}). That includes a number key pressed after the last use: pressed
 *   order leaves it selected, where Vanilla drops it for a higher slot and Herzium's last-input
 *   order applies it before the use.</li>
 *   <li><b>Target.</b> Before the second and later uses of a tick, when the item in hand changed
 *   since the previous use, the crosshair is read again with Vanilla's own raycast, so a
 *   glowstone press aims at the anchor that the previous press just placed. A repeat with the
 *   same item keeps the previous target, as in Vanilla, so it cannot stack an anchor on the one
 *   it just placed.</li>
 *   <li><b>Early clicks.</b> A click aimed at an anchor this client has just detonated, before the
 *   server has removed it, waits and runs the moment the server's removal arrives, aimed at the
 *   free block. Vanilla would spend it on the old anchor. Measured on a Grim server, this is also
 *   the earliest moment the next anchor can go down without being flagged. A repeat click with the
 *   same item joins the one already waiting instead of stacking a second anchor, and waiting
 *   clicks whose anchor the server never removes are dropped instead of landing on it.</li>
 *   <li><b>No stacking.</b> In anchor play, a second use with the same item in the same tick is a
 *   double click: it could only fail, or, on grass, snow or the fire an explosion leaves, stack a
 *   second anchor on the first. It joins the first one. Glowstone on an anchor is left alone.</li>
 * </ul>
 *
 * <h2>What it never does</h2>
 *
 * <p>Every action is one press that Minecraft already counted: a press is consumed from
 * Vanilla's own counter before it is applied, and anything this class does not apply stays in
 * that counter for Vanilla. No press is created or repeated, and none is moved out of the tick
 * loop into an input callback. The only press that runs in a later tick is an early click held
 * as above: at most six of them, for at most 0.7 s, each applied at most once with the slot that
 * was selected when it was pressed; a repeat of the waiting click and a click whose anchor stayed
 * are not applied, as Vanilla would have spent them on that anchor. No slot is chosen that the
 * player did not press, the repeat delay for a held key is untouched, and no packet is written
 * here: Vanilla's {@code startUseItem} sends exactly what it would send for the same press.</p>
 */
public final class AnchorInput {
    private static final InputJournal JOURNAL = new InputJournal();
    private static final int[] BURST = new int[InputJournal.CAPACITY];

    /** At most this many clicks wait for one anchor; more than that is not a burst but a hold. */
    private static final int HOLD_CAPACITY = 6;
    /** Longer than any removal worth waiting for; a click still waiting then is dropped. */
    private static final long HOLD_TIMEOUT_NANOS = 700_000_000L;
    private static final int[] HELD_SLOTS = new int[HOLD_CAPACITY];

    private static boolean hotbarPointReached;
    private static int usesThisPass;
    /** The slot of the last use this pass, to tell a repeat with the same item from a new item. */
    private static int lastUseSlot = -1;
    private static int heldCount;
    private static long heldSince;
    private static BlockPos heldFor;
    /** Why the held clicks wait: an exploding anchor, or a drawn anchor the world does not have yet. */
    private static boolean heldForCatchUp;

    /** What happens to one use press. */
    private enum Decision { RUN, RUN_PREDICTED, HOLD, DROP }

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

    /**
     * After Minecraft counted a press, from the key or button handler, between client ticks.
     *
     * <p>A "use" press that will certainly detonate the anchor under the crosshair is shown at
     * once (sound, flash and the anchor drawn as gone); the use itself runs in the next tick as
     * always. With the advanced instant detonation, that use is sent right here instead.</p>
     */
    public static void afterKeyClicked(InputConstants.Key key) {
        Minecraft minecraft = Minecraft.getInstance();
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        if (minecraft.player == null || minecraft.level == null
                || !(settings.predictDetonation || settings.hideDetonating || settings.instantDetonation)) {
            return;
        }
        Options options = minecraft.options;
        if (!key.equals(keyOf(options.keyUse)) || !AnchorContext.ready(minecraft)) {
            return;
        }
        // A number key waiting for the tick decides which item this click uses; the tick judges.
        for (KeyMapping slot : options.keyHotbarSlots) {
            if (pending(slot)) {
                return;
            }
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos target = hit.getBlockPos();
        BlockState drawn = AnchorVeil.predicted(target);
        BlockState state = drawn != null ? drawn : minecraft.level.getBlockState(target);
        if (!DetonationPredictor.willDetonate(minecraft.level, target, state, minecraft.player)) {
            return;
        }
        if (drawn == null && DetonationPredictor.isDetonating(target)) {
            // Already exploding: this press is an early click for the next anchor.
            return;
        }
        if (settings.instantDetonation && pending(options.keyUse) && options.keyUse.consumeClick()) {
            JOURNAL.forgetLastUse();
            DetonationPredictor.detonate(minecraft.level, target);
            DetonationPredictor.runPredicted(() -> ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem());
            AnchorStats.instantDetonation();
            return;
        }
        if (drawn == null && (settings.predictDetonation || settings.hideDetonating)) {
            DetonationPredictor.showAtInput(minecraft, target);
        }
    }

    /** Once a client tick, before its input pass. */
    public static void tick(Minecraft minecraft) {
        AnchorVeil.tick(minecraft);
        DetonationPredictor.tick(minecraft);
    }

    /** Start of {@code handleKeybinds}: held clicks whose anchor is gone run first, in order. */
    public static void beginPass(Minecraft minecraft) {
        hotbarPointReached = false;
        usesThisPass = 0;
        lastUseSlot = -1;
        if (heldCount > 0) {
            releaseHeld(minecraft);
        }
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
        if (!needsPressedOrder(BURST, count)) {
            return;
        }
        if (!isAnchorBurst(minecraft, BURST, count)) {
            return;
        }
        replay(minecraft, BURST, count);
    }

    /**
     * Before each use press Vanilla runs from its own loop in {@code handleKeybinds}.
     *
     * @return whether Vanilla should run it now; {@code false} when it is held
     */
    public static boolean admitQueuedUse(Minecraft minecraft) {
        return switch (decide(minecraft)) {
            case RUN -> true;
            case RUN_PREDICTED -> {
                runPredicted(minecraft);
                yield false;
            }
            case HOLD, DROP -> false;
        };
    }

    /**
     * What happens to the use press about to run: now, now with its outcome already drawn, held,
     * or dropped because it could only fail or misplace a block.
     */
    private static Decision decide(Minecraft minecraft) {
        if (refreshTargetBeforeUse(minecraft) && mergesRepeat(minecraft)) {
            AnchorStats.mergedClick();
            return Decision.DROP;
        }
        if (AnchorsConfig.settings().fastChain) {
            return switch (FastChain.decide(minecraft)) {
                case RUN -> Decision.RUN;
                case RUN_PREDICTED -> Decision.RUN_PREDICTED;
                case DROP -> Decision.DROP;
                case CATCH_UP -> hold(minecraft, FastChain.catchUpTarget(), true);
            };
        }
        return admit(minecraft);
    }

    private static void runPredicted(Minecraft minecraft) {
        DetonationPredictor.runPredicted(() -> ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem());
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
                switch (decide(minecraft)) {
                    case RUN -> ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem();
                    case RUN_PREDICTED -> runPredicted(minecraft);
                    default -> {
                    }
                }
            } else if (slots[action].consumeClick()) {
                // Exactly what Vanilla does for a number key outside spectator and creative saving.
                minecraft.player.getInventory().setSelectedSlot(action);
            }
        }
        AnchorStats.orderedBurst();
    }

    /**
     * Whether a use about to run may run now. A use aimed at an anchor this client detonated and
     * the server has not removed yet is held instead, with the slot it was pressed with.
     */
    private static Decision admit(Minecraft minecraft) {
        if (!AnchorsConfig.settings().holdEarlyClicks || minecraft.player == null
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return Decision.RUN;
        }
        BlockPos target = hit.getBlockPos();
        if (!DetonationPredictor.isDetonating(target)) {
            return Decision.RUN;
        }
        return hold(minecraft, target, false);
    }

    /**
     * Holds the use press about to run until {@code target} is ready: the server removed the
     * exploding anchor there, or ({@code catchUp}) the world has the anchor that is already drawn.
     */
    private static Decision hold(Minecraft minecraft, BlockPos target, boolean catchUp) {
        if (minecraft.player == null || target == null) {
            return Decision.RUN;
        }
        if (heldCount > 0 && (!heldFor.equals(target) || heldForCatchUp != catchUp)) {
            return Decision.RUN;
        }
        int slot = minecraft.player.getInventory().getSelectedSlot();
        if (heldCount > 0 && HELD_SLOTS[heldCount - 1] == slot) {
            // Another click with the same item on the same exploding anchor. The one already
            // waiting does what it asks for once the block is free; applied twice it would stack a
            // second anchor on the first, or charge it with glowstone meant for nothing. Vanilla
            // would have spent this click on the old anchor too.
            AnchorStats.mergedClick();
            return Decision.DROP;
        }
        if (heldCount == HOLD_CAPACITY) {
            return Decision.RUN;
        }
        if (heldCount == 0) {
            heldSince = System.nanoTime();
            heldFor = target.immutable();
            heldForCatchUp = catchUp;
        }
        HELD_SLOTS[heldCount++] = slot;
        AnchorStats.heldClick();
        return Decision.HOLD;
    }

    /**
     * Runs the held clicks once the server has removed their anchor, each with the slot it was
     * pressed with and aimed at what the crosshair hits now. The slot the player holds now is
     * restored afterwards.
     *
     * <p>If the anchor is still there when the wait runs out, the server did not explode it (a
     * protected area, a rejected click), and the clicks are dropped: aimed at the anchor that
     * stayed, they would stack another anchor on it or glowstone beside it. Vanilla would have
     * spent them on that same anchor.</p>
     */
    private static void releaseHeld(Minecraft minecraft) {
        boolean cleared = heldForCatchUp ? FastChain.caughtUp(minecraft.level, heldFor)
                : !DetonationPredictor.isDetonating(heldFor);
        if (!cleared && System.nanoTime() - heldSince < HOLD_TIMEOUT_NANOS) {
            return;
        }
        int count = heldCount;
        heldCount = 0;
        if (!cleared) {
            AnchorStats.droppedClicks(count);
            return;
        }
        if (!AnchorContext.ready(minecraft) || minecraft.player == null) {
            // A screen or an item in use since: these clicks belong to a moment that has passed.
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        int selected = inventory.getSelectedSlot();
        for (int index = 0; index < count && AnchorContext.ready(minecraft); index++) {
            if (inventory.getSelectedSlot() != HELD_SLOTS[index]) {
                inventory.setSelectedSlot(HELD_SLOTS[index]);
            }
            Mc.pick(minecraft);
            usesThisPass++;
            lastUseSlot = HELD_SLOTS[index];
            if (!AnchorsConfig.settings().fastChain) {
                ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem();
                continue;
            }
            // The chain judges the released click against what is drawn now, as any other.
            switch (FastChain.decide(minecraft)) {
                case RUN -> ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem();
                case RUN_PREDICTED -> runPredicted(minecraft);
                default -> AnchorStats.droppedClicks(1);
            }
        }
        if (inventory.getSelectedSlot() != selected) {
            inventory.setSelectedSlot(selected);
        }
    }

    /**
     * Re-reads the crosshair before a use when the item changed since the previous use of this
     * pass.
     *
     * @return whether this use repeats the previous use of this pass with the same item
     */
    private static boolean refreshTargetBeforeUse(Minecraft minecraft) {
        int slot = minecraft.player == null ? -1 : minecraft.player.getInventory().getSelectedSlot();
        boolean repeat = usesThisPass > 0 && slot == lastUseSlot;
        lastUseSlot = slot;
        if (usesThisPass++ == 0) {
            // The first use of a tick aims with the raycast Vanilla just made for this tick.
            return false;
        }
        if (repeat) {
            // A repeat with the same item keeps the target the previous use had, as in Vanilla.
            // Aimed again, it would hit the anchor that use just placed and stack a second one on it.
            return true;
        }
        if (!AnchorsConfig.settings().freshTarget
                || !AnchorContext.ready(minecraft)
                || !AnchorContext.inAnchorPlay(minecraft)) {
            return false;
        }
        HitResult before = minecraft.hitResult;
        Mc.pick(minecraft);
        if (AnchorContext.differentTarget(before, minecraft.hitResult)) {
            AnchorStats.retargetedUse();
        }
        return false;
    }

    /**
     * Whether a repeat with the same item in the same tick joins the use before it. In anchor play
     * a double click can only fail, or, on grass, snow or the fire an explosion leaves (blocks a
     * placement replaces), put a second anchor on top of the first. Glowstone on an anchor is left
     * alone: charging twice is legitimate where anchors set the spawn point.
     */
    private static boolean mergesRepeat(Minecraft minecraft) {
        if (!AnchorsConfig.settings().noStacking || minecraft.player == null
                || !AnchorContext.inAnchorPlay(minecraft)) {
            return false;
        }
        return !(minecraft.player.getMainHandItem().is(Items.GLOWSTONE) && AnchorContext.targetsAnchor(minecraft));
    }

    /**
     * Whether the burst has to be applied in pressed order for every use, and the slot left
     * selected after it, to be what was pressed.
     *
     * <p>Vanilla applies every number key of the pass before the first use, and which of several
     * keys wins depends on who decides: Vanilla takes the highest slot, Herzium's last-input order
     * takes the last key pressed, even one pressed after the use. So "glowstone, use, sword" in one
     * pass charges with glowstone and forgets the sword under Vanilla, and hits the anchor with the
     * sword under Herzium; "anchor, use, glowstone" places the anchor under Vanilla and a glowstone
     * block under Herzium. Pressed order is right under both.</p>
     *
     * <p>A burst is only left to the pass when every order gives the same result: all its number
     * keys pressed before its first use, and all of them the same slot.</p>
     */
    static boolean needsPressedOrder(int[] burst, int count) {
        boolean anyUse = false;
        boolean severalSlots = false;
        int slot = -1;
        for (int index = 0; index < count; index++) {
            int action = burst[index];
            if (action == InputJournal.USE) {
                anyUse = true;
            } else if (anyUse) {
                // A number key after a use: every order that applies number keys first moves it
                // in front of that use.
                return true;
            } else if (slot >= 0 && action != slot) {
                severalSlots = true;
            } else {
                slot = action;
            }
        }
        return anyUse && severalSlots;
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
