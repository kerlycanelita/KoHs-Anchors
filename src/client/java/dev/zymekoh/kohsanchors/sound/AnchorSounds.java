package dev.zymekoh.kohsanchors.sound;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The anchor's charge and explosion sounds, replaced by the player's choice.
 *
 * <p>Only what this client hears changes: the charge sound the server sends, the explosion the
 * server sends, and the explosion KoHs Anchor's plays the moment a detonation is pressed. Every
 * sound keeps its position, category and randomness; the choice only swaps the sound and scales
 * its volume and pitch. Any sound the game knows can be chosen, including a resource pack's.</p>
 */
public final class AnchorSounds {
    /** Hand-picked sounds that suit a charge or a blast, first in the chooser. */
    private static final String[] FEATURED = {
            "minecraft:block.respawn_anchor.charge",
            "minecraft:entity.generic.explode",
            "minecraft:block.respawn_anchor.deplete",
            "minecraft:block.respawn_anchor.set_spawn",
            "minecraft:block.respawn_anchor.ambient",
            "minecraft:block.end_portal_frame.fill",
            "minecraft:block.end_portal.spawn",
            "minecraft:block.beacon.activate",
            "minecraft:block.beacon.power_select",
            "minecraft:block.amethyst_block.chime",
            "minecraft:block.amethyst_cluster.break",
            "minecraft:block.bell.use",
            "minecraft:block.bell.resonate",
            "minecraft:block.note_block.pling",
            "minecraft:block.note_block.chime",
            "minecraft:block.note_block.bell",
            "minecraft:block.glass.break",
            "minecraft:block.conduit.activate",
            "minecraft:block.trial_spawner.ominous_activate",
            "minecraft:block.vault.open_shutter",
            "minecraft:item.totem.use",
            "minecraft:item.trident.thunder",
            "minecraft:item.mace.smash_ground_heavy",
            "minecraft:entity.firework_rocket.large_blast",
            "minecraft:entity.firework_rocket.twinkle",
            "minecraft:entity.lightning_bolt.thunder",
            "minecraft:entity.lightning_bolt.impact",
            "minecraft:entity.warden.sonic_boom",
            "minecraft:entity.ender_dragon.growl",
            "minecraft:entity.wither.spawn",
            "minecraft:entity.wither.break_block",
            "minecraft:entity.dragon_fireball.explode",
            "minecraft:entity.wind_charge.wind_burst",
            "minecraft:entity.breeze.wind_burst",
            "minecraft:entity.experience_orb.pickup",
            "minecraft:entity.player.levelup",
            "minecraft:entity.illusioner.cast_spell",
            "minecraft:entity.evoker.cast_spell",
            "minecraft:entity.blaze.shoot",
            "minecraft:entity.ghast.shoot",
    };

    private static String cachedId = "";
    private static SoundEvent cachedEvent;
    private static List<String> allSounds;

    private AnchorSounds() {
    }

    /** The sound event for {@code id}, or {@code null} when the id cannot be read. */
    public static SoundEvent event(String id) {
        if (id.equals(cachedId) && cachedEvent != null) {
            return cachedEvent;
        }
        Identifier location = Identifier.tryParse(id);
        if (location == null) {
            return null;
        }
        cachedId = id;
        cachedEvent = SoundEvent.createVariableRangeEvent(location);
        return cachedEvent;
    }

    /**
     * The charge sound the server sent at an anchor, when the player replaced it.
     *
     * @return the player's charge sound, or {@code null} to play the server's as it is
     */
    public static AnchorsConfig.AnchorSound chargeReplacement(Holder<SoundEvent> sound) {
        AnchorsConfig.AnchorSound charge = AnchorsConfig.settings().chargeSound;
        if (!charge.custom || !isCharge(sound)) {
            return null;
        }
        return event(charge.sound) == null ? null : charge;
    }

    /** The server's explosion at {@code x, y, z}, when it is an anchor's and the player chose a sound. */
    public static AnchorsConfig.AnchorSound explosionReplacement(ClientLevel level, double x, double y, double z) {
        AnchorsConfig.AnchorSound explosion = AnchorsConfig.settings().explosionSound;
        if (!explosion.custom || !DetonationPredictor.isAnchorExplosion(level, new Vec3(x, y, z))) {
            return null;
        }
        return event(explosion.sound) == null ? null : explosion;
    }

    public static Holder<SoundEvent> holder(String id) {
        SoundEvent event = event(id);
        return Holder.direct(event == null ? SoundEvents.RESPAWN_ANCHOR_CHARGE : event);
    }

    /** The explosion KoHs Anchor's plays the moment a detonation is pressed. */
    public static void playExplosion(ClientLevel level, double x, double y, double z) {
        RandomSource random = level.getRandom();
        // The volume and pitch Vanilla uses for the server's explosion packet.
        float volume = 4.0F;
        float pitch = (1.0F + (random.nextFloat() - random.nextFloat()) * 0.2F) * 0.7F;
        SoundEvent sound = SoundEvents.GENERIC_EXPLODE.value();
        AnchorsConfig.AnchorSound explosion = AnchorsConfig.settings().explosionSound;
        if (explosion.custom && event(explosion.sound) instanceof SoundEvent chosen) {
            sound = chosen;
            volume *= explosion.volume / 100.0F;
            pitch *= explosion.pitch / 100.0F;
        }
        level.playLocalSound(x, y, z, sound, SoundSource.BLOCKS, volume, pitch, false);
    }

    /** The charge sound as the settings screen plays it. */
    public static void previewCharge(float pitchScale) {
        AnchorsConfig.AnchorSound charge = AnchorsConfig.settings().chargeSound;
        preview(charge.custom ? charge : null, SoundEvents.RESPAWN_ANCHOR_CHARGE, pitchScale, 1.0F);
    }

    /** The explosion as the settings screen plays it. */
    public static void previewExplosion() {
        AnchorsConfig.AnchorSound explosion = AnchorsConfig.settings().explosionSound;
        preview(explosion.custom ? explosion : null, SoundEvents.GENERIC_EXPLODE.value(), 1.0F, 0.8F);
    }

    /** Plays {@code id} once in the interface, with the given volume and pitch percentages. */
    public static void previewSound(String id, int volume, int pitch) {
        SoundEvent event = event(id);
        if (event != null) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch / 100.0F,
                    Math.max(0.0F, volume / 100.0F)));
        }
    }

    private static void preview(AnchorsConfig.AnchorSound custom, SoundEvent vanilla, float pitchScale, float volume) {
        SoundEvent event = custom != null && event(custom.sound) instanceof SoundEvent chosen ? chosen : vanilla;
        float pitch = pitchScale * (custom != null ? custom.pitch / 100.0F : 1.0F);
        float scaledVolume = volume * (custom != null ? custom.volume / 100.0F : 1.0F);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, scaledVolume));
    }

    private static boolean isCharge(Holder<SoundEvent> sound) {
        return sound != null && sound.value().location().equals(SoundEvents.RESPAWN_ANCHOR_CHARGE.location());
    }

    /** The featured sounds first, then every other sound the game knows, sorted. */
    public static List<String> catalogue() {
        if (allSounds == null) {
            List<String> sounds = new ArrayList<>();
            for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
                sounds.add(id.toString());
            }
            sounds.sort(String::compareTo);
            List<String> ordered = new ArrayList<>(sounds.size() + FEATURED.length);
            for (String featured : FEATURED) {
                // Only what this version has: a missing id would only log a warning when played.
                if (sounds.contains(featured)) {
                    ordered.add(featured);
                }
            }
            for (String sound : sounds) {
                if (!ordered.contains(sound)) {
                    ordered.add(sound);
                }
            }
            allSounds = ordered;
        }
        return allSounds;
    }

    /** How many of the catalogue's first entries are the hand-picked ones. */
    public static int featuredCount() {
        List<String> catalogue = catalogue();
        int count = 0;
        for (String featured : FEATURED) {
            if (count < catalogue.size() && catalogue.get(count).equals(featured)) {
                count++;
            }
        }
        return count;
    }

    /** A sound id as a player reads it: "Respawn anchor · charge". */
    public static String label(String id) {
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        String[] parts = path.split("\\.");
        StringBuilder label = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index].replace('_', ' ');
            if (index == 0 && (part.equals("block") || part.equals("entity") || part.equals("item")
                    || part.equals("ambient") || part.equals("music") || part.equals("ui"))) {
                continue;
            }
            if (label.length() > 0) {
                label.append(" · ");
            }
            label.append(part.isEmpty() ? part : part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        }
        return label.length() == 0 ? id : label.toString();
    }
}
