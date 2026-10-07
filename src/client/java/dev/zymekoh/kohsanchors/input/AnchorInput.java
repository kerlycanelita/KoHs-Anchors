package dev.zymekoh.kohsanchors.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.bridge.BridgeClient;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorGlowRenderer;
import dev.zymekoh.kohsanchors.glow.AnchorTracker;
import dev.zymekoh.kohsanchors.integration.CrystalPalette;
import dev.zymekoh.kohsanchors.integration.HerziumBridge;
import dev.zymekoh.kohsanchors.mixin.KeyMappingAccessor;
import dev.zymekoh.kohsanchors.mixin.MinecraftUseInvoker;
import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import dev.zymekoh.kohsanchors.predict.Latency;
import dev.zymekoh.kohsanchors.safety.ServerLock;
import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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
 *   <li><b>Early clicks.</b> A click aimed at an anchor this client has just detonated goes out at
 *   once, at the old anchor, as Vanilla sends it; the server puts the next anchor in its place.
 *   What it will do is drawn at once ({@link AnchorChain}). Only a click whose raycast passes
 *   through an anchor that is drawn but not in the world yet waits, until the world has it.</li>
 *   <li><b>No stacking.</b> In anchor play, a second use with the same item in the same tick is a
 *   double click: it could only fail, or, on grass, snow or the fire an explosion leaves, stack a
 *   second anchor on the first. It joins the first one. Glowstone on an anchor is left alone.</li>
 *   <li><b>One slot change per tick, first.</b> The server, and anticheats such as Grim, read a
 *   tick's packets between two tick-end packets, and Vanilla never sends a slot change after a
 *   click of the same tick: it applies every number key before the first use. A burst is therefore
 *   applied one stretch per tick, number keys then the uses that follow them; at the first number
 *   key that would change the slot after a click, the rest of the burst waits for the next tick,
 *   in pressed order and still in Vanilla's counters. "Anchor, use, glowstone, use, sword, use"
 *   pressed within one tick lands on three consecutive ticks, which is how Vanilla sends it when
 *   the presses are a tick apart. Grim's experimental {@code PacketOrderE} (a slot change during
 *   another action) and {@code MultiPlace} (several placements in one tick) then have nothing to
 *   see.</li>
 * </ul>
 *
 * <h2>What it never does</h2>
 *
 * <p>Every action is one press that Minecraft already counted: a press is consumed from
 * Vanilla's own counter before it is applied, and anything this class does not apply stays in
 * that counter for Vanilla. No press is created or repeated, and none is moved out of the tick
 * loop into an input callback. Presses run in a later tick only to keep the tick shape above, or
 * as clicks held as above: at most six of them, for at most 0.7 s, each applied at most once
 * with the slot that was selected when it was pressed; a repeat of the waiting click, and a click
 * that could only stack an anchor or put glowstone down as a block, are not applied. No slot is chosen that the
 * player did not press, the repeat delay for a held key is untouched, and no packet is written
 * here: Vanilla's {@code startUseItem} sends exactly what it would send for the same press.</p>
 */
public final class AnchorInput {
    private static final InputJournal JOURNAL = new InputJournal();
    private static final int[] BURST = new int[InputJournal.CAPACITY];

    /** At most this many clicks wait for one anchor; more than that is not a burst but a hold. */
    private static final int HOLD_CAPACITY = 6;
    /**
     * The shortest wait for a drawn anchor to reach the world; on a slower connection the wait
     * follows the round trip, as the veil does (three round trips and a quarter second, up to
     * 1.5 s). A click still waiting then is dropped.
     */
    private static final long HOLD_TIMEOUT_NANOS = 700_000_000L;
    private static final long HOLD_TIMEOUT_MAX_NANOS = 1_500_000_000L;
    private static final int[] HELD_SLOTS = new int[HOLD_CAPACITY];
    /** The two clicks of a double anchor waiting together: the detonation and the next anchor. */
    private static final boolean[] HELD_DOUBLE = new boolean[HOLD_CAPACITY];

    private static boolean hotbarPointReached;
    private static int usesThisPass;
    /** The slot of the last use this pass, to tell a repeat with the same item from a new item. */
    private static int lastUseSlot = -1;
    private static int heldCount;
    private static long heldSince;
    /** The block drawn as an anchor that the held clicks wait for the world to have. */
    private static BlockPos heldFor;
    /** The world has it already; the held clicks only wait for a tick of their own. */
    private static boolean heldCleared;

    /**
     * The hotbar slot the first click or attack of this client tick was sent with, or -1 before
     * any. The tick ends with its tick-end packet; until then no other slot may be sent.
     */
    private static int tickSlot = -1;
    /** Presses of this pass that wait for the next tick: Vanilla's loops leave them queued. */
    private static boolean deferring;
    /**
     * When a burst goes on next tick: the slot its next stretch selects first, -1 for the slot
     * held now, {@link #NO_BURST} when no burst waits. Told to Herzium at the end of the tick.
     */
    private static final int NO_BURST = -2;
    private static int burstNextSlot = NO_BURST;

    /** What happens to one use press. */
    private enum Decision { RUN, RUN_PREDICTED, RUN_DETONATED, HOLD, DROP }

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
        boolean instant = ServerLock.instantDetonation();
        if (minecraft.player == null || minecraft.level == null
                || !(settings.predictDetonation || settings.hideDetonating || instant)) {
            return;
        }
        Options options = minecraft.options;
        if (!key.equals(keyOf(options.keyUse)) || !AnchorContext.ready(minecraft)) {
            return;
        }
        // Another use already waits for this tick: the tick decides the burst, including whether
        // this press is a double click that joins the first. Showing it now could show a
        // detonation that never happens.
        if (((KeyMappingAccessor) options.keyUse).kohsAnchors$clickCount() > 1) {
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
            instantDoubleAnchor(minecraft, hit, drawn);
            return;
        }
        if (drawn == null && DetonationPredictor.isDetonating(target)) {
            // Already exploding: this press is an early click for the next anchor.
            return;
        }
        if (instant && (tickSlot < 0 || minecraft.player.getInventory().getSelectedSlot() == tickSlot)
                && pending(options.keyUse) && options.keyUse.consumeClick()) {
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

    /**
     * The second click of a double anchor, with the instant detonation click of the server's bridge:
     * the anchor its first click detonated a moment ago is drawn as gone, and this click with anchors
     * puts the next one in its place. It goes out now too, right behind the detonation, drawn at once,
     * instead of on the next tick.
     */
    private static void instantDoubleAnchor(Minecraft minecraft, BlockHitResult hit, BlockState drawn) {
        BlockPos target = hit.getBlockPos();
        if (!ServerLock.instantDoubleAnchor() || drawn == null || drawn.is(Blocks.RESPAWN_ANCHOR)
                || !minecraft.player.getMainHandItem().is(Items.RESPAWN_ANCHOR) || !DetonationPredictor.isDetonating(target)
                || AnchorVeil.firstAlong(minecraft.player.getEyePosition(), hit.getLocation(), target) != null) {
            return;
        }
        Options options = minecraft.options;
        if ((tickSlot < 0 || minecraft.player.getInventory().getSelectedSlot() == tickSlot)
                && pending(options.keyUse) && options.keyUse.consumeClick()) {
            JOURNAL.forgetLastUse();
            AnchorVeil.predict(minecraft.level, target, AnchorVeil.anchor(0), AnchorVeil.anchor(0));
            DetonationPredictor.runChained(() -> ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem());
            AnchorStats.doubleAnchor();
        }
    }

    /** Once a client tick, before its input pass. */
    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || Mc.screen(minecraft) != null
                || Mc.overlay(minecraft) != null) {
            // A screen releases every key mapping and a world change starts input over: the order
            // of presses Vanilla no longer counts goes with them.
            JOURNAL.clear();
        }
        AnchorVeil.tick(minecraft);
        DetonationPredictor.tick(minecraft);
        AnchorDebounce.tick(minecraft);
        BridgeClient.tick(minecraft);
        AtlasSkin.tick(minecraft);
        AnchorTracker.tick(minecraft);
        dev.zymekoh.kohsanchors.glow.SafeAnchorView.tick(minecraft);
        if (minecraft.level != null) {
            long gameTime = minecraft.level.getGameTime();
            AnchorGlowRenderer.tick(gameTime);
            if (gameTime % 100L == 0L) {
                // Crystal Tweaks' colours changed while playing: follow them, one file time check.
                CrystalPalette.syncIfChanged();
            }
        }
    }

    /**
     * End of the client tick, after its tick-end packet: the next tick may change slot again. A
     * burst that goes on next tick is told to Herzium, whose hotbar preview is final by now.
     */
    public static void endTick(Minecraft minecraft) {
        tickSlot = -1;
        if (burstNextSlot != NO_BURST) {
            if (minecraft.player != null) {
                Inventory inventory = minecraft.player.getInventory();
                HerziumBridge.burstContinues(inventory, inventory.getSelectedSlot(), burstNextSlot);
            }
            burstNextSlot = NO_BURST;
        }
    }

    /**
     * A click or attack is about to be sent ({@code useItemOn}, {@code useItem}, {@code attack}):
     * the slot it goes out with is this tick's slot.
     */
    public static void onInteraction(Minecraft minecraft) {
        if (tickSlot < 0 && minecraft.player != null) {
            tickSlot = minecraft.player.getInventory().getSelectedSlot();
        }
    }

    /**
     * Before Vanilla tells the server the selected slot. After a click of the same tick, a new
     * slot there is what Grim's {@code PacketOrderE} flags; nothing in this mod should cause it,
     * so it is only counted, and logged in developer mode.
     */
    public static void beforeCarriedItemSync(Minecraft minecraft, int carried) {
        if (tickSlot < 0 || minecraft.player == null) {
            return;
        }
        int selected = minecraft.player.getInventory().getSelectedSlot();
        // The sync inside this tick's first click sends that click's own slot: that one is fine.
        if (selected != carried && selected != tickSlot) {
            AnchorStats.orderRisk();
            if (AnchorsConfig.settings().devMode) {
                dev.zymekoh.kohsanchors.KoHsAnchorsClient.LOGGER.info(
                        "Slot {} sent after a click with slot {} in the same tick", selected, tickSlot);
            }
        }
    }

    /**
     * Whether Vanilla's loops in {@code handleKeybinds} may take a press of {@code mapping} now.
     * Presses left for the next tick stay queued; after a click of this tick, a number key that
     * would change the slot, and a use, attack or pick with another slot selected, wait too.
     */
    public static boolean mayConsume(Minecraft minecraft, KeyMapping mapping) {
        if (!deferring && tickSlot < 0) {
            return true;
        }
        Options options = minecraft.options;
        boolean use = mapping == options.keyUse;
        int slot = -1;
        KeyMapping[] slots = options.keyHotbarSlots;
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == mapping) {
                slot = index;
                break;
            }
        }
        if (deferring && (use || slot >= 0)) {
            return false;
        }
        if (tickSlot < 0 || minecraft.player == null) {
            return true;
        }
        int selected = minecraft.player.getInventory().getSelectedSlot();
        if (slot >= 0) {
            return slot == selected;
        }
        if (use || mapping == options.keyAttack || mapping == options.keyPickItem) {
            return selected == tickSlot;
        }
        return true;
    }

    /** Start of {@code handleKeybinds}: held clicks whose anchor is gone run first, in order. */
    public static void beginPass(Minecraft minecraft) {
        hotbarPointReached = false;
        usesThisPass = 0;
        lastUseSlot = -1;
        deferring = false;
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
        if (!needsPressedOrder(BURST, count) && tickSlot < 0) {
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
        Decision decision = decide(minecraft);
        if (decision == Decision.RUN) {
            return true;
        }
        run(minecraft, decision);
        return false;
    }

    /**
     * What happens to the use press about to run: now, now with its outcome already drawn, held,
     * or dropped because it could only fail or misplace a block.
     */
    private static Decision decide(Minecraft minecraft) {
        if (usesThisPass == 0) {
            DetonationPredictor.firstUseOfTick(minecraft.level, targetBlock(minecraft));
        }
        if (refreshTargetBeforeUse(minecraft) && mergesRepeat(minecraft) && !doubleAnchor(minecraft)) {
            AnchorStats.mergedClick();
            return Decision.DROP;
        }
        if (DetonationPredictor.awaitsUse(targetBlock(minecraft))) {
            // The detonation this very press showed when it was pressed. Its anchor counts as
            // exploding already, but that explosion is what this use is about to cause: it runs
            // now, as Vanilla would run it, instead of waiting for a removal only it can bring.
            return Decision.RUN;
        }
        if (!AnchorsConfig.settings().anchorChain) {
            return Decision.RUN;
        }
        return switch (AnchorChain.decide(minecraft)) {
            case RUN -> Decision.RUN;
            case RUN_PREDICTED -> Decision.RUN_PREDICTED;
            case RUN_DETONATED -> Decision.RUN_DETONATED;
            case DROP -> Decision.DROP;
            case CATCH_UP -> hold(minecraft, AnchorChain.catchUpTarget());
        };
    }

    /**
     * Runs a use the way {@code decision} says: as Vanilla runs it, or with its anchor, charge or
     * detonation already drawn. A held or dropped use does not run.
     */
    private static void run(Minecraft minecraft, Decision decision) {
        Runnable use = () -> ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem();
        switch (decision) {
            case RUN -> use.run();
            case RUN_PREDICTED -> DetonationPredictor.runChained(use);
            case RUN_DETONATED -> DetonationPredictor.runPredicted(use);
            default -> {
            }
        }
    }

    private static void replay(Minecraft minecraft, int[] burst, int count) {
        Options options = minecraft.options;
        KeyMapping[] slots = options.keyHotbarSlots;
        int index = 0;
        for (; index < count; index++) {
            // A use that opened a screen, started eating or started breaking ends the burst; the
            // presses still counted are Vanilla's to handle, as they would have been.
            if (!AnchorContext.ready(minecraft) || minecraft.player == null) {
                index = count;
                break;
            }
            int action = burst[index];
            int selected = minecraft.player.getInventory().getSelectedSlot();
            if (action == InputJournal.USE) {
                if (tickSlot >= 0 && selected != tickSlot) {
                    // This tick already clicked with another item: this click is the next tick's.
                    break;
                }
                if (!options.keyUse.consumeClick()) {
                    continue;
                }
                run(minecraft, decide(minecraft));
            } else {
                if (tickSlot >= 0 && action != selected) {
                    // A slot change after this tick's click: it and what follows wait a tick.
                    break;
                }
                if (slots[action].consumeClick()) {
                    // Exactly what Vanilla does for a number key outside spectator and creative saving.
                    minecraft.player.getInventory().setSelectedSlot(action);
                    HerziumBridge.pressConsumed(action, ((KeyMappingAccessor) slots[action]).kohsAnchors$clickCount());
                }
            }
        }
        if (index < count) {
            JOURNAL.carry(burst, index, count - index, System.nanoTime());
            deferring = true;
            AnchorStats.nextTickPresses(count - index);
            // The next stretch selects the last number key before its first use, if any.
            burstNextSlot = -1;
            for (int next = index; next < count && burst[next] != InputJournal.USE; next++) {
                burstNextSlot = burst[next];
            }
        }
        AnchorStats.orderedBurst();
    }

    /**
     * Holds the use press about to run until the world has the anchor drawn at {@code target}, the
     * block its raycast passed through: released then, it lands on that anchor instead of on the
     * floor behind it.
     */
    private static Decision hold(Minecraft minecraft, BlockPos target) {
        if (minecraft.player == null || target == null) {
            return Decision.RUN;
        }
        if (heldCount > 0 && !heldFor.equals(target) || heldCount == HOLD_CAPACITY) {
            // Already waiting for another block, or a full wait: it runs, as the chain always has.
            return Decision.RUN;
        }
        int slot = minecraft.player.getInventory().getSelectedSlot();
        if (heldCount > 0 && HELD_SLOTS[heldCount - 1] == slot) {
            if (heldDoubleAnchor(minecraft)) {
                // The double anchor, waiting for its anchor like the glowstone before it: the first
                // click detonates the anchor, this one puts the next in its place. Both go.
                HELD_DOUBLE[heldCount - 1] = true;
                HELD_DOUBLE[heldCount] = true;
                HELD_SLOTS[heldCount++] = slot;
                AnchorStats.heldClick();
                AnchorStats.doubleAnchor();
                return Decision.HOLD;
            }
            // Another click with the same item for the same anchor: the one already waiting does
            // what it asks for; applied twice it would stack an anchor or waste a charge.
            AnchorStats.mergedClick();
            return Decision.DROP;
        }
        if (heldCount == 0) {
            heldSince = System.nanoTime();
            heldFor = target.immutable();
            heldCleared = false;
        }
        HELD_DOUBLE[heldCount] = false;
        HELD_SLOTS[heldCount++] = slot;
        AnchorStats.heldClick();
        return Decision.HOLD;
    }

    /**
     * Whether a second click with anchors, after one with anchors already waiting, is a double
     * anchor's: the waiting one will detonate the anchor (charged by glowstone waiting before it, or
     * drawn charged already), and is not itself the second click of a pair.
     */
    private static boolean heldDoubleAnchor(Minecraft minecraft) {
        if (!minecraft.player.getMainHandItem().is(Items.RESPAWN_ANCHOR) || HELD_DOUBLE[heldCount - 1]
                || !AnchorsConfig.settings().anchorChain) {
            return false;
        }
        Inventory inventory = minecraft.player.getInventory();
        for (int index = 0; index < heldCount - 1; index++) {
            if (inventory.getItem(HELD_SLOTS[index]).is(Items.GLOWSTONE)) {
                return true;
            }
        }
        BlockState drawn = AnchorVeil.predicted(heldFor);
        BlockState state = drawn != null ? drawn : minecraft.level.getBlockState(heldFor);
        return DetonationPredictor.isCharged(state);
    }

    /**
     * Whether a held click, released, still does what it was pressed for: an anchor goes down on a
     * free block, never on an anchor (a second anchor on top, or a detonation); glowstone charges an
     * anchor, never goes down as a block. Anything else runs as pressed.
     */
    private static boolean stillMeant(Minecraft minecraft, ItemStack stack) {
        if (stack.is(Items.RESPAWN_ANCHOR)) {
            return !AnchorContext.targetsAnchor(minecraft);
        }
        if (stack.is(Items.GLOWSTONE)) {
            return AnchorContext.targetsChargeableAnchor(minecraft);
        }
        return true;
    }

    /**
     * Runs the held clicks once the world has their anchor, each with the slot it was pressed with
     * and aimed at what the crosshair hits now; the slot the player holds now is restored afterwards.
     * If the anchor never reaches the world (the server did not place it), they are dropped when the
     * wait runs out: aimed again, they would land on the floor where it was meant to be.
     */
    private static void releaseHeld(Minecraft minecraft) {
        boolean caughtUp = heldCleared || AnchorChain.caughtUp(minecraft.level, heldFor);
        if (!caughtUp && System.nanoTime() - heldSince
                < Latency.answerWindowNanos(HOLD_TIMEOUT_NANOS, HOLD_TIMEOUT_MAX_NANOS)) {
            return;
        }
        int count = heldCount;
        heldCount = 0;
        if (!caughtUp) {
            AnchorStats.droppedClicks(count);
            return;
        }
        if (!AnchorContext.ready(minecraft) || minecraft.player == null) {
            // A screen or an item in use since: these clicks belong to a moment that has passed.
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        int selected = inventory.getSelectedSlot();
        int index = 0;
        boolean waitAgain = false;
        for (; index < count && AnchorContext.ready(minecraft); index++) {
            if (tickSlot >= 0 && HELD_SLOTS[index] != tickSlot) {
                // One slot per tick: the clicks held with another item run on the next tick.
                break;
            }
            if (inventory.getSelectedSlot() != HELD_SLOTS[index]) {
                inventory.setSelectedSlot(HELD_SLOTS[index]);
            }
            Mc.pick(minecraft);
            // A double anchor's clicks detonate an anchor and put one in its place: the chain judges them.
            if (!HELD_DOUBLE[index] && !stillMeant(minecraft, inventory.getItem(HELD_SLOTS[index]))) {
                AnchorStats.droppedClicks(1);
                continue;
            }
            AnchorChain.Outcome outcome = AnchorChain.decide(minecraft);
            if (outcome == AnchorChain.Outcome.CATCH_UP) {
                // Another anchor drawn but not in the world yet on its way: it waits for that one.
                waitAgain = true;
                heldFor = AnchorChain.catchUpTarget().immutable();
                break;
            }
            usesThisPass++;
            lastUseSlot = HELD_SLOTS[index];
            run(minecraft, switch (outcome) {
                case RUN_PREDICTED -> Decision.RUN_PREDICTED;
                case RUN_DETONATED -> Decision.RUN_DETONATED;
                case DROP -> Decision.DROP;
                default -> Decision.RUN;
            });
        }
        if (index < count && AnchorContext.ready(minecraft)) {
            // The world has their anchor already: they wait only for a tick that has not clicked
            // yet. Or another drawn anchor is on their way: they wait for it.
            System.arraycopy(HELD_SLOTS, index, HELD_SLOTS, 0, count - index);
            System.arraycopy(HELD_DOUBLE, index, HELD_DOUBLE, 0, count - index);
            heldCount = count - index;
            heldSince = System.nanoTime();
            heldCleared = !waitAgain;
            if (!waitAgain) {
                AnchorStats.nextTickPresses(count - index);
            }
        }
        // Selected again at once, sent to the server only by the next click or tick: this tick's
        // other clicks, pressed with the slot held now, wait for the next tick (mayConsume).
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
     * The double anchor: a double click with anchors on the player's charged anchor, both clicks in
     * one tick. The first detonates it; the second is the next anchor, which the server puts where
     * the old one exploded. Vanilla sends both, so the second is not a double click that could stack:
     * it goes on to the chain, which draws the new anchor at once. A third click finds that anchor
     * drawn and is a repeat again.
     */
    private static boolean doubleAnchor(Minecraft minecraft) {
        BlockPos target = targetBlock(minecraft);
        if (target == null || minecraft.player == null || !AnchorsConfig.settings().anchorChain
                || !minecraft.player.getMainHandItem().is(Items.RESPAWN_ANCHOR) || !DetonationPredictor.isDetonating(target)) {
            return false;
        }
        BlockState drawn = AnchorVeil.predicted(target);
        if (drawn == null || drawn.is(Blocks.RESPAWN_ANCHOR)) {
            return false;
        }
        AnchorStats.doubleAnchor();
        return true;
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

    /** The block under the crosshair, or {@code null}. */
    private static BlockPos targetBlock(Minecraft minecraft) {
        return minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos() : null;
    }

    private static boolean pending(KeyMapping mapping) {
        return ((KeyMappingAccessor) mapping).kohsAnchors$clickCount() > 0;
    }

    private static InputConstants.Key keyOf(KeyMapping mapping) {
        return ((KeyMappingAccessor) mapping).kohsAnchors$key();
    }
}
