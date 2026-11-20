package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * What the reasoner a release uses says before anything is built (D1: an inconsistent ontology,
 * or a class that can have no members, is not released).
 *
 * <p>The ontology is the user's egg project in miniature (2026-11-19): a quality that is said to
 * be "measurement unit label of" a process, a pattern that makes that property the inverse of one
 * whose range is an information entity, and BFO's disjointness of the two. ELK, which ignores
 * inverse properties, finds nothing; HermiT finds the ontology inconsistent.
 */
class ReleaseVerdictTest {

    private static final String PREFIXES = "Prefix(obo:=<http://purl.obolibrary.org/obo/>)\n"
            + "Prefix(mwo:=<http://purls.helmholtz-metadaten.de/mwo/>)\n"
            + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n";

    static final String MODULE = PREFIXES
            + "Ontology(<http://purl.obolibrary.org/obo/egg/imports/mwo_import.owl>\n"
            + "Declaration(Class(obo:BFO_0000019))\nDeclaration(Class(obo:BFO_0000020))\n"
            + "Declaration(Class(obo:BFO_0000031))\nDeclaration(Class(obo:IAO_0000030))\n"
            + "Declaration(Class(obo:IAO_0000009))\nDeclaration(Class(obo:IAO_0000003))\n"
            + "Declaration(ObjectProperty(obo:IAO_0000039))\n"
            + "Declaration(ObjectProperty(mwo:MWO_0001118))\n"
            + "AnnotationAssertion(rdfs:label obo:IAO_0000039 \"has measurement unit label\"@en)\n"
            + "AnnotationAssertion(rdfs:label mwo:MWO_0001118 \"measurement unit label of\"@en)\n"
            + "AnnotationAssertion(rdfs:label obo:IAO_0000003 \"measurement unit label\"@en)\n"
            + "SubClassOf(obo:BFO_0000019 obo:BFO_0000020)\n"
            + "DisjointClasses(obo:BFO_0000020 obo:BFO_0000031)\n"
            + "SubClassOf(obo:IAO_0000030 obo:BFO_0000031)\n"
            + "SubClassOf(obo:IAO_0000009 obo:IAO_0000030)\n"
            + "SubClassOf(obo:IAO_0000003 obo:IAO_0000009)\n"
            + "InverseObjectProperties(obo:IAO_0000039 mwo:MWO_0001118)\n"
            + "ObjectPropertyRange(obo:IAO_0000039 obo:IAO_0000003)\n"
            + ")\n";

    private static String edit(boolean withTheIndividual) {
        return PREFIXES
                + "Ontology(<http://purl.obolibrary.org/obo/egg.owl>\n"
                + "Import(<http://purl.obolibrary.org/obo/egg/imports/mwo_import.owl>)\n"
                + "Declaration(Class(obo:EGG_0000000))\nDeclaration(Class(obo:EGG_0000001))\n"
                + "Declaration(NamedIndividual(obo:EGG_0000002))\n"
                + "AnnotationAssertion(rdfs:label obo:EGG_0000001 \"quality egg\"@en)\n"
                + "SubClassOf(obo:EGG_0000001 obo:BFO_0000019)\n"
                + "SubClassOf(obo:EGG_0000001 ObjectSomeValuesFrom(mwo:MWO_0001118 "
                + "obo:EGG_0000000))\n"
                + (withTheIndividual ? "ClassAssertion(obo:EGG_0000001 obo:EGG_0000002)\n" : "")
                + ")\n";
    }

    private static final String CATALOG = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\" prefer=\"public\">\n"
            + "  <uri name=\"http://purl.obolibrary.org/obo/egg/imports/mwo_import.owl\" "
            + "uri=\"imports/mwo_import.owl\"/>\n"
            + "</catalog>\n";

    /** The egg project's src/ontology, as an ODK project lays it out; returns the edit file. */
    static File eggProject(File top, boolean withTheIndividual, boolean withTheModule)
            throws Exception {
        File ontology = new File(top, "src/ontology");
        File imports = new File(ontology, "imports");
        assertTrue(imports.mkdirs());
        File edit = new File(ontology, "egg-edit.owl");
        write(edit, edit(withTheIndividual));
        write(new File(ontology, "catalog-v001.xml"), CATALOG);
        if (withTheModule) {
            write(new File(imports, "mwo_import.owl"), MODULE);
        }
        return edit;
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static final java.util.function.BooleanSupplier GOING_ON = () -> false;

    @Test
    void theEggIsInconsistentForHermiT(@TempDir File top) throws Exception {
        ReleaseVerdict verdict = ReleaseVerdict.check(eggProject(top, true, true), "HermiT",
                60_000, GOING_ON);

        assertEquals(ReleaseVerdict.Kind.INCONSISTENT, verdict.kind());
        assertTrue(verdict.stopsARelease());
        assertEquals("HermiT", verdict.reasoner());
        assertTrue(verdict.said().contains("inconsistent"), verdict.said());
        // The explanation names the axioms, with their labels, so the person can find the one
        // that is wrong.
        assertTrue(verdict.explanation().contains("measurement unit label of"),
                verdict.explanation());
        assertTrue(verdict.explanation().contains("InverseOf"), verdict.explanation());
    }

    @Test
    void theEggIsCleanForElk(@TempDir File top) throws Exception {
        // Why the form says what ELK misses: chosen, it lets this ontology through.
        ReleaseVerdict verdict = ReleaseVerdict.check(eggProject(top, true, true), "ELK",
                60_000, GOING_ON);

        assertEquals(ReleaseVerdict.Kind.CLEAN, verdict.kind());
        assertFalse(verdict.stopsARelease());
        assertEquals("", verdict.explanation());
        assertTrue(verdict.said().contains("ELK"), verdict.said());
    }

    @Test
    void anUnsatisfiableClassStops(@TempDir File top) throws Exception {
        // Without the individual the ontology is consistent, and quality egg still can have no
        // members - which ODK's own reason step refuses too.
        ReleaseVerdict verdict = ReleaseVerdict.check(eggProject(top, false, true), "HermiT",
                60_000, GOING_ON);

        assertEquals(ReleaseVerdict.Kind.UNSATISFIABLE, verdict.kind());
        assertTrue(verdict.stopsARelease());
        assertEquals(Collections.singletonList("http://purl.obolibrary.org/obo/EGG_0000001"),
                verdict.unsatisfiable());
        assertTrue(verdict.said().contains("quality egg"), verdict.said());
        assertTrue(verdict.explanation().contains("measurement unit label of"),
                verdict.explanation());
    }

    @Test
    void aMissingModuleIsNotLoaded(@TempDir File top) throws Exception {
        // A module the catalog names and that is not there: the reasoner was never asked, and
        // "consistent" would be a guess.
        ReleaseVerdict verdict = ReleaseVerdict.check(eggProject(top, true, false), "HermiT",
                60_000, GOING_ON);

        assertEquals(ReleaseVerdict.Kind.NOT_LOADED, verdict.kind());
        assertTrue(verdict.stopsARelease());
        assertTrue(verdict.said().contains(
                "http://purl.obolibrary.org/obo/egg/imports/mwo_import.owl"), verdict.said());
    }

    @Test
    void aCleanOntologyIsClean(@TempDir File top) throws Exception {
        File ontology = new File(top, "src/ontology");
        assertTrue(ontology.mkdirs());
        File edit = new File(ontology, "pizza-edit.owl");
        write(edit, PREFIXES + "Ontology(<http://purl.obolibrary.org/obo/pizza.owl>\n"
                + "Declaration(Class(obo:PIZZA_1))\nDeclaration(Class(obo:PIZZA_2))\n"
                + "SubClassOf(obo:PIZZA_2 obo:PIZZA_1)\n)\n");

        ReleaseVerdict verdict = ReleaseVerdict.check(edit, "HermiT", 60_000, GOING_ON);

        assertEquals(ReleaseVerdict.Kind.CLEAN, verdict.kind());
        assertTrue(verdict.said().contains("consistent"), verdict.said());
    }

    @Test
    void notFinishedIsNotAFinding(@TempDir File top) throws Exception {
        ReleaseVerdict verdict = ReleaseVerdict.check(eggProject(top, true, true), "HermiT",
                slow(), 300, GOING_ON);

        assertEquals(ReleaseVerdict.Kind.TIMED_OUT, verdict.kind());
        assertFalse(verdict.stopsARelease(), "a reasoner that did not finish found nothing");
        assertTrue(verdict.said().contains("did not finish"), verdict.said());
    }

    @Test
    void stoppedStops(@TempDir File top) throws Exception {
        ReleaseVerdict verdict = ReleaseVerdict.check(eggProject(top, true, true), "HermiT",
                slow(), 60_000, () -> true);

        assertEquals(ReleaseVerdict.Kind.STOPPED, verdict.kind());
        assertTrue(verdict.stopsARelease());
    }

    @Test
    void anUnknownReasonerFails(@TempDir File top) throws Exception {
        ReleaseVerdict verdict = ReleaseVerdict.check(eggProject(top, true, true), "Pellet",
                60_000, GOING_ON);

        assertEquals(ReleaseVerdict.Kind.FAILED, verdict.kind());
        assertTrue(verdict.stopsARelease());
        assertTrue(verdict.said().contains("Pellet"), verdict.said());
    }

    /** A reasoner that takes ten seconds over isConsistent, and stops when interrupted. */
    private static OWLReasonerFactory slow() {
        final OWLReasonerFactory hermit = ReleaseReasoners.factory("HermiT");
        InvocationHandler factory = (proxy, method, args) -> {
            if ("createReasoner".equals(method.getName())
                    || "createNonBufferingReasoner".equals(method.getName())) {
                final OWLReasoner real = (OWLReasoner) method.invoke(hermit, args);
                InvocationHandler reasoner = (inner, call, callArgs) -> {
                    if ("isConsistent".equals(call.getName())) {
                        Thread.sleep(10_000);
                    }
                    return call.invoke(real, callArgs);
                };
                return Proxy.newProxyInstance(OWLReasoner.class.getClassLoader(),
                        new Class<?>[] {OWLReasoner.class}, reasoner);
            }
            return method.invoke(hermit, args);
        };
        return (OWLReasonerFactory) Proxy.newProxyInstance(
                OWLReasonerFactory.class.getClassLoader(),
                new Class<?>[] {OWLReasonerFactory.class}, factory);
    }

    /**
     * An ontology open with an import that did not load is not called consistent with its
     * imports: a release of an ontology in no project, and Check this ontology..., asked the
     * reasoner about what had loaded and recorded "with its imports, consistent" (the final
     * review of 1.155.0).
     */
    @Test
    void anOpenOntologyWhoseImportDidNotLoadIsNotChecked() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology open = manager.createOntology(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/open"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(open,
                manager.getOWLDataFactory().getOWLImportsDeclaration(
                        org.semanticweb.owlapi.model.IRI.create("http://example.org/missing.owl"))));

        ReleaseVerdict verdict = ReleaseVerdict.check(open, "HermiT", 60_000, GOING_ON);

        assertEquals(ReleaseVerdict.Kind.NOT_LOADED, verdict.kind(), verdict.said());
        assertTrue(verdict.said().contains("http://example.org/missing.owl"), verdict.said());
        assertFalse(verdict.said().contains("finds the ontology"), verdict.said());
        assertTrue(verdict.stopsARelease());
    }

    /**
     * Check this ontology... on a file whose import did not load asks the reasoner about what
     * loaded, and says so: the import named, never "with its imports" (the final review of
     * 1.155.0).
     */
    @Test
    void whatLoadedIsCheckedAndTheImportThatDidNotIsNamed() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology open = manager.createOntology(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/open"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(open,
                manager.getOWLDataFactory().getOWLImportsDeclaration(
                        org.semanticweb.owlapi.model.IRI.create("http://example.org/missing.owl"))));

        ReleaseVerdict verdict = ReleaseVerdict.checkWhatLoaded(open, "HermiT", 60_000,
                GOING_ON);

        assertEquals(ReleaseVerdict.Kind.CLEAN, verdict.kind(), verdict.said());
        assertTrue(verdict.said().contains("http://example.org/missing.owl"), verdict.said());
        assertTrue(verdict.said().contains("did not load"), verdict.said());
        assertFalse(verdict.said().contains("with its imports"), verdict.said());
    }
}
