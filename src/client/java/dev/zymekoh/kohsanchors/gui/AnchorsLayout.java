package dev.zymekoh.kohsanchors.gui;

/**
 * Geometry of the settings screen for one logical GUI size. Pure arithmetic with no Minecraft
 * state, so every size can be checked without starting the game.
 *
 * <p>Three shapes: STANDARD shows the options beside the animated anchor and the session
 * numbers; COMPACT drops the anchor column and tightens the spacing; TIGHT keeps only what is
 * needed to change a setting and leave. The options always scroll before anything overlaps.</p>
 */
final class AnchorsLayout {
    static final int MAX_PANEL_WIDTH = 600;
    static final int MAX_PANEL_HEIGHT = 360;
    /** Room on the right of the options for the scrollbar. */
    static final int SCROLL_GUTTER = 6;

    final int screenWidth;
    final int screenHeight;
    final Rect panel;
    final Rect header;
    final Rect options;
    final Rect preview;
    final Rect footer;
    final Rect resetButton;
    final Rect doneButton;
    final boolean compact;
    final boolean tight;
    final int padding;

    private AnchorsLayout(int screenWidth, int screenHeight, Rect panel, Rect header, Rect options, Rect preview,
            Rect footer, Rect resetButton, Rect doneButton, boolean compact, boolean tight, int padding) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.panel = panel;
        this.header = header;
        this.options = options;
        this.preview = preview;
        this.footer = footer;
        this.resetButton = resetButton;
        this.doneButton = doneButton;
        this.compact = compact;
        this.tight = tight;
        this.padding = padding;
    }

    static AnchorsLayout fit(int width, int height) {
        int marginX = clamp(width / 20, 4, 28);
        int marginY = clamp(height / 16, 3, 22);
        int panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(0, width - marginX * 2));
        int panelHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(0, height - marginY * 2));
        Rect panel = new Rect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);

        boolean compact = panelWidth < 430 || panelHeight < 250;
        boolean tight = panelWidth < 300 || panelHeight < 180;
        int padding = tight ? 4 : compact ? 6 : 10;

        // Header and footer never take more than their share of a short screen.
        int headerHeight = Math.min(compact ? 24 : 34, Math.max(16, panelHeight / 5));
        int footerHeight = Math.min(compact ? 22 : 28, Math.max(16, panelHeight / 6));
        Rect header = new Rect(panel.x(), panel.y(), panel.width(), headerHeight);
        Rect footer = new Rect(panel.x(), panel.bottom() - footerHeight, panel.width(), footerHeight);

        int bodyX = panel.x() + padding;
        int bodyY = header.bottom() + (compact ? 3 : 6);
        int bodyWidth = Math.max(0, panel.width() - padding * 2);
        int bodyHeight = Math.max(0, footer.y() - bodyY - (compact ? 2 : 4));

        // The anchor column is decoration and statistics: it only appears when the options keep
        // a comfortable width beside it.
        boolean showPreview = bodyWidth >= 380 && bodyHeight >= 150;
        int previewWidth = showPreview ? clamp(Math.round(bodyWidth * 0.34F), 130, 200) : 0;
        int gap = showPreview ? 8 : 0;
        Rect options = new Rect(bodyX, bodyY, bodyWidth - previewWidth - gap, bodyHeight);
        Rect preview = showPreview ? new Rect(options.right() + gap, bodyY, previewWidth, bodyHeight) : Rect.EMPTY;

        int buttonHeight = clamp(footerHeight - 8, 12, 20);
        int buttonWidth = clamp(panel.width() / 5, 50, 96);
        int buttonY = footer.y() + (footerHeight - buttonHeight) / 2;
        Rect doneButton = new Rect(footer.right() - padding - buttonWidth, buttonY, buttonWidth, buttonHeight);
        Rect resetButton = new Rect(footer.x() + padding, buttonY, buttonWidth, buttonHeight);

        return new AnchorsLayout(width, height, panel, header, options, preview, footer, resetButton, doneButton,
                compact, tight, padding);
    }

    boolean showsPreview() {
        return this.preview.width() > 0;
    }

    /** Width of an option row inside the scrolling area. */
    int rowWidth() {
        return Math.max(0, this.options.width() - SCROLL_GUTTER);
    }

    static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Rect(int x, int y, int width, int height) {
        static final Rect EMPTY = new Rect(0, 0, 0, 0);

        int right() {
            return this.x + this.width;
        }

        int bottom() {
            return this.y + this.height;
        }

        int centerX() {
            return this.x + this.width / 2;
        }

        boolean contains(double pointX, double pointY) {
            return pointX >= this.x && pointX < right() && pointY >= this.y && pointY < bottom();
        }

        boolean intersects(Rect other) {
            return this.x < other.right() && other.x < right() && this.y < other.bottom() && other.y < bottom();
        }
    }
}
