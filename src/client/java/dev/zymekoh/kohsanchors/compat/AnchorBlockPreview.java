package dev.zymekoh.kohsanchors.compat;

import dev.zymekoh.kohsanchors.gui.preview.FullBrightLightEngine;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;

/**
 * The real respawn anchor model, textures and all, for the settings screen. It is the render state
 * of a falling block, which Vanilla's GUI entity renderer can draw with any rotation.
 *
 * <p>This copy is for 26.2 and 26.3, where entity types moved to {@code EntityTypes}; the
 * classic and legacy copies differ only in how the state is spelled.</p>
 */
public final class AnchorBlockPreview {
    private final FallingBlockRenderState state = new FallingBlockRenderState();

    public AnchorBlockPreview() {
        this.state.entityType = EntityTypes.FALLING_BLOCK;
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

    /** The anchor with {@code charge} glowstone in it, 0 to 4. */
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
}
