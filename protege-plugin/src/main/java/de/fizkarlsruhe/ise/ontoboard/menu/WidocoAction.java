package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import de.fizkarlsruhe.ise.ontoboard.widoco.Widoco;
import de.fizkarlsruhe.ise.ontoboard.widoco.WidocoInstall;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; Documentation… - generate the ontology's HTML page and open it.
 *
 * <p>Roadmap item 2. ODK projects publish a human-readable page for their ontology and the three
 * real ones measured for this do it by hand in CI; until now the only way to see what that page
 * will look like was to push and wait for the deploy.
 *
 * <p><b>Widoco is installed, not embedded, and that is measured rather than preferred.</b> It is
 * not on Maven Central at all, and its only published form is a 40,858,575-byte shaded jar
 * carrying 1,533 OWL API entries and 2,044 Guava entries - packages this bundle imports from the
 * host. A second copy inside the bundle is how an OSGi plugin stops resolving. So this finds the
 * jar the way {@code KoncludeAction} finds Konclude, and says how to get it when it cannot.
 *
 * <p><b>It also needs a Java 11.</b> Measured on both smoked hosts: Prot&eacute;g&eacute; 5.6.9
 * runs on Temurin 11.0.25 and can run Widoco with its own JVM; 5.5.0 runs on 1.8.0_121 and
 * cannot. So the dialog shows which Java it found and what version it reported, and on 5.5.0 it
 * looks for another one rather than failing with a class file error nobody can read.
 *
 * <p><b>Success is the page, not the exit code.</b> A real run exited 0 while printing
 * {@code ERROR … Could not generate changelog} - it could not fetch the previous release to diff
 * against and quietly left that section out. The page is checked for directly, and the log lines
 * worth reading are reported beside it.
 */
public class WidocoAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_JAR = "jar";
    private static final String OPTION_JAVA = "java";
    private static final String OPTION_INPUT = "input";
    private static final String OPTION_OUT = "out";
    private static final String OPTION_LANGUAGE = "language";
    private static final String OPTION_VOWL = "vowl";

    private static final String THE_RELEASE = "The release product (what gets published)";
    private static final String THE_OPEN_ONE = "The ontology open in Protege";

    /** Generous: a large ontology takes minutes, and the dialog has a Stop button. */
    private static final long TIMEOUT_MINUTES = 20;

    private volatile File jar;
    private volatile File java;
    private volatile File out;
    private volatile String which = THE_RELEASE;
    private volatile String language = Widoco.DEFAULT_LANGUAGE;
    private volatile boolean webVowl;

    @Override
    protected String operationName() {
        return "Documentation";
    }

    @Override
    protected boolean runsInBackground() {
        return true;
    }

    @Override
    protected boolean configure() {
        File found = WidocoInstall.firstPresent(WidocoInstall.candidates(
                WidocoInstall.remembered(), downloads(), null));
        File chosenJava = WidocoInstall.firstPresent(WidocoInstall.javaCandidates(
                WidocoInstall.rememberedJava(), javaHome(), javaHomeFromEnvironment(), null));

        StringBuilder explanation = new StringBuilder();
        if (found == null) {
            explanation.append("Widoco is not installed. ");
            for (String line : WidocoInstall.howToInstall()) {
                explanation.append(line).append(' ');
            }
        } else {
            explanation.append("Widoco found at ").append(found.getName())
                    .append(". It generates the HTML page an ODK project publishes, from the "
                            + "ontology's own annotations, and opens it when it is done.");
        }

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Documentation",
                explanation.toString(),
                Arrays.asList(
                        Parameter.of(OPTION_JAR, "Widoco jar", Parameter.Kind.FILE)
                                .defaultValue(found == null ? "" : found.getAbsolutePath())
                                .help("The jar from " + WidocoInstall.downloadUrl() + "\n\n"
                                        + "It is not bundled with OntoBoard: it carries its own "
                                        + "OWL API and Guava, and a second copy of those inside "
                                        + "this plugin would stop it loading. OntoBoard "
                                        + "remembers where you put it.")
                                .build(),
                        Parameter.of(OPTION_JAVA, "Run it with", Parameter.Kind.FILE)
                                .defaultValue(chosenJava == null ? "" : chosenJava.getPath())
                                .help("Widoco's jar needs Java 11 or newer.\n\nProtege 5.6.9 "
                                        + "runs on Java 11 and can run it directly. Protege "
                                        + "5.5.0 runs on Java 8 and cannot, so point this at a "
                                        + "JDK 11 if the field is empty or the run reports a "
                                        + "class file version error.")
                                .build(),
                        Parameter.of(OPTION_INPUT, "Document", Parameter.Kind.CHOICE)
                                .choices(THE_RELEASE, THE_OPEN_ONE)
                                .defaultValue(THE_RELEASE)
                                .help("The release product is the merged file a reader "
                                        + "downloads, so its page describes everything the "
                                        + "ontology reuses as well as what you wrote.\n\nThe "
                                        + "open ontology imports its modules rather than "
                                        + "containing them, so a page made from it covers only "
                                        + "your own terms - useful as a preview of unreleased "
                                        + "work, misleading as the published page.")
                                .build(),
                        Parameter.of(OPTION_OUT, "Write it to", Parameter.Kind.DIRECTORY)
                                .defaultValue("")
                                .help("Empty means a scratch folder beside the project, which "
                                        + "is the right answer for a preview.\n\nDeliberately "
                                        + "not docs/: that directory is published, and a "
                                        + "generated page landing there would be committed by "
                                        + "accident.")
                                .build(),
                        Parameter.of(OPTION_LANGUAGE, "Language", Parameter.Kind.TEXT)
                                .defaultValue(Widoco.DEFAULT_LANGUAGE)
                                .help("The language tag for the page, matching the language of "
                                        + "the labels you want documented.")
                                .build(),
                        Parameter.of(OPTION_VOWL, "Include the WebVowl diagram",
                                Parameter.Kind.FLAG)
                                .defaultValue("false")
                                .help("An interactive diagram of the ontology. Off by default "
                                        + "because it adds noticeably to the time on anything "
                                        + "large, and the page is complete without it.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        jar = fileFrom(chosen.get(OPTION_JAR));
        java = fileFrom(chosen.get(OPTION_JAVA));
        out = fileFrom(chosen.get(OPTION_OUT));
        which = chosen.get(OPTION_INPUT);
        language = chosen.get(OPTION_LANGUAGE) == null || chosen.get(OPTION_LANGUAGE).isEmpty()
                ? Widoco.DEFAULT_LANGUAGE : chosen.get(OPTION_LANGUAGE).trim();
        webVowl = "true".equalsIgnoreCase(chosen.get(OPTION_VOWL));
        if (jar != null) {
            WidocoInstall.remember(jar);
        }
        if (java != null) {
            WidocoInstall.rememberJava(java);
        }
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());

        if (jar == null || !jar.isFile()) {
            result.failed("Widoco is not installed, or the path given is not a file.");
            for (String line : WidocoInstall.howToInstall()) {
                result.note(line);
            }
            return result.build();
        }
        File editFile = fileOf(ontology);
        if (editFile == null) {
            return result.failed("This ontology has not been saved, so there is nothing to "
                    + "document.").build();
        }
        File projectRoot = projectRootOf(ontology);

        // Which Java, and whether it will do. A clear sentence beats a class file error.
        ProcessRunner.Runner runner = ProcessRunner.real();
        String whyNot = checkJava(runner, result);
        if (whyNot != null) {
            return result.failed(whyNot).build();
        }

        File input = THE_OPEN_ONE.equals(which) ? editFile
                : Widoco.inputFor(projectRoot, idOf(editFile), editFile);
        if (Widoco.isEditFile(input)) {
            result.warn("Documenting the edit file. It imports its modules rather than "
                    + "containing them, so the page will cover the terms this project wrote and "
                    + "not the ones it reuses - and the version IRI that Release... stamps is "
                    + "not in it yet. Run Project > Release... first for the page a reader "
                    + "would see.");
        }
        result.note("Documenting " + input.getName());

        File target = out != null ? out
                : new File(projectRoot != null ? projectRoot : editFile.getParentFile(),
                        "build/documentation");
        if (!target.isDirectory() && !target.mkdirs()) {
            return result.failed("Could not create " + target.getAbsolutePath()).build();
        }

        List<String> command = Widoco.command(java, jar, input, target, language, webVowl);
        try {
            // The working directory is set deliberately, though Widoco resolves its own
            // config/ beside the jar rather than here - measured, and worth not guessing at.
            ProcessRunner.Outcome ran = runner.run(target, command, TIMEOUT_MINUTES, null);
            if (ran.timedOut()) {
                return result.failed("Widoco did not finish within " + TIMEOUT_MINUTES
                        + " minutes.").build();
            }
            File page = Widoco.indexIn(target, language);
            Widoco.Outcome outcome = Widoco.outcome(ran.getExitCode(), ran.getOutput(), page);

            for (String line : outcome.getWorthReading()) {
                result.warn(line);
            }
            if (!outcome.wrotePage()) {
                return result.failed("Widoco finished but wrote no page to "
                        + page.getAbsolutePath() + ".").build();
            }
            result.wrote(page);
            open(page, result);
            return result.summary("Documentation written to " + page.getName() + ".").build();
        } catch (IOException cannotRun) {
            return result.failed("Could not run Widoco: " + cannotRun.getMessage()).build();
        }
    }

    /** Why the chosen Java will not do, or null. Reports which one it is either way. */
    private String checkJava(ProcessRunner.Runner runner, OperationResult.Builder result) {
        try {
            ProcessRunner.Outcome version = runner.run(jar.getParentFile(),
                    WidocoInstall.versionCommand(java), 2, null);
            int major = WidocoInstall.majorVersionIn(version.getOutput());
            String where = java == null ? "the java on your PATH" : java.getPath();
            if (major > 0) {
                result.note("Java " + major + " (" + where + ")");
            }
            return WidocoInstall.whyJavaWillNotDo(where, version.getOutput());
        } catch (IOException cannotRun) {
            return "Could not run java: " + cannotRun.getMessage()
                    + ". Widoco needs a Java " + WidocoInstall.LEAST_JAVA + " or newer.";
        }
    }

    /**
     * Opens the page, and says where it is when it cannot.
     *
     * <p>{@code Desktop} is unavailable on some Linux desktops, and a menu item that appears to
     * do nothing is worse than one that prints a path - the same guard {@code TrackerItemAction}
     * uses.
     */
    private void open(File page, OperationResult.Builder result) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(page.toURI());
                return;
            }
        } catch (Exception cannotOpen) {
            // Falls through to printing the path, which is all the user needs.
        }
        result.note("Open it yourself: " + page.getAbsolutePath());
    }

    private String idOf(File editFile) {
        String name = editFile.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.endsWith("-edit") ? stem.substring(0, stem.length() - "-edit".length())
                : stem;
    }

    private static File fileFrom(String path) {
        return path == null || path.trim().isEmpty() ? null : new File(path.trim());
    }

    private static File downloads() {
        String home = System.getProperty("user.home", "");
        return home.isEmpty() ? null : new File(home, "Downloads");
    }

    private static File javaHome() {
        String home = System.getProperty("java.home", "");
        return home.isEmpty() ? null : new File(home);
    }

    private static File javaHomeFromEnvironment() {
        String home = System.getenv("JAVA_HOME");
        return home == null || home.isEmpty() ? null : new File(home);
    }
}
