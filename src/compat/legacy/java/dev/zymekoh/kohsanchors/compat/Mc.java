package dev.zymekoh.kohsanchors.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

/** 1.21.11: the screen lives on Minecraft and the crosshair raycast on GameRenderer. */
public final class Mc {
    private Mc() {
    }

    public static Screen screen(Minecraft minecraft) {
        return minecraft.screen;
    }

    public static Overlay overlay(Minecraft minecraft) {
        return minecraft.getOverlay();
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    public static void pick(Minecraft minecraft) {
        minecraft.gameRenderer.pick(1.0F);
    }

    /** 1.21.11 draws subtitles with the HUD, not from the screen background. */
    public static void extractDeferredSubtitles(Minecraft minecraft) {
    }
}
