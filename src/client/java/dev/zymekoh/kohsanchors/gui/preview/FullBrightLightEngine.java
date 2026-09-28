package dev.zymekoh.kohsanchors.gui.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Full sky and block light everywhere. The preview anchor is rendered outside any world, where
 * the only other choice is darkness; this lights it the way blocks look in the inventory.
 */
public final class FullBrightLightEngine extends LevelLightEngine {
    public static final FullBrightLightEngine INSTANCE = new FullBrightLightEngine();

    private static final LayerLightEventListener FULL = new LayerLightEventListener() {
        @Override
        public DataLayer getDataLayerData(SectionPos position) {
            return null;
        }

        @Override
        public int getLightValue(BlockPos position) {
            return 15;
        }

        @Override
        public void checkBlock(BlockPos position) {
        }

        @Override
        public boolean hasLightWork() {
            return false;
        }

        @Override
        public int runLightUpdates() {
            return 0;
        }

        @Override
        public void updateSectionStatus(SectionPos position, boolean empty) {
        }

        @Override
        public void setLightEnabled(ChunkPos position, boolean enabled) {
        }

        @Override
        public void propagateLightSources(ChunkPos position) {
        }
    };

    private FullBrightLightEngine() {
        super(new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
                return null;
            }

            @Override
            public BlockGetter getLevel() {
                return EmptyBlockGetter.INSTANCE;
            }
        }, false, false);
    }

    @Override
    public LayerLightEventListener getLayerListener(LightLayer layer) {
        return FULL;
    }

    @Override
    public int getRawBrightness(BlockPos position, int skyDarkening) {
        return 15;
    }
}
