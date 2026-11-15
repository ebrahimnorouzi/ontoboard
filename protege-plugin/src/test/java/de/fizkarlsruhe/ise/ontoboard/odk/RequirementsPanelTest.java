package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.canvas.Wcag;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The requirements report's section titles took the look and feel's
 * {@code Label.disabledForeground}, which in Protégé 5.6.9's look and feel on Windows is near
 * white. Every line it writes has to be readable on the panel.
 */
class RequirementsPanelTest {

    @Test
    void everyLineCanBeReadWhenTheThemeGreyIsNearWhite() {
        UIManager.put("Label.disabledForeground", new ColorUIResource(220, 220, 220));
        try {
            Toolchain.Report report = new Toolchain.Report(
                    Collections.singletonList(new Toolchain.Tool("GNU make", Toolchain.State.ABSENT,
                            "not on this PATH", "Install make")),
                    Collections.singletonList(new Toolchain.Capability("Create a new ODK project", true,
                            "Inside Protégé")));
            RequirementsPanel panel = new RequirementsPanel(report);
            List<JLabel> labels = Wcag.labelsIn(panel);
            boolean sawSection = false;
            for (JLabel label : labels) {
                String text = label.getText() == null ? "" : label.getText();
                if (text.trim().isEmpty()) {
                    // A status disc: an icon, no words.
                    continue;
                }
                sawSection |= "What is installed".equals(text);
                double ratio = Wcag.contrast(label.getForeground(), panel.getBackground());
                assertTrue(ratio >= Wcag.READABLE, String.format("\"%s\" reads at %.2f:1, %s on %s",
                        text, ratio, label.getForeground(), panel.getBackground()));
            }
            assertTrue(sawSection, "the section titles are on the panel");
        } finally {
            UIManager.put("Label.disabledForeground", null);
        }
    }

    /**
     * The window does not scroll sideways, so a row wider than what is shown is cut off: in the
     * first Protege that showed the row for ODK projects, every longer row ended in the middle
     * of a sentence ("... ROBOT runs inside Protege," and on the next line "to install"). Each
     * row asked for a width of 520px, which Swing's HTML takes for about 676 pixels - in a
     * window that shows some 600.
     *
     * <p>Rendered here as the window is shown - at the size it asks for, with as many rows as
     * the real one has, so that it scrolls down - every row ends inside what is shown. The
     * picture is kept under target/ for a person to look at.
     */
    @Test
    void noRowIsWiderThanWhatIsShown() throws Exception {
        List<Toolchain.Capability> can = new java.util.ArrayList<Toolchain.Capability>();
        List<Toolchain.Tool> tools = new java.util.ArrayList<Toolchain.Tool>();
        String sentence = "Through one program or another, in the project's own image - and by "
                + "OntoBoard itself where that image is not on the machine, with nothing to "
                + "install and nothing to fetch";
        for (int i = 0; i < 4; i++) {
            can.add(new Toolchain.Capability("Do a thing of some length, number " + i, true,
                    sentence));
        }
        for (int i = 0; i < 10; i++) {
            tools.add(new Toolchain.Tool("A program, number " + i, Toolchain.State.ABSENT,
                    "not found", sentence + ". " + sentence + "."));
        }
        final RequirementsPanel panel = new RequirementsPanel(new Toolchain.Report(tools, can));
        final java.awt.image.BufferedImage[] picture = {null};
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                java.awt.Dimension size = panel.getPreferredSize();
                panel.setSize(size);
                panel.addNotify();
                panel.validate();
                picture[0] = new java.awt.image.BufferedImage(size.width, size.height,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = picture[0].createGraphics();
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, size.width, size.height);
                panel.paint(graphics);
                graphics.dispose();
            }
        });
        java.io.File kept = new java.io.File("target/requirements-panel.png");
        kept.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(picture[0], "png", kept);

        javax.swing.JScrollPane scroll = null;
        for (java.awt.Component child : panel.getComponents()) {
            if (child instanceof javax.swing.JScrollPane) {
                scroll = (javax.swing.JScrollPane) child;
            }
        }
        assertTrue(scroll != null, "the rows are in a scroll pane");
        assertTrue(scroll.getVerticalScrollBar().isVisible(), "as many rows as the real window "
                + "has: it scrolls down, and the bar takes its share of the width");
        java.awt.Component rows = scroll.getViewport().getView();
        int shown = scroll.getViewport().getExtentSize().width;
        int checked = 0;
        for (JLabel label : Wcag.labelsIn((java.awt.Container) rows)) {
            java.awt.Rectangle bounds = javax.swing.SwingUtilities.convertRectangle(
                    label.getParent(), label.getBounds(), rows);
            assertTrue(bounds.x + bounds.width <= shown, "a row is cut off: it ends at "
                    + (bounds.x + bounds.width) + " and " + shown + " are shown - "
                    + label.getText());
            checked++;
        }
        assertTrue(checked >= 14, "every row was looked at: " + checked);
    }

    // ------------------------------------------------------------------ ODK's image

    /**
     * A made-up machine: the programs that answer, and what they say. A program it knows
     * answers what it has an answer for and fails the rest; any other program cannot be started.
     */
    private static final class Machine implements ProcessRunner.Runner {
        final List<List<String>> asked = new ArrayList<List<String>>();
        final Map<String, List<String>> answers = new LinkedHashMap<String, List<String>>();
        final List<String> programs = new ArrayList<String>();

        /** docker answers, and has these images: {@code tag=ODK_VERSION}. */
        static Machine withDocker(String... tagsAndVersions) {
            Machine machine = new Machine();
            machine.programs.add("docker");
            machine.answer("docker --version", "Docker version 29.0.0");
            machine.answer("docker info", "Server Version: 29.0.0");
            List<String> tags = new ArrayList<String>();
            for (String entry : tagsAndVersions) {
                String tag = entry.substring(0, entry.indexOf('='));
                String version = entry.substring(entry.indexOf('=') + 1);
                tags.add(tag);
                machine.answer("docker image inspect --format {{.Id}} obolibrary/odkfull:" + tag,
                        "sha256:0123");
                machine.answer("docker image inspect --format {{json .Config.Env}} "
                        + "obolibrary/odkfull:" + tag, "[\"ODK_VERSION=" + version + "\"]");
            }
            machine.answers.put("docker image ls --format {{.Tag}} obolibrary/odkfull", tags);
            return machine;
        }

        void answer(String command, String... lines) {
            answers.put(command, Arrays.asList(lines));
        }

        @Override
        public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
            asked.add(new ArrayList<String>(command));
            String line = String.join(" ", command);
            if (answers.containsKey(line)) {
                return new ProcessRunner.Outcome(0, answers.get(line), false);
            }
            if (programs.contains(command.get(0))) {
                return new ProcessRunner.Outcome(1,
                        Collections.singletonList("Error: no such object"), false);
            }
            throw new IOException("Cannot run program \"" + command.get(0) + "\"");
        }
    }

    private static Toolchain.Tool imageRow(Toolchain.Report report) {
        for (Toolchain.Tool tool : report.getTools()) {
            if (tool.getName().equals(OdkImagePull.NAME)) {
                return tool;
            }
        }
        throw new AssertionError("no row for ODK's image in " + Toolchain.asText(report));
    }

    /**
     * Task O26: Check requirements has a row for ODK's image - here, under which name; missing
     * while a container program answers, "not on this machine (about 3.4 GB)" and that nothing
     * needs it; no container program, no failure either - and the pull is offered only where a
     * container program answers and the image is missing. Never a fault: without Docker
     * OntoBoard is complete.
     */
    @Test
    void requirementsSaysWhichImageIsHereAndOffersThePull() throws Exception {
        Toolchain.Report here = Toolchain.probe(Machine.withDocker("latest=v1.6.1"));
        Toolchain.Report missing = Toolchain.probe(Machine.withDocker("v1.5.4=v1.5.4"));
        Toolchain.Report nothing = Toolchain.probe(new Machine());

        assertTrue(imageRow(here).getDetail().contains("obolibrary/odkfull:latest"),
                imageRow(here).getDetail());
        assertEquals(Toolchain.State.PRESENT, imageRow(here).getState());
        assertFalse(here.getImage().offersThePull());

        assertEquals("ODK image v1.6.1", imageRow(missing).getName());
        assertEquals("not on this machine (about 3.4 GB)", imageRow(missing).getDetail());
        assertTrue(imageRow(missing).getRemedy().contains("OntoBoard runs ODK's targets itself"),
                imageRow(missing).getRemedy());
        assertTrue(missing.getImage().offersThePull(), "a Pull... action where it is missing");
        assertEquals(Arrays.asList("docker", "pull", "obolibrary/odkfull:v1.6.1"),
                missing.getImage().command());

        assertTrue(imageRow(nothing).getRemedy().contains("OntoBoard runs ODK's targets itself"),
                imageRow(nothing).getRemedy());
        assertFalse(nothing.getImage().offersThePull(), "no program to pull it with");

        for (Toolchain.Report report : Arrays.asList(here, missing, nothing)) {
            assertTrue(imageRow(report).getState() != Toolchain.State.NOT_WORKING,
                    "never a fault: " + imageRow(report).getDetail());
            assertTrue(report.isEverythingAvailable(), Toolchain.asText(report));
            assertEquals("Everything OntoBoard can do is available on this machine.",
                    Toolchain.summary(report));
            Toolchain.Report plus = report.plus(Toolchain.Tool.of("Another", Toolchain.State.ABSENT,
                    "", ""));
            assertTrue(plus.getImage() == report.getImage(), "a row added keeps the image");
        }

        // The window shows it.
        boolean shown = false;
        for (JLabel label : Wcag.labelsIn(new RequirementsPanel(missing))) {
            shown |= label.getText() != null
                    && label.getText().contains("not on this machine (about 3.4 GB)");
        }
        assertTrue(shown, "the row is in the window");
    }

    /**
     * Task O26: nothing but the person's yes to the question starts a pull. Opening Check
     * requirements, refreshing it, changing the container settings and the route decisions
     * ask the machine what is there - and never pull, on a machine where the image is missing
     * and a container program answers.
     */
    @Test
    void thePullIsNeverStartedByAnythingElse(@TempDir File temp) throws Exception {
        Machine machine = Machine.withDocker();

        // Opening Check requirements, and opening it again.
        Toolchain.probe(machine);
        Toolchain.probe(machine);

        // Changing the container settings, each way, with the window opened after each.
        String before = System.getProperty(OdkRoute.SETTINGS_PROPERTY);
        String path = "ontoboard-test-requirements-" + System.nanoTime();
        java.util.prefs.Preferences node = java.util.prefs.Preferences.userRoot().node(path);
        try {
            System.setProperty(OdkRoute.SETTINGS_PROPERTY, "node:" + path);
            for (OdkRoute.Use use : OdkRoute.Use.values()) {
                for (String program : new String[] {null, "docker"}) {
                    OdkRoute.setUse(use);
                    OdkRoute.setContainerProgram(program);
                    Toolchain.probe(machine);
                    OdkRoute.runtime(machine);
                    OdkRoute.whyNoContainer(machine);
                    OdkRoute.noContainerBecause(machine);
                }
            }
        } finally {
            if (before == null) {
                System.clearProperty(OdkRoute.SETTINGS_PROPERTY);
            } else {
                System.setProperty(OdkRoute.SETTINGS_PROPERTY, before);
            }
            node.removeNode();
        }

        // The route decisions: where a project is made, where its targets run, in which image.
        OdkProjectConfig config = new OdkProjectConfig("routes", "Routes",
                "A project to decide routes for.", "http://www.example.org/routes.owl",
                "https://creativecommons.org/publicdomain/zero/1.0/", temp);
        OdkCreate.Created created = OdkCreate.create(config, null, machine, false);
        assertEquals(OdkCreate.Route.BUNDLED, created.route(),
                "the image is missing, so ODK's templates in OntoBoard write the project");
        File edit = config.getEditFile();
        File ontology = edit.getParentFile();
        MakeRun.routeFor(edit, machine);
        MakeRun.whyNotRunnable(edit, machine);
        OdkImage.forProject(ontology, OdkImage.probe("docker", machine));
        OdkImage.ofProjectsOwnVersion(ontology, OdkImage.probe("docker", machine));
        OdkImage.pinned(OdkImage.probe("docker", machine));
        OdkContainer.Host.detect("docker", machine);

        boolean askedForImages = false;
        for (List<String> command : machine.asked) {
            assertFalse(command.contains("pull"), "a pull was started: " + command);
            String second = command.size() > 1 ? command.get(1) : "";
            assertTrue(Arrays.asList("info", "--version", "version", "image", "images",
                    "inspect", "-u", "-g").contains(second), "only questions: " + command);
            if (second.equals("image")) {
                assertTrue(command.get(2).equals("inspect") || command.get(2).equals("ls"),
                        "only questions: " + command);
                askedForImages = true;
            }
        }
        assertTrue(askedForImages, "the image was asked about: " + machine.asked);
    }

    /**
     * Only one class composes a container pull, and only Check requirements runs it, after the
     * question: no other source in OntoBoard has a {@code "pull"} other than git's.
     */
    @Test
    void onlyThePullOfCheckRequirementsComposesAPull() throws Exception {
        List<String> pulls = new ArrayList<String>();
        List<String> runs = new ArrayList<String>();
        java.nio.file.Path main = new File("src/main/java").toPath();
        java.util.stream.Stream<java.nio.file.Path> sources = java.nio.file.Files.walk(main);
        try {
            for (Object each : sources.toArray()) {
                java.nio.file.Path source = (java.nio.file.Path) each;
                if (!source.toString().endsWith(".java")) {
                    continue;
                }
                String text = new String(java.nio.file.Files.readAllBytes(source),
                        java.nio.charset.StandardCharsets.UTF_8);
                String name = source.getFileName().toString();
                if (text.contains("\"pull\"")) {
                    pulls.add(name);
                }
                if (text.contains("OdkImagePull") && text.matches("(?s).*\\.pull\\(.*")) {
                    runs.add(name);
                }
            }
        } finally {
            sources.close();
        }
        java.util.Collections.sort(pulls);
        assertEquals(Arrays.asList("GitAction.java", "GitRepo.java", "OdkImagePull.java",
                "OpenFromGitHubAction.java"), pulls, "git's pulls, and ODK's image's");
        assertEquals(Collections.singletonList("RequirementsAction.java"), runs);
    }
}
