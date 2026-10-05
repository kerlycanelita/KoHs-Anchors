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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

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
 * as early clicks held as above: at most six of them, for at most 0.7 s, each applied at most once
 * with the slot that was selected when it was pressed; a repeat of the waiting click and a click
 * whose anchor stayed are not applied, as Vanilla would have spent them on that anchor. No slot is chosen that the
 * player did not press, the repeat delay for a held key is untouched, and no packet is written
 * here: Vanilla's {@code startUseItem} sends exactly what it would send for the same press.</p>
 */
public final class AnchorInput {
    private static final InputJournal JOURNAL = new InputJournal();
    private static final int[] BURST = new int[InputJournal.CAPACITY];

    /** At most this many clicks wait for one anchor; more than that is not a burst but a hold. */
    private static final int HOLD_CAPACITY = 6;
    /**
     * The shortest wait for a removal; on a slower connection the wait follows the round trip, as
     * the veil does (three round trips and a quarter second, up to 1.5 s). A click still waiting
     * then is dropped. A fixed 0.7 s dropped the clicks of players on 200 ms and more, or of a server
     * whose tick lagged, which they felt as anchors that ghosted.
     */
    private static final long HOLD_TIMEOUT_NANOS = 700_000_000L;
    private static final long HOLD_TIMEOUT_MAX_NANOS = 1_500_000_000L;
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
    /** The held clicks' block is already free; they only wait for a tick of their own. */
    private static boolean heldCleared;
    /** What the held clicks will do is drawn already ({@link #drawHeld}); taken back if they are dropped. */
    private static boolean heldDrawn;

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
        if (usesThisPass == 0) {
            DetonationPredictor.firstUseOfTick(minecraft.level, targetBlock(minecraft));
        }
        if (refreshTargetBeforeUse(minecraft) && mergesRepeat(minecraft)) {
            AnchorStats.mergedClick();
            return Decision.DROP;
        }
        if (DetonationPredictor.awaitsUse(targetBlock(minecraft))) {
            // The detonation this very press showed when it was pressed. Its anchor counts as
            // exploding already, but that explosion is what this use is about to cause: it runs
            // now, as Vanilla would run it, instead of waiting for a removal only it can bring.
            return Decision.RUN;
        }
        if (ServerLock.fastChain()) {
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
                switch (decide(minecraft)) {
                    case RUN -> ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem();
                    case RUN_PREDICTED -> runPredicted(minecraft);
                    default -> {
                    }
                }
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
            return overflow(catchUp);
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
            return overflow(catchUp);
        }
        if (heldCount == 0) {
            heldSince = System.nanoTime();
            heldFor = target.immutable();
            heldForCatchUp = catchUp;
            heldCleared = false;
            heldDrawn = false;
        }
        HELD_SLOTS[heldCount++] = slot;
        AnchorStats.heldClick();
        if (!catchUp) {
            drawHeld(minecraft, heldFor);
        }
        return Decision.HOLD;
    }

    /**
     * A click aimed at an exploding anchor that cannot wait (the wait is full, or waits for another
     * block) is dropped: run now it could only land on the anchor that is going away, placing an
     * anchor beside it or glowstone as a block where the server has already cleared it. Pressing
     * faster than the server can remove anchors is what fills the wait. A click that waits for a
     * drawn anchor to catch up runs, as the no-wait chain always has.
     */
    private static Decision overflow(boolean catchUp) {
        if (catchUp) {
            return Decision.RUN;
        }
        AnchorStats.droppedClicks(1);
        return Decision.DROP;
    }

    /**
     * Whether a held click, released, still does what it was pressed for: an anchor goes down on a
     * free block, never on an anchor (a second anchor on top, or a detonation); glowstone charges an
     * anchor, never goes down as a block. Anything else runs as pressed. A cycle whose anchor click
     * was dropped (a full wait) would otherwise put its glowstone in the hole.
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
        // The server acknowledged the detonation and kept the anchor: the clicks meant for the
        // free block would land on it, so they are dropped now instead of at the timeout.
        // Kept: the server acknowledged the detonation and the anchor is still there; or the
        // detonation was taken back (shown at a press whose click went elsewhere) or ran out while
        // the anchor still stands. The clicks meant for the free block would land on it: dropped.
        boolean kept = !heldCleared && !heldForCatchUp && (DetonationPredictor.failed(heldFor)
                || !DetonationPredictor.isDetonating(heldFor) && minecraft.level != null
                && minecraft.level.getBlockState(heldFor).is(net.minecraft.world.level.block.Blocks.RESPAWN_ANCHOR));
        boolean cleared = heldCleared || !kept && (heldForCatchUp ? FastChain.caughtUp(minecraft.level, heldFor)
                : !DetonationPredictor.isDetonating(heldFor));
        if (!cleared && !kept && System.nanoTime() - heldSince
                < Latency.answerWindowNanos(HOLD_TIMEOUT_NANOS, HOLD_TIMEOUT_MAX_NANOS)) {
            return;
        }
        int count = heldCount;
        heldCount = 0;
        if (!cleared) {
            AnchorStats.droppedClicks(count);
            if (kept) {
                DetonationPredictor.forget(heldFor);
            }
            takeBackDrawn(minecraft);
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
        boolean dropped = false;
        for (; index < count && AnchorContext.ready(minecraft); index++) {
            if (tickSlot >= 0 && HELD_SLOTS[index] != tickSlot) {
                // One slot per tick: the clicks held with another item run on the next tick.
                break;
            }
            if (inventory.getSelectedSlot() != HELD_SLOTS[index]) {
                inventory.setSelectedSlot(HELD_SLOTS[index]);
            }
            Mc.pick(minecraft);
            if (!ServerLock.fastChain()) {
                BlockPos aim = targetBlock(minecraft);
                if (aim != null && DetonationPredictor.isDetonating(aim)) {
                    // Since they were held, the clicks before these placed, charged and detonated
                    // another anchor where they aim: they wait for that anchor's removal now, as
                    // they would have if they had been pressed at this moment.
                    waitAgain = true;
                    heldFor = aim.immutable();
                    heldForCatchUp = false;
                    break;
                }
                if (!stillMeant(minecraft, inventory.getItem(HELD_SLOTS[index]))) {
                    AnchorStats.droppedClicks(1);
                    dropped = true;
                    continue;
                }
                usesThisPass++;
                lastUseSlot = HELD_SLOTS[index];
                ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem();
                continue;
            }
            usesThisPass++;
            lastUseSlot = HELD_SLOTS[index];
            // The chain judges the released click against what is drawn now, as any other.
            switch (FastChain.decide(minecraft)) {
                case RUN -> ((MinecraftUseInvoker) minecraft).kohsAnchors$startUseItem();
                case RUN_PREDICTED -> runPredicted(minecraft);
                default -> AnchorStats.droppedClicks(1);
            }
        }
        if (dropped) {
            // What was drawn for them will not come now: the world is drawn as it is.
            takeBackDrawn(minecraft);
        }
        if (index < count && AnchorContext.ready(minecraft)) {
            // Their block is free already: they wait only for a tick that has not clicked yet.
            // Or it holds a new exploding anchor: they wait for its removal.
            System.arraycopy(HELD_SLOTS, index, HELD_SLOTS, 0, count - index);
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
     * Draws what a held click will do the moment it is pressed, instead of when the server's
     * removal lets it run: the next anchor where the exploding one stands, or one more charge in
     * the anchor drawn there. The click itself still waits and is sent exactly as before; only the
     * frame stops waiting, the way Vanilla draws a block the moment it is placed. Nothing is drawn
     * when the released click could land elsewhere (the crosshair crosses the anchor towards open
     * space, or the block behind is out of reach), and what was drawn is taken back if the click
     * is dropped. Players who press faster than their ping saw nothing until the removal and then
     * the whole cycle at once: that wait and that jump were the heavy, abrupt clicks.
     */
    private static void drawHeld(Minecraft minecraft, BlockPos target) {
        if (!(minecraft.level instanceof ClientLevel level) || minecraft.player == null
                || minecraft.player.isSecondaryUseActive() || DetonationPredictor.anchorsWork(level, target)) {
            return;
        }
        BlockState drawn = AnchorVeil.predicted(target);
        if (drawn == null) {
            return;
        }
        ItemStack main = minecraft.player.getMainHandItem();
        boolean anchorDrawn = drawn.is(Blocks.RESPAWN_ANCHOR);
        if (main.is(Items.RESPAWN_ANCHOR) && !anchorDrawn && landsInPlace(minecraft, target)) {
            AnchorVeil.predict(level, target, AnchorVeil.anchor(0), AnchorVeil.anchor(0));
            heldDrawn = true;
            AnchorStats.drawnHeldClick();
        } else if (main.is(Items.GLOWSTONE) && anchorDrawn
                && drawn.getValue(RespawnAnchorBlock.CHARGE) < RespawnAnchorBlock.MAX_CHARGES) {
            int charge = drawn.getValue(RespawnAnchorBlock.CHARGE) + 1;
            AnchorVeil.predict(level, target, AnchorVeil.anchor(charge), AnchorVeil.anchor(charge));
            heldDrawn = true;
            AnchorStats.drawnHeldClick();
        }
    }

    /** Draws the world again where held clicks that were dropped had their outcome drawn. */
    private static void takeBackDrawn(Minecraft minecraft) {
        if (heldDrawn && heldFor != null && minecraft.level != null) {
            AnchorVeil.lift(minecraft.level, heldFor);
        }
        heldDrawn = false;
    }

    /**
     * Whether a click aimed at the anchor at {@code target} lands in its place once the anchor is
     * gone: the crosshair's ray leaves the anchor's block into a sturdy face within reach, which
     * the released click then hits, putting the new anchor right where the old one stood.
     */
    static boolean landsInPlace(Minecraft minecraft, BlockPos target) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || minecraft.level == null || minecraft.player == null) {
            return false;
        }
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 entry = hit.getLocation();
        Vec3 direction = entry.subtract(eye);
        if (direction.lengthSqr() < 1.0E-6D) {
            return false;
        }
        Optional<Vec3> exit = new AABB(target).clip(entry.add(direction.normalize().scale(2.0D)), entry);
        if (exit.isEmpty() || exit.get().distanceTo(eye) > minecraft.player.blockInteractionRange()) {
            return false;
        }
        Direction face = exitFace(target, exit.get());
        BlockPos behind = target.relative(face);
        return !AnchorVeil.isVeiled(behind)
                && minecraft.level.getBlockState(behind).isFaceSturdy(minecraft.level, behind, face.getOpposite());
    }

    /** The face of {@code block} that {@code point}, on its surface, lies on. */
    private static Direction exitFace(BlockPos block, Vec3 point) {
        double x = point.x - block.getX();
        double y = point.y - block.getY();
        double z = point.z - block.getZ();
        double[] distances = {y, 1.0D - y, z, 1.0D - z, x, 1.0D - x};
        Direction[] faces = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        int nearest = 0;
        for (int index = 1; index < distances.length; index++) {
            if (Math.abs(distances[index]) < Math.abs(distances[nearest])) {
                nearest = index;
            }
        }
        return faces[nearest];
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
