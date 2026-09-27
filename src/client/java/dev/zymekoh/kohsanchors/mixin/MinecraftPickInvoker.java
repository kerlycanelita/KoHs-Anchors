package dev.zymekoh.kohsanchors.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 26.x only: the crosshair raycast moved from GameRenderer to a private method on Minecraft. */
@Mixin(Minecraft.class)
public interface MinecraftPickInvoker {
    @Invoker("pick")
    void kohsAnchors$pick(float partialTick);
}
