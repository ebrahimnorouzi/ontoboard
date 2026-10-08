package de.fizkarlsruhe.ise.ontoboard.konclude;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Driving the Konclude reasoner, which is a native binary rather than a Java library.
 *
 * <p>Konclude won five of the six ORE 2014 reasoning disciplines and is still the fastest thing
 * available for some OWL 2 DL ontologies, so it is worth reaching for. It is also C++, LGPLv3, and
 * has had no release since 2021-06-19 - which decides almost every design question here.
 *
 * <p><b>Nothing of Konclude ships inside this plugin.</b> It is LGPLv3 and OntoBoard is Apache-2.0.
 * Running a binary the user installed creates no licence obligation at all: no copying, no linking,
 * no distribution. Bundling it would be distribution of the Library, and would drag in Qt's LGPLv3
 * and a statically linked Redland with it - for an x86-64-only artifact that still leaves Apple
 * silicon users with nothing, because upstream publishes no arm64 build. So OntoBoard asks where
 * the binary is and runs it. See {@link KoncludeInstall}.
 *
 * <p><b>The command line, not OWLlink.</b> Konclude speaks OWLlink over a socket, and the OWL API
 * has an OWLlink binding - but that binding is {@code owllinkapi} 2.0.0, which targets OWL API 5,
 * and this plugin is built against 4.5.x for both its Protege hosts. The CLI's
 * {@code classification} command writes a plain OWL 2 XML {@code <Ontology>} that OWL API 4 reads
 * directly, so the file is the interface.
 *
 * <p><b>Two things about Konclude that are not obvious and that this class exists to handle.</b>
 *
 * <ol>
 *   <li><b>It exits 0 when it has failed.</b> Pointed at a file that does not exist it logs
 *       {@code {error} ... File 'NOPE.owl' not found}, returns exit code <b>0</b>, and still writes
 *       a well-formed 896-byte {@code <Ontology>} containing two declarations. So the obvious
 *       success test - exit code plus "the output parses" - reports "finished, 0 inferences" for a
 *       run that never read the ontology. {@link KoncludeLog#failed} is the only sound test.
 *   <li><b>It resolves {@code owl:imports} over HTTP.</b> Given an input declaring an import it
 *       logs "Scheduling the import of", fetches it, and carries on. Every real ODK edit file has
 *       imports, so an unflattened input would make Konclude reason over a different ontology than
 *       Protege is showing, from the network, on a desktop, silently. {@link #writeInput} merges
 *       the imports closure first.
 * </ol>
 */
public final class Konclude {

    private Konclude() {
    }

    /** Long enough for a real classification, short enough that a hang is not forever. */
    public static final int TIMEOUT_MINUTES = 20;

    /**
     * What Konclude can be asked to do.
     *
     * <p>The two property-classification commands are real and undocumented: they appear in no
     * help text and on no vendor page, only in Konclude's own command-translation table. They run
     * and they produce {@code SubObjectPropertyOf} and {@code SubDataPropertyOf} output. They are
     * offered because they work, and the dialog says they are undocumented so that a future
     * Konclude dropping them produces a readable failure rather than a mystery.
     */
    public enum Task {
        CLASSIFY_CLASSES("Classify classes", "classification", false,
                "Computes the class hierarchy and writes the inferred SubClassOf and "
                        + "EquivalentClasses axioms. The usual reason to run a reasoner."),
        CLASSIFY_OBJECT_PROPERTIES("Classify object properties", "classifyobjectproperties", false,
                "The object property hierarchy. Undocumented upstream - it is in Konclude's "
                        + "command table but in no help text - and it does work."),
        CLASSIFY_DATA_PROPERTIES("Classify data properties", "classifydataproperties", false,
                "The data property hierarchy. Undocumented upstream, like the object property "
                        + "command, and it does work."),
        REALIZE("Realize individuals", "realization", false,
                "Computes the most specific class of every individual and writes the inferred "
                        + "ClassAssertion axioms. Useful only on an ontology that has individuals."),
        CONSISTENCY("Check consistency", "consistency", false,
                "Answers whether the ontology is consistent at all. One word, not axioms - but it "
                        + "is the cheapest way to find out whether Konclude can read your ontology "
                        + "before trusting a long classification."),
        SATISFIABILITY("Check one class is satisfiable", "satisfiability", true,
                "Answers whether one named class can have any instance. Needs the IRI of the "
                        + "class to test.");

        private final String label;
        private final String command;
        private final boolean needsEntity;
        private final String help;

        Task(String label, String command, boolean needsEntity, String help) {
            this.label = label;
            this.command = command;
            this.needsEntity = needsEntity;
            this.help = help;
        }

        public String getLabel() {
            return label;
        }

        /** The sub-command Konclude's command line takes. */
        public String getCommand() {
            return command;
        }

        /** Whether {@code -x} must carry an entity IRI. */
        public boolean needsEntity() {
            return needsEntity;
        }

        public String getHelp() {
            return help;
        }

        /** Whether the answer is a word rather than a set of axioms. */
        public boolean answersYesOrNo() {
            return this == CONSISTENCY || this == SATISFIABILITY;
        }
    }

    /** Every task label, in the order they are offered. */
    public static List<String> labels() {
        List<String> labels = new ArrayList<String>();
        for (Task task : Task.values()) {
            labels.add(task.getLabel());
        }
        return labels;
    }

    /** The task with that label. */
    public static Task byLabel(String label) {
        String wanted = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        for (Task task : Task.values()) {
            if (task.getLabel().toLowerCase(Locale.ROOT).equals(wanted)) {
                return task;
            }
        }
        throw new IllegalArgumentException(
                "no Konclude task called '" + label + "'; there are " + labels());
    }

    /** One paragraph covering all of them, for the "?" on the task parameter. */
    public static String help() {
        StringBuilder text = new StringBuilder("What to ask Konclude for.\n");
        for (Task task : Task.values()) {
            text.append('\n').append(task.getLabel()).append(": ").append(task.getHelp())
                    .append('\n');
        }
        return text.toString();
    }

    /**
     * The exact command line, as a token list.
     *
     * <p>Four things in here are load-bearing, each learned by running the binary:
     *
     * <ul>
     *   <li><b>The binary itself, never {@code Scripts/Konclude.bat}.</b> That wrapper forwards
     *       only {@code %1} to {@code %9} and this line is twelve to fourteen tokens, so the tail
     *       would be dropped - including {@code -o}, which turns a classification into a run that
     *       computes everything and writes nothing.
     *   <li><b>{@code -u} is what makes the log parseable.</b> It selects the observer that tags
     *       every line with a domain, which is what {@link KoncludeLog} filters on.
     *   <li><b>Never {@code +Konclude.CLI.Output.AbbreviatedIRIs=true}.</b> It writes
     *       {@code IRI="owl:Nothing"} and, for namespaces the input did not declare, {@code IRI=""}
     *       - invalid OWL that loses entity identity, so the inferences cannot be matched back to
     *       the ontology they came from.
     *   <li><b>Only the canonical single-letter flags.</b> Konclude matches a flag on its first
     *       letter after stripping dashes, so a typo like {@code -out} is silently accepted as
     *       {@code -o} and a wrong flag never announces itself. That is also why
     *       {@code SelfCheck} asserts this list rather than probing for the binary.
     * </ul>
     *
     * @param entityIri required for {@link Task#SATISFIABILITY}, ignored otherwise
     */
    public static List<String> commandLine(File binary, Task task, File input, File output,
            boolean timings, String entityIri) {
        if (binary == null || task == null || input == null || output == null) {
            throw new IllegalArgumentException("binary, task, input and output are all required");
        }
        List<String> command = new ArrayList<String>();
        command.add(binary.getAbsolutePath());
        command.add(task.getCommand());
        command.add("-i");
        command.add(input.getAbsolutePath());
        command.add("-o");
        command.add(output.getAbsolutePath());
        // Let Konclude choose its worker count. Pinning it would be a guess about the machine.
        command.add("-w");
        command.add("AUTO");
        command.add("-u");
        // Konclude re-declares every entity it was given. They are not inferences and they would
        // swamp the diff, so they are turned off at the source as well as filtered afterwards.
        command.add("+Konclude.CLI.Output.WriteDeclarations=false");
        if (timings) {
            command.add("-v");
        }
        if (task.needsEntity()) {
            if (entityIri == null || entityIri.trim().isEmpty()) {
                throw new IllegalArgumentException(task.getLabel() + " needs a class IRI");
            }
            command.add("-x");
            command.add(entityIri.trim());
        }
        return Collections.unmodifiableList(command);
    }

    /**
     * Writes the ontology where Konclude can read it, with its imports merged in.
     *
     * <p>OWL 2 XML rather than Turtle: Konclude's own documentation calls its RDF-to-OWL mapping
     * experimental, and OWL/XML is one of the two syntaxes it parses natively.
     *
     * <p>Flattened first, and that is the point of this method. Konclude chases {@code owl:imports}
     * over HTTP - measured, with a real import it logged "Scheduling the import of", fetched the
     * document and went on; with an unreachable host it logged an HTTP error and classified only
     * the local axioms, exiting 0 either way. An ODK edit file always has imports, so without this
     * the reasoner would be answering about a different ontology than the one on screen, and would
     * reach the network from a desktop application to do it.
     */
    public static File writeInput(OWLOntology ontology, File target) throws IOException {
        try {
            OWLOntology flattened = flattenedCopy(ontology);
            flattened.getOWLOntologyManager().saveOntology(flattened, new OWLXMLDocumentFormat(),
                    org.semanticweb.owlapi.model.IRI.create(target));
            return target;
        } catch (Exception cannotWrite) {
            throw new IOException("Could not write the ontology for Konclude: "
                    + cannotWrite.getMessage(), cannotWrite);
        }
    }

    /**
     * The ontology with its imports closure merged into one graph, in a manager of its own.
     *
     * <p>The same shape as {@code OntologyDataset}'s private flatten, which exists for the same
     * reason on the SPARQL path. Kept here rather than shared because the two differ in what they
     * do with the root ontology's annotations, and because a reasoner input and a query dataset
     * are allowed to diverge later without one silently changing the other.
     *
     * <p>Returns the ontology untouched when it imports nothing: no imports means no
     * {@code <Import>} element for Konclude to chase, so there is nothing to protect against and
     * no reason to pay for a copy.
     */
    static OWLOntology flattenedCopy(OWLOntology ontology) throws Exception {
        if (ontology.getImportsDeclarations().isEmpty()) {
            return ontology;
        }
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology merged = manager.createOntology();
        manager.addAxioms(merged, ontology.getAxioms(Imports.INCLUDED));
        return merged;
    }

    /** The tokens as one line, for the result to record what was actually run. */
    public static String describe(List<String> command) {
        StringBuilder line = new StringBuilder();
        for (String token : command) {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(token.contains(" ") ? "\"" + token + "\"" : token);
        }
        return line.toString();
    }

    /** The file extension Konclude writes for a task: OWL/XML, or plain text for a yes-or-no. */
    public static String outputExtension(Task task) {
        return task.answersYesOrNo() ? ".txt" : ".owl";
    }

    /**
     * The one-word answer {@code consistency} and {@code satisfiability} write.
     *
     * <p>Their {@code -o} file holds the literal ASCII {@code true} or {@code false} - no axioms,
     * no OWL. Read as text, because handing it to an OWL parser would throw and be reported as a
     * failed reasoning run rather than as the answer it is.
     *
     * @return TRUE, FALSE, or null when the file says neither
     */
    public static Boolean yesOrNo(String fileContents) {
        if (fileContents == null) {
            return null;
        }
        String word = fileContents.trim().toLowerCase(Locale.ROOT);
        if (word.startsWith("true")) {
            return Boolean.TRUE;
        }
        if (word.startsWith("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** The release this plugin was developed against, for the install guidance to name. */
    public static final String KNOWN_RELEASE = "v0.7.0-1138";

    /** Where to get it. Named in the dialog, so a user with no Konclude has somewhere to go. */
    public static final String RELEASES_URL =
            "https://github.com/konclude/Konclude/releases";

    /** What Konclude cannot tell you, said once so the dialog and the docs agree. */
    public static final List<String> LIMITS = Collections.unmodifiableList(Arrays.asList(
            "Konclude writes the DIRECT hierarchy only. Given A subClassOf B and B subClassOf C it "
                    + "returns both of those and not A subClassOf C.",
            "Its output covers seven axiom shapes: declarations, SubClassOf between named "
                    + "classes, EquivalentClasses, sub and equivalent object properties, sub and "
                    + "equivalent data properties, and ClassAssertion.",
            "It cannot emit inferred property assertions, SameIndividual, DifferentIndividuals, "
                    + "disjointness or property characteristics - there is no code path for them.",
            "It offers no explanations. Use ROBOT > Explain... for those, which runs in process.",
            "It is x86-64 only. There is no arm64 build, so on Apple silicon it runs under "
                    + "Rosetta 2.",
            "Its last release was 2021-06-19."));
}
