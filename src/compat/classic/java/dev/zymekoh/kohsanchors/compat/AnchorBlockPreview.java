package dev.zymekoh.kohsanchors.compat;

import dev.zymekoh.kohsanchors.gui.preview.FullBrightLightEngine;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;

/** 26.1.x: the same preview, with entity types still on {@code EntityType}. */
public final class AnchorBlockPreview {
    private final FallingBlockRenderState state = new FallingBlockRenderState();

    public AnchorBlockPreview() {
        this.state.entityType = EntityType.FALLING_BLOCK;
        this.state.boundingBoxWidth = 1.0F;
        this.state.boundingBoxHeight = 1.0F;
        this.state.eyeHeight = 0.5F;
        this.state.outlineColor = EntityRenderState.NO_OUTLINE;
        MovingBlockRenderState block = this.state.movingBlockRenderState;
        block.blockPos = BlockPos.ZERO;
        block.randomSeedPos = BlockPos.ZERO;
        block.cardinalLighting = CardinalLighting.DEFAULT;
        block.lightEngine = FullBrightLightEngine.INSTANCE;
        block.blockState = Blocks.RESPAWN_ANCHOR.defaultBlockState();
    }

    public EntityRenderState withCharge(int charge) {
        this.state.movingBlockRenderState.blockState = Blocks.RESPAWN_ANCHOR.defaultBlockState()
                .setValue(RespawnAnchorBlock.CHARGE, Math.max(0, Math.min(4, charge)));
        return this.state;
    }

    /** Any block, for the settings screen's other previews. */
    public EntityRenderState withState(net.minecraft.world.level.block.state.BlockState blockState) {
        this.state.movingBlockRenderState.blockState = blockState;
        return this.state;
    }

    /**
     * A block drawn on its own in the world, lit by the world around it: the anchor fade's copy of
     * a detonated anchor. Opaque to the shared code, whose versions build it differently.
     */
    public static Object worldBlock(net.minecraft.client.multiplayer.ClientLevel level, BlockPos position,
            net.minecraft.world.level.block.state.BlockState blockState) {
        MovingBlockRenderState block = new MovingBlockRenderState();
        block.blockPos = position.immutable();
        block.randomSeedPos = block.blockPos;
        block.blockState = blockState;
        block.cardinalLighting = level.cardinalLighting();
        block.lightEngine = level.getLightEngine();
        return block;
    }

    /** Submits {@link #worldBlock} in the block's own space of {@code poses}. */
    public static void submitWorldBlock(com.mojang.blaze3d.vertex.PoseStack poses,
            net.minecraft.client.renderer.SubmitNodeCollector collector, Object block) {
        collector.submitMovingBlock(poses, (MovingBlockRenderState) block);
    }
}
