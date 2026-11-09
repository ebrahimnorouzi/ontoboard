package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A class from elsewhere under a class in a frame stands in that frame, and so does what is under it.
 *
 * <p>Found by a tester: a class found and added with Enter landed at the bottom of the board, 1,880
 * pixels from its superclass in a pattern's frame.
 */
class ImportedClassesUnderTest {

    private static final String M = "http://example.org/module#";
    private static final String O = "http://example.org/mine#";

    @Test
    void aChainFromElsewhereFollowsItsTopIntoTheFrameAndOwnClassesFollowIt() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology module = manager.createOntology(IRI.create("http://example.org/module"));
        OWLClass entity = df.getOWLClass(IRI.create(M + "entity"));
        OWLClass content = df.getOWLClass(IRI.create(M + "content"));
        OWLClass document = df.getOWLClass(IRI.create(M + "document"));
        OWLClass report = df.getOWLClass(IRI.create(M + "report"));
        OWLClass elsewhere = df.getOWLClass(IRI.create(M + "elsewhere"));
        manager.addAxiom(module, df.getOWLSubClassOfAxiom(content, entity));
        manager.addAxiom(module, df.getOWLSubClassOfAxiom(document, content));
        manager.addAxiom(module, df.getOWLSubClassOfAxiom(report, document));
        manager.addAxiom(module, df.getOWLDeclarationAxiom(elsewhere));
        OWLOntology mine = manager.createOntology(IRI.create("http://example.org/mine"));
        manager.applyChange(new AddImport(mine,
                df.getOWLImportsDeclaration(IRI.create("http://example.org/module"))));
        OWLClass mineUnderReport = df.getOWLClass(IRI.create(O + "testReport"));
        manager.addAxiom(mine, df.getOWLDeclarationAxiom(mineUnderReport));
        manager.addAxiom(mine, df.getOWLSubClassOfAxiom(mineUnderReport, report));

        Set<String> framed = Collections.singleton(M + "content");
        Set<String> onBoard = new HashSet<String>(Arrays.asList(M + "entity", M + "content",
                M + "document", M + "report", M + "elsewhere", O + "testReport"));

        Map<String, String> brought = OntologyProjection.importedClassesUnder(mine, framed,
                onBoard, Collections.<String>emptySet());

        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put(M + "document", M + "content");
        expected.put(M + "report", M + "document");
        assertEquals(expected, brought, "the chain under content, top first; not its superclass,"
                + " not a class under nothing framed, not an own class");

        Set<String> nowFramed = new HashSet<String>(framed);
        nowFramed.addAll(brought.keySet());
        assertEquals(Collections.singletonMap(O + "testReport", M + "report"),
                OntologyProjection.ownClassesUnder(mine, nowFramed),
                "a class made under the imported report follows it");
    }

    @Test
    void whatStandsInAFrameSomebodyDrewStaysThere() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology module = manager.createOntology(IRI.create("http://example.org/module"));
        manager.addAxiom(module, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(M + "document")),
                df.getOWLClass(IRI.create(M + "content"))));
        OWLOntology mine = manager.createOntology(IRI.create("http://example.org/mine"));
        manager.applyChange(new AddImport(mine,
                df.getOWLImportsDeclaration(IRI.create("http://example.org/module"))));

        assertFalse(OntologyProjection.importedClassesUnder(mine,
                Collections.singleton(M + "content"),
                new HashSet<String>(Arrays.asList(M + "content", M + "document")),
                Collections.singleton(M + "document")).containsKey(M + "document"));
    }
}
