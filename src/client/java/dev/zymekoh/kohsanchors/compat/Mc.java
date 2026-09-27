package dev.zymekoh.kohsanchors.compat;

import dev.zymekoh.kohsanchors.mixin.MinecraftPickInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

/**
 * Everything that is spelled differently between the supported Minecraft versions.
 *
 * <p>This copy is the 26.2 and 26.3 one, where the screen and overlay moved from Minecraft to
 * Gui. The build swaps in {@code src/compat/classic} for 26.1.x and {@code src/compat/legacy} for
 * 1.21.11; the three must keep the same methods with the same meaning.</p>
 */
public final class Mc {
    private Mc() {
    }

    public static Screen screen(Minecraft minecraft) {
        return minecraft.gui.screen();
    }

    public static Overlay overlay(Minecraft minecraft) {
        return minecraft.gui.overlay();
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.gui.setScreen(screen);
    }

    /** Vanilla's crosshair raycast, exactly as the start of every tick runs it. */
    public static void pick(Minecraft minecraft) {
        ((MinecraftPickInvoker) minecraft).kohsAnchors$pick(1.0F);
    }

    /** Subtitles are drawn from the screen background, so a screen that owns it must ask. */
    public static void extractDeferredSubtitles(Minecraft minecraft) {
        minecraft.gui.hud.extractDeferredSubtitles();
    }
}
