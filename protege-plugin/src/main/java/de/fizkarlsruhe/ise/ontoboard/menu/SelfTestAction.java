package de.fizkarlsruhe.ise.ontoboard.menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * OntoBoard &gt; Run self-test - actually runs the menu items, in the host, and reports.
 *
 * <p>This is the half of the plan's Phase 2 that the startup check cannot reach. {@link
 * de.fizkarlsruhe.ise.ontoboard.SelfCheck} proves every action class <em>loads and constructs</em>
 * under Felix, which catches a dead menu item. It cannot tell you an item throws the moment it does
 * any work, because loading a class is not running it.
 *
 * <p>So this constructs each action the way Protege does - {@code setEditorKit}, {@code initialise}
 * - and calls its {@code run} against a scratch ontology built here. Everything the action needs
 * from the live session it still gets, because it is wired to the real editor kit; what it operates
 * on is a throwaway. A maintainer runs this before cutting a release and learns, in about a second,
 * whether six of the menu items still do their job in a bundle.
 *
 * <p><b>Why a scratch ontology and not the open one.</b> An action that ran against the user's work
 * and went wrong would be the worst possible failure mode for a self-test, and some of these
 * actions read the open ontology's project directory anyway - so the session is still exercised.
 *
 * <p><b>What is deliberately left out, and why.</b> Only actions that change nothing are run:
 *
 * <ul>
 *   <li>{@code TransformAction} defaults to {@code apply = true}. Without its dialog it would
 *       compute a relax and then push the changes through the model manager, which is the live
 *       session's, not the scratch ontology's. A self-test that edits an ontology is not a test.
 *   <li>{@code RenameAction}, {@code ImportTermsAction}, {@code ObsoleteAction},
 *       {@code ReleaseAction}, {@code BuildAction}, {@code GitAction} and the rest either write
 *       files, need a configured project, or reach the network. They are covered by unit tests and
 *       by the ODK build test; what they are not covered by is this, and saying so is better than
 *       a self-test that quietly checks a quarter of what its name suggests.
 * </ul>
 *
 * <p>So this is six of the twenty-odd items. That is a real and stated limit, not a claim to have
 * driven the menu.
 */
public class SelfTestAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String NS = "http://www.ontoboard.org/self-test#";

    @Override
    protected String operationName() {
        return "Self-test";
    }

    /**
     * No ontology needed: it builds its own.
     *
     * <p>Which also makes this the one item a maintainer can run immediately after installing,
     * before opening anything.
     */
    @Override
    protected boolean needsAnOntology() {
        return false;
    }

    @Override
    protected boolean runsInBackground() {
        return true;
    }

    /**
     * The self-test, callable without going through the menu.
     *
     * <p>{@link de.fizkarlsruhe.ise.ontoboard.OntoBoardStartup} uses this so the automated run and
     * the menu item execute the same code. A self-test with two implementations is two things to
     * keep in step, and the one nobody runs is the one that rots.
     */
    public OperationResult selfTest() {
        return run((OWLOntology) null);
    }

    @Override
    protected OperationResult run(OWLOntology ignored) {
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Menu item", "Result", "What it reported");

        OWLOntology scratch;
        try {
            scratch = scratchOntology();
        } catch (Exception cannotBuild) {
            return result.failed("Could not build the scratch ontology to test against: "
                    + cannotBuild.getMessage()).build();
        }

        Map<String, OntoBoardAction> underTest = new LinkedHashMap<String, OntoBoardAction>();
        underTest.put("ROBOT > Measure...", new MeasureAction());
        underTest.put("ROBOT > Quality report...", new QualityReportAction());
        underTest.put("ROBOT > Explain...", new ExplainAction());
        underTest.put("ROBOT > SPARQL...", new SparqlAction());
        underTest.put("ROBOT > Export terms...", new ExportAction());
        underTest.put("ROBOT > Profile...", new ProfileAction());

        int failed = 0;
        for (Map.Entry<String, OntoBoardAction> entry : underTest.entrySet()) {
            String outcome = runOne(entry.getValue(), scratch);
            boolean ok = outcome.startsWith("ok:");
            if (!ok) {
                failed++;
                result.warn(entry.getKey() + " failed: " + outcome);
            }
            result.row(entry.getKey(), ok ? "ok" : "FAILED",
                    outcome.startsWith("ok:") ? outcome.substring(3).trim() : outcome);
        }

        for (String skipped : skippedWithReasons()) {
            result.note(skipped);
        }
        result.note("Ran against a scratch ontology, not the one you have open.");

        if (failed > 0) {
            return result.failed(failed + " of " + underTest.size()
                    + " menu items failed. This is a defect in the plugin, not in your "
                    + "ontology.").build();
        }
        return result.summary(underTest.size() + " of " + underTest.size()
                + " menu items ran. " + skippedWithReasons().size()
                + " others are not covered here - see the notes.").build();
    }

    /**
     * Wires one action the way Protege does and runs it.
     *
     * <p>{@code configure()} is deliberately not called: it opens a modal dialog, and a self-test
     * that asks twenty questions is one nobody runs. Each action therefore runs on its field
     * defaults, which is what it would do if somebody pressed OK without changing anything.
     */
    private String runOne(OntoBoardAction action, OWLOntology scratch) {
        try {
            action.setEditorKit(getEditorKit());
            action.initialise();
        } catch (Exception | LinkageError cannotWire) {
            return "could not be constructed: " + describe(cannotWire);
        }
        try {
            OperationResult outcome = action.run(scratch);
            if (outcome == null) {
                return "returned no result at all";
            }
            if (!outcome.isSuccess()) {
                return "reported a failure: " + outcome.getSummary();
            }
            return "ok: " + outcome.getSummary();
        } catch (Exception | LinkageError threw) {
            return "threw " + describe(threw);
        } finally {
            try {
                action.dispose();
            } catch (Exception ignored) {
                // An action that cannot be disposed has still told us what we came to find out.
            }
        }
    }

    private static List<String> skippedWithReasons() {
        List<String> skipped = new ArrayList<String>();
        skipped.add("Not run - Transform...: it defaults to applying its changes, and without its "
                + "dialog it would push them through the live session's model manager.");
        skipped.add("Not run - Rename IRIs..., Import terms..., Obsolete..., Release..., Build..., "
                + "Git..., Open from GitHub..., New ODK project...: they write files, need a "
                + "configured project, or reach the network.");
        skipped.add("Not run - the canvas, the notes and the collaboration items: they are Swing "
                + "surfaces, and nothing here opens a window.");
        return skipped;
    }

    /**
     * A small ontology with something for each operation to say.
     *
     * <p>One labelled class, one unlabelled class with a parent - so the quality report has a
     * violation to find - a property, and no ontology metadata, which is three more. Consistent and
     * satisfiable, so Explain's answer is "nothing to explain", which is itself the outcome worth
     * checking: it must read as success rather than as an empty failure.
     */
    private OWLOntology scratchOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(
                IRI.create("http://www.ontoboard.org/self-test"));

        OWLClass agent = factory.getOWLClass(IRI.create(NS + "Agent"));
        OWLClass person = factory.getOWLClass(IRI.create(NS + "Person"));
        OWLObjectProperty knows = factory.getOWLObjectProperty(IRI.create(NS + "knows"));

        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(agent));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(person));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(knows));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(person, agent));
        manager.addAxiom(ontology, factory.getOWLObjectPropertyDomainAxiom(knows, person));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                agent.getIRI(), factory.getOWLLiteral("agent")));
        return ontology;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }
}
