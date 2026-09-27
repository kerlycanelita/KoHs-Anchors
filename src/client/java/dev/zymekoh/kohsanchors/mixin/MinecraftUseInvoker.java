package dev.zymekoh.kohsanchors.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftUseInvoker {
    /** Vanilla's handling of one use press, the same call {@code handleKeybinds} makes. */
    @Invoker("startUseItem")
    void kohsAnchors$startUseItem();
}
