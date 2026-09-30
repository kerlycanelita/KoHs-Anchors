package dev.zymekoh.kohsanchors.integration;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Leaves out the Mixins for other mods when those mods are not installed. Without it Mixin still
 * looks for their classes and writes "Error loading class" for Sodium's {@code LevelSlice} on
 * every launch, which reads like a fault of KoHs Anchor's. Minecraft's own targets are never
 * filtered: they apply, or the game says why at once.
 */
public final class OptionalMixins implements IMixinConfigPlugin {
    private static final String SODIUM_MIXIN = "dev.zymekoh.kohsanchors.mixin.SodiumLevelSliceMixin";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (SODIUM_MIXIN.equals(mixinClassName)) {
            return FabricLoader.getInstance().isModLoaded("sodium");
        }
        return true;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
