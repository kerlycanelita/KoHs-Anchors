package dev.zymekoh.kohsanchors.input;

import dev.zymekoh.kohsanchors.compat.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** What counts as anchor play, and when the game is in a state where input can be sequenced. */
public final class AnchorContext {
    private AnchorContext() {
    }

    public static boolean isAnchorItem(ItemStack stack) {
        return stack.is(Items.RESPAWN_ANCHOR) || stack.is(Items.GLOWSTONE);
    }

    /**
     * The conditions under which Vanilla itself would run a use press as an interaction: in the
     * world, no screen, not already using an item, not breaking a block, hands free.
     */
    public static boolean ready(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        return player != null
                && minecraft.level != null
                && minecraft.gameMode != null
                && Mc.screen(minecraft) == null
                && Mc.overlay(minecraft) == null
                && !player.isSpectator()
                && !player.isUsingItem()
                && !player.isHandsBusy()
                && !minecraft.gameMode.isDestroying();
    }

    /** An anchor or glowstone in either hand, or the crosshair on an anchor. */
    public static boolean inAnchorPlay(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return false;
        }
        return isAnchorItem(player.getMainHandItem())
                || isAnchorItem(player.getOffhandItem())
                || targetsAnchor(minecraft);
    }

    public static boolean targetsAnchor(Minecraft minecraft) {
        return targetState(minecraft) instanceof BlockState state && state.is(Blocks.RESPAWN_ANCHOR);
    }

    /**
     * A container or other block with a menu under the crosshair, which a use would open. Those
     * presses are never reordered: opening a screen mid-burst would change what the rest mean.
     */
    public static boolean targetsMenuBlock(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        if (minecraft.player.isSecondaryUseActive()) {
            return false;
        }
        BlockPos position = hit.getBlockPos();
        return minecraft.level.getBlockState(position).getMenuProvider(minecraft.level, position) != null;
    }

    /** Whether two hit results point at different things, for the statistics. */
    public static boolean differentTarget(HitResult before, HitResult after) {
        if (before == after) {
            return false;
        }
        if (before == null || after == null || before.getType() != after.getType()) {
            return true;
        }
        if (before instanceof BlockHitResult first && after instanceof BlockHitResult second) {
            return !first.getBlockPos().equals(second.getBlockPos()) || first.getDirection() != second.getDirection();
        }
        if (before instanceof EntityHitResult first && after instanceof EntityHitResult second) {
            return first.getEntity() != second.getEntity();
        }
        return false;
    }

    private static BlockState targetState(Minecraft minecraft) {
        if (minecraft.level == null
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return minecraft.level.getBlockState(hit.getBlockPos());
    }
}
