package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.sound.AnchorSounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The sound chooser: every sound the game knows, the hand-picked ones for a charge or a blast
 * first, filtered as the player types. A click plays the sound and chooses it; Enter or the button
 * closes the chooser.
 */
final class SoundPicker {
    private static final int ROW_HEIGHT = 12;

    private final Consumer<String> choose;
    private final String title;
    private final long openedAt = System.nanoTime();
    private String query = "";
    private List<String> filtered = new ArrayList<>();
    private String chosen;
    private float scroll;
    private boolean closed;
    private AnchorsLayout.Rect box = AnchorsLayout.Rect.EMPTY;
    private AnchorsLayout.Rect list = AnchorsLayout.Rect.EMPTY;
    private AnchorsLayout.Rect done = AnchorsLayout.Rect.EMPTY;
    private int volume;
    private int pitch;

    SoundPicker(String title, String current, int volume, int pitch, Consumer<String> choose) {
        this.title = title;
        this.chosen = current;
        this.volume = volume;
        this.pitch = pitch;
        this.choose = choose;
        filter();
        int index = this.filtered.indexOf(current);
        if (index >= 0) {
            this.scroll = Math.max(0, index - 3) * ROW_HEIGHT;
        }
    }

    boolean closed() {
        return this.closed;
    }

    private void filter() {
        String needle = this.query.toLowerCase(Locale.ROOT).trim();
        List<String> result = new ArrayList<>();
        for (String id : AnchorSounds.catalogue()) {
            if (needle.isEmpty() || id.contains(needle) || AnchorSounds.label(id).toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(id);
            }
        }
        this.filtered = result;
        this.scroll = 0.0F;
    }

    void render(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY, boolean motion) {
        float open = motion ? AnchorsTheme.easeOutCubic((System.nanoTime() - this.openedAt) / 220_000_000.0F) : 1.0F;
        graphics.fill(0, 0, width, height, AnchorsTheme.withAlpha(0x06020A, Math.round(170 * open)));
        int boxWidth = Math.min(width - 16, 300);
        int boxHeight = Math.min(height - 16, 250);
        this.box = new AnchorsLayout.Rect((width - boxWidth) / 2, (height - boxHeight) / 2, boxWidth, boxHeight);
        AnchorsUi.halo(graphics, this.box.x(), this.box.y(), boxWidth, boxHeight, AnchorsTheme.ACCENT, 5, open);
        AnchorsUi.panel(graphics, this.box.x(), this.box.y(), boxWidth, boxHeight, AnchorsTheme.fade(AnchorsTheme.PANEL_TOP, open),
                AnchorsTheme.fade(AnchorsTheme.PANEL_BOTTOM, open));
        AnchorsUi.roundedOutline(graphics, this.box.x(), this.box.y(), boxWidth, boxHeight,
                AnchorsTheme.fade(AnchorsTheme.PANEL_BORDER, open));
        AnchorsUi.bladeCorners(graphics, this.box.x(), this.box.y(), boxWidth, boxHeight, 7, AnchorsTheme.fade(0xE0E9D5FF, open));
        if (open < 0.95F) {
            return;
        }
        int x = this.box.x() + 8;
        int y = this.box.y() + 7;
        int inner = boxWidth - 16;
        AnchorsUi.label(graphics, font, AnchorsUi.fit(font, this.title.toUpperCase(Locale.ROOT), inner), x, y,
                AnchorsTheme.TITLE, true);
        y += 13;
        AnchorsUi.energyLine(graphics, x, x + inner, y - 2, AnchorsTheme.ACCENT, System.nanoTime() / 1.0E9D, 1.0F);

        // The search field, always typing.
        AnchorsUi.panel(graphics, x, y + 2, inner, 14, 0xC0100818, 0xC0080410);
        AnchorsUi.roundedOutline(graphics, x, y + 2, inner, 14, AnchorsTheme.ACCENT_BRIGHT);
        String shown = this.query.isEmpty() ? Component.translatable("kohs_anchors.sounds.search").getString()
                : this.query + ((System.nanoTime() / 400_000_000L) % 2 == 0 ? "_" : "");
        AnchorsUi.label(graphics, font, this.query.isEmpty() ? AnchorsUi.fit(font, shown, inner - 8)
                : AnchorsUi.fitEnd(font, shown, inner - 8), x + 4, y + 5,
                this.query.isEmpty() ? AnchorsTheme.TEXT_DIM : AnchorsTheme.TITLE, false);
        y += 20;

        int buttonHeight = 16;
        this.list = new AnchorsLayout.Rect(x, y, inner, this.box.bottom() - 8 - buttonHeight - 4 - y);
        int featured = AnchorSounds.featuredCount();
        int maxScroll = Math.max(0, this.filtered.size() * ROW_HEIGHT - this.list.height());
        this.scroll = Math.max(0, Math.min(maxScroll, this.scroll));
        graphics.enableScissor(this.list.x(), this.list.y(), this.list.right(), this.list.bottom());
        int first = (int) (this.scroll / ROW_HEIGHT);
        for (int index = first; index < this.filtered.size(); index++) {
            int rowY = this.list.y() + index * ROW_HEIGHT - Math.round(this.scroll);
            if (rowY > this.list.bottom()) {
                break;
            }
            String id = this.filtered.get(index);
            boolean over = mouseX >= this.list.x() && mouseX < this.list.right() - 4 && mouseY >= rowY
                    && mouseY < rowY + ROW_HEIGHT && this.list.contains(mouseX, mouseY);
            boolean selected = id.equals(this.chosen);
            if (selected || over) {
                graphics.fill(this.list.x(), rowY, this.list.right() - 4, rowY + ROW_HEIGHT,
                        selected ? 0xC04A1C80 : 0x802A1248);
            }
            boolean isFeatured = this.query.isEmpty() && index < featured;
            if (isFeatured) {
                AnchorsUi.diamond(graphics, this.list.x() + 4, rowY + 6, 2, AnchorsTheme.MAGENTA);
            }
            // The name on the left, the id on the right, each cut short on its own side of a gap;
            // Vanilla's ids drop their "minecraft:" to leave room for the part that differs.
            int row = this.list.width() - 4 - 10 - 6;
            int labelRoom = Math.round(row * 0.56F);
            int idRoom = row - labelRoom - 10;
            String label = AnchorSounds.label(id);
            AnchorsUi.label(graphics, font, AnchorsUi.fit(font, label, labelRoom), this.list.x() + 10, rowY + 2,
                    selected ? AnchorsTheme.TITLE : AnchorsTheme.TEXT, false);
            String small = fitEnd(font, id.startsWith("minecraft:") ? id.substring(10) : id, idRoom);
            AnchorsUi.label(graphics, font, small, this.list.right() - 10 - font.width(small), rowY + 2,
                    AnchorsTheme.TEXT_DIM, false);
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int trackX = this.list.right() - 2;
            graphics.fill(trackX, this.list.y(), trackX + 2, this.list.bottom(), AnchorsTheme.SCROLL_TRACK);
            int thumb = Math.max(10, this.list.height() * this.list.height() / Math.max(1, this.filtered.size() * ROW_HEIGHT));
            int thumbY = this.list.y() + Math.round((this.list.height() - thumb) * (this.scroll / maxScroll));
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumb, AnchorsTheme.SCROLL_THUMB);
        }
        if (this.filtered.isEmpty()) {
            String none = Component.translatable("kohs_anchors.sounds.none").getString();
            AnchorsUi.label(graphics, font, none, this.list.centerX() - font.width(none) / 2, this.list.y() + 10,
                    AnchorsTheme.TEXT_DIM, false);
        }

        int doneWidth = Math.min(96, inner / 2);
        this.done = new AnchorsLayout.Rect(this.box.right() - 8 - doneWidth, this.box.bottom() - 8 - buttonHeight, doneWidth,
                buttonHeight);
        boolean overDone = this.done.contains(mouseX, mouseY);
        AnchorsButton.draw(graphics, this.done.x(), this.done.y(), doneWidth, buttonHeight,
                Component.translatable("kohs_anchors.button.done").getString(), true, false, overDone ? 1.0F : 0.0F, 0.0F,
                1.0F, -1.0F);
        String count = this.filtered.size() + " / " + AnchorSounds.catalogue().size();
        AnchorsUi.label(graphics, font, count, x, this.done.y() + 4, AnchorsTheme.TEXT_DIM, false);
        DevInspector.node("SoundPicker", this.title, this.box.x(), this.box.y(), boxWidth, boxHeight,
                "AnchorSounds.catalogue(): BuiltInRegistries.SOUND_EVENT", "featured first, filtered by id and label");
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != Keys.LEFT_BUTTON) {
            return true;
        }
        if (this.done.contains(mouseX, mouseY) || !this.box.contains(mouseX, mouseY)) {
            this.closed = true;
            return true;
        }
        if (this.list.contains(mouseX, mouseY)) {
            int index = (int) ((mouseY - this.list.y() + this.scroll) / ROW_HEIGHT);
            if (index >= 0 && index < this.filtered.size()) {
                this.chosen = this.filtered.get(index);
                this.choose.accept(this.chosen);
                AnchorSounds.previewSound(this.chosen, this.volume, this.pitch);
            }
        }
        return true;
    }

    boolean mouseScrolled(double amount) {
        this.scroll -= (float) amount * ROW_HEIGHT * 3.0F;
        return true;
    }

    boolean keyPressed(int key) {
        if (key == Keys.ESCAPE || Keys.confirms(key)) {
            this.closed = true;
        } else if (key == Keys.BACKSPACE && !this.query.isEmpty()) {
            this.query = this.query.substring(0, this.query.length() - 1);
            filter();
        }
        return true;
    }

    boolean charTyped(char character) {
        if (character >= ' ' && this.query.length() < 40) {
            this.query += character;
            filter();
        }
        return true;
    }

    /** {@code text} cut from the start with an ellipsis, so the end, which tells ids apart, shows. */
    private static String fitEnd(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        String dots = "\u2026";
        int start = 0;
        while (start < text.length() && font.width(dots + text.substring(start)) > width) {
            start++;
        }
        return dots + text.substring(start);
    }
}
