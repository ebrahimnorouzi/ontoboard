package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * Renders the parameter form to a PNG so its appearance can be looked at.
 *
 * <p>{@code CanvasDesignProofTest} does this for the diagram, and the reasoning is the same and
 * applies more strongly here: the form is what every single operation in this plugin puts in
 * front of a user, and until now the only way to see one was to launch Prot&eacute;g&eacute;,
 * find the menu item and open it. A claim about how it looks rested on somebody squinting.
 *
 * <p>A Swing panel paints headlessly as long as it is given a size and laid out, which is all
 * this does. The images land in {@code target/design/}, beside the canvas ones.
 *
 * <p>What it asserts is deliberately weak, for the reason the canvas proof gives: a test cannot
 * tell handsome from ugly. It can tell blank from not blank, and it can measure the things that
 * are actually decidable - that every control fits inside the panel, and that the form does not
 * run off the bottom of a laptop screen - which are the two ways a form goes wrong without
 * anybody noticing until a user reports it.
 */
class DialogDesignProofTest {

    private static File designDirectory() {
        File directory = new File("target/design");
        directory.mkdirs();
        return directory;
    }

    /**
     * One of each kind of control, with the help text and defaults a real operation supplies.
     *
     * <p>Taken from the real actions rather than invented: the choice and the two flags are
     * <i>Refresh imports</i>'s, the multiline is a commit message, the file field is
     * <i>Import terms…</i>'s, and the long help is the sort those actions actually carry. A
     * form reviewed on three short labels is a form reviewed on nothing.
     */
    private static List<Parameter> representativeForm() {
        return Arrays.asList(
                Parameter.of("what", "Do what", Parameter.Kind.CHOICE)
                        .choices("Show what is open", "Open a pull request for this branch")
                        .defaultValue("Show what is open")
                        .help("Showing what is open needs nothing but a GitHub remote and a "
                                + "logged-in gh.\n\nOpening one proposes the branch you are "
                                + "currently on. It is checked with gh's own --dry-run first, "
                                + "and you see the exact command before anything is created.")
                        .build(),
                Parameter.of("title", "Title", Parameter.Kind.TEXT)
                        .defaultValue("")
                        .help("What the pull request is called. Required when opening one.")
                        .build(),
                Parameter.of("body", "Description", Parameter.Kind.MULTILINE)
                        .defaultValue("")
                        .help("What changed and why, for whoever reviews it. Worth saying: "
                                + "which terms were added or obsoleted, and whether the "
                                + "quality report is clean.")
                        .build(),
                Parameter.of("source", "Take terms from", Parameter.Kind.FILE)
                        .defaultValue("")
                        .help("An OWL file on this machine, or an IRI to download.")
                        .build(),
                Parameter.of("rebuild", "Rebuild the modules", Parameter.Kind.FLAG)
                        .defaultValue("false")
                        .help("Off by default, so this reports without touching anything.")
                        .build(),
                Parameter.of("draft", "Open it as a draft", Parameter.Kind.FLAG)
                        .defaultValue("false")
                        .help("A draft is visible and reviewable but cannot be merged.")
                        .build());
    }

    /**
     * Lays the panel out at its preferred size and paints it.
     *
     * <p><b>{@code validate()}, not {@code doLayout()}.</b> This used recursive {@code doLayout}
     * and produced a picture of the result dialog with no column headers on its table - which
     * looked exactly like a real defect and was not one. A {@code JTable}'s header lives in its
     * scroll pane's column-header viewport, and only {@code validate} lays that out; checked
     * afterwards, the header is there with its three columns at 672x20. A proof image that
     * lies about the product is worse than no proof image.
     *
     * <p>{@code addNotify} comes first because validation needs a peer-ish hierarchy to settle
     * sizes against.
     */
    private static BufferedImage render(JPanel panel) {
        Dimension size = panel.getPreferredSize();
        panel.setSize(size);
        panel.addNotify();
        panel.validate();
        BufferedImage image = new BufferedImage(Math.max(1, size.width),
                Math.max(1, size.height), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        panel.paint(graphics);
        graphics.dispose();
        return image;
    }

    private static int distinctColours(BufferedImage image) {
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                seen.add(image.getRGB(x, y));
                if (seen.size() > 64) {
                    return seen.size();
                }
            }
        }
        return seen.size();
    }

    @Test
    void theFormRendersToAnImageSomebodyCanLookAt() throws Exception {
        Map<String, java.awt.Component> controls = new HashMap<String, java.awt.Component>();
        JPanel form = ParameterDialog.buildForm(null, representativeForm(), controls);

        BufferedImage image = render(form);
        File png = new File(designDirectory(), "parameter-form.png");
        ImageIO.write(image, "PNG", png);

        assertTrue(png.isFile() && png.length() > 2048,
                "no usable image at " + png.getAbsolutePath());
        assertTrue(distinctColours(image) > 8,
                "the form rendered as " + distinctColours(image) + " colours - it is blank");
    }

    /**
     * Every control is inside the panel it was laid out in.
     *
     * <p>A control whose bounds fall outside its parent is one a user cannot reach, and
     * {@code GridBagLayout} produces that silently when a cell's fill and weight disagree. This
     * is decidable, unlike whether the form looks nice.
     */
    @Test
    void noControlFallsOutsideTheForm() {
        Map<String, java.awt.Component> controls = new HashMap<String, java.awt.Component>();
        JPanel form = ParameterDialog.buildForm(null, representativeForm(), controls);
        form.setSize(form.getPreferredSize());
        form.addNotify();
        form.validate();

        for (Map.Entry<String, java.awt.Component> each : controls.entrySet()) {
            java.awt.Rectangle bounds = each.getValue().getBounds();
            java.awt.Component parent = each.getValue().getParent();
            assertTrue(parent != null, each.getKey() + " is in no container");
            assertTrue(bounds.width > 0 && bounds.height > 0,
                    each.getKey() + " laid out to " + bounds.width + "x" + bounds.height);
            assertTrue(bounds.x >= 0 && bounds.y >= 0,
                    each.getKey() + " is at " + bounds.x + "," + bounds.y);
            assertTrue(bounds.x + bounds.width <= parent.getWidth() + 1,
                    each.getKey() + " runs " + (bounds.x + bounds.width - parent.getWidth())
                            + "px past the right edge of its container");
        }
    }

    /**
     * A six-field form fits on a laptop screen without scrolling.
     *
     * <p>{@code TallForm} exists because a dialog once ran off the bottom of a 640px screen and
     * its buttons went with it. That fix wraps a tall form in a scroll pane; this checks the
     * other half, that an ordinary form does not need one. The threshold is deliberately the
     * smallest screen this plugin is smoked against rather than a round number.
     */
    @Test
    void anOrdinaryFormFitsWithoutScrolling() {
        JPanel form = ParameterDialog.buildForm(null, representativeForm(),
                new HashMap<String, java.awt.Component>());

        int height = form.getPreferredSize().height;

        assertTrue(height <= TallForm.tallestUnscrolled(),
                "a six-field form wants " + height + "px, and anything over "
                        + TallForm.tallestUnscrolled() + " gets a scroll pane - which is the "
                        + "right behaviour for a long form and a bad sign for a short one");
    }

    // ---------------------------------------------------------------- the result

    /**
     * The panel every operation shows when it finishes, rendered so it can be looked at.
     *
     * <p>Built from a real result shape: a summary, a table with the columns
     * <i>Refresh imports</i> uses, two warnings of the length those actions actually produce,
     * and a written file. A result panel reviewed on "Done." is a panel reviewed on nothing.
     */
    @Test
    void theResultPanelRendersToAnImageSomebodyCanLookAt() throws Exception {
        OperationResult result = OperationResult.of("Refresh imports")
                .columns("Import", "Declared as", "Module", "Term list", "Source")
                .row("bfo", "mirror", "present", "not used",
                        "http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl")
                .row("iao", "custom", "present", "present",
                        "http://purl.obolibrary.org/obo/iao.owl")
                .row("obi", "slme BOT, individuals exclude", "present", "present",
                        "http://purl.obolibrary.org/obo/obi.owl")
                .row("schema", "NOT DECLARED", "present", "present", "unrecorded")
                .note("Branch: main")
                .warn("schema is in src/ontology/imports but is not in this project's "
                        + "import_group.products, so make refresh-imports leaves it alone.")
                .warn("iao: module_type: custom - ODK's own generated rule for a custom module "
                        + "fails on purpose, because the real command is hand-written.")
                .summary("4 declared, 1 on disk and undeclared, 1 rebuildable here.")
                .build();

        JPanel content = ResultDialog.buildContent(result);
        BufferedImage image = render(content);
        File png = new File(designDirectory(), "result-dialog.png");
        ImageIO.write(image, "PNG", png);

        assertTrue(png.isFile() && png.length() > 2048, png.getAbsolutePath());
        assertTrue(distinctColours(image) > 8,
                "the result panel rendered as " + distinctColours(image) + " colours");
    }
}
