package dev.zymekoh.kohsanchors.mixin;

import dev.zymekoh.kohsanchors.input.AnchorDebounce;
import dev.zymekoh.kohsanchors.input.AnchorInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every use of an item on a block, one hand at a time, whatever key started it. A use the anchor or
 * glowstone debounce refuses ends here with {@code FAIL}, before any prediction or packet, and
 * Vanilla's {@code startUseItem} stops at a {@code FAIL}. Every other use is only watched.
 *
 * <p>Clicks and attacks also mark the slot this tick was sent with, and the moment Vanilla tells
 * the server the selected slot is watched, so a slot change after a click of the same tick would
 * show up in the session counters.</p>
 */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Shadow
    private int carriedIndex;

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void kohsAnchors$debounce(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> callback) {
        if (AnchorDebounce.refuses(player, hand, hit)) {
            callback.setReturnValue(InteractionResult.FAIL);
            return;
        }
        AnchorInput.onInteraction(Minecraft.getInstance());
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void kohsAnchors$useItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callback) {
        AnchorInput.onInteraction(Minecraft.getInstance());
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void kohsAnchors$attack(Player player, Entity target, CallbackInfo callback) {
        AnchorInput.onInteraction(Minecraft.getInstance());
    }

    @Inject(method = "ensureHasSentCarriedItem", at = @At("HEAD"))
    private void kohsAnchors$carriedItem(CallbackInfo callback) {
        AnchorInput.beforeCarriedItemSync(Minecraft.getInstance(), this.carriedIndex);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void kohsAnchors$afterUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> callback) {
        AnchorDebounce.afterUse(player, callback.getReturnValue());
    }
}
