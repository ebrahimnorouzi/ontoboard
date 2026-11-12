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

    // ---------------------------------------------------------------- the log stays on screen

    /** How much of the panel's height the log needs for its tab and a handful of lines. */
    private static final int LOG_NEEDS = 110;

    private static <T extends java.awt.Component> T find(java.awt.Container in, Class<T> type) {
        for (java.awt.Component child : in.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof java.awt.Container) {
                T deeper = find((java.awt.Container) child, type);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    private static void labelTexts(java.awt.Container in, StringBuilder into) {
        for (java.awt.Component child : in.getComponents()) {
            if (child instanceof javax.swing.JLabel) {
                into.append(((javax.swing.JLabel) child).getText()).append('\n');
            }
            if (child instanceof java.awt.Container) {
                labelTexts((java.awt.Container) child, into);
            }
        }
    }

    /** A release of an ODK project: twelve files written, a transcript of a hundred lines. */
    private static OperationResult aReleasesResult(List<File> files) {
        OperationResult.Builder result = OperationResult.of("Release");
        result.note("Project: C:\\Users\\somebody\\Documents\\ontologies\\eggboil");
        for (int line = 0; line < 100; line++) {
            result.note("robot --catalog catalog-v001.xml merge --input line-" + line + ".owl");
        }
        for (File file : files) {
            result.wrote(file);
        }
        return result.summary("Released eggboil 1.0.0: 6 files at the top of the project, a "
                + "copy under releases/1.0.0, committed and tagged v1.0.0 on main. Nothing was "
                + "pushed - when you are ready, in a terminal: git push --follow-tags").build();
    }

    private static List<File> twelveFiles() {
        List<File> files = new java.util.ArrayList<File>();
        File root = new File("C:\\Users\\somebody\\Documents\\ontologies\\eggboil");
        for (String folder : new String[] {"", "releases/1.0.0/"}) {
            for (String name : new String[] {"eggboil.obo", "eggboil.owl", "eggboil-base.obo",
                "eggboil-base.owl", "eggboil-full.obo", "eggboil-full.owl"}) {
                files.add(new File(root, folder + name));
            }
        }
        return files;
    }

    /**
     * The result dialog cannot be resized, so what is pushed out of it cannot be reached. A
     * release writes a dozen files; listed one a line above the log, they took the whole panel
     * and the transcript of the build was not on screen at all - seen in a live run of
     * 1.153.0's Release, where only "Save report..." still led to it.
     */
    @Test
    void aResultThatWroteManyFilesStillShowsItsLog() throws Exception {
        List<File> files = twelveFiles();
        JPanel content = ResultDialog.buildContent(aReleasesResult(files));
        BufferedImage image = render(content);
        ImageIO.write(image, "PNG", new File(designDirectory(), "result-dialog-release.png"));

        javax.swing.JTabbedPane tabs = find(content, javax.swing.JTabbedPane.class);
        assertTrue(tabs.getHeight() >= LOG_NEEDS, "the log has " + tabs.getHeight()
                + " px of the panel's " + content.getHeight());
        assertTrue(content.getPreferredSize().height <= 560, "and the panel still fits a "
                + "laptop screen: " + content.getPreferredSize().height + " px");

        // Every file is still named somewhere a person can read it.
        StringBuilder shown = new StringBuilder();
        labelTexts(content, shown);
        String log = find(tabs, javax.swing.JTextArea.class).getText();
        for (File file : files) {
            assertTrue(shown.toString().contains(file.getAbsolutePath())
                    || log.contains(file.getAbsolutePath()), file + " is named nowhere");
        }
        assertTrue(shown.toString().contains(files.get(0).getAbsolutePath()),
                "the first file is still above the log, where it was");
        // And the dialog stays the size it has for every other result: a dozen paths are
        // counted above the log, not stacked there.
        assertTrue(shown.toString().contains("and 10 more"), shown.toString());
        assertTrue(content.getPreferredSize().height <= 300, "the panel is "
                + content.getPreferredSize().height + " px high");
    }

    /** Up to three files are listed as they always were, and the log is not added to. */
    @Test
    void aFewFilesAreListedAsBefore() {
        List<File> files = twelveFiles().subList(0, 3);
        OperationResult result = aReleasesResult(files);
        JPanel content = ResultDialog.buildContent(result);
        render(content);

        StringBuilder shown = new StringBuilder();
        labelTexts(content, shown);
        for (File file : files) {
            assertTrue(shown.toString().contains(file.getAbsolutePath()), file.toString());
        }
        assertTrue(find(content, javax.swing.JTextArea.class).getText()
                .equals(String.join("\n", result.getLog())), "the log is the log");
    }

    /**
     * A failure's sentence can be long - it says what happened, what is on disk and what to
     * do. The panel grows for it instead of taking the log's room.
     */
    @Test
    void aLongSummaryStillLeavesTheLogItsRoom() {
        JPanel content = ResultDialog.buildContent(aFailureOf(12));
        render(content);

        javax.swing.JTabbedPane tabs = find(content, javax.swing.JTabbedPane.class);
        assertTrue(tabs.getHeight() >= LOG_NEEDS, "the log has " + tabs.getHeight()
                + " px of the panel's " + content.getHeight() + "; above it "
                + content.getComponent(0).getClass().getSimpleName() + " of "
                + content.getComponent(0).getHeight() + " px");
        assertTrue(content.getHeight() > 260, "the panel grew for it: " + content.getHeight());
        assertTrue(content.getPreferredSize().height <= 560,
                content.getPreferredSize().height + " px");
        assertTrue(content.getComponent(0) instanceof JPanel, "and nothing needs scrolling");
    }

    /**
     * Past what a laptop screen holds the panel grows no further: then it is the sentence that
     * scrolls, and the log still has its room.
     */
    @Test
    void aSummaryLongerThanTheScreenScrollsAndTheLogStays() {
        JPanel content = ResultDialog.buildContent(aFailureOf(60));
        render(content);

        javax.swing.JTabbedPane tabs = find(content, javax.swing.JTabbedPane.class);
        assertTrue(tabs.getHeight() >= LOG_NEEDS, "the log has " + tabs.getHeight()
                + " px of the panel's " + content.getHeight());
        assertTrue(content.getPreferredSize().height <= 560,
                content.getPreferredSize().height + " px");
        assertTrue(content.getComponent(0) instanceof javax.swing.JScrollPane,
                "the sentence scrolls: " + content.getComponent(0).getClass().getSimpleName());
    }

    /** A failed result whose reason is this many sentences long. */
    private static OperationResult aFailureOf(int sentences) {
        StringBuilder reason = new StringBuilder();
        for (int i = 0; i < sentences; i++) {
            reason.append("The release was built, and then something about it was not as it "
                    + "should be, which this sentence explains at length. ");
        }
        return OperationResult.of("Release").note("a line of the log")
                .failed(reason.toString()).build();
    }

    /**
     * The git commands of a release, shown before they run. The line that adds the release's
     * files is long - six names, twelve with more formats - and has to be on screen whole:
     * seen cut off at the dialog's edge in a live run, behind a sideways scroll bar.
     */
    @Test
    void theGitCommandsOfAReleaseAreOnScreenWithoutScrollingSideways() throws Exception {
        String commands = "The release is built. To record it, these git commands run in\n"
                + "C:\\Users\\somebody\\Documents\\ontologies\\eggboil\non branch main:\n\n"
                + "git add -u\ngit add -f -- eggboil.obo eggboil.owl eggboil.json eggboil.ttl "
                + "eggboil-base.obo eggboil-base.owl eggboil-base.json eggboil-base.ttl "
                + "eggboil-full.obo eggboil-full.owl eggboil-full.json eggboil-full.ttl\n"
                + "git add -- releases/1.0.0\ngit commit -m \"release(1.0.0)\"\n"
                + "git tag -a v1.0.0 -m \"eggboil 1.0.0\"\n\nNothing is pushed.";
        javax.swing.JScrollPane scroll = ReleaseAction.commandsShown(commands);
        JPanel holder = new JPanel(new java.awt.BorderLayout());
        holder.add(scroll);
        BufferedImage image = render(holder);
        ImageIO.write(image, "PNG", new File(designDirectory(), "release-git-question.png"));

        javax.swing.JTextArea text = find(scroll, javax.swing.JTextArea.class);
        assertTrue(text.getWidth() <= scroll.getViewport().getWidth(), "the text is "
                + text.getWidth() + " px wide in a view of " + scroll.getViewport().getWidth());
        assertTrue(text.getText().contains("eggboil-full.ttl\ngit add -- releases/1.0.0"),
                "the commands themselves are unchanged: a wrapped line is still one line");
    }
}
