package dev.zymekoh.kohsanchors.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits the settings screen to every logical GUI size from 240 x 140 to 1600 x 1000 and fails the
 * build if any of them puts a region outside the screen, lets two regions overlap, or leaves no
 * room for the options. Runs without Minecraft: {@code gradlew checkLayout}.
 */
public final class AnchorsLayoutCheck {
    private AnchorsLayoutCheck() {
    }

    public static void main(String[] arguments) {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (int width = 240; width <= 1600; width += 7) {
            for (int height = 140; height <= 1000; height += 7) {
                check(width, height, failures);
                checked++;
            }
        }
        // Real windows at every GUI scale Minecraft offers for them: it never picks a scale that
        // leaves less than 320 x 240 logical pixels.
        int[][] windows = {{1280, 720}, {1366, 768}, {1920, 1080}, {1920, 1001}, {2560, 1440}, {854, 480}};
        for (int[] window : windows) {
            for (int scale = 1; window[0] / scale >= 320 && window[1] / scale >= 240; scale++) {
                check(window[0] / scale, window[1] / scale, failures);
                checked++;
            }
        }
        if (!failures.isEmpty()) {
            failures.stream().limit(40).forEach(System.err::println);
            throw new IllegalStateException(failures.size() + " layout problems in " + checked + " sizes");
        }
        System.out.println("Layout OK in " + checked + " sizes");
    }

    private static void check(int width, int height, List<String> failures) {
        AnchorsLayout layout = AnchorsLayout.fit(width, height);
        String size = width + "x" + height + ": ";
        AnchorsLayout.Rect screen = new AnchorsLayout.Rect(0, 0, width, height);
        AnchorsLayout.Rect panel = layout.panel;

        inside(size + "panel", panel, screen, failures);
        inside(size + "header", layout.header, panel, failures);
        inside(size + "options", layout.options, panel, failures);
        inside(size + "footer", layout.footer, panel, failures);
        inside(size + "done", layout.doneButton, layout.footer, failures);
        inside(size + "reset", layout.resetButton, layout.footer, failures);
        if (layout.header.bottom() > layout.options.y()) {
            failures.add(size + "header overlaps the options");
        }
        if (layout.options.bottom() > layout.footer.y()) {
            failures.add(size + "options overlap the footer");
        }
        if (layout.doneButton.intersects(layout.resetButton)) {
            failures.add(size + "footer buttons overlap");
        }
        if (layout.doneButton.width() < 40 || layout.doneButton.height() < 12) {
            failures.add(size + "done button too small: " + layout.doneButton);
        }
        if (layout.showsPreview()) {
            inside(size + "preview", layout.preview, panel, failures);
            if (layout.preview.intersects(layout.options)) {
                failures.add(size + "preview overlaps the options");
            }
            if (layout.options.width() < 230) {
                failures.add(size + "options too narrow beside the preview: " + layout.options.width());
            }
        }
        // At least one full option row (about 50 px with a three-line description) must fit.
        if (height >= 180 && layout.options.height() < 50) {
            failures.add(size + "options area too short: " + layout.options.height());
        }
        if (layout.rowWidth() < 150) {
            failures.add(size + "rows too narrow: " + layout.rowWidth());
        }

        inside(size + "tabs", layout.tabs, panel, failures);
        if (layout.header.bottom() > layout.tabs.y() || layout.tabs.bottom() > layout.options.y()) {
            failures.add(size + "tabs overlap the header or the options");
        }
        for (int index = 0; index < AnchorsLayout.TAB_COUNT; index++) {
            AnchorsLayout.Rect tab = layout.tab(index);
            inside(size + "tab " + index, tab, layout.tabs, failures);
            if (tab.width() < AnchorsLayout.MIN_TAB_WIDTH || tab.height() < 12) {
                failures.add(size + "tab " + index + " too small for its icon: " + tab);
            }
            if (index > 0 && tab.intersects(layout.tab(index - 1))) {
                failures.add(size + "tabs " + (index - 1) + " and " + index + " overlap");
            }
        }

        // The anchor workshop shares the body between its two rails and the stage.
        AnchorsLayout.Rect body = layout.body();
        inside(size + "body", body, panel, failures);
        AnchorsLayout.Workshop workshop = AnchorsLayout.workshop(body);
        inside(size + "workshop left", workshop.left(), body, failures);
        inside(size + "workshop stage", workshop.stage(), body, failures);
        inside(size + "workshop right", workshop.right(), body, failures);
        if (workshop.left().intersects(workshop.stage()) || workshop.stage().intersects(workshop.right())
                || workshop.left().intersects(workshop.right())) {
            failures.add(size + "workshop regions overlap");
        }
        if (workshop.stage().width() < 56) {
            failures.add(size + "workshop stage too narrow for the anchor: " + workshop.stage());
        }
        if (workshop.right().width() < 104) {
            failures.add(size + "workshop colour rail too narrow for the picker: " + workshop.right());
        }

        AnchorsLayout.Modal modal = AnchorsLayout.modal(width, height);
        inside(size + "modal", modal.box(), screen, failures);
        inside(size + "modal cancel", modal.cancel(), modal.box(), failures);
        inside(size + "modal confirm", modal.confirm(), modal.box(), failures);
        if (modal.cancel().intersects(modal.confirm())) {
            failures.add(size + "modal buttons overlap");
        }
        if (modal.confirm().width() < 60 || modal.cancel().width() < 40 || modal.confirm().height() < 12) {
            failures.add(size + "modal buttons too small: " + modal.cancel() + " " + modal.confirm());
        }
        // The warning needs room for its title and at least three lines above the buttons.
        if (modal.cancel().y() - modal.box().y() < 70) {
            failures.add(size + "modal too short for the warning: " + modal.box());
        }
    }

    private static void inside(String name, AnchorsLayout.Rect rect, AnchorsLayout.Rect outer, List<String> failures) {
        if (rect.width() < 0 || rect.height() < 0) {
            failures.add(name + " has a negative size: " + rect);
            return;
        }
        if (rect.x() < outer.x() || rect.y() < outer.y() || rect.right() > outer.right()
                || rect.bottom() > outer.bottom()) {
            failures.add(name + " " + rect + " leaves " + outer);
        }
    }
}
