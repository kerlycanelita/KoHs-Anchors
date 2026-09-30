package dev.zymekoh.kohsanchors.input;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.glow.AnchorTracker;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Anchor debounce and glowstone debounce: a second anchor placement, or a second glowstone charge
 * or placement, that follows the last one with the same slot too closely is refused. That is how a
 * double click, a bouncing switch or a held key puts two anchors, or two glowstone, where the player
 * meant one.
 *
 * <p>It hooks the use itself ({@code MultiPlayerGameMode.useItemOn}), not a key, so it covers
 * whatever is bound to "use". A refused click returns {@code FAIL} before anything is predicted or
 * sent, exactly as if it had never been pressed: the server only sees the uses that happen. It only
 * ever removes the player's own clicks and never adds, repeats or times one.</p>
 *
 * <p>Detonations are never refused: a click with an anchor or glowstone on a charged anchor that
 * explodes it is not a placement. Switching to another slot ends the window, so "anchor, glowstone,
 * anchor" is never slowed down; only the same item straight after itself is. Both windows are off
 * (Vanilla) until the player moves the slider.</p>
 */
public final class AnchorDebounce {
    private static final int OFFHAND = -2;

    private enum Intent { NONE, PLACE_ANCHOR, USE_GLOWSTONE }

    private static final Window ANCHORS = new Window();
    private static final Window GLOWSTONE = new Window();

    /** A glowstone click this long after the last anchor action still belongs to the fight. */
    private static final long ANCHOR_PLAY_NANOS = 4_000_000_000L;

    private static Intent pendingIntent = Intent.NONE;
    private static long lastAnchorPlay = Long.MIN_VALUE / 2;
    private static long pendingAt;
    private static int pendingSlot;
    private static BlockPos pendingTarget;
    private static BlockPos pendingAlternative;

    private AnchorDebounce() {
    }

    /**
     * Start of {@code useItemOn}, for one hand.
     *
     * @return whether this use is refused
     */
    public static boolean refuses(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
        pendingIntent = Intent.NONE;
        ItemStack stack = player.getItemInHand(hand);
        boolean anchor = stack.is(Items.RESPAWN_ANCHOR);
        boolean glowstone = stack.is(Items.GLOWSTONE);
        if ((!anchor && !glowstone) || !(player.level() instanceof ClientLevel level)) {
            return false;
        }
        BlockPos clicked = hit.getBlockPos();
        BlockState state = level.getBlockState(clicked);
        Intent intent = intentOf(level, player, glowstone, clicked, state);
        if (intent == Intent.NONE) {
            return false;
        }
        long now = System.nanoTime();
        if (intent == Intent.PLACE_ANCHOR) {
            lastAnchorPlay = now;
        } else if (placesGlowstoneBlock(player, state) && AnchorsConfig.settings().glowstoneGuard
                && now - lastAnchorPlay < ANCHOR_PLAY_NANOS) {
            // Glowstone guard: in an anchor fight glowstone is only ever meant to charge an anchor.
            // Put down as a block (the anchor not there yet, or the click slipped off it) it blocks
            // the next anchor. The click is dropped, exactly as if it had not been pressed.
            AnchorStats.glowstoneGuardedClick();
            return true;
        }
        int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : OFFHAND;
        noteSelected(player);
        AnchorsConfig.Settings settings = AnchorsConfig.settings();
        if (intent == Intent.PLACE_ANCHOR) {
            if (ANCHORS.refuses(now, slot, settings.anchorDebounceMillis)) {
                AnchorStats.anchorDebounced();
                return true;
            }
        } else if (GLOWSTONE.refuses(now, slot, settings.glowstoneDebounceMillis)) {
            AnchorStats.glowstoneDebounced();
            return true;
        }
        pendingIntent = intent;
        pendingAt = now;
        pendingSlot = slot;
        // Where the anchor goes: the clicked block when it can be replaced (fire, grass), else the
        // one beside it. Vanilla asks the block in the context of the placement, which can differ
        // for a few blocks (layered snow), so the other one is kept as an alternative unless it is
        // a full block that no anchor can take.
        BlockPos beside = clicked.relative(hit.getDirection()).immutable();
        if (state.canBeReplaced()) {
            pendingTarget = clicked.immutable();
            pendingAlternative = beside;
        } else {
            pendingTarget = beside;
            pendingAlternative = state.isSolidRender() ? null : clicked.immutable();
        }
        return false;
    }

    /** End of {@code useItemOn}: a placement or charge that went through starts its window. */
    public static void afterUse(LocalPlayer player, InteractionResult result) {
        Intent intent = pendingIntent;
        pendingIntent = Intent.NONE;
        if (intent == Intent.NONE || !(result instanceof InteractionResult.Success)) {
            return;
        }
        lastAnchorPlay = System.nanoTime();
        if (intent == Intent.PLACE_ANCHOR) {
            ANCHORS.start(pendingAt, pendingSlot);
            if (player.level() instanceof ClientLevel level) {
                AnchorTracker.ownPlacement(level, pendingTarget, pendingAlternative);
                AnchorCycles.placed(pendingTarget);
            }
        } else {
            GLOWSTONE.start(pendingAt, pendingSlot);
        }
    }

    /** Once a client tick: a slot change ends both windows, a world change forgets them. */
    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            ANCHORS.clear();
            GLOWSTONE.clear();
            return;
        }
        noteSelected(minecraft.player);
    }

    /** An anchor was placed, charged or detonated: the glowstone guard is on for a few seconds. */
    public static void noteAnchorPlay() {
        lastAnchorPlay = System.nanoTime();
    }

    /** Whether a glowstone use goes down as a block instead of charging an anchor. */
    private static boolean placesGlowstoneBlock(LocalPlayer player, BlockState state) {
        return player.isSecondaryUseActive() || !state.is(Blocks.RESPAWN_ANCHOR);
    }

    /** Forgets both windows, so a new value never inherits an old click. */
    public static void settingsChanged() {
        ANCHORS.clear();
        GLOWSTONE.clear();
    }

    /**
     * What this use will do: place an anchor, use glowstone (charge or place it), or something the
     * debounce never touches (a detonation, setting the spawn, opening a container).
     */
    private static Intent intentOf(ClientLevel level, LocalPlayer player, boolean glowstone, BlockPos clicked,
            BlockState state) {
        boolean sneaking = player.isSecondaryUseActive();
        if (!sneaking && state.getMenuProvider(level, clicked) != null) {
            return Intent.NONE;
        }
        if (!sneaking && state.is(Blocks.RESPAWN_ANCHOR)) {
            int charge = state.getValue(RespawnAnchorBlock.CHARGE);
            if (glowstone) {
                // A full anchor is detonated (or used) by glowstone, not charged.
                return charge < RespawnAnchorBlock.MAX_CHARGES ? Intent.USE_GLOWSTONE : Intent.NONE;
            }
            if (charge > 0 || DetonationPredictor.anchorsWork(level, clicked)) {
                // Detonation, or setting the spawn point where anchors work.
                return Intent.NONE;
            }
        }
        return glowstone ? Intent.USE_GLOWSTONE : Intent.PLACE_ANCHOR;
    }

    private static void noteSelected(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        ANCHORS.noteSelected(selected);
        GLOWSTONE.noteSelected(selected);
    }

    /** One debounce window: the last use that went through, and whether the slot changed since. */
    private static final class Window {
        private boolean active;
        private long startedAt;
        private int slot = -1;
        private boolean switched;

        boolean refuses(long now, int slot, int millis) {
            return millis > 0 && this.active && !this.switched && slot == this.slot
                    && now - this.startedAt < millis * 1_000_000L;
        }

        void start(long at, int slot) {
            this.active = true;
            this.startedAt = at;
            this.slot = slot;
            this.switched = false;
        }

        void noteSelected(int selected) {
            if (this.active && this.slot != OFFHAND && selected != this.slot) {
                this.switched = true;
            }
        }

        void clear() {
            this.active = false;
            this.slot = -1;
            this.switched = false;
        }
    }
}
