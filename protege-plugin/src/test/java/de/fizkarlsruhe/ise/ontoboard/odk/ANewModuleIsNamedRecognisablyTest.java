package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A new import module is named after something a reader recognises, and an existing one keeps its
 * name (1.135.2).
 *
 * <p>Found building an application ontology on PMDco: terms found in it went into
 * {@code imports/co_import.owl}, and Find terms offered to "Add 1 term from co" - the last part of
 * PMDco's IRI, https://w3id.org/pmd/co/, which says nothing on its own.
 */
class ANewModuleIsNamedRecognisablyTest {

    private static final String PMDCO = "https://w3id.org/pmd/co/";

    @TempDir
    File root;

    private static OWLOntology ontology(String iri) throws Exception {
        return OWLManager.createOWLOntologyManager().createOntology(IRI.create(iri));
    }

    @Test
    void pmdcoIsCalledPmdcoNotCo() throws Exception {
        assertEquals("pmdco", TermExtract.recognisableNameOf(ontology(PMDCO)));
    }

    @Test
    void anOntologyWhoseNameSaysEnoughKeepsIt() throws Exception {
        assertEquals("tto", TermExtract.recognisableNameOf(ontology("https://w3id.org/pmd/tto/")));
        assertEquals("ro", TermExtract.recognisableNameOf(
                ontology("http://purl.obolibrary.org/obo/ro.owl")), "an OBO id is meant short");
        assertEquals("iao", TermExtract.recognisableNameOf(
                ontology("http://purl.obolibrary.org/obo/iao.owl")));
    }

    /** SKOS is .../skos/core: the prefix it asks for says what it is. */
    @Test
    void thePrefixAnOntologyAsksForNamesIt() throws Exception {
        OWLOntology skos = ontology("http://www.w3.org/2004/02/skos/core");
        OWLOntologyManager manager = skos.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(skos,
                df.getOWLAnnotation(df.getOWLAnnotationProperty(IRI.create(
                        "http://purl.org/vocab/vann/preferredNamespacePrefix")),
                        df.getOWLLiteral("skos"))));

        assertEquals("skos", TermExtract.recognisableNameOf(skos));
    }

    @Test
    void aNewModuleGetsTheRecognisableName() {
        assertEquals("pmdco", ImportModules.nameFor(root, "co", "pmdco",
                Collections.singletonList(PMDCO)));
    }

    /** A project that has a co module for PMDco keeps it. */
    @Test
    void aModuleTheProjectHasKeepsItsName() throws Exception {
        ImportModules.writeTerms(root, "co",
                Arrays.asList(IRI.create(PMDCO + "PMD_0000075")), PMDCO);

        assertEquals("co", ImportModules.nameFor(root, "co", "pmdco",
                Arrays.asList(PMDCO, "https://w3id.org/pmd/co/3.1.2")));
    }

    @Test
    void anOldListForAnotherOntologyIsNotThisOnesModule() throws Exception {
        ImportModules.writeTerms(root, "co",
                Arrays.asList(IRI.create("https://example.org/other/co/X")),
                "https://example.org/other/co/");

        assertEquals("pmdco", ImportModules.nameFor(root, "co", "pmdco",
                Collections.singletonList(PMDCO)));
    }

    /** A list written before lists said where they came from is taken as this source's. */
    @Test
    void anOldListThatSaysNothingOfItsSourceKeepsItsName() throws Exception {
        File list = ImportModules.termsFileFor(root, "co");
        list.getParentFile().mkdirs();
        Files.write(list.toPath(), (PMDCO + "PMD_0000075\n").getBytes(StandardCharsets.UTF_8));

        assertEquals("co", ImportModules.nameFor(root, "co", "pmdco",
                Collections.singletonList(PMDCO)));
    }

    private static final String TIME = "http://www.w3.org/2006/time";

    /**
     * A name another source's module has is not given to this one's (1.135.2 review): its terms
     * would be spliced into that list, and the module cut again from this source.
     */
    @Test
    void aNameAnotherSourcesModuleHasIsNotGivenToThisOne() throws Exception {
        ImportModules.writeTerms(root, "time",
                Arrays.asList(IRI.create("https://example.org/clock/Tick")),
                "https://example.org/clock/");

        assertEquals("time-2", ImportModules.nameFor(root, "time", "time",
                Collections.singletonList(TIME)));
    }

    /**
     * Nor a name a pattern's module has: a pattern cut from a file called time.owl and OWL-Time
     * are both "time" (1.135.2 review). The next name nothing has is taken.
     */
    @Test
    void aNameAPatternsModuleHasIsNotGivenToAnImport() throws Exception {
        ImportModules.writeTerms(root, "time",
                Arrays.asList(IRI.create("https://example.org/patterns/time/Interval")),
                "https://example.org/patterns/time.owl", "time-interval");
        File taken = ImportModules.moduleFileFor(root, "time-2");
        Files.write(taken.toPath(), new byte[0]);

        assertEquals("time-3", ImportModules.nameFor(root, "time", "time",
                Collections.singletonList(TIME)));
    }

    /**
     * And a pattern's module is not given a name another import has, while a pattern imported
     * again is rebuilt where it is (1.135.2 review).
     */
    @Test
    void aPatternsModuleGetsANameOfItsOwn() throws Exception {
        ImportModules.writeTerms(root, "time",
                Arrays.asList(IRI.create(TIME + "#Instant")), TIME);

        assertEquals("time-2", ImportModules.nameForPattern(root, "time", "time-interval"));

        ImportModules.writeTerms(root, "time-2",
                Arrays.asList(IRI.create("https://example.org/patterns/time/Interval")),
                "https://example.org/patterns/time.owl", "time-interval");
        assertEquals("time-2", ImportModules.nameForPattern(root, "time", "time-interval"),
                "imported again, it is rebuilt where it is");
        assertEquals("time-3", ImportModules.nameForPattern(root, "time", "another-pattern"));
    }

    /**
     * A pattern imported before term lists named their pattern - until 1.133.0 a list recorded
     * the pattern's copy beside the project as its source - is rebuilt where it is too.
     */
    @Test
    void aPatternImportedBeforeListsNamedThePatternIsRebuiltWhereItIs() throws Exception {
        String copy = new File(root, "src/ontology/mirror/time-interval.owl").toURI().toString();
        ImportModules.writeTerms(root, "time",
                Arrays.asList(IRI.create("https://example.org/patterns/time/Interval")), copy);

        assertEquals("time", ImportModules.nameForPattern(root, "time", "time-interval", copy));
        assertEquals("time-2", ImportModules.nameForPattern(root, "time", "time-interval",
                new File(root, "src/ontology/mirror/another.owl").toURI().toString()));
    }

    /**
     * The same ontology read from another address - a local copy first and its address later, or
     * http and then https - keeps the module it has (1.135.2 review): the terms its list holds are
     * this source's, whatever address the list recorded.
     */
    @Test
    void theSameOntologyFromAnotherAddressKeepsItsModule() throws Exception {
        ImportModules.writeTerms(root, "time",
                Arrays.asList(IRI.create(TIME + "#Instant"), IRI.create(TIME + "#Interval")),
                "file:/C:/data/time.owl");

        assertEquals("time", ImportModules.nameFor(root, "time", "time",
                Collections.singletonList(TIME), iri -> iri.toString().startsWith(TIME)));
    }

    /**
     * An ODK project's list records no source; one of another vocabulary under the name this source
     * would take is not this source's (1.135.2 review): SKOS is .../skos/core, short name "core".
     */
    @Test
    void anOdkListOfAnotherVocabularyIsNotThisOnes() throws Exception {
        File list = ImportModules.termsFileFor(root, "core");
        list.getParentFile().mkdirs();
        Files.write(list.toPath(),
                "https://example.org/vocab/core#Thing\n".getBytes(StandardCharsets.UTF_8));
        String skos = "http://www.w3.org/2004/02/skos/core";

        assertEquals("skos", ImportModules.nameFor(root, "core", "skos",
                Collections.singletonList(skos), iri -> iri.toString().startsWith(skos)));
    }

    /** A module with no term list beside it is somebody's: no other import is given its name. */
    @Test
    void aModuleWithoutAListIsNotGivenAway() throws Exception {
        File module = ImportModules.moduleFileFor(root, "time");
        module.getParentFile().mkdirs();
        Files.write(module.toPath(), new byte[0]);

        assertEquals("time-2", ImportModules.nameForPattern(root, "time", "time-interval"));
        assertEquals("time-2", ImportModules.nameFor(root, "owltime", "time",
                Collections.singletonList(TIME), iri -> false));
    }

    /** A pattern's list from before 1.133.0, in a clone at another path, is still its list. */
    @Test
    void aPatternsOldListInACloneElsewhereIsStillItsList() throws Exception {
        ImportModules.writeTerms(root, "time",
                Arrays.asList(IRI.create("https://example.org/patterns/time/Interval")),
                "file:/D:/elsewhere/project/src/ontology/mirror/time-interval.owl");

        assertEquals("time", ImportModules.nameForPattern(root, "time", "time-interval",
                new File(root, "src/ontology/mirror/time-interval.owl").toURI().toString()));
    }

    /**
     * With no IRI of the project's to put it under, the module is still named as its file is
     * (1.135.2 review): the IRI was made from the source's IRI alone - for PMDco's, which ends in
     * a slash, the whole IRI again.
     */
    @Test
    void withoutTheProjectsIriTheModuleIsStillCalledByItsName() throws Exception {
        assertEquals("https://w3id.org/pmd/co/imports/pmdco_import.owl",
                TermExtract.moduleIriIn(null, ontology(PMDCO), "pmdco").toString());
    }
}
