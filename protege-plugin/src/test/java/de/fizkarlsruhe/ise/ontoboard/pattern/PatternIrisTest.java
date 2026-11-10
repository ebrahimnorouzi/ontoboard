package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * No bundled pattern is named after a path on somebody's disk (1.135.1).
 *
 * <p>Airline and Airline (OWL) had the harvesting machine's path for their ontology IRI and for
 * their one property - {@code file:/C:/Users/eno/.../patterns/airline/pattern.owl#hasAircraft} -
 * and imported {@code file:/schemas/cpannotationschema.owl}. A participant who imported one got
 * that path into their ontology and releases, both patterns imported as the same module,
 * "pattern", and the library listed them under "unknown". The index hides a file: IRI by design,
 * so nothing failed; this looks at what the jar ships.
 */
class PatternIrisTest {

    private static File patternsDirectory() {
        return new File(new File(System.getProperty("user.dir")).getAbsoluteFile()
                .getParentFile(), "patterns");
    }

    private static boolean onADisk(IRI iri) {
        String text = iri.toString();
        return text.startsWith("file:") || text.startsWith("jar:");
    }

    @Test
    void noBundledPatternIsNamedAfterAPathOnADisk() throws Exception {
        List<String> found = new ArrayList<String>();
        for (DesignPattern pattern : PatternLibrary.all()) {
            OWLOntology ontology = PatternLibrary.loadAs(pattern, PatternLibrary.baseFor(pattern));
            if (ontology.getOntologyID().getOntologyIRI().isPresent()
                    && onADisk(ontology.getOntologyID().getOntologyIRI().get())) {
                found.add(pattern.getId() + " is named " + ontology.getOntologyID().getOntologyIRI().get());
            }
            for (OWLEntity entity : ontology.getSignature(Imports.EXCLUDED)) {
                if (onADisk(entity.getIRI())) {
                    found.add(pattern.getId() + " has " + entity.getIRI());
                }
            }
            for (OWLImportsDeclaration imported : ontology.getImportsDeclarations()) {
                if (onADisk(imported.getIRI())) {
                    found.add(pattern.getId() + " imports " + imported.getIRI());
                }
            }
        }
        assertEquals("[]", found.toString());
    }

    @Test
    void noPatternFileHoldsAFilePath() throws Exception {
        File patterns = patternsDirectory();
        assertTrue(patterns.isDirectory(), "no patterns directory at " + patterns);
        List<String> found = new ArrayList<String>();
        File[] folders = patterns.listFiles(File::isDirectory);
        for (File folder : folders == null ? new File[0] : folders) {
            for (String name : new String[] {"pattern.owl", "pattern.ttl", "example.ttl",
                "metadata.json"}) {
                File file = new File(folder, name);
                if (file.isFile() && new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8).contains("file:/")) {
                    found.add(folder.getName() + "/" + name);
                }
            }
        }
        assertEquals("[]", found.toString());
    }
}
