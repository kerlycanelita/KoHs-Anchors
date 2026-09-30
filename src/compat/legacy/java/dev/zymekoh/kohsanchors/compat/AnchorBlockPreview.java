package dev.zymekoh.kohsanchors.compat;

import dev.zymekoh.kohsanchors.gui.preview.FullBrightLightEngine;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 1.21.11: the moving block reads light and face shading from a whole {@code level} instead of
 * carrying them itself, so the preview supplies a one-block level that is fully lit.
 */
public final class AnchorBlockPreview {
    private static final BlockAndTintGetter LIT = new BlockAndTintGetter() {
        @Override
        public float getShade(Direction direction, boolean shade) {
            if (!shade) {
                return 1.0F;
            }
            return switch (direction) {
                case DOWN -> 0.5F;
                case UP -> 1.0F;
                case NORTH, SOUTH -> 0.8F;
                case WEST, EAST -> 0.6F;
            };
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return FullBrightLightEngine.INSTANCE;
        }

        @Override
        public int getBlockTint(BlockPos position, ColorResolver resolver) {
            return -1;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    };

    private final FallingBlockRenderState state = new FallingBlockRenderState();
    private final MovingBlockRenderState block = new MovingBlockRenderState();

    public AnchorBlockPreview() {
        this.state.entityType = EntityType.FALLING_BLOCK;
        this.state.boundingBoxWidth = 1.0F;
        this.state.boundingBoxHeight = 1.0F;
        this.state.eyeHeight = 0.5F;
        this.state.outlineColor = EntityRenderState.NO_OUTLINE;
        this.block.blockPos = BlockPos.ZERO;
        this.block.randomSeedPos = BlockPos.ZERO;
        this.block.level = LIT;
        this.block.blockState = Blocks.RESPAWN_ANCHOR.defaultBlockState();
        this.state.movingBlockRenderState = this.block;
    }

    public EntityRenderState withCharge(int charge) {
        this.block.blockState = Blocks.RESPAWN_ANCHOR.defaultBlockState()
                .setValue(RespawnAnchorBlock.CHARGE, Math.max(0, Math.min(4, charge)));
        return this.state;
    }

    /** Any block, for the settings screen's other previews. */
    public EntityRenderState withState(net.minecraft.world.level.block.state.BlockState blockState) {
        this.block.blockState = blockState;
        return this.state;
    }
}
