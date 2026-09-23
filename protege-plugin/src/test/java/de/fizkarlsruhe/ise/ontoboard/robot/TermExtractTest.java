package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Extracting a module from somebody else's ontology.
 *
 * <p>Two things are being defended. The first is that nothing is taken from the user in silence:
 * a term the source no longer has - obsoleted since the term list was written - produces a smaller
 * module that looks entirely correct, and the only way anyone finds out is a dangling reference
 * later. The second is that the source ontology is not touched, since it is often one Protege has
 * open.
 */
class TermExtractTest {

    private static final String NS = "http://example.org/source#";

    private OWLOntologyManager manager;
    private OWLOntology source;
    private OWLDataFactory factory;

    /** A small ontology with a hierarchy, some logic, and labels - enough to tell the methods apart. */
    @BeforeEach
    void aSourceOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        source = manager.createOntology(IRI.create("http://example.org/source.owl"));
        factory = manager.getOWLDataFactory();

        // Thing > Material > Polymer > Polyethylene
        add(factory.getOWLSubClassOfAxiom(cls("Material"), cls("Thing")));
        add(factory.getOWLSubClassOfAxiom(cls("Polymer"), cls("Material")));
        add(factory.getOWLSubClassOfAxiom(cls("Polyethylene"), cls("Polymer")));
        add(factory.getOWLSubClassOfAxiom(cls("Polypropylene"), cls("Polymer")));
        // An unrelated branch, so a module can be smaller than the whole ontology.
        add(factory.getOWLSubClassOfAxiom(cls("Process"), cls("Thing")));
        add(factory.getOWLSubClassOfAxiom(cls("Sintering"), cls("Process")));

        for (String name : new String[] {"Thing", "Material", "Polymer", "Polyethylene",
                "Polypropylene", "Process", "Sintering"}) {
            add(factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                    cls(name).getIRI(), factory.getOWLLiteral(name)));
        }
    }

    private void add(OWLAxiom axiom) {
        manager.addAxiom(source, axiom);
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private static List<IRI> terms(String... names) {
        IRI[] iris = new IRI[names.length];
        for (int i = 0; i < names.length; i++) {
            iris[i] = IRI.create(NS + names[i]);
        }
        return Arrays.asList(iris);
    }

    /**
     * Whether the module says anything at all about this IRI.
     *
     * <p>Not just the signature: an annotation assertion's subject is an IRI rather than an
     * entity, so a module holding only {@code rdfs:label} for a term has an empty class
     * signature - which is exactly what STAR produces on a plain hierarchy.
     */
    private static boolean mentions(OWLOntology ontology, IRI iri) {
        if (ontology.containsEntityInSignature(iri)) {
            return true;
        }
        for (OWLAxiom axiom : ontology.getAxioms()) {
            if (axiom instanceof org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom
                    && iri.equals(((org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom)
                            axiom).getSubject())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> classNamesIn(OWLOntology ontology) {
        Set<String> names = new HashSet<String>();
        for (OWLClass owlClass : ontology.getClassesInSignature()) {
            names.add(owlClass.getIRI().getFragment());
        }
        return names;
    }

    // ---------- nothing is taken away in silence ----------

    /**
     * The reason this class exists rather than a direct call to ROBOT. A term list is maintained
     * by hand against an ontology that keeps changing; when a class is obsoleted the extraction
     * quietly returns a module without it, and the module looks perfectly correct.
     */
    @Test
    void aTermTheSourceDoesNotHaveIsReportedRatherThanSkipped() {
        TermExtract.Result result = TermExtract.run(source,
                terms("Polymer", "Unobtainium"), TermExtract.Method.STAR, null);

        assertEquals(1, result.getMissing().size(), "the obsolete term should be named");
        assertEquals(IRI.create(NS + "Unobtainium"), result.getMissing().get(0));
        assertEquals(2, result.getRequested(), "the count should include what was not found");
    }

    @Test
    void aTermListNoneOfWhichIsInTheSourceIsARefusalRatherThanAnEmptyModule() {
        RobotException refused = assertThrows(RobotException.class,
                () -> TermExtract.run(source, terms("Nothing", "AlsoNothing"),
                        TermExtract.Method.STAR, null));

        assertTrue(refused.getMessage().contains("2"), refused.getMessage());
        assertTrue(refused.getMessage().toLowerCase().contains("import"),
                "not loading the imports is the usual cause and should be suggested: "
                        + refused.getMessage());
    }

    @Test
    void everythingFoundIsReportedAsNothingMissing() {
        TermExtract.Result result = TermExtract.run(source, terms("Polymer"),
                TermExtract.Method.STAR, null);

        assertTrue(result.getMissing().isEmpty(), result.getMissing().toString());
    }

    // ---------- the source is left alone ----------

    /** The source is often an ontology Protege has open, and extraction must be read-only. */
    @Test
    void extractingChangesNothingInTheSource() {
        Set<OWLAxiom> before = new HashSet<OWLAxiom>(source.getAxioms());

        for (TermExtract.Method method : TermExtract.Method.values()) {
            TermExtract.run(source, terms("Polymer"), method, null);
            assertEquals(before, new HashSet<OWLAxiom>(source.getAxioms()),
                    method + " modified the ontology it was reading from");
        }
    }

    @Test
    void theModuleIsItsOwnOntologyRatherThanTheSource() {
        TermExtract.Result result = TermExtract.run(source, terms("Polymer"),
                TermExtract.Method.STAR, null);

        assertNotEquals(source, result.getModule());
        assertTrue(result.getModule().getAxiomCount() < source.getAxiomCount(),
                "a module the size of the source is not a module");
    }

    // ---------- the methods differ, in the way they are described as differing ----------

    /**
     * Every module has to say something about the term that was asked for, even when it says very
     * little. STAR can legitimately come back with only the label - see below - so this asks for
     * a mention rather than a class in the signature.
     */
    @Test
    void everyMethodBringsBackSomethingAboutTheTermItWasAskedFor() {
        for (TermExtract.Method method : TermExtract.Method.values()) {
            TermExtract.Result result = TermExtract.run(source, terms("Polymer"), method, null);

            assertTrue(mentions(result.getModule(), IRI.create(NS + "Polymer")),
                    method + " came back with nothing about the term at all");
        }
    }

    /**
     * The names are backwards and this is the test that says so. BOT walks up and TOP walks down,
     * which is the opposite of what both names suggest. A plugin whose help said "BOT takes the
     * subclasses" would hand a user the wrong module and be believed, because the module it
     * produces is perfectly valid - just not the half they wanted.
     */
    @Test
    void bottomWalksUpTheHierarchyAndTopWalksDown() {
        Set<String> bottom = classNamesIn(
                TermExtract.run(source, terms("Polymer"), TermExtract.Method.BOT, null)
                        .getModule());
        Set<String> top = classNamesIn(
                TermExtract.run(source, terms("Polymer"), TermExtract.Method.TOP, null)
                        .getModule());

        assertTrue(bottom.contains("Material") && bottom.contains("Thing"),
                "BOT is documented as bringing the ancestors: " + bottom);
        assertFalse(bottom.contains("Polyethylene"),
                "BOT should not bring the subclasses: " + bottom);

        assertTrue(top.contains("Polyethylene") && top.contains("Polypropylene"),
                "TOP is documented as bringing the descendants: " + top);
        assertFalse(top.contains("Thing"),
                "TOP should not bring the ancestors: " + top);
    }

    /**
     * STAR's minimality is the other thing a user will not expect. On a plain hierarchy - which is
     * most of OBO - a class whose only axiom is "is a subclass of X" has no logical content that
     * must travel with it, so the module comes back with the labels and nothing else. It is
     * correct, it is startling, and the help says so; this pins that claim to the behaviour.
     */
    @Test
    void starOnAPlainHierarchyComesBackNearlyEmpty() {
        TermExtract.Result star = TermExtract.run(source, terms("Polymer"),
                TermExtract.Method.STAR, null);
        TermExtract.Result bottom = TermExtract.run(source, terms("Polymer"),
                TermExtract.Method.BOT, null);

        assertTrue(star.getAxiomCount() < bottom.getAxiomCount(),
                "STAR is documented as the smallest module: " + star.getAxiomCount() + " vs "
                        + bottom.getAxiomCount());
        assertTrue(classNamesIn(star.getModule()).isEmpty(),
                "on this ontology STAR has no subclass axioms to keep, so no class signature: "
                        + classNamesIn(star.getModule()));
        assertTrue(TermExtract.Method.STAR.getHelp().toLowerCase().contains("empty"),
                "a user choosing STAR must be warned it can come back nearly empty");
    }

    /** MIREOT is described as giving the chain of parents. */
    @Test
    void mireotBringsTheAncestors() {
        Set<String> names = classNamesIn(
                TermExtract.run(source, terms("Polyethylene"), TermExtract.Method.MIREOT, null)
                        .getModule());

        assertTrue(names.contains("Material"),
                "MIREOT should walk up to the ancestors: " + names);
        assertFalse(names.contains("Sintering"),
                "an unrelated branch has no business in the module: " + names);
    }

    /** No method should drag in a branch nothing asked for. */
    @Test
    void noMethodBringsBackAnUnrelatedBranch() {
        for (TermExtract.Method method : TermExtract.Method.values()) {
            Set<String> names = classNamesIn(
                    TermExtract.run(source, terms("Polymer"), method, null).getModule());

            assertFalse(names.contains("Sintering"),
                    method + " included an unrelated class: " + names);
        }
    }

    /** The default is the one an ODK import module uses, so the plugin agrees with the build. */
    @Test
    void theDefaultIsWhatAnOdkImportUses() {
        assertEquals(TermExtract.Method.BOT, TermExtract.DEFAULT_METHOD);
    }

    // ---------- the module gets an IRI of its own ----------

    /**
     * A module carrying the same IRI as the ontology it came from is a trap: load both and OWL API
     * has two different ontologies under one name and keeps whichever it saw first.
     */
    @Test
    void theModuleDoesNotClaimToBeTheOntologyItCameFrom() {
        TermExtract.Result result = TermExtract.run(source, terms("Polymer"),
                TermExtract.Method.STAR, null);

        assertNotEquals(source.getOntologyID().getOntologyIRI(),
                result.getModule().getOntologyID().getOntologyIRI());
    }

    /** ODK names these imports/&lt;name&gt;_import.owl, and a familiar name is worth having. */
    @Test
    void theDerivedIriFollowsTheOdkNamingConvention() {
        assertEquals(IRI.create("http://example.org/imports/source_import.owl"),
                TermExtract.moduleIriFor(source));
    }

    @Test
    void anOntologyWithNoIriStillGetsAUsableModuleIri() throws Exception {
        OWLOntology anonymous = OWLManager.createOWLOntologyManager().createOntology();

        IRI iri = TermExtract.moduleIriFor(anonymous);

        assertTrue(iri.toString().startsWith("http"), iri.toString());
        assertTrue(iri.toString().endsWith("_import.owl"), iri.toString());
    }

    /**
     * MireotOperation takes no output IRI, so the module came back carrying the SOURCE ontology's
     * identity. Saved and imported under that IRI it claims to be the whole of the ontology it was
     * cut from: the import IRI the catalog maps belongs to no ontology at all, so the import never
     * resolves, and anything loading both has two different ontologies under one name.
     */
    @Test
    void aMireotModuleDoesNotClaimToBeTheOntologyItWasCutFrom() {
        TermExtract.Result result = TermExtract.run(source, terms("Polymer"),
                TermExtract.Method.MIREOT, IRI.create("http://example.org/my-module.owl"));

        assertEquals(IRI.create("http://example.org/my-module.owl"),
                result.getModule().getOntologyID().getOntologyIRI().get());
        assertNotEquals(source.getOntologyID().getOntologyIRI(),
                result.getModule().getOntologyID().getOntologyIRI());
    }

    /** Every method, so the next one added cannot quietly skip it. */
    @Test
    void everyMethodGivesTheModuleTheIriItWasAskedFor() {
        for (TermExtract.Method method : TermExtract.Method.values()) {
            TermExtract.Result result = TermExtract.run(source, terms("Polymer"), method,
                    IRI.create("http://example.org/asked-for.owl"));

            assertEquals(IRI.create("http://example.org/asked-for.owl"),
                    result.getModule().getOntologyID().getOntologyIRI().get(),
                    method + " ignored the module IRI");
        }
    }

    @Test
    void anExplicitIriIsUsedAsGiven() {
        TermExtract.Result result = TermExtract.run(source, terms("Polymer"),
                TermExtract.Method.STAR, IRI.create("http://example.org/my-module.owl"));

        assertEquals(IRI.create("http://example.org/my-module.owl"),
                result.getModule().getOntologyID().getOntologyIRI().get());
    }

    // ---------- a module nobody else can resolve ----------

    /**
     * Found by running the whole import against a real project. Its ontology IRI was a file: URL,
     * so the module IRI was one too, and the import statement and catalog entry both went to disk
     * naming a path on one machine. Nothing failed. It just did not work for anybody else.
     */
    @Test
    void aModuleIriUnderFileIsWarnedAboutBecauseItResolvesOnOneMachineOnly() {
        String warning = TermExtract.warningFor(
                IRI.create("file:/C:/Users/someone/project/imports/iao_import.owl"));

        assertTrue(warning != null && warning.contains("this machine"), String.valueOf(warning));
        assertTrue(warning.contains("catalog"),
                "the catalog is where it does the damage, and should be named: " + warning);
    }

    @Test
    void anHttpModuleIriIsNotWarnedAbout() {
        assertNull(TermExtract.warningFor(
                IRI.create("http://purl.obolibrary.org/obo/mwo/imports/iao_import.owl")));
        assertNull(TermExtract.warningFor(
                IRI.create("https://w3id.org/mwo/imports/iao_import.owl")));
        assertNull(TermExtract.warningFor(null));
    }

    /** A real project with a file: IRI produces a file: module IRI - that is the path checked. */
    @Test
    void anOntologyWithAFileIriProducesAModuleIriThatIsWarnedAbout() throws Exception {
        OWLOntology local = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("file:/C:/Users/someone/project/src/ontology/o"));

        IRI moduleIri = TermExtract.moduleIriIn(
                local.getOntologyID().getOntologyIRI().get(), source);

        assertTrue(moduleIri.toString().startsWith("file:"), moduleIri.toString());
        assertNotNull(TermExtract.warningFor(moduleIri));
    }

    /** The module belongs to the importing project, not to the ontology it was taken from. */
    @Test
    void theModuleIriSitsUnderTheImportingProjectsNamespace() {
        IRI moduleIri = TermExtract.moduleIriIn(
                IRI.create("http://purl.obolibrary.org/obo/mwo.owl"), source);

        // Under the project, not beside it. This asserted .../obo/imports/source_import.owl -
        // OBO's shared root, which belongs to no project and which every ODK project extracting
        // from the same source would mint identically. The test's own name said the opposite.
        assertEquals(IRI.create("http://purl.obolibrary.org/obo/mwo/imports/source_import.owl"),
                moduleIri);
    }

    // ---------- refusals a caller can act on ----------

    @Test
    void noTermsIsRefusedRatherThanProducingAnEmptyModule() {
        assertThrows(IllegalArgumentException.class,
                () -> TermExtract.run(source, Collections.<IRI>emptyList(),
                        TermExtract.Method.STAR, null));
        assertThrows(IllegalArgumentException.class,
                () -> TermExtract.run(source, null, TermExtract.Method.STAR, null));
    }

    @Test
    void noSourceIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> TermExtract.run(null, terms("Polymer"), TermExtract.Method.STAR, null));
    }

    // ---------- the choice is explained ----------

    @Test
    void everyMethodExplainsWhenToChooseIt() {
        Set<String> seen = new HashSet<String>();
        for (TermExtract.Method method : TermExtract.Method.values()) {
            assertTrue(method.getHelp().length() > 120,
                    method + " needs enough explanation to choose by: " + method.getHelp());
            assertTrue(seen.add(method.getHelp()), method + " reuses another method's description");
            assertFalse(method.getHelp().toLowerCase().startsWith(method.getLabel().toLowerCase()),
                    method + " restates its own name instead of explaining");
        }
    }

    /** ROBOT's spelling, so a result can be set beside a Makefile's --method. */
    @Test
    void theLabelsAreRobotsOwn() {
        assertEquals("STAR", TermExtract.Method.STAR.getLabel());
        assertEquals("BOT", TermExtract.Method.BOT.getLabel());
        assertEquals("TOP", TermExtract.Method.TOP.getLabel());
        assertEquals("MIREOT", TermExtract.Method.MIREOT.getLabel());
    }
}
