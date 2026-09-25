package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology;
import de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Does the plugin actually run ROBOT, or does it merely resemble it?
 *
 * <p>{@link RobotParityTest} answers that for one operation - the quality report - by comparing the
 * plugin's findings against {@code robot report} in {@code obolibrary/odkfull}. For every other
 * operation the plugin claims, the evidence was that it did not throw. That is a weaker claim than
 * it looks: a wrapper that quietly passes different options, or reimplements a step, produces
 * plausible output that nobody can tell is wrong.
 *
 * <p>So this runs each operation twice - once in process through the plugin, once as the real
 * command-line ROBOT a project's build uses - over the same pizza, and requires the answers to
 * match.
 *
 * <p><b>Like for like.</b> Each comparison invokes the CLI with the options the plugin's own call
 * passes, not with the options a project's Makefile passes. {@code RobotTransform} calls
 * {@code ReasonOperation.reason(ontology, factory)} - the two-argument form, ROBOT's defaults - so
 * the CLI is run without {@code --equivalent-classes-allowed} or {@code --exclude-tautologies}.
 * Comparing against the Makefile's invocation would be comparing two different questions and
 * reporting the difference as a defect.
 *
 * <p><b>Two ROBOT versions, deliberately.</b> The plugin embeds robot-core 1.9.8; {@code odkfull}
 * currently ships ROBOT 1.9.10. Pinning both to one version would make the suite agree with itself
 * and answer a question nobody asked - what a project's CI actually runs is {@code odkfull}, so that
 * is what the plugin has to agree with. The cost is that the two carry different OWL API versions
 * and therefore render an axiom slightly differently; {@link #normaliseLiterals} and {@link
 * #normaliseSpacing} absorb exactly those two renderings and nothing else, so a real disagreement
 * about which axioms, how many, or what value still fails.
 *
 * <p>Skipped where Docker or the image is absent, like the other container-dependent tests. A green
 * run on a machine without Docker proves nothing here.
 */
class RobotCliParityTest {

    private static final String IMAGE = "obolibrary/odkfull";

    private static final int TIMEOUT_SECONDS = 600;

    // ======================================================================= reason

    /**
     * The axioms the reasoner adds are the same ones.
     *
     * <p>The operation an OBO release is built on: {@code make reason} is what produces the
     * artefact people download. If the plugin's "Reason" inferred a different set from the build's,
     * a curator would be checking their work against something other than what ships.
     */
    @Test
    void reasonInfersTheSameAxioms(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available");

        OWLOntology pizza = PizzaOntology.v2();
        File input = save(pizza, dir, "in.owl");

        // ROBOT's own reason, with the defaults the plugin's call uses.
        run(dir, "reason", "-r", "ELK", "-i", input.getName(), "-o", "reasoned.owl");

        // Subtracting the in-memory pizza from the reloaded output would report thirty inferences
        // that are nothing of the kind: OWL API writes a plain literal for an xsd:string one and
        // reads it back untyped, so every rdfs:label in the file differs from its counterpart in
        // memory as a string. Both sides of this subtraction are therefore reloaded from disk,
        // where that difference cancels. What is left is what the reasoner actually added.
        TreeSet<String> byRobot =
                axiomsAddedBy(load(input), load(new File(dir, "reasoned.owl")));
        TreeSet<String> byPlugin = addedBy(RobotTransform.preview(pizza,
                RobotTransform.Kind.REASON, Reasoners.Choice.ELK.newFactory()));

        assertFalse(byRobot.isEmpty(), "ELK infers something about the pizza, or this proves nothing");
        assertEquals(describe(byRobot), describe(byPlugin),
                "the plugin must infer exactly what robot reason infers");
    }

    // ======================================================================= measure

    /**
     * Every metric ROBOT reports, with the same value.
     *
     * <p>Measure is the cheapest operation on the menu and the easiest to get subtly wrong: the
     * plugin groups and orders forty-odd metrics for presentation, and a grouping that dropped one
     * would look like a tidier table rather than a missing number.
     */
    @Test
    void measureReportsTheSameMetrics(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available");

        OWLOntology pizza = PizzaOntology.v2();
        File input = save(pizza, dir, "in.owl");

        // -m all, because the plugin's panel asks for every metric and ROBOT's command line
        // defaults to the essential ones. Without this the comparison is between two different
        // questions, and the plugin looks like it invented the extra rows.
        run(dir, "measure", "-i", input.getName(), "-m", "all", "-f", "tsv", "-o", "metrics.tsv");
        Map<String, String> byRobot = metricsTsv(new File(dir, "metrics.tsv"));

        // Compared on ROBOT's own metric keys rather than the humanised labels the panel shows:
        // the label is this plugin's wording, so comparing on it would only ever test humanise().
        Map<String, String> byPlugin = new TreeMap<String, String>();
        for (OntologyMeasurements.Measurement m
                : OntologyMeasurements.run(pizza, OntologyMeasurements.Depth.ALL)) {
            byPlugin.put(m.getKey(), m.getValue());
        }

        assertFalse(byRobot.isEmpty(), "robot measure produced no metrics");
        List<String> missing = new ArrayList<String>();
        List<String> differing = new ArrayList<String>();
        for (Map.Entry<String, String> metric : byRobot.entrySet()) {
            String mine = byPlugin.get(metric.getKey());
            if (mine == null) {
                missing.add(metric.getKey());
            } else if (!mine.equals(metric.getValue())) {
                differing.add(metric.getKey() + ": robot=" + metric.getValue() + " plugin=" + mine);
            }
        }
        assertEquals(new ArrayList<String>(), missing,
                "the plugin must report every metric robot measure does; these are absent");
        assertEquals(new ArrayList<String>(), differing,
                "and with the same value");
    }

    // ======================================================================= export

    /**
     * The same table of terms, column for column.
     *
     * <p>An export is read by somebody who will not open Protege, and often by a script. A column
     * rendered differently from the CLI's is a difference that surfaces in somebody else's
     * spreadsheet.
     */
    @Test
    void exportProducesTheSameTable(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available");

        OWLOntology pizza = PizzaOntology.v2();
        File input = save(pizza, dir, "in.owl");
        List<String> columns = Arrays.asList("IRI", "LABEL", "SubClass Of");

        run(dir, "export", "-i", input.getName(), "-c", String.join("|", columns),
                "-f", "tsv", "--export", "terms.tsv");
        List<String> byRobot = linesOf(new File(dir, "terms.tsv"));

        Map<String, String> options = TermExport.defaultOptions();
        TermExport.Result mine = TermExport.run(pizza, columns, options);
        File written = new File(dir, "plugin.tsv");
        mine.save(written);
        List<String> byPlugin = linesOf(written);

        assertTrue(byRobot.size() > 1, "robot export produced only a header");
        assertEquals(String.join("\n", byRobot), String.join("\n", byPlugin),
                "the plugin's export must be the table robot export produces");
    }

    // ======================================================================= extract

    /**
     * The same module, axiom for axiom.
     *
     * <p>An import module is what a downstream ontology actually consumes. If the plugin extracted
     * a different set from what {@code make all_imports} would, the module committed by a curator
     * and the module rebuilt by CI would differ - and the diff would appear on somebody else's
     * next build.
     */
    @Test
    void extractProducesTheSameModule(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available");

        OWLOntology upstream = PizzaOntology.upstreamFoodOntology();
        File input = save(upstream, dir, "upstream.owl");
        List<IRI> terms = PizzaOntology.borrowedTerms();

        StringBuilder termFile = new StringBuilder();
        for (IRI term : terms) {
            termFile.append(term.toString()).append('\n');
        }
        Files.write(new File(dir, "terms.txt").toPath(),
                termFile.toString().getBytes(StandardCharsets.UTF_8));

        IRI moduleIri = IRI.create("http://example.org/pizza/imports/food_import.owl");
        run(dir, "extract", "-i", input.getName(), "-m", "BOT", "--term-file", "terms.txt",
                "-o", "module.owl", "--output-iri", moduleIri.toString());
        TreeSet<String> byRobot = axiomStrings(load(new File(dir, "module.owl")).getAxioms());

        // Written and reloaded for the same reason the reason test reloads both sides, and because
        // a module is a file in the end: what a project commits and what CI rebuilds are both
        // serialised, so that is the form worth comparing.
        TermExtract.Result mine =
                TermExtract.run(upstream, terms, TermExtract.Method.BOT, moduleIri);
        TreeSet<String> byPlugin = axiomStrings(
                load(save(mine.getModule(), dir, "plugin-module.owl")).getAxioms());

        assertFalse(byRobot.isEmpty(), "robot extract produced an empty module");
        assertEquals(describe(byRobot), describe(byPlugin),
                "the plugin's module must be the one robot extract produces");
    }

    // ======================================================================= diff

    /**
     * The same verdict, and the same number of differing axioms.
     *
     * <p>Compared as counts and the identical/different verdict rather than as text: ROBOT's
     * markdown carries headings and blank lines that say nothing about the ontologies, and
     * asserting on those would fail on a cosmetic change upstream while missing a real one.
     */
    @Test
    void diffFindsTheSameDifferences(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available");

        OWLOntology v1 = PizzaOntology.v1();
        OWLOntology v2 = PizzaOntology.v2();
        File left = save(v1, dir, "left.owl");
        File right = save(v2, dir, "right.owl");

        run(dir, "diff", "--left", left.getName(), "--right", right.getName(),
                "-f", "plain", "-o", "diff.txt");
        String byRobot = String.join("\n", linesOf(new File(dir, "diff.txt")));

        // The plugin's own defaults are markdown with labels on, which is right for a dialog and
        // wrong for this comparison - it would be comparing two different renderings and calling
        // the difference a defect. Given the command line's options, the text should be the same
        // text, because both go through DiffOperation.compare.
        Map<String, String> asTheCliRanIt = AxiomDiff.defaultOptions();
        asTheCliRanIt.put(AxiomDiff.OPTION_FORMAT, "plain");
        asTheCliRanIt.put(AxiomDiff.OPTION_LABELS, "false");
        AxiomDiff.Result mine = AxiomDiff.between(v1, v2, asTheCliRanIt);

        assertFalse(mine.isIdentical(), "v1 and v2 differ");
        assertFalse(byRobot.isEmpty(), "robot diff reported nothing");
        assertEquals(normaliseSpacing(byRobot),
                normaliseSpacing(String.join("\n", nonBlank(mine.getLines()))),
                "the plugin's diff must be the text robot diff produces");
    }

    // ======================================================================= verify

    /**
     * The same checks pass and the same ones fail, with the same violation counts.
     *
     * <p>This is the one a project's CI acts on: {@code make sparql_test} runs {@code robot verify}
     * and a non-zero exit stops a release. If the plugin said a check passed where verify said it
     * failed, a curator would commit and then be surprised by CI.
     */
    @Test
    void verifyAgreesOnWhichChecksFail(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available");

        OWLOntology pizza = PizzaOntology.v2();
        File input = save(pizza, dir, "in.owl");

        // One check that finds nothing and one that finds every class, so both outcomes are covered.
        File passes = new File(dir, "passes.rq");
        Files.write(passes.toPath(), ("PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                + "SELECT ?entity WHERE { ?entity a owl:NoSuchThing }")
                .getBytes(StandardCharsets.UTF_8));
        File fails = new File(dir, "fails.rq");
        Files.write(fails.toPath(), ("PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                + "SELECT ?entity WHERE { ?entity a owl:Class . FILTER(!isBlank(?entity)) }")
                .getBytes(StandardCharsets.UTF_8));

        // verify exits non-zero when a query finds rows, which is the point of it.
        String output = runAllowingFailure(dir, "verify", "-i", input.getName(),
                "--queries", passes.getName(), fails.getName(), "-O", ".");

        assertTrue(output.contains("PASS Rule " + passes.getName())
                        || output.contains("PASS Rule ./" + passes.getName()),
                "robot verify should pass the empty check: " + output);
        assertTrue(output.contains("FAIL Rule " + fails.getName())
                        || output.contains("FAIL Rule ./" + fails.getName()),
                "robot verify should fail the check that finds every class: " + output);

        List<SparqlQuery.Check> mine = SparqlQuery.verify(pizza, Arrays.asList(passes, fails));
        assertEquals(2, mine.size());
        assertTrue(mine.get(0).isPassed(), "the plugin must pass the empty check");
        assertFalse(mine.get(1).isPassed(), "and fail the other one");

        // The count verify prints for the failing query, against the plugin's.
        int fromRobot = violationsIn(output, fails.getName());
        assertEquals(fromRobot, mine.get(1).getViolations(),
                "the plugin must count the same violations robot verify does");
    }

    // ======================================================================= explain

    /**
     * The same classes are explained, by the same axioms.
     *
     * <p>Compared as content rather than as text, and deliberately. ROBOT's command line writes
     * Markdown with the entities as links - {@code [Tomato](http://...#Tomato) SubClassOf ...} -
     * while the plugin renders Manchester syntax for a dialog somebody reads. Asserting on the
     * wording would only pin the plugin's presentation in place; what has to agree is which classes
     * are unsatisfiable, how many justifications each one has, and which axioms are in them.
     */
    @Test
    void explainFindsTheSameJustifications(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available");

        OWLOntology broken = PizzaOntology.withUnsatisfiableClass();
        File input = save(broken, dir, "in.owl");

        // -u all, or explain finds nothing in unsatisfiability mode and the comparison passes
        // vacuously against an empty file.
        run(dir, "explain", "-i", input.getName(), "--mode", "unsatisfiability", "-u", "all",
                "-r", "ELK", "--explanation", "explain.md");

        List<String> markdown = linesOf(new File(dir, "explain.md"));
        assertFalse(markdown.isEmpty(), "robot explain wrote nothing");
        assertFalse(markdown.get(0).startsWith("No explanations"),
                "robot explain found no explanation, so this test proves nothing: " + markdown);

        Explanations.Result mine =
                Explanations.run(broken, Reasoners.Choice.ELK.newFactory());

        // The classes ROBOT put a heading on, against the ones the plugin calls unsatisfiable.
        TreeSet<String> explainedByRobot = new TreeSet<String>();
        for (String line : markdown) {
            if (line.startsWith("## ") && line.contains("Nothing")) {
                explainedByRobot.add(firstLinkLabel(line));
            }
        }
        assertFalse(explainedByRobot.isEmpty(), "no unsatisfiability heading in " + markdown);
        assertEquals(explainedByRobot, new TreeSet<String>(mine.getUnsatisfiable()),
                "the plugin must call the same classes unsatisfiable as robot explain explains");

        // And the supporting axioms, by the terms they are about. Taken from the bullets under
        // the heading rather than from explain's --output: that file holds the whole input
        // ontology's signature, not only the axioms of the justification, so comparing against it
        // would pass whatever the plugin did.
        TreeSet<String> byRobot = new TreeSet<String>();
        for (String line : markdown) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                break; // "# Axiom Impact" - ROBOT's own summary, not a justification
            }
            if (trimmed.startsWith("- ")) {
                byRobot.addAll(linkLabels(trimmed));
            }
        }
        TreeSet<String> byPlugin = new TreeSet<String>();
        for (Explanations.Justification justification : mine.getJustifications()) {
            for (String axiom : justification.getAxioms()) {
                byPlugin.addAll(wordsIn(axiom));
            }
        }
        assertFalse(byRobot.isEmpty(), "no justification bullets in " + markdown);
        // A superset is allowed, not an accident: ROBOT's --max defaults to one explanation per
        // class and the plugin asks for three, so the plugin can rest its case on more axioms. What
        // would be wrong is missing one ROBOT used.
        assertTrue(byPlugin.containsAll(byRobot),
                "every term robot's justification rests on must appear in the plugin's: missing "
                        + minus(byRobot, byPlugin) + " from " + byPlugin);
    }

    // ======================================================================= template

    /**
     * The same axioms are built from the same template.
     *
     * <p>A template is how most OBO projects add terms in bulk, and {@code make all} applies them
     * on the next build. If the plugin built something other than what the build will, a curator
     * would be previewing one set of terms and shipping another.
     */
    @Test
    void templateBuildsTheSameAxioms(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available");

        OWLOntology pizza = PizzaOntology.v2();
        File input = save(pizza, dir, "in.owl");

        File template = new File(dir, "terms.csv");
        Files.write(template.toPath(), ("ID,Label,Type,Superclass\n"
                + "ID,LABEL,TYPE,SC %\n"
                + "http://example.org/pizza#Calzone,calzone,owl:Class,"
                + "http://example.org/pizza#Pizza\n"
                + "http://example.org/pizza#Focaccia,focaccia,owl:Class,"
                + "http://example.org/pizza#Pizza\n").getBytes(StandardCharsets.UTF_8));

        IRI resultIri = IRI.create("http://example.org/pizza/from-template.owl");
        run(dir, "template", "-i", input.getName(), "--template", template.getName(),
                "-o", "built.owl");
        TreeSet<String> byRobot = axiomStrings(load(new File(dir, "built.owl")).getAxioms());

        TemplateSheet.Result mine = TemplateSheet.run(template.getName(),
                TemplateSheet.read(template), pizza, new LinkedHashMap<String, String>(),
                resultIri);
        assertTrue(mine.getProblems().isEmpty(),
                "the plugin reported problems with a template robot accepted: "
                        + mine.getProblems());
        TreeSet<String> byPlugin =
                axiomStrings(load(save(mine.getOntology(), dir, "plugin-built.owl")).getAxioms());

        assertFalse(byRobot.isEmpty(), "robot template produced no axioms");
        assertEquals(describe(byRobot), describe(byPlugin),
                "the plugin must build what robot template builds");
    }

    // ======================================================================= profile

    /**
     * Where the plugin and {@code robot validate-profile} genuinely disagree, and why that is right.
     *
     * <p>This is the one operation that does not match, and the difference is not a defect. The OWL
     * API adds a missing annotation-property declaration while parsing, so an ontology that is
     * outside OWL 2 DL for exactly that reason is inside it again by the time it has been written to
     * a file and read back. {@code robot validate-profile} only ever sees the file, and so reports
     * it in profile. The plugin checks the ontology the curator is editing, before it is saved, and
     * reports the violation.
     *
     * <p>The plugin is the more useful of the two here: the point of a profile check in an editor is
     * to catch this while it can still be fixed by hand. But a curator who runs both and sees two
     * answers deserves to know which is which, so it is pinned here rather than left as a surprise -
     * and so that nobody later "fixes" the plugin to agree with the command line by removing the
     * check that does the work.
     */
    @Test
    void profileCheckSeesWhatValidateProfileCannot(@TempDir File dir) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available");

        // An annotation assertion whose property is never declared: an OWL 2 DL violation, and one
        // a curator creates by typing a property name into Protege.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology =
                manager.createOntology(IRI.create("http://example.org/undeclared"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLClass pizza = factory.getOWLClass(IRI.create("http://example.org/undeclared#Pizza"));
        OWLAnnotationProperty neverDeclared = factory.getOWLAnnotationProperty(
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000119"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(pizza));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(neverDeclared,
                pizza.getIRI(), factory.getOWLLiteral("a cookbook")));

        List<ProfileCheck.Violation> beforeSaving =
                ProfileCheck.violations(ontology, ProfileCheck.Target.DL);
        assertFalse(beforeSaving.isEmpty(),
                "the plugin must see the undeclared annotation property in the live ontology");

        // The same ontology through a file, which is all the command line can be given.
        File input = save(ontology, dir, "undeclared.owl");
        String output = run(dir, "validate-profile", "--profile", "DL",
                "-i", input.getName(), "-o", "verdict.txt");
        String verdict = String.join("\n", linesOf(new File(dir, "verdict.txt"))) + output;
        assertTrue(verdict.contains("in profile"),
                "robot validate-profile is expected to pass this file, because the OWL API repaired "
                        + "it on load; if this now fails, the divergence this test documents is "
                        + "gone and the comment above it is stale: " + verdict);

        // And the plugin agrees with the command line once the repair has happened, which shows the
        // disagreement is about when the ontology is read, not about the rule.
        assertTrue(ProfileCheck.violations(load(input), ProfileCheck.Target.DL).isEmpty(),
                "reloaded, the plugin must agree with robot that the file is in profile");
    }

    // ======================================================================= plumbing

    /** The label out of the first {@code [label](iri)} link on a line. */
    private static String firstLinkLabel(String line) {
        int open = line.indexOf('[');
        int close = line.indexOf(']', open + 1);
        return open < 0 || close < 0 ? line.trim() : line.substring(open + 1, close);
    }

    /** Every {@code [label](iri)} label on a line, which is how ROBOT names a term in Markdown. */
    private static TreeSet<String> linkLabels(String line) {
        TreeSet<String> labels = new TreeSet<String>();
        for (int open = line.indexOf('['); open >= 0; open = line.indexOf('[', open + 1)) {
            int close = line.indexOf("](", open + 1);
            if (close > open) {
                labels.addAll(wordsIn(line.substring(open + 1, close)));
            }
        }
        return labels;
    }

    /** The bare words of a rendered axiom, for comparing which entities it mentions. */
    private static TreeSet<String> wordsIn(String axiom) {
        TreeSet<String> words = new TreeSet<String>();
        for (String word : axiom.split("[^A-Za-z0-9_:-]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static TreeSet<String> minus(TreeSet<String> all, TreeSet<String> remove) {
        TreeSet<String> left = new TreeSet<String>(all);
        left.removeAll(remove);
        return left;
    }

    /** Axioms in {@code after} that are not in {@code before}, as strings. */
    private static TreeSet<String> axiomsAddedBy(OWLOntology before, OWLOntology after) {
        TreeSet<String> added = new TreeSet<String>(axiomStrings(after.getAxioms()));
        added.removeAll(axiomStrings(before.getAxioms()));
        return added;
    }

    private static TreeSet<String> addedBy(RobotTransform.Diff diff) {
        TreeSet<String> added = new TreeSet<String>();
        for (org.semanticweb.owlapi.model.OWLOntologyChange change : diff.getChanges()) {
            if (change.isAddAxiom()) {
                added.add(change.getAxiom().toString());
            }
        }
        return added;
    }

    /**
     * Removes whitespace before a closing parenthesis.
     *
     * <p>The one rendering difference between the two ROBOT versions' OWL APIs on this fixture:
     * {@code EquivalentClasses(A ObjectIntersectionOf(...) )} against {@code
     * EquivalentClasses(A ObjectIntersectionOf(...))}. The plugin contributes no rendering of its
     * own here - it hands back what {@code DiffOperation.compare} wrote into its writer - so the
     * space comes from whichever OWL API is on the classpath, and normalising it is not excusing
     * anything. Everything that would indicate a real disagreement survives this: the two heading
     * lines with their counts, the sign on each axiom, and the axioms themselves.
     */
    private static String normaliseSpacing(String text) {
        String previous;
        String current = text;
        do {
            previous = current;
            current = previous.replace(" )", ")");
        } while (!current.equals(previous));
        return current;
    }

    /** Blank lines dropped, matching how {@link #linesOf} reads the CLI's file. */
    private static List<String> nonBlank(List<String> lines) {
        List<String> kept = new ArrayList<String>();
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                kept.add(line);
            }
        }
        return kept;
    }

    private static TreeSet<String> axiomStrings(java.util.Set<OWLAxiom> axioms) {
        TreeSet<String> strings = new TreeSet<String>();
        for (OWLAxiom axiom : axioms) {
            strings.add(normaliseLiterals(axiom.toString()));
        }
        return strings;
    }

    /**
     * Drops an explicit {@code xsd:string} datatype from a rendered axiom.
     *
     * <p>Not a convenience. The OWL API this plugin runs against writes {@code <rdfs:label
     * rdf:datatype="&xsd;string">pizza</rdfs:label>}; the newer one inside {@code odkfull} writes
     * {@code <rdfs:label>pizza</rdfs:label>} and takes the datatype as read, which OWL 2 allows -
     * a literal with no language tag <i>is</i> an {@code xsd:string}. The two files therefore mean
     * the same thing and reload into literals that {@code OWLLiteral.toString()} renders
     * differently, so every label in the ontology looks like a difference.
     *
     * <p>Without this, a comparison of anything carrying annotations reports thirty spurious
     * findings and hides the handful of real ones underneath them.
     */
    private static String normaliseLiterals(String axiom) {
        return axiom
                .replace("^^xsd:string", "")
                .replace("^^<http://www.w3.org/2001/XMLSchema#string>", "");
    }

    /** Sorted, one per line, so an inequality reads as a diff. */
    private static String describe(TreeSet<String> values) {
        StringBuilder text = new StringBuilder(values.size() + " axioms\n");
        for (String value : values) {
            text.append(value).append('\n');
        }
        return text.toString();
    }

    /**
     * {@code robot measure}'s TSV as one value per metric.
     *
     * <p>The file is not two columns of name and number. It is {@code metric}, {@code
     * metric_value}, {@code metric_type}, and the type is one of three: a {@code single_value} has
     * one row, while a {@code list_value} and a {@code map_value} get a row per entry and so repeat
     * the metric name. Reading it as a plain two-column table keeps only whichever entry came last
     * and quietly compares one number out of twelve.
     *
     * <p>Multi-row metrics are joined with {@code ", "} because that is how the panel puts a
     * breakdown in a single cell - see {@code OntologyMeasurements.joinMap}.
     */
    private static Map<String, String> metricsTsv(File file) throws IOException {
        Map<String, List<String>> gathered = new TreeMap<String, List<String>>();
        for (String line : linesOf(file)) {
            String[] cells = line.split("\t", -1);
            if (cells.length < 2 || "metric".equalsIgnoreCase(cells[0].trim())) {
                continue;
            }
            String name = cells[0].trim();
            if (!gathered.containsKey(name)) {
                gathered.put(name, new ArrayList<String>());
            }
            gathered.get(name).add(cells[1].trim());
        }
        Map<String, String> metrics = new TreeMap<String, String>();
        for (Map.Entry<String, List<String>> metric : gathered.entrySet()) {
            metrics.put(metric.getKey(), String.join(", ", metric.getValue()));
        }
        return metrics;
    }

    private static List<String> linesOf(File file) throws IOException {
        List<String> lines = new ArrayList<String>();
        if (!file.isFile()) {
            return lines;
        }
        for (String line : new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** The violation count robot verify prints for one query. */
    private static int violationsIn(String output, String queryName) {
        for (String line : output.split("\\r?\\n")) {
            if (line.contains(queryName) && line.contains("violation")) {
                java.util.regex.Matcher number =
                        java.util.regex.Pattern.compile("(\\d+) violation").matcher(line);
                if (number.find()) {
                    return Integer.parseInt(number.group(1));
                }
            }
        }
        return -1;
    }

    private static File save(OWLOntology ontology, File dir, String name) throws Exception {
        File file = new File(dir, name);
        ontology.getOWLOntologyManager().saveOntology(ontology, new RDFXMLDocumentFormat(),
                IRI.create(file.toURI()));
        return file;
    }

    /** Loaded in a manager of its own, so nothing is shared with the ontology under test. */
    private static OWLOntology load(File file) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(file);
    }

    private static String run(File workingDir, String... robotArguments) throws Exception {
        return docker(workingDir, true, robotArguments);
    }

    /** For commands whose non-zero exit is the answer rather than a failure - verify. */
    private static String runAllowingFailure(File workingDir, String... robotArguments)
            throws Exception {
        return docker(workingDir, false, robotArguments);
    }

    private static String docker(File workingDir, boolean mustSucceed, String... robotArguments)
            throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList("docker", "run", "--rm",
                "-v", workingDir.getAbsolutePath() + ":/work", "-w", "/work", IMAGE, "robot"));
        command.addAll(Arrays.asList(robotArguments));
        return execute(command, mustSucceed);
    }

    private static boolean imageIsAvailable() {
        try {
            execute(Arrays.asList("docker", "image", "inspect", IMAGE), true);
            return true;
        } catch (Exception notThere) {
            return false;
        }
    }

    private static String execute(List<String> command, boolean mustSucceed) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        InputStream out = process.getInputStream();
        byte[] chunk = new byte[8192];
        for (int read = out.read(chunk); read >= 0; read = out.read(chunk)) {
            captured.write(chunk, 0, read);
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timed out after " + TIMEOUT_SECONDS + "s: " + command);
        }
        String output = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        if (mustSucceed && process.exitValue() != 0) {
            throw new IOException(command + " exited " + process.exitValue() + ":\n" + output);
        }
        return output;
    }
}
