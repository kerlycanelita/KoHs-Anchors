package dev.zymekoh.kohsanchors.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The server's explosion: its sound, then its flash, then its block debris, then knockback. The
 * first two are skipped when a prediction already showed them; debris is only skipped for anchors
 * with anchor debris turned off; knockback is never touched.
 *
 * <p>Each call is wrapped where it happens instead of at the head of the handler, which first
 * runs on the network thread only to be rescheduled onto the client thread.</p>
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @WrapWithCondition(method = "handleExplosion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private boolean kohsAnchors$explosionSound(ClientLevel level, double x, double y, double z, SoundEvent sound,
            SoundSource source, float volume, float pitch, boolean distanceDelay) {
        return DetonationPredictor.shouldPlayServerSound(x, y, z);
    }

    @WrapWithCondition(method = "handleExplosion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private boolean kohsAnchors$explosionFlash(ClientLevel level, ParticleOptions particle, double x, double y, double z,
            double xSpeed, double ySpeed, double zSpeed) {
        return DetonationPredictor.shouldDrawServerFlash();
    }

    @WrapWithCondition(method = "handleExplosion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;trackExplosionEffects(Lnet/minecraft/world/phys/Vec3;FILnet/minecraft/util/random/WeightedList;)V"))
    private boolean kohsAnchors$explosionDebris(ClientLevel level, Vec3 center, float radius, int blockCount,
            WeightedList<ExplosionParticleInfo> particles) {
        return DetonationPredictor.shouldDrawDebris(level, center);
    }
}
