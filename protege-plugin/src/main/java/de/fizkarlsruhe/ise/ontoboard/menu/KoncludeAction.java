package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.konclude.Konclude;
import de.fizkarlsruhe.ise.ontoboard.konclude.KoncludeInferences;
import de.fizkarlsruhe.ise.ontoboard.konclude.KoncludeInstall;
import de.fizkarlsruhe.ise.ontoboard.konclude.KoncludeLog;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * ROBOT &gt; Reason with Konclude... - classification, realization and consistency, from a binary
 * the user installed.
 *
 * <p>Konclude is not another entry in {@link de.fizkarlsruhe.ise.ontoboard.robot.Reasoners}, and
 * deliberately not: that roster is the reasoners ROBOT ships, every one of which is an
 * {@code OWLReasonerFactory} on the classpath, and three existing dialogs offer the whole list.
 * Adding a native binary to it would put Konclude in front of users of
 * <em>Explain</em>, <em>Transform</em> and <em>Release</em>, where it cannot work at all, and
 * would break the end-to-end test that constructs a factory for every choice. So this is its own
 * action, and the in-process reasoners stay where they are.
 *
 * <p><b>The dialog answers "do I have it?" before it asks anything else.</b> Konclude is not
 * bundled - it is LGPLv3 and OntoBoard is Apache-2.0 - so a user meeting this menu item for the
 * first time quite likely has nothing installed. The explanation at the top of the dialog says
 * whether it was found and what version answered; when it was not, it names the exact release
 * asset for this operating system and where to download it, and the binary field is pre-filled
 * with the path it <em>would</em> have inside the Downloads folder, so after unpacking there the
 * next open finds it by itself.
 *
 * <p><b>Two Konclude behaviours this action exists to contain.</b> It exits 0 after failing, so
 * success is judged by {@link KoncludeLog#failed} and never by the exit code. And it fetches
 * {@code owl:imports} over HTTP, so the input is written flattened - see
 * {@link Konclude#writeInput}.
 */
public class KoncludeAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    /** A classification of a large ontology produces more rows than anybody reads. */
    private static final int MAX_LISTED = 1000;

    private static final String OPTION_BINARY = "binary";
    private static final String OPTION_TASK = "task";
    private static final String OPTION_ENTITY = "entity";
    private static final String OPTION_APPLY = "apply";
    private static final String OPTION_LOG_PREFIX = "log.";

    private volatile File binary;
    private volatile Konclude.Task task = Konclude.Task.CLASSIFY_CLASSES;
    private volatile String entityIri = "";
    private volatile boolean apply;
    private volatile Set<KoncludeLog.Stage> wantedStages =
            new LinkedHashSet<KoncludeLog.Stage>();

    @Override
    protected String operationName() {
        return "Reason with Konclude";
    }

    /** What the probe runs to make a found file prove it starts. */
    private static List<String> probeVersion(File candidate) {
        try {
            ProcessRunner.Outcome outcome = ProcessRunner.real().run(candidate.getParentFile(),
                    Arrays.asList(candidate.getAbsolutePath(), "-v"), 1, null);
            return outcome == null ? null : outcome.getOutput();
        } catch (java.io.IOException didNotStart) {
            // A file that will not execute is the Windows missing-DLL case, which the caller
            // turns into a sentence rather than a stack trace.
            return null;
        }
    }

    @Override
    protected boolean configure() {
        KoncludeInstall.Found found = locate();

        StringBuilder explanation = new StringBuilder(found.headline()).append(".\n\n");
        if (found.isUsable()) {
            explanation.append("Konclude is a native OWL 2 DL reasoner. It writes the DIRECT "
                    + "hierarchy only, and cannot infer property assertions, sameAs, "
                    + "differentFrom or disjointness - for those, and for explanations, use the "
                    + "in-process reasoners under ROBOT > Explain... and ROBOT > Transform...");
        } else if (found.getBinary() != null) {
            explanation.append(found.getProblem());
        } else {
            explanation.append("OntoBoard does not ship it: Konclude is LGPLv3 and this plugin is "
                    + "Apache-2.0, and there is no build for Apple silicon, so it is yours to "
                    + "install.\n\nDownload ").append(KoncludeInstall.assetForThisMachine())
                    .append("\nfrom ").append(Konclude.RELEASES_URL)
                    .append("\n\nUnpack it and point the field below at ")
                    .append(KoncludeInstall.executableName())
                    .append(". Unpacking into your Downloads folder is enough - OntoBoard looks "
                            + "there and will fill this in for you next time.");
        }

        List<Parameter> parameters = new ArrayList<Parameter>();
        parameters.add(Parameter.of(OPTION_BINARY, "Konclude binary", Parameter.Kind.FILE)
                .defaultValue(suggestedPath(found))
                .help("The Konclude executable. OntoBoard looks for it in the path you last "
                        + "chose, on your PATH, and in your Downloads folder - including inside "
                        + "an unpacked release directory.\n\nOn Windows the release links Qt "
                        + "dynamically, so keep " + KoncludeInstall.executableName() + " together "
                        + "with the DLLs it was unpacked beside; moved on its own it will not "
                        + "start.")
                .required()
                .build());
        parameters.add(Parameter.of(OPTION_TASK, "What to compute", Parameter.Kind.CHOICE)
                .choices(Konclude.labels().toArray(new String[0]))
                .defaultValue(Konclude.Task.CLASSIFY_CLASSES.getLabel())
                .help(Konclude.help())
                .build());
        parameters.add(Parameter.of(OPTION_ENTITY, "Class IRI", Parameter.Kind.TEXT)
                .defaultValue("")
                .help("Only for 'Check one class is satisfiable'. The full IRI of the class to "
                        + "test, for example http://purl.obolibrary.org/obo/BFO_0000015. Leave "
                        + "empty for every other task.")
                .build());
        // The log checkboxes. Errors and warnings are never hidden by any of them - see
        // KoncludeLog.filter - so these add detail rather than deciding whether you are told
        // about a problem.
        for (KoncludeLog.Stage stage : KoncludeLog.stages()) {
            parameters.add(Parameter.of(OPTION_LOG_PREFIX + stage.name(),
                    "Log: " + stage.getLabel(), Parameter.Kind.FLAG)
                    .defaultValue(stage == KoncludeLog.Stage.PROGRESS ? "true" : "false")
                    .help(stage.getHelp() + "\n\nErrors and warnings are always included, "
                            + "whatever these are set to.")
                    .build());
        }
        parameters.add(Parameter.of(OPTION_APPLY, "Add the inferences to the ontology",
                Parameter.Kind.FLAG)
                .defaultValue("false")
                .help("Off by default. A reasoner you have just installed should show you its "
                        + "axioms before it writes them into your ontology. When it is on, "
                        + "everything it adds is one Edit > Undo step.")
                .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(),
                "Reason with Konclude", explanation.toString(), parameters);
        if (chosen == null) {
            return false;
        }
        String path = chosen.get(OPTION_BINARY);
        binary = path == null || path.trim().isEmpty() ? null : new File(path.trim());
        if (binary != null) {
            KoncludeInstall.remember(binary);
        }
        task = Konclude.byLabel(chosen.get(OPTION_TASK));
        entityIri = chosen.get(OPTION_ENTITY) == null ? "" : chosen.get(OPTION_ENTITY).trim();
        apply = "true".equalsIgnoreCase(chosen.get(OPTION_APPLY));
        Set<KoncludeLog.Stage> stages = new LinkedHashSet<KoncludeLog.Stage>();
        for (KoncludeLog.Stage stage : KoncludeLog.stages()) {
            if ("true".equalsIgnoreCase(chosen.get(OPTION_LOG_PREFIX + stage.name()))) {
                stages.add(stage);
            }
        }
        wantedStages = stages;
        return true;
    }

    /** Where Konclude is, asking the binary rather than trusting the filename. */
    private KoncludeInstall.Found locate() {
        try {
            File remembered = KoncludeInstall.remembered();
            File candidate = KoncludeInstall.firstPresent(KoncludeInstall.candidates(
                    remembered, KoncludeInstall.downloads(), nativeEnvironment()));
            if (candidate == null) {
                return KoncludeInstall.absent();
            }
            return KoncludeInstall.describe(candidate, new KoncludeInstall.Probe() {
                @Override
                public List<String> run(File each) {
                    return probeVersion(each);
                }
            });
        } catch (RuntimeException cannotLook) {
            // Looking for a reasoner must never stop the dialog opening.
            return KoncludeInstall.absent();
        }
    }

    private File nativeEnvironment() {
        try {
            return de.fizkarlsruhe.ise.ontoboard.odk.Toolchain.nativeEnvironment();
        } catch (RuntimeException none) {
            return null;
        }
    }

    /** What to put in the field: what was found, else where it would be once unpacked. */
    private String suggestedPath(KoncludeInstall.Found found) {
        if (found.getBinary() != null) {
            return found.getBinary().getAbsolutePath();
        }
        return new File(KoncludeInstall.downloads(),
                KoncludeInstall.executableName()).getAbsolutePath();
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Change", "Axiom");
        result.note("Task: " + task.getLabel());

        if (binary == null || !binary.isFile()) {
            return result.failed("No Konclude binary at "
                    + (binary == null ? "(nothing chosen)" : binary.getAbsolutePath())
                    + ". Download " + KoncludeInstall.assetForThisMachine() + " from "
                    + Konclude.RELEASES_URL + ", unpack it, and choose "
                    + KoncludeInstall.executableName() + " from inside it.").build();
        }
        if (task.needsEntity() && entityIri.isEmpty()) {
            return result.failed(task.getLabel() + " needs the IRI of the class to test. Put it "
                    + "in the 'Class IRI' field.").build();
        }

        File work;
        File input;
        File output;
        try {
            work = Files.createTempDirectory("ontoboard-konclude").toFile();
            input = Konclude.writeInput(ontology, new File(work, "input.owl.xml"));
            output = new File(work, "inferred" + Konclude.outputExtension(task));
        } catch (Exception cannotWrite) {
            return result.failed("Could not prepare the ontology for Konclude: "
                    + cannotWrite.getMessage()).build();
        }

        List<String> command = Konclude.commandLine(binary, task, input, output,
                wantedStages.contains(KoncludeLog.Stage.TIMINGS), entityIri);
        // Recorded before the run, as the build runner does, so a failure still says what was run.
        result.note("Command: " + Konclude.describe(command));

        ProcessRunner.Outcome outcome;
        try {
            outcome = ProcessRunner.real().run(work, command, Konclude.TIMEOUT_MINUTES, null);
        } catch (java.io.IOException | RuntimeException cannotRun) {
            return result.failed("Konclude could not be started: " + cannotRun.getMessage()
                    + ". " + startupAdvice()).build();
        }
        if (outcome.timedOut()) {
            return result.failed("Konclude did not finish within " + Konclude.TIMEOUT_MINUTES
                    + " minutes and was stopped.").build();
        }

        List<KoncludeLog.Line> log = KoncludeLog.parse(outcome.getOutput());
        result.note(KoncludeLog.summarise(log));
        for (String line : KoncludeLog.filter(log, wantedStages)) {
            result.note(line);
        }
        // Never the exit code. Konclude returns 0 after logging an error and still writes a
        // well-formed, empty-looking output file - see KoncludeLog's own note.
        if (KoncludeLog.failed(log)) {
            return result.failed("Konclude reported an error: " + KoncludeLog.firstError(log))
                    .build();
        }

        if (task.answersYesOrNo()) {
            return answer(result, output);
        }

        KoncludeInferences.Result inferences;
        try {
            inferences = KoncludeInferences.read(output, ontology);
        } catch (Exception cannotRead) {
            return result.failed(cannotRead.getMessage()).build();
        }
        String dropped = inferences.describeDropped();
        if (!dropped.isEmpty()) {
            result.note(dropped);
        }
        if (inferences.getInferred().isEmpty()) {
            return result.summary("Konclude found nothing this ontology does not already say.")
                    .build();
        }

        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        for (OWLAxiom axiom : inferences.getInferred()) {
            changes.add(new AddAxiom(ontology, axiom));
        }
        for (String[] row : render(changes)) {
            result.row(row);
        }
        if (changes.size() > MAX_LISTED) {
            result.note("Listing the first " + MAX_LISTED + " of " + changes.size() + ".");
        }

        String counts = changes.size() + " inferred axiom" + (changes.size() == 1 ? "" : "s");
        if (!apply) {
            result.note("Nothing was changed - 'Add the inferences to the ontology' was off.");
            return result.summary("Konclude inferred " + counts + ".").build();
        }
        if (BackgroundRun.abandoned()) {
            result.note("Nothing was changed - you stopped waiting before it finished.");
            return result.summary("Konclude inferred " + counts + ", but was abandoned.").build();
        }
        try {
            applyOnEventThread(changes);
        } catch (RuntimeException failure) {
            return result.failed("The inferences were computed but could not be applied: "
                    + failure.getMessage()).build();
        }
        result.note("Edit > Undo reverses all of this in one step.");
        return result.summary("Konclude inferred and added " + counts + ".").build();
    }

    /** Consistency and satisfiability write a word, not axioms. */
    private OperationResult answer(OperationResult.Builder result, File output) {
        String text;
        try {
            text = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        } catch (Exception cannotRead) {
            return result.failed("Konclude finished but its answer could not be read: "
                    + cannotRead.getMessage()).build();
        }
        Boolean yes = Konclude.yesOrNo(text);
        if (yes == null) {
            return result.failed("Konclude finished but answered neither true nor false: "
                    + text.trim()).build();
        }
        if (task == Konclude.Task.CONSISTENCY) {
            return yes.booleanValue()
                    ? result.summary("Konclude says this ontology is consistent.").build()
                    : result.warn("Konclude says this ontology is INCONSISTENT. Every class is "
                            + "entailed to be unsatisfiable, so no other result from it means "
                            + "anything until this is fixed. ROBOT > Explain... will say why.")
                            .summary("Inconsistent.").build();
        }
        return yes.booleanValue()
                ? result.summary("Konclude says " + entityIri + " is satisfiable.").build()
                : result.warn("Konclude says " + entityIri + " cannot have any instance. "
                        + "ROBOT > Explain... will give the justification.")
                        .summary("Unsatisfiable.").build();
    }

    private String startupAdvice() {
        if (KoncludeInstall.isWindows()) {
            return "The Windows release links Qt dynamically, so Konclude.exe needs the DLLs it "
                    + "was unpacked beside.";
        }
        return "Check the file is executable.";
    }

    /** The changes as rows, rendered the way the rest of Protege renders terms. */
    private List<String[]> render(List<OWLOntologyChange> changes) {
        final List<OWLOntologyChange> listed =
                changes.size() > MAX_LISTED ? changes.subList(0, MAX_LISTED) : changes;
        final List<String[]> rows = new ArrayList<String[]>(listed.size());
        Runnable rendering = new Runnable() {
            @Override
            public void run() {
                for (OWLOntologyChange change : listed) {
                    rows.add(new String[] {"Add", text(change)});
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            rendering.run();
            return rows;
        }
        try {
            SwingUtilities.invokeAndWait(rendering);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException thrown) {
            // Rendering is presentation. Losing it must not lose the inferences.
            for (OWLOntologyChange change : listed) {
                rows.add(new String[] {"Add", String.valueOf(change)});
            }
        }
        return rows;
    }

    private String text(OWLOntologyChange change) {
        if (!change.isAxiomChange()) {
            return String.valueOf(change);
        }
        try {
            return getOWLModelManager().getRendering(change.getAxiom());
        } catch (RuntimeException noRenderer) {
            return String.valueOf(change.getAxiom());
        }
    }
}
