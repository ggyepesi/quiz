package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A FlowLayout row inside the run section's fixed-height GridLayout WRAPS rather than
 * growing, so anything past the first line is simply not on screen — buttons vanish on
 * a narrow window instead of the panel getting taller. Each row therefore has to fit
 * the narrowest window worth supporting.
 */
class RunButtonRowLayoutTest {

    private static final int NARROW_WINDOW = 520;

    @Test void everyRunRowFitsANarrowWindowOnOneLine() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        for (String label : List.of("Remap (no download)", "Enrich (declared fields)")) {
            row.add(new javax.swing.JButton(label));
        }
        assertTrue(widthOf(row) <= NARROW_WINDOW,
                "the reuse row needs " + widthOf(row) + "px, more than a "
                        + NARROW_WINDOW + "px window can show on one line");

        JPanel generate = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        for (String label : List.of("Generate instances", "Generate domain", "Cancel",
                "Depth")) {
            generate.add(new javax.swing.JButton(label));
        }
        assertTrue(widthOf(generate) <= NARROW_WINDOW,
                "the generate row needs " + widthOf(generate) + "px");
    }

    /** The width every child needs side by side, plus the layout's gaps. */
    private static int widthOf(Container row) {
        List<Component> children = new ArrayList<>(List.of(row.getComponents()));
        int width = 0;
        for (Component child : children) {
            if (child instanceof AbstractButton button) {
                width += button.getPreferredSize().width;
            }
        }
        return width + 4 * (children.size() + 1);
    }
}
