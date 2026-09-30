package dev.zymekoh.kohsanchors.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The developer mode's inspector, like a browser's F12: every element the settings screen draws
 * registers its box, kind and details while it is drawn; under the pointer, the innermost one is
 * outlined (content in blue, its margin in orange) and described in the panel at the top left,
 * with the path of boxes it sits in and the screen's own numbers.
 *
 * <p>Nothing is recorded while developer mode is off: {@link #node} returns at once.</p>
 */
final class DevInspector {
    private static final int MAX_NODES = 512;
    private static final List<Node> NODES = new ArrayList<>(MAX_NODES);
    private static boolean enabled;
    private static boolean visible = true;
    private static long lastFrame = System.nanoTime();
    private static float frameMillis = 16.0F;

    private DevInspector() {
    }

    record Node(String kind, String name, int x, int y, int width, int height, String[] details) {
        boolean contains(double px, double py) {
            return px >= this.x && py >= this.y && px < this.x + this.width && py < this.y + this.height;
        }

        int area() {
            return Math.max(1, this.width) * Math.max(1, this.height);
        }
    }

    /** Start of a frame: forget last frame's boxes. */
    static void begin(boolean on) {
        enabled = on;
        NODES.clear();
        long now = System.nanoTime();
        frameMillis += (Math.min(250.0F, (now - lastFrame) / 1_000_000.0F) - frameMillis) * 0.1F;
        lastFrame = now;
    }

    static boolean enabled() {
        return enabled;
    }

    /** F12: shows or hides the panel and the highlight; the credit line stays. */
    static void toggle() {
        visible = !visible;
    }

    /** One drawn element: a panel, a text, a button, a card, a face of the anchor... */
    static void node(String kind, String name, int x, int y, int width, int height, String... details) {
        if (!enabled || NODES.size() >= MAX_NODES || width <= 0 || height <= 0) {
            return;
        }
        NODES.add(new Node(kind, name, x, y, width, height, details));
    }

    /** Draws the highlight, the inspector panel and the credit line. Called last, on top. */
    static void render(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY,
            String screenPath, String layoutShape) {
        if (!enabled) {
            return;
        }
        if (!visible) {
            credit(graphics, font, screenHeight);
            return;
        }
        // The innermost box under the pointer: the smallest that contains it, the latest on a tie.
        Node hovered = null;
        List<Node> path = new ArrayList<>();
        for (Node node : NODES) {
            if (node.contains(mouseX, mouseY)) {
                path.add(node);
                if (hovered == null || node.area() <= hovered.area()) {
                    hovered = node;
                }
            }
        }
        path.sort((a, b) -> Integer.compare(b.area(), a.area()));
        if (hovered != null) {
            highlight(graphics, font, hovered);
        }
        drawPanel(graphics, font, screenWidth, screenHeight, hovered, path, screenPath, layoutShape);
        credit(graphics, font, screenHeight);
    }

    private static void highlight(GuiGraphicsExtractor graphics, Font font, Node node) {
        int margin = 3;
        // Margin in orange, content in blue, border in cyan, as browsers draw it.
        graphics.fill(node.x() - margin, node.y() - margin, node.x() + node.width() + margin, node.y(), 0x40F6A04D);
        graphics.fill(node.x() - margin, node.y() + node.height(), node.x() + node.width() + margin,
                node.y() + node.height() + margin, 0x40F6A04D);
        graphics.fill(node.x() - margin, node.y(), node.x(), node.y() + node.height(), 0x40F6A04D);
        graphics.fill(node.x() + node.width(), node.y(), node.x() + node.width() + margin, node.y() + node.height(),
                0x40F6A04D);
        graphics.fill(node.x(), node.y(), node.x() + node.width(), node.y() + node.height(), 0x305B8CFF);
        AnchorsUi.outline(graphics, node.x(), node.y(), node.width(), node.height(), 0xE052F2FF);
        String tag = node.kind() + "  " + node.width() + "×" + node.height();
        int tagWidth = font.width(tag) + 8;
        int tagX = node.x();
        int tagY = node.y() - 14 >= 0 ? node.y() - 14 : node.y() + node.height() + 2;
        graphics.fill(tagX, tagY, tagX + tagWidth, tagY + 12, 0xF0101535);
        AnchorsUi.outline(graphics, tagX, tagY, tagWidth, 12, 0xFF5B6CFF);
        AnchorsUi.label(graphics, font, tag, tagX + 4, tagY + 2, 0xFF9DB0FF, false);
    }

    private static void drawPanel(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, Node hovered,
            List<Node> path, String screenPath, String layoutShape) {
        int width = Math.min(210, Math.max(150, screenWidth / 3));
        List<String> lines = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        int fps = frameMillis > 0.0F ? Math.round(1000.0F / frameMillis) : 0;
        lines.add("§9screen§r " + screenPath);
        lines.add("§9gui§r " + screenWidth + "×" + screenHeight + " @" + minecraft.getWindow().getGuiScale()
                + "x §8|§r " + layoutShape);
        lines.add("§9frame§r " + fps + " fps §8|§r " + String.format(Locale.ROOT, "%.1f", frameMillis)
                + " ms §8|§r " + NODES.size() + " nodes");
        if (hovered == null) {
            lines.add("");
            lines.add("§7" + Component.translatable("kohs_anchors.dev.inspector.hint").getString());
        } else {
            lines.add("");
            StringBuilder crumbs = new StringBuilder();
            for (int index = Math.max(0, path.size() - 4); index < path.size(); index++) {
                if (crumbs.length() > 0) {
                    crumbs.append(" › ");
                }
                crumbs.append(path.get(index).kind());
            }
            lines.add("§8" + crumbs);
            lines.add("§d" + hovered.kind() + "§r " + hovered.name());
            lines.add("§9box§r x=" + hovered.x() + " y=" + hovered.y() + " w=" + hovered.width() + " h="
                    + hovered.height());
            for (String detail : hovered.details()) {
                if (detail != null && !detail.isEmpty()) {
                    lines.add(detail);
                }
            }
        }
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                wrapped.add(FormattedCharSequence.EMPTY);
                continue;
            }
            wrapped.addAll(font.split(Component.literal(line), width - 10));
        }
        int height = 16 + wrapped.size() * 10 + 4;
        int x = 4;
        int y = 4;
        graphics.fill(x, y, x + width, y + height, 0xE00B0E24);
        graphics.fill(x, y, x + width, y + 13, 0xF0161C48);
        AnchorsUi.outline(graphics, x, y, width, height, 0xFF5B6CFF);
        AnchorsUi.label(graphics, font, "INSPECTOR · F12", x + 5, y + 3, 0xFF9DB0FF, false);
        String badge = "DEV";
        int badgeWidth = font.width(badge) + 6;
        graphics.fill(x + width - badgeWidth - 3, y + 2, x + width - 3, y + 11, 0xFF4F2FD9);
        AnchorsUi.label(graphics, font, badge, x + width - badgeWidth, y + 2, 0xFFFFFFFF, false);
        int lineY = y + 17;
        for (FormattedCharSequence line : wrapped) {
            AnchorsUi.line(graphics, font, line, x + 5, lineY, 0xFFDDE4FF);
            lineY += 10;
        }
    }

    /** The developer mode's signature, bottom left. */
    static void credit(GuiGraphicsExtractor graphics, Font font, int screenHeight) {
        String credit = Component.translatable("kohs_anchors.dev.credit").getString();
        AnchorsUi.label(graphics, font, credit, 5, screenHeight - 11, 0xFFA855F7, true);
    }
}
