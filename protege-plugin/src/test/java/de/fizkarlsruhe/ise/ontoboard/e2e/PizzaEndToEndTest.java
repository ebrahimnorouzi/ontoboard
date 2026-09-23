package de.fizkarlsruhe.ise.ontoboard.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseDiff;
import de.fizkarlsruhe.ise.ontoboard.reason.InferredEdges;
import de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologyMeasurements;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * The whole plugin, driven end to end over a pizza ontology, with every report written to disk.
 *
 * <p>This exists because of a gap nothing else in the suite covers. Every feature has a unit test
 * that proves the feature works; none of them proves the features work <em>together</em> on one
 * ontology a person would recognise. Two defects found while writing this were of exactly that
 * kind: "Add all" collected classes and individuals but not properties, and inferences were drawn
 * for classes but never for individuals. Both are invisible to a unit test of the thing itself,
 * because in both cases the thing itself was right.
 *
 * <p><b>What this cannot do.</b> It does not open Protege, and it draws nothing. Protege is a
 * desktop application and there is no installation on the machine this runs on. What it does is
 * call exactly the code the canvas calls - {@link OntologyProjection}, {@link InferredEdges},
 * {@link Reasoners} - so a defect in what the canvas would show is a failure here. A defect in the
 * Swing painting of it is not, and this cannot see one.
 *
 * <p>Every artefact is written under {@code -De2e.out=...} (default {@code target/e2e}) so the run
 * is reproducible and the reports can be published:
 * {@code mvn -o test -Dtest=PizzaEndToEndTest -De2e.out=/somewhere}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PizzaEndToEndTest {

    private static final String NS = PizzaOntology.NS;
    private static File out;

    @BeforeAll
    static void anOutputDirectory() throws IOException {
        out = new File(System.getProperty("e2e.out", "target/e2e"));
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IOException("could not create " + out.getAbsolutePath());
        }
        new File(out, "ontologies").mkdirs();
        new File(out, "reports").mkdirs();
    }

    private static void report(String name, String body) {
        try {
            Files.write(new File(out, "reports/" + name).toPath(),
                    body.getBytes(Charset.forName("UTF-8")));
        } catch (IOException cannotWrite) {
            throw new IllegalStateException("could not write " + name, cannotWrite);
        }
    }

    private static void save(OWLOntology ontology, String name) throws Exception {
        File file = new File(out, "ontologies/" + name);
        ontology.getOWLOntologyManager().saveOntology(ontology, IRI.create(file.toURI()));
    }

    // ================================================================= 1. the ontology

    @Test
    @Order(1)
    void theThreeReleasesAreBuiltAndSaved() throws Exception {
        OWLOntology v1 = PizzaOntology.v1();
        OWLOntology v2 = PizzaOntology.v2();
        OWLOntology v3 = PizzaOntology.v3();
        save(v1, "pizza-v1.owl");
        save(v2, "pizza-v2.owl");
        save(v3, "pizza-v3.owl");

        StringBuilder text = new StringBuilder("Pizza ontology, three releases\n");
        text.append("==============================\n\n");
        for (Object[] release : new Object[][] {{"v1", v1}, {"v2", v2}, {"v3", v3}}) {
            OWLOntology o = (OWLOntology) release[1];
            text.append(release[0]).append("  version ")
                    .append(o.getOntologyID().getVersionIRI().isPresent()
                            ? o.getOntologyID().getVersionIRI().get() : "(none)")
                    .append('\n');
            text.append("    axioms      ").append(o.getAxiomCount()).append('\n');
            text.append("    classes     ").append(o.getClassesInSignature().size()).append('\n');
            text.append("    obj props   ").append(o.getObjectPropertiesInSignature().size())
                    .append('\n');
            text.append("    data props  ").append(o.getDataPropertiesInSignature().size())
                    .append('\n');
            text.append("    individuals ").append(o.getIndividualsInSignature().size())
                    .append("\n\n");
        }
        report("01-releases.txt", text.toString());

        assertTrue(v1.getAxiomCount() > 40, "v1 is too small to be worth testing");
        assertTrue(v2.getAxiomCount() > v1.getAxiomCount());
        assertTrue(v3.getAxiomCount() > v2.getAxiomCount());
        assertFalse(v2.getDataPropertiesInSignature().isEmpty(), "v2 must have a data property");
        assertFalse(v2.getIndividualsInSignature().isEmpty(), "v2 must have individuals");
    }

    // ================================================================= 2. the canvas

    /**
     * The defect this run was written for. "Add all" offered classes and individuals only, so on
     * this ontology it withheld four properties and drew no property hierarchy at all.
     */
    @Test
    @Order(2)
    void everyKindOfTermReachesTheCanvas() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();
        Set<String> offered = OntologyProjection.everythingWorthShowing(pizza);
        Projection projection = OntologyProjection.project(pizza, offered);

        TreeMap<NodeKind, List<String>> byKind = new TreeMap<NodeKind, List<String>>();
        for (CanvasNode node : projection.getNodes()) {
            if (!byKind.containsKey(node.getKind())) {
                byKind.put(node.getKind(), new ArrayList<String>());
            }
            byKind.get(node.getKind()).add(node.getLabel());
        }
        TreeMap<CanvasEdge.Kind, Integer> edgeCounts = new TreeMap<CanvasEdge.Kind, Integer>();
        for (CanvasEdge edge : projection.getEdges()) {
            edgeCounts.put(edge.getKind(),
                    edgeCounts.containsKey(edge.getKind()) ? edgeCounts.get(edge.getKind()) + 1 : 1);
        }

        StringBuilder text = new StringBuilder("What the canvas shows for pizza v2\n");
        text.append("==================================\n\n");
        text.append("Offered by \"Add all\": ").append(offered.size()).append(" terms\n\n");
        text.append("NODES\n");
        for (NodeKind kind : byKind.keySet()) {
            List<String> labels = byKind.get(kind);
            Collections.sort(labels);
            text.append("  ").append(kind).append("  (").append(labels.size()).append(")\n");
            for (String label : labels) {
                text.append("      ").append(label).append('\n');
            }
        }
        text.append("\nEDGES\n");
        for (CanvasEdge.Kind kind : edgeCounts.keySet()) {
            text.append("  ").append(kind).append("  ").append(edgeCounts.get(kind)).append('\n');
        }
        report("02-canvas-projection.txt", text.toString());

        assertTrue(byKind.containsKey(NodeKind.CLASS), "no classes on the canvas");
        assertTrue(byKind.containsKey(NodeKind.INDIVIDUAL), "no individuals on the canvas");
        assertTrue(byKind.containsKey(NodeKind.OBJECT_PROPERTY),
                "no object properties on the canvas - the defect this run was written for");
        assertTrue(byKind.containsKey(NodeKind.DATA_PROPERTY),
                "no data properties on the canvas");
        assertTrue(edgeCounts.containsKey(CanvasEdge.Kind.SUBCLASS), "no subclass edges");
        assertTrue(edgeCounts.containsKey(CanvasEdge.Kind.TYPE),
                "individuals are on the board with no edge to their class");
    }

    // ================================================================= 3. the reasoner

    @Test
    @Order(3)
    void theOntologyIsConsistentAndTheReasonerAgrees() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();
        StringBuilder text = new StringBuilder("Consistency and coherence\n");
        text.append("=========================\n\n");

        for (Reasoners.Choice choice : new Reasoners.Choice[] {
            Reasoners.Choice.ELK, Reasoners.Choice.HERMIT}) {
            OWLReasoner reasoner = choice.newFactory().createReasoner(pizza);
            boolean consistent = reasoner.isConsistent();
            Set<String> unsatisfiable = InferredEdges.unsatisfiableClasses(reasoner,
                    OntologyProjection.everythingWorthShowing(pizza));
            text.append(choice.getLabel()).append('\n');
            text.append("    consistent            ").append(consistent).append('\n');
            text.append("    unsatisfiable classes ").append(unsatisfiable.size()).append('\n');
            assertTrue(consistent, choice.getLabel() + " says pizza v2 is inconsistent");
            assertTrue(unsatisfiable.isEmpty(),
                    choice.getLabel() + " found unsatisfiable classes: " + unsatisfiable);
            reasoner.dispose();
        }

        // The deliberate faults, so the unhealthy paths are exercised too.
        OWLOntology broken = PizzaOntology.withUnsatisfiableClass();
        OWLReasoner onBroken = Reasoners.Choice.HERMIT.newFactory().createReasoner(broken);
        Set<String> unsatisfiable = InferredEdges.unsatisfiableClasses(onBroken,
                OntologyProjection.everythingWorthShowing(broken));
        text.append("\nwithUnsatisfiableClass (deliberate)\n");
        text.append("    unsatisfiable: ").append(unsatisfiable).append('\n');
        assertTrue(unsatisfiable.contains(NS + "ImpossiblePizza"),
                "the deliberately impossible class was not reported: " + unsatisfiable);
        onBroken.dispose();

        OWLOntology inconsistent = PizzaOntology.madeInconsistent();
        OWLReasoner onInconsistent =
                Reasoners.Choice.HERMIT.newFactory().createReasoner(inconsistent);
        text.append("\nmadeInconsistent (deliberate)\n");
        text.append("    consistent: ").append(onInconsistent.isConsistent()).append('\n');
        assertFalse(onInconsistent.isConsistent(),
                "an individual in two disjoint classes should make the ontology inconsistent");
        onInconsistent.dispose();

        report("03-consistency.txt", text.toString());
    }

    /**
     * The other defect: inferences were drawn for classes and never for individuals, so a board of
     * individuals showed nothing when inferences were switched on.
     */
    @Test
    @Order(4)
    void theReasonersConclusionsReachTheCanvas() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();
        Set<String> onCanvas = OntologyProjection.everythingWorthShowing(pizza);
        Projection asserted = OntologyProjection.project(pizza, onCanvas);

        StringBuilder text = new StringBuilder("What the reasoner adds to the canvas\n");
        text.append("====================================\n\n");
        Set<String> elkConclusions = new LinkedHashSet<String>();

        for (Reasoners.Choice choice : new Reasoners.Choice[] {
            Reasoners.Choice.ELK, Reasoners.Choice.HERMIT}) {
            OWLReasoner reasoner = choice.newFactory().createReasoner(pizza);
            List<CanvasEdge> subclasses = InferredEdges.subClassEdges(reasoner, onCanvas,
                    asserted.getEdges(), pizza.getOWLOntologyManager().getOWLDataFactory());
            List<CanvasEdge> types = InferredEdges.typeEdges(reasoner, onCanvas,
                    asserted.getEdges(), pizza.getOWLOntologyManager().getOWLDataFactory());

            text.append(choice.getLabel()).append('\n');
            text.append("  inferred subclass edges (").append(subclasses.size()).append(")\n");
            for (CanvasEdge edge : sorted(subclasses)) {
                text.append("      ").append(shortName(edge.getSourceId())).append("  ->  ")
                        .append(shortName(edge.getTargetId())).append('\n');
            }
            text.append("  inferred type edges (").append(types.size()).append(")\n");
            for (CanvasEdge edge : sorted(types)) {
                text.append("      ").append(shortName(edge.getSourceId())).append("  ->  ")
                        .append(shortName(edge.getTargetId())).append('\n');
                if (choice == Reasoners.Choice.ELK) {
                    elkConclusions.add(shortName(edge.getSourceId()) + " -> "
                            + shortName(edge.getTargetId()));
                }
            }
            text.append('\n');

            // Every reasoner must reach the existential conclusion: margherita has mozzarella,
            // mozzarella is a cheese topping, so margherita is a cheesy pizza. Nobody said so.
            assertTrue(contains(types, NS + "margherita", NS + "CheesyPizza"),
                    choice.getLabel() + " did not infer that margherita is a cheesy pizza: "
                            + describe(types));
            assertFalse(contains(types, NS + "americanHot", NS + "CheesyPizza"),
                    choice.getLabel() + " thinks a pepperoni pizza is cheesy");
            reasoner.dispose();
        }

        // The contrast the profile warning exists to make visible. VegetarianPizza is defined with
        // a universal restriction, which is outside OWL 2 EL - so HermiT reaches the conclusion
        // and ELK silently does not.
        OWLReasoner elk = Reasoners.Choice.ELK.newFactory().createReasoner(pizza);
        OWLReasoner hermit = Reasoners.Choice.HERMIT.newFactory().createReasoner(pizza);
        List<CanvasEdge> elkTypes = InferredEdges.typeEdges(elk, onCanvas, asserted.getEdges(),
                pizza.getOWLOntologyManager().getOWLDataFactory());
        List<CanvasEdge> hermitTypes = InferredEdges.typeEdges(hermit, onCanvas,
                asserted.getEdges(), pizza.getOWLOntologyManager().getOWLDataFactory());

        text.append("The EL boundary, shown rather than described\n");
        text.append("-------------------------------------------\n");
        text.append("VegetarianPizza is defined with a universal restriction, which OWL 2 EL\n");
        text.append("does not contain. ELK ignores such an axiom silently.\n\n");
        text.append("    HermiT infers margherita is a vegetarian pizza: ")
                .append(contains(hermitTypes, NS + "margherita", NS + "VegetarianPizza"))
                .append('\n');
        text.append("    ELK    infers margherita is a vegetarian pizza: ")
                .append(contains(elkTypes, NS + "margherita", NS + "VegetarianPizza"))
                .append('\n');
        report("04-inferences.txt", text.toString());

        assertTrue(contains(hermitTypes, NS + "margherita", NS + "VegetarianPizza"),
                "HermiT should reach the universal-restriction conclusion: "
                        + describe(hermitTypes));
        assertFalse(contains(elkTypes, NS + "margherita", NS + "VegetarianPizza"),
                "ELK reached a conclusion outside its profile, which would mean this test's "
                        + "premise about the EL boundary is wrong");
        elk.dispose();
        hermit.dispose();
    }

    // ================================================================= 4. ROBOT

    @Test
    @Order(5)
    void robotRunsOverIt() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();

        StringBuilder measure = new StringBuilder("ROBOT measure\n=============\n\n");
        List<OntologyMeasurements.Measurement> measurements =
                OntologyMeasurements.run(pizza, OntologyMeasurements.Depth.EXTENDED);
        for (OntologyMeasurements.Measurement m : measurements) {
            measure.append(String.format("%-14s %-42s %s%n", m.getGroup(), m.getLabel(),
                    m.getValue()));
        }
        report("05-robot-measure.txt", measure.toString());
        assertFalse(measurements.isEmpty(), "ROBOT measure produced nothing");

        // ROBOT report either runs or explains why this host cannot support it. robot-core
        // 1.9.8 routes it through Rio, which needs OWL API 4.5.25 or newer; Protege 5.5 supplies
        // 4.5.9. Recording which of the two happened is the useful thing for a reproducible run -
        // failing here would say "the plugin is broken" about a host limitation it already
        // diagnoses for the user.
        StringBuilder quality = new StringBuilder("ROBOT report\n============\n\n");
        try {
            List<QualityFinding> findings = QualityReport.run(pizza);
            quality.append(findings.size()).append(" findings\n\n");
            for (QualityFinding finding : findings) {
                quality.append(String.format("%-8s %-34s %s%n", finding.getSeverity(),
                        finding.getRule(), finding.getSubject()));
            }
            assertNotNull(findings);
        } catch (QualityReport.QualityReportException cannotRunHere) {
            quality.append("Did not run on this host.\n\n");
            quality.append(cannotRunHere.getMessage()).append("\n\n");
            quality.append("This is the documented OWL API incompatibility, not a defect in the\n");
            quality.append("plugin: robot-core routes report through Rio, which needs OWL API\n");
            quality.append("4.5.25 or newer, and Protege 5.5 supplies 4.5.9. Reasoning, loading\n");
            quality.append("and saving are unaffected. Every other ROBOT operation in this run\n");
            quality.append("worked.\n");
            assertTrue(cannotRunHere.isHostIncompatibility(),
                    "a report failure must be the known incompatibility carrying an explanation, "
                            + "never a bare crash: " + cannotRunHere.getMessage());
        }
        report("06-robot-report.txt", quality.toString());

        StringBuilder transforms = new StringBuilder("ROBOT transforms\n================\n\n");
        for (RobotTransform.Kind kind : new RobotTransform.Kind[] {
            RobotTransform.Kind.RELAX, RobotTransform.Kind.REDUCE, RobotTransform.Kind.REASON}) {
            RobotTransform.Diff diff = RobotTransform.preview(pizza, kind,
                    Reasoners.Choice.ELK.newFactory());
            transforms.append(kind.getLabel()).append(": ")
                    .append(diff.getChanges().size()).append(" changes\n");
            for (org.semanticweb.owlapi.model.OWLOntologyChange change : diff.getChanges()) {
                transforms.append("    ").append(change.isAddAxiom() ? "+ " : "- ")
                        .append(change.getAxiom()).append('\n');
            }
            transforms.append('\n');
        }
        report("07-robot-transforms.txt", transforms.toString());
    }

    @Test
    @Order(6)
    void theProfileIsReportedWithTheAxiomsThatLeaveIt() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();
        ProfileCheck.Target tightest = ProfileCheck.tightestProfile(pizza);
        List<ProfileCheck.Violation> outsideEl =
                ProfileCheck.violations(pizza, ProfileCheck.Target.EL);

        StringBuilder text = new StringBuilder("OWL 2 profile\n=============\n\n");
        text.append("Tightest profile: ")
                .append(tightest == null ? "outside OWL 2 DL" : "OWL 2 " + tightest.getLabel())
                .append("\n\n");
        text.append(outsideEl.size()).append(" axioms outside OWL 2 EL");
        text.append(" - ELK ignores each of these silently:\n\n");
        for (ProfileCheck.Violation violation : outsideEl) {
            text.append("    ").append(violation.getMessage()).append('\n');
        }
        report("08-profile.txt", text.toString());

        assertFalse(outsideEl.isEmpty(),
                "VegetarianPizza's universal restriction should leave EL, so this ontology "
                        + "exercises the profile warning");
    }

    // ================================================================= 5. release evolution

    @Test
    @Order(7)
    void theEvolutionBetweenReleasesIsDescribed() throws Exception {
        OWLOntology v1 = PizzaOntology.v1();
        OWLOntology v2 = PizzaOntology.v2();
        OWLOntology v3 = PizzaOntology.v3();

        ReleaseDiff oneToTwo = ReleaseDiff.between(v1, v2);
        ReleaseDiff twoToThree = ReleaseDiff.between(v2, v3);

        StringBuilder text = new StringBuilder("Evolution across releases\n");
        text.append("=========================\n\n");
        text.append("v1 -> v2\n").append(oneToTwo.summary()).append("\n\n");
        for (ReleaseDiff.TermChange change : oneToTwo.getChanges()) {
            text.append("    ").append(change).append('\n');
        }
        text.append("\nv2 -> v3\n").append(twoToThree.summary()).append("\n\n");
        for (ReleaseDiff.TermChange change : twoToThree.getChanges()) {
            text.append("    ").append(change).append('\n');
        }
        report("09-release-diff.txt", text.toString());

        report("10-release-notes-v2.md", oneToTwo.asReleaseNotes("2026-03-02", v2));
        report("11-release-notes-v3.md", twoToThree.asReleaseNotes("2026-06-10", v3));

        assertFalse(oneToTwo.of(ReleaseDiff.Change.ADDED).isEmpty(), "v2 added terms");
        assertFalse(oneToTwo.of(ReleaseDiff.Change.RELABELLED).isEmpty(),
                "v2 relabelled the thin and crispy base: " + oneToTwo.getChanges());
        assertFalse(twoToThree.of(ReleaseDiff.Change.OBSOLETED).isEmpty(),
                "v3 obsoleted the olive topping: " + twoToThree.getChanges());
        assertFalse(twoToThree.of(ReleaseDiff.Change.MOVED).isEmpty(),
                "v3 moved pepperoni under cured meat: " + twoToThree.getChanges());
        assertTrue(twoToThree.removals().isEmpty(),
                "nothing should be deleted outright - obsoletion is the OBO way, and the "
                        + "release check refuses a dropped published term");
    }

    @Test
    @Order(8)
    void theRunLeavesAReadableIndexBehind() throws Exception {
        StringBuilder index = new StringBuilder("# Pizza end-to-end run\n\n");
        index.append("Produced by `mvn -o test -Dtest=PizzaEndToEndTest`. Every file here is\n");
        index.append("regenerated from code in `PizzaOntology.java`, so the run is reproducible\n");
        index.append("and nothing was edited by hand.\n\n");
        index.append("| File | What it holds |\n|---|---|\n");
        index.append("| `ontologies/pizza-v1.owl` | first release |\n");
        index.append("| `ontologies/pizza-v2.owl` | defined classes, data property, individuals |\n");
        index.append("| `ontologies/pizza-v3.owl` | one term obsoleted, one moved |\n");
        index.append("| `reports/01-releases.txt` | size and shape of each release |\n");
        index.append("| `reports/02-canvas-projection.txt` | every node and edge the canvas draws |\n");
        index.append("| `reports/03-consistency.txt` | ELK and HermiT, plus two deliberate faults |\n");
        index.append("| `reports/04-inferences.txt` | what each reasoner concludes, and where they differ |\n");
        index.append("| `reports/05-robot-measure.txt` | ROBOT measure |\n");
        index.append("| `reports/06-robot-report.txt` | ROBOT report findings |\n");
        index.append("| `reports/07-robot-transforms.txt` | relax, reduce, reason |\n");
        index.append("| `reports/08-profile.txt` | which axioms leave OWL 2 EL |\n");
        index.append("| `reports/09-release-diff.txt` | term-by-term evolution |\n");
        index.append("| `reports/10-release-notes-v2.md` | generated release notes |\n");
        index.append("| `reports/11-release-notes-v3.md` | generated release notes |\n");
        index.append("\n## What this run does not cover\n\n");
        index.append("It does not open Protege and it draws nothing - there is no Protege on the\n");
        index.append("machine that produced these files. It calls the same code the canvas calls,\n");
        index.append("so a defect in *what* would be drawn fails this run; a defect in the Swing\n");
        index.append("painting of it would not.\n");
        report("00-index.md", index.toString());

        assertTrue(new File(out, "reports/04-inferences.txt").isFile(),
                "the inference report should exist by now - check the @Order sequence");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean contains(List<CanvasEdge> edges, String source, String target) {
        for (CanvasEdge edge : edges) {
            if (edge.getSourceId().equals(source) && edge.getTargetId().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<CanvasEdge> edges) {
        StringBuilder text = new StringBuilder();
        for (CanvasEdge edge : edges) {
            text.append(shortName(edge.getSourceId())).append("->")
                    .append(shortName(edge.getTargetId())).append(' ');
        }
        return text.toString();
    }

    private static List<CanvasEdge> sorted(List<CanvasEdge> edges) {
        List<CanvasEdge> copy = new ArrayList<CanvasEdge>(edges);
        Collections.sort(copy, (a, b) -> (a.getSourceId() + a.getTargetId())
                .compareTo(b.getSourceId() + b.getTargetId()));
        return copy;
    }

    private static String shortName(String iri) {
        int hash = iri.lastIndexOf('#');
        return hash < 0 ? iri : iri.substring(hash + 1);
    }

    @Test
    @Order(9)
    void assertedAndInferredAreNeverConfused() throws Exception {
        OWLOntology pizza = PizzaOntology.v2();
        Set<String> onCanvas = OntologyProjection.everythingWorthShowing(pizza);
        Projection asserted = OntologyProjection.project(pizza, onCanvas);
        OWLReasoner hermit = Reasoners.Choice.HERMIT.newFactory().createReasoner(pizza);

        List<CanvasEdge> inferred = new ArrayList<CanvasEdge>();
        inferred.addAll(InferredEdges.subClassEdges(hermit, onCanvas, asserted.getEdges(),
                pizza.getOWLOntologyManager().getOWLDataFactory()));
        inferred.addAll(InferredEdges.typeEdges(hermit, onCanvas, asserted.getEdges(),
                pizza.getOWLOntologyManager().getOWLDataFactory()));

        // The single most misleading thing this canvas could do is draw a conclusion identically
        // to an asserted axiom, so every inferred edge must carry an inferred kind and no
        // asserted edge may.
        for (CanvasEdge edge : inferred) {
            assertTrue(edge.getKind() == CanvasEdge.Kind.INFERRED_SUBCLASS
                            || edge.getKind() == CanvasEdge.Kind.INFERRED_TYPE,
                    "an inferred edge is drawn as an asserted one: " + edge.getKind());
        }
        for (CanvasEdge edge : asserted.getEdges()) {
            assertFalse(edge.getKind() == CanvasEdge.Kind.INFERRED_SUBCLASS
                            || edge.getKind() == CanvasEdge.Kind.INFERRED_TYPE,
                    "an asserted edge is drawn as a conclusion: " + edge);
        }
        assertEquals(0, countDuplicates(asserted.getEdges(), inferred),
                "a conclusion was drawn on top of an axiom that already said it");
        hermit.dispose();
    }

    private static int countDuplicates(List<CanvasEdge> asserted, List<CanvasEdge> inferred) {
        Set<String> assertedPairs = new LinkedHashSet<String>();
        for (CanvasEdge edge : asserted) {
            assertedPairs.add(edge.getSourceId() + "->" + edge.getTargetId());
        }
        int duplicates = 0;
        for (CanvasEdge edge : inferred) {
            if (assertedPairs.contains(edge.getSourceId() + "->" + edge.getTargetId())) {
                duplicates++;
            }
        }
        return duplicates;
    }
}
