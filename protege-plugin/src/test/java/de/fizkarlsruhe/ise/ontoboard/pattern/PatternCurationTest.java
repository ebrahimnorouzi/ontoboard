package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What PatternCuration (1.155.0, D2) changes in a pattern, and what it leaves alone: every rule
 * on a small pattern of its own.
 */
class PatternCurationTest {

    private static final String EX = "http://example.org/p#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String LICENSED = "https://licensed.example/";
    private static final String CC_BY = "https://creativecommons.org/licenses/by/4.0/";

    private File root;

    @BeforeEach
    void aFolderOfItsOwn(TestInfo test) {
        // Not @TempDir and never deleted here: under target/, which mvn clean owns.
        root = new File("target/pattern-curation-test/" + test.getTestMethod().get().getName()
                + "-" + System.nanoTime());
        assertTrue(root.mkdirs(), "could not make " + root);
    }

    // --- rule 3: a term without a label -------------------------------------------------------

    @Test
    void aLabelFromAnotherPattern() throws Exception {
        File dir = pattern("p", ":A a owl:Class ; rdfs:subClassOf :B . :B a owl:Class ; rdfs:label \"b\"@en .");
        Map<String, PatternCuration.Given> library = new HashMap<String, PatternCuration.Given>();
        library.put(EX + "A", new PatternCuration.Given("a from the library", "en", "pattern q"));

        PatternCuration.Changes changes = curation(library, none(), noFixes()).curate(dir);

        assertEquals(Arrays.asList("a from the library@en"), labelsOf(read(dir, "pattern.owl"), EX + "A"));
        assertTrue(changes.lines().contains("label added: <" + EX + "A> \"a from the library\"@en"
                + " - from the library's pattern q"), changes.lines().toString());
    }

    @Test
    void aLabelFromKnownTerms() throws Exception {
        File dir = pattern("p", ":A a owl:Class ; rdfs:label \"a\"@en ;"
                + " <http://purl.obolibrary.org/obo/IAO_0000115> \"what A is\" .");
        Map<String, PatternCuration.Given> known = new HashMap<String, PatternCuration.Given>();
        known.put("http://purl.obolibrary.org/obo/IAO_0000115",
                new PatternCuration.Given("definition", "en", "http://purl.obolibrary.org/obo/iao.owl"));

        PatternCuration.Changes changes = curation(none(), known, noFixes()).curate(dir);

        assertEquals(Arrays.asList("definition@en"), labelsOf(read(dir, "pattern.owl"),
                "http://purl.obolibrary.org/obo/IAO_0000115"));
        assertTrue(changes.lines().contains("label added: <http://purl.obolibrary.org/obo/IAO_0000115>"
                + " \"definition\"@en - from http://purl.obolibrary.org/obo/iao.owl"),
                changes.lines().toString());
    }

    @Test
    void aLabelFromTheLocalName() throws Exception {
        File dir = pattern("p", ":hasPart a owl:ObjectProperty . :ContextualizedRelation a owl:Class .");

        PatternCuration.Changes changes = curation(none(), none(), noFixes()).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("has part@en"), labelsOf(curated, EX + "hasPart"));
        assertEquals(Arrays.asList("contextualized relation@en"),
                labelsOf(curated, EX + "ContextualizedRelation"));
        assertTrue(changes.lines().contains("label added: <" + EX + "hasPart> \"has part\"@en"
                + " - from its name"), changes.lines().toString());
    }

    // --- rule 2: more than one rdfs:label (the Task 3 ruling) ----------------------------------

    @Test
    void otherLanguagesBecomePreferredLabels() throws Exception {
        File dir = pattern("p", ":Agent a owl:Class ; rdfs:label \"Agent\"@en, \"Agente\"@it ."
                + " :Person a owl:Class ; rdfs:label \"Person\"@en, \"Persona {it}\" .");
        Map<String, PatternCuration.Given> known = new HashMap<String, PatternCuration.Given>();
        known.put(SKOS + "prefLabel", new PatternCuration.Given("preferred label", "en", SKOS));

        PatternCuration.Changes changes = curation(none(), known, noFixes()).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("Agent@en"), labelsOf(curated, EX + "Agent"));
        assertEquals(Arrays.asList("Agente@it"), annotationsOf(curated, EX + "Agent", SKOS + "prefLabel"));
        assertEquals(Arrays.asList("Person@en"), labelsOf(curated, EX + "Person"));
        assertEquals(Arrays.asList("Persona@it"), annotationsOf(curated, EX + "Person", SKOS + "prefLabel"));
        assertTrue(changes.lines().contains("label moved to skos:prefLabel: <" + EX + "Agent> \"Agente\"@it"),
                changes.lines().toString());
        assertEquals(Arrays.asList("preferred label@en"), labelsOf(curated, SKOS + "prefLabel"),
                "a pattern that uses skos:prefLabel labels it too");
    }

    @Test
    void aSecondEnglishLabelBecomesAnAlternative() throws Exception {
        File dir = pattern("p", ":Licence a owl:Class ; rdfs:label \"licence\"@en, \"software license\" ."
                + " :realizedIn a owl:ObjectProperty ; rdfs:label \"realized in\"@en, \"has realization\"@en ."
                + " :isHabitatFor a owl:ObjectProperty ; rdfs:label \"is habitat for\"@en, \"is habitat for\" .");
        Map<String, PatternCuration.Given> known = new HashMap<String, PatternCuration.Given>();
        known.put(EX + "realizedIn", new PatternCuration.Given("has realization", "en", "its home"));

        curation(none(), known, noFixes()).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("licence@en"), labelsOf(curated, EX + "Licence"));
        assertEquals(Arrays.asList("software license"), annotationsOf(curated, EX + "Licence", SKOS + "altLabel"));
        assertEquals(Arrays.asList("has realization@en"), labelsOf(curated, EX + "realizedIn"),
                "the label the term's home ontology gives it stays");
        assertEquals(Arrays.asList("realized in@en"), annotationsOf(curated, EX + "realizedIn", SKOS + "altLabel"));
        assertEquals(Arrays.asList("is habitat for@en"), labelsOf(curated, EX + "isHabitatFor"));
        assertTrue(annotationsOf(curated, EX + "isHabitatFor", SKOS + "altLabel").isEmpty(),
                "a duplicate is dropped, not kept as an alternative");
    }

    // --- rule 4: the header ---------------------------------------------------------------------

    @Test
    void theHeaderIsWritten() throws Exception {
        File dir = pattern("p", ":A a owl:Class ; rdfs:label \"a\"@en .");
        Files.write(new File(dir, "metadata.json").toPath(), ("{\n  \"id\": \"p\",\n  \"name\": \"Test Pattern\",\n"
                + "  \"description\": \"What it is for.\",\n  \"source\": \"" + LICENSED + "p\"\n}\n")
                .getBytes(StandardCharsets.UTF_8));
        File unlicensed = pattern("q", ":B a owl:Class ; rdfs:label \"b\"@en .");

        PatternCuration curation = curation(none(), none(), licensed(), noFixes());
        PatternCuration.Changes changes = curation.curate(dir);
        curation.curate(unlicensed);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("Test Pattern"), headerValues(curated, DCTERMS + "title"));
        assertEquals(Arrays.asList("What it is for."), headerValues(curated, DCTERMS + "description"));
        assertEquals(Arrays.asList(CC_BY), headerValues(curated, DCTERMS + "license"));
        assertTrue(changes.lines().contains("header: dcterms:license <" + CC_BY + "> - " + LICENSED
                + "evidence"), changes.lines().toString());
        assertTrue(headerValues(read(unlicensed, "pattern.owl"), DCTERMS + "license").isEmpty(),
                "a source whose licence is not known gets none");
    }

    // --- rules 1 and 5: what a person listed ----------------------------------------------------

    @Test
    void aListedFixIsApplied() throws Exception {
        File dir = pattern("p", ":M a owl:Class ; rdfs:label \"has Aircraft\" ."
                + " :A a owl:Class ; rdfs:label \"a\"@en . :B a owl:Class ; rdfs:label \"b\"@en ."
                + " :p a owl:ObjectProperty ; rdfs:label \"p\"@en ; rdfs:domain :A, :B .");
        List<PatternCuration.Fix> fixes = Arrays.asList(
                new PatternCuration.Fix("p", EX + "M", "rdfs:label", "has Aircraft",
                        "mean of transportation", "the label of another term"),
                new PatternCuration.Fix("p", EX + "p", "axiom", "ObjectPropertyDomain(<" + EX + "p> <" + EX + "A>)",
                        "ObjectPropertyDomain(<" + EX + "p> ObjectUnionOf(<" + EX + "A> <" + EX + "B>))", "a union"),
                new PatternCuration.Fix("p", EX + "p", "axiom", "ObjectPropertyDomain(<" + EX + "p> <" + EX + "B>)",
                        "", "replaced by the union"));

        PatternCuration.Changes changes = curation(none(), none(), fixes).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("mean of transportation"), labelsOf(curated, EX + "M"));
        Set<String> domains = new TreeSet<String>();
        for (OWLAxiom axiom : curated.getAxioms(AxiomType.OBJECT_PROPERTY_DOMAIN)) {
            domains.add(axiom.getAxiomWithoutAnnotations().toString());
        }
        assertEquals(Collections.singleton("ObjectPropertyDomain(<" + EX + "p> ObjectUnionOf(<" + EX + "A> <"
                + EX + "B>))"), domains);
        assertTrue(changes.lines().contains("label corrected: <" + EX + "M> \"has Aircraft\" is now"
                + " \"mean of transportation\" - the label of another term"), changes.lines().toString());
        assertTrue(changes.lines().contains("axiom removed: ObjectPropertyDomain(<" + EX + "p> <" + EX
                + "B>) - replaced by the union"), changes.lines().toString());
    }

    @Test
    void aFixAlreadyMadeIsDone() throws Exception {
        File dir = pattern("p", ":M a owl:Class ; rdfs:label \"has Aircraft\" ."
                + " :A a owl:Class ; rdfs:label \"a\"@en . :B a owl:Class ; rdfs:label \"b\"@en ."
                + " :p a owl:ObjectProperty ; rdfs:label \"p\"@en ; rdfs:domain :A, :B .");
        List<PatternCuration.Fix> fixes = Arrays.asList(
                new PatternCuration.Fix("p", EX + "M", "rdfs:label", "has Aircraft", "mean of transportation", "why"),
                new PatternCuration.Fix("p", EX + "p", "axiom", "ObjectPropertyDomain(<" + EX + "p> <" + EX + "A>)",
                        "ObjectPropertyDomain(<" + EX + "p> ObjectUnionOf(<" + EX + "A> <" + EX + "B>))", "why"),
                new PatternCuration.Fix("p", EX + "p", "axiom", "ObjectPropertyDomain(<" + EX + "p> <" + EX + "B>)",
                        "", "why"));
        PatternCuration curation = curation(none(), none(), fixes);
        curation.curate(dir);

        PatternCuration.Changes again = curation.curate(dir);

        assertTrue(again.lines().isEmpty(), again.lines().toString());
    }

    @Test
    void aFixThatNoLongerMatchesIsRefused() throws Exception {
        File dir = pattern("p", ":M a owl:Class ; rdfs:label \"m\"@en .");
        List<PatternCuration.Fix> fixes = Arrays.asList(new PatternCuration.Fix("p", EX + "M", "rdfs:label",
                "has Aircraft", "mean of transportation", "why"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> curation(none(), none(), fixes).curate(dir));
        assertTrue(refused.getMessage().contains("has Aircraft"), refused.getMessage());
    }

    @Test
    void noLogicalAxiomChangesUnlessListed() throws Exception {
        File dir = pattern("p", ":A a owl:Class ; rdfs:subClassOf :B, [ a owl:Restriction ;"
                + " owl:onProperty :p ; owl:someValuesFrom :C ] ; rdfs:label \"A\"@en, \"A\"@it ."
                + " :B a owl:Class . :C a owl:Class ; owl:disjointWith :B ."
                + " :p a owl:ObjectProperty , owl:TransitiveProperty ; rdfs:range :C .");
        Set<OWLAxiom> before = logical(read(dir, "pattern.owl"));

        PatternCuration.Changes changes = curation(none(), none(), noFixes()).curate(dir);

        assertFalse(changes.lines().isEmpty());
        assertEquals(before, logical(read(dir, "pattern.owl")));
    }

    // --- rule 6 and the record ------------------------------------------------------------------

    @Test
    void theChangesAreRecorded() throws Exception {
        File dir = pattern("p", ":hasPart a owl:ObjectProperty .");

        PatternCuration.Changes changes = curation(none(), none(), noFixes()).curate(dir);

        JsonNode curated = new ObjectMapper().readTree(new File(dir, "metadata.json")).path("curated");
        List<String> recorded = new ArrayList<String>();
        for (JsonNode line : curated) {
            recorded.add(line.asText());
        }
        assertEquals(changes.lines(), recorded);
        OWLOntology owl = read(dir, "pattern.owl");
        boolean commented = false;
        for (OWLAnnotation annotation : owl.getAnnotations()) {
            commented |= annotation.getProperty().isComment()
                    && ((OWLLiteral) annotation.getValue()).getLiteral().startsWith("Curated by OntoBoard");
        }
        assertTrue(commented, owl.getAnnotations().toString());
        assertTrue(new String(Files.readAllBytes(new File(dir, "pattern.owl").toPath()), StandardCharsets.UTF_8)
                .startsWith("<?xml"), "pattern.owl is RDF/XML");
        assertEquals(logical(owl), logical(read(dir, "pattern.ttl")), "both files say the same");
        assertEquals(allAxioms(owl), allAxioms(read(dir, "pattern.ttl")), "annotations too");
    }

    @Test
    void aBlankNodeIsNeverSaidToBeOnlyInTheTtl() throws Exception {
        File dir = pattern("p", ":C a owl:Class ; rdfs:label \"c\"@en ."
                + " _:orphan owl:minQualifiedCardinality \"0\"^^<http://www.w3.org/2001/XMLSchema#nonNegativeInteger> .");
        PatternCuration curation = curation(none(), none(), noFixes());

        PatternCuration.Changes first = curation.curate(dir);
        PatternCuration.Changes again = curation.curate(dir);

        for (String line : first.lines()) {
            assertFalse(line.startsWith("file: only pattern.ttl said"), line);
        }
        assertTrue(again.lines().isEmpty(), again.lines().toString());
    }

    @Test
    void curatingTwiceChangesNothing() throws Exception {
        File dir = pattern("p", ":hasPart a owl:ObjectProperty ; rdfs:label \"has part\"@en, \"ha parte\"@it .");
        PatternCuration curation = curation(none(), none(), noFixes());
        curation.curate(dir);
        byte[] owl = Files.readAllBytes(new File(dir, "pattern.owl").toPath());
        byte[] metadata = Files.readAllBytes(new File(dir, "metadata.json").toPath());

        PatternCuration.Changes again = curation.curate(dir);

        assertTrue(again.lines().isEmpty(), again.lines().toString());
        assertArrayEquals(owl, Files.readAllBytes(new File(dir, "pattern.owl").toPath()));
        assertArrayEquals(metadata, Files.readAllBytes(new File(dir, "metadata.json").toPath()));
    }

    @Test
    void borrowedTermsAreDeclaredFromTheirImports() throws Exception {
        String imported = "http://example.org/imported";
        File fetched = new File(root, "fetched");
        assertTrue(fetched.mkdirs());
        Files.write(new File(fetched, PatternAudit.fileNameFor(imported)).toPath(), ("<" + imported
                + "> a <http://www.w3.org/2002/07/owl#Ontology> . <" + imported + "#isAbout> a"
                + " <http://www.w3.org/2002/07/owl#ObjectProperty> .\n").getBytes(StandardCharsets.UTF_8));
        File dir = new File(root, "p");
        assertTrue(dir.mkdirs());
        String ttl = prefixes() + "<http://example.org/p> a owl:Ontology ; owl:imports <" + imported + "> ."
                + " :V a owl:Class ; rdfs:label \"v\"@en ; rdfs:subClassOf [ a owl:Restriction ; owl:onProperty <"
                + imported + "#isAbout> ; owl:qualifiedCardinality \"1\"^^<http://www.w3.org/2001/XMLSchema#"
                + "nonNegativeInteger> ; owl:onClass :E ] . :E a owl:Class ; rdfs:label \"e\"@en .\n";
        Files.write(new File(dir, "pattern.owl").toPath(), ttl.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "pattern.ttl").toPath(), ttl.getBytes(StandardCharsets.UTF_8));
        metadata(dir, "p");

        PatternCuration.Changes changes = new PatternCuration(none(), none(), PatternCuration.Licences.none(),
                noFixes(), stubs(), iri -> imported.equals(iri) ? new File(fetched,
                        PatternAudit.fileNameFor(imported)) : null).curate(dir);

        OWLOntology alone = read(dir, "pattern.owl");               // imports silently missing
        boolean declared = false;
        for (OWLEntity entity : alone.getObjectPropertiesInSignature()) {
            declared |= entity.getIRI().toString().equals(imported + "#isAbout") && alone.isDeclared(entity);
        }
        assertTrue(declared, "isAbout is declared where it is used");
        assertTrue(alone.getAxioms(AxiomType.SUBCLASS_OF).toString().contains("ObjectExactCardinality(1"),
                alone.getAxioms(AxiomType.SUBCLASS_OF).toString());
        assertTrue(changes.lines().contains("term declared: <" + imported + "#isAbout> as an object property"),
                changes.lines().toString());
        assertTrue(changes.lines().contains("file: pattern.owl written as RDF/XML - it held Turtle"),
                changes.lines().toString());
    }

    // --- two terms, one label (ROBOT's duplicate_label) -----------------------------------------

    @Test
    void aDeclarationOnlyTheTtlHadIsTheTtls() throws Exception {
        File dir = new File(root, "p");
        assertTrue(dir.mkdirs());
        String head = prefixes() + "<http://example.org/p> a owl:Ontology .\n"
                + ":A a owl:Class ; rdfs:label \"a\"@en ; rdfs:subClassOf :B .\n";
        Files.write(new File(dir, "pattern.owl").toPath(), head.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "pattern.ttl").toPath(), (head + ":B a owl:Class ; rdfs:label \"b\"@en .\n")
                .getBytes(StandardCharsets.UTF_8));
        metadata(dir, "p");

        PatternCuration.Changes changes = curation(none(), none(), noFixes()).curate(dir);

        assertTrue(changes.lines().contains("file: pattern.owl declares <" + EX + "B> as a class, as pattern.ttl did"),
                changes.lines().toString());
        for (String line : changes.lines()) {
            assertFalse(line.startsWith("term declared: <" + EX + "B>"), line);
        }
    }

    @Test
    void twoTermsTheAuthorLabelledAlikeAreRefused() throws Exception {
        File dir = pattern("p", ":A a owl:Class ; rdfs:label \"x\"@en . :B a owl:Class ; rdfs:label \"x\"@en .");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> curation(none(), none(), noFixes()).curate(dir));
        assertTrue(refused.getMessage().contains(EX + "A") && refused.getMessage().contains(EX + "B"),
                refused.getMessage());
    }

    @Test
    void anOboTermKeepsItsLabelAndTheOtherGetsItsSource() throws Exception {
        File dir = pattern("p", ":A a owl:Class ; rdfs:label \"a\"@en ;"
                + " <http://purl.obolibrary.org/obo/IAO_0000115> \"x\" ; <" + SKOS + "definition> \"y\" .");

        PatternCuration.Changes changes = curation(none(), definitions(), noFixes()).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("definition@en"), labelsOf(curated, "http://purl.obolibrary.org/obo/IAO_0000115"));
        assertEquals(Arrays.asList("definition (SKOS)@en"), labelsOf(curated, SKOS + "definition"));
        assertTrue(changes.lines().contains("label added: <" + SKOS + "definition> \"definition (SKOS)\"@en"
                + " - from " + SKOS + ", with its source in brackets: another term of the pattern is"
                + " called \"definition\""), changes.lines().toString());
    }

    @Test
    void theAuthorsLabelStaysAndTheAddedOneGetsItsSource() throws Exception {
        File dir = pattern("p", ":Description a owl:Class ; rdfs:label \"Description\"@en ."
                + " :A a owl:Class ; rdfs:label \"a\"@en ; <" + DCTERMS + "description> \"about A\" .");
        Map<String, PatternCuration.Given> known = new HashMap<String, PatternCuration.Given>();
        known.put(DCTERMS + "description", new PatternCuration.Given("Description", "en", DCTERMS));

        curation(none(), known, noFixes()).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("Description@en"), labelsOf(curated, EX + "Description"));
        assertEquals(Arrays.asList("Description (DC terms)@en"), labelsOf(curated, DCTERMS + "description"));
    }

    @Test
    void aPropertyBesideItsClassSaysProperty() throws Exception {
        File dir = pattern("p", ":Context a owl:Class . :context a owl:ObjectProperty .");

        curation(none(), none(), noFixes()).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("context@en"), labelsOf(curated, EX + "Context"));
        assertEquals(Arrays.asList("context (property)@en"), labelsOf(curated, EX + "context"));
    }

    @Test
    void aPatternsOwnTermKeepsItsLabel() throws Exception {
        String schema = "http://www.ontologydesignpatterns.org/schemas/cpannotationschema.owl#hasComponent";
        File dir = pattern("p", ":hasComponent a owl:ObjectProperty . :Whole a owl:Class ; rdfs:label \"whole\"@en ;"
                + " rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :hasComponent ; owl:someValuesFrom :Whole ] ."
                + " <" + schema + "> rdfs:comment \"said, never declared\" .");

        curation(none(), none(), noFixes()).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("has component@en"), labelsOf(curated, EX + "hasComponent"));
        assertEquals(Arrays.asList("has component (cpannotationschema)@en"), labelsOf(curated, schema));
    }

    @Test
    void termsOfTwoSourcesGetTheirSources() throws Exception {
        File dir = pattern("p", "<http://a.example/hasPart> a owl:ObjectProperty ."
                + " <http://b.example/hasPart> a owl:ObjectProperty .");

        curation(none(), none(), noFixes()).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("has part (a.example)@en"), labelsOf(curated, "http://a.example/hasPart"));
        assertEquals(Arrays.asList("has part (b.example)@en"), labelsOf(curated, "http://b.example/hasPart"));
    }

    @Test
    void aLabelWithItsSourceIsTheSameInEveryPattern() throws Exception {
        File clash = pattern("p1", ":A a owl:Class ; rdfs:label \"a\"@en ;"
                + " <http://purl.obolibrary.org/obo/IAO_0000115> \"x\" ; <" + SKOS + "definition> \"y\" .");
        File alone = pattern("p2", ":B a owl:Class ; rdfs:label \"b\"@en ; <" + SKOS + "definition> \"y\" .");
        byte[] before = Files.readAllBytes(new File(clash, "pattern.owl").toPath());
        PatternCuration curation = curation(none(), definitions(), noFixes());

        Map<String, String> qualified = curation.qualifiedIn(Arrays.asList(clash, alone));

        assertEquals(Collections.singletonMap(SKOS + "definition", "definition (SKOS)"), qualified);
        assertArrayEquals(before, Files.readAllBytes(new File(clash, "pattern.owl").toPath()),
                "finding them writes nothing");
        curation.withQualified(qualified).curate(alone);
        assertEquals(Arrays.asList("definition (SKOS)@en"), labelsOf(read(alone, "pattern.owl"), SKOS + "definition"));
    }

    @Test
    void whatOdksReportFindsUnlabelledIsLabelled() throws Exception {
        File dir = pattern("p", ":C a owl:Class ; rdfs:label \"c\"@en . :p a owl:ObjectProperty ; rdfs:label \"p\"@en ."
                + " owl:Thing rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :p ; owl:someValuesFrom :C ] ."
                + " :hasMember rdfs:comment \"only said, never declared\" .");
        Map<String, PatternCuration.Given> known = new HashMap<String, PatternCuration.Given>();
        known.put("http://www.w3.org/2002/07/owl#Thing",
                new PatternCuration.Given("Thing", "", "http://www.w3.org/2002/07/owl"));

        curation(none(), known, noFixes()).curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertEquals(Arrays.asList("Thing"), labelsOf(curated, "http://www.w3.org/2002/07/owl#Thing"));
        assertEquals(Arrays.asList("has member@en"), labelsOf(curated, EX + "hasMember"));
    }

    @Test
    void whatOnlyTheImportsSayIsNotThePatterns() throws Exception {
        String imported = "http://example.org/imported";
        File fetched = new File(root, "fetched");
        assertTrue(fetched.mkdirs());
        Files.write(new File(fetched, PatternAudit.fileNameFor(imported)).toPath(), ("<" + imported
                + "> a <http://www.w3.org/2002/07/owl#Ontology> . <https://orcid.org/0000-0001>"
                + " <http://schema.org/name> \"Someone\" . <" + EX + "hasMember>"
                + " <http://www.w3.org/2000/01/rdf-schema#label> \"has member\"@en .\n").getBytes(StandardCharsets.UTF_8));
        File dir = new File(root, "p");
        assertTrue(dir.mkdirs());
        String ttl = prefixes() + "<http://example.org/p> a owl:Ontology ; owl:imports <" + imported + "> ."
                + " :C a owl:Class ; rdfs:label \"c\"@en . :hasMember rdfs:comment \"only commented here\" .\n";
        Files.write(new File(dir, "pattern.owl").toPath(), ttl.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "pattern.ttl").toPath(), ttl.getBytes(StandardCharsets.UTF_8));
        metadata(dir, "p");

        new PatternCuration(none(), none(), PatternCuration.Licences.none(), noFixes(), stubs(),
                iri -> imported.equals(iri) ? new File(fetched, PatternAudit.fileNameFor(imported)) : null)
                .curate(dir);

        OWLOntology curated = read(dir, "pattern.owl");
        assertTrue(labelsOf(curated, "https://orcid.org/0000-0001").isEmpty(),
                "what only an import describes is the import's to label");
        assertEquals(Arrays.asList("has member@en"), labelsOf(curated, EX + "hasMember"),
                "what the pattern itself describes is labelled in the pattern, imports or not");
    }

    @Test
    void anImportsTermsThePatternNeverUsesAreNotItsOwn() throws Exception {
        String schema = "http://example.org/schema";
        File fetched = new File(root, "fetched");
        assertTrue(fetched.mkdirs());
        Files.write(new File(fetched, PatternAudit.fileNameFor(schema)).toPath(), ("<" + schema
                + "> a <http://www.w3.org/2002/07/owl#Ontology> . <" + schema + "#hasIntent> a"
                + " <http://www.w3.org/2002/07/owl#AnnotationProperty> . <" + schema + "#extractedFrom> a"
                + " <http://www.w3.org/2002/07/owl#AnnotationProperty> .\n").getBytes(StandardCharsets.UTF_8));
        File dir = new File(root, "p");
        assertTrue(dir.mkdirs());
        String ttl = prefixes() + "<http://example.org/p> a owl:Ontology ; owl:imports <" + schema + "> ;"
                + " <" + schema + "#hasIntent> \"what it is for\" . :C a owl:Class ; rdfs:label \"c\"@en .\n";
        Files.write(new File(dir, "pattern.owl").toPath(), ttl.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "pattern.ttl").toPath(), ttl.getBytes(StandardCharsets.UTF_8));
        metadata(dir, "p");
        PatternCuration curation = new PatternCuration(none(), none(), PatternCuration.Licences.none(),
                noFixes(), stubs(), iri -> schema.equals(iri) ? new File(fetched, PatternAudit.fileNameFor(schema)) : null);

        curation.curate(dir);
        PatternCuration.Changes again = curation.curate(dir);

        assertTrue(again.lines().isEmpty(), again.lines().toString());
        assertFalse(new String(Files.readAllBytes(new File(dir, "pattern.owl").toPath()), StandardCharsets.UTF_8)
                .contains("extractedFrom"), "a term only the import declares is not written into the pattern");
    }

    @Test
    void anOntologyThatIsAlsoATermIsLabelledInItsHeader() throws Exception {
        File dir = pattern("p", "<http://example.org/p> a owl:NamedIndividual . :C a owl:Class ; rdfs:label \"c\"@en .");
        PatternCuration curation = curation(none(), none(), noFixes());

        curation.curate(dir);
        PatternCuration.Changes again = curation.curate(dir);

        assertEquals(Arrays.asList("p pattern"), headerValues(read(dir, "pattern.owl"),
                "http://www.w3.org/2000/01/rdf-schema#label"));
        assertTrue(again.lines().isEmpty(), again.lines().toString());
    }

    @Test
    void aStaleDefaultNamespaceIsNotCarriedOver() throws Exception {
        File dir = new File(root, "p");
        assertTrue(dir.mkdirs());
        String owl = "<?xml version=\"1.0\"?>\n<rdf:RDF xmlns=\"http://stale.example/old#\""
                + " xml:base=\"http://stale.example/old\""
                + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
                + " xmlns:owl=\"http://www.w3.org/2002/07/owl#\" xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n"
                + "<owl:Ontology rdf:about=\"http://example.org/p\"/>\n"
                + "<owl:Class rdf:about=\"" + EX + "A\"><rdfs:label xml:lang=\"en\">a</rdfs:label>"
                + "<rdfs:subClassOf rdf:resource=\"" + EX + "B\"/></owl:Class>\n"
                + "<owl:Class rdf:about=\"" + EX + "B\"><rdfs:label xml:lang=\"en\">b</rdfs:label></owl:Class>\n"
                + "</rdf:RDF>\n";
        Files.write(new File(dir, "pattern.owl").toPath(), owl.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "pattern.ttl").toPath(), owl.getBytes(StandardCharsets.UTF_8));
        metadata(dir, "p");

        curation(none(), none(), noFixes()).curate(dir);

        assertEquals(logical(read(dir, "pattern.owl")), logical(read(dir, "pattern.ttl")));
        assertTrue(logical(read(dir, "pattern.ttl")).toString().contains("SubClassOf(<" + EX + "A> <" + EX + "B>)"),
                logical(read(dir, "pattern.ttl")).toString());
    }

    // --- the library's labels -------------------------------------------------------------------

    @Test
    void theLibraryPrefersATaggedLabelInWords() throws Exception {
        pattern("q1", ":X a owl:ObjectProperty ; rdfs:label \"hasPart\" .");
        pattern("q2", ":X a owl:ObjectProperty ; rdfs:label \"has part\"@en .");

        Map<String, PatternCuration.Given> library = PatternCuration.libraryLabels(root, none(), noFixes(), stubs());

        assertEquals("has part", library.get(EX + "X").text);
        assertEquals("en", library.get(EX + "X").lang);
    }

    @Test
    void theLibraryGivesAFixedLabel() throws Exception {
        pattern("q1", ":H a owl:Class ; rdfs:label \"Section\"@en .");
        List<PatternCuration.Fix> fixes = Arrays.asList(new PatternCuration.Fix("q1", EX + "H", "rdfs:label",
                "Section", "Homogeneous fragment", "a copied label"));

        Map<String, PatternCuration.Given> library = PatternCuration.libraryLabels(root, none(), fixes, stubs());

        assertEquals("Homogeneous fragment", library.get(EX + "H").text);
    }

    // --- helpers --------------------------------------------------------------------------------

    private PatternCuration curation(Map<String, PatternCuration.Given> library,
            Map<String, PatternCuration.Given> known, PatternCuration.Licences licences,
            List<PatternCuration.Fix> fixes) {
        return new PatternCuration(library, known, licences, fixes, stubs(), iri -> null);
    }

    private PatternCuration curation(Map<String, PatternCuration.Given> library,
            Map<String, PatternCuration.Given> known, List<PatternCuration.Fix> fixes) {
        return curation(library, known, PatternCuration.Licences.none(), fixes);
    }

    private static PatternCuration.Licences licensed() {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] {LICENSED, CC_BY, LICENSED + "evidence"});
        rows.add(new String[] {"https://unlicensed.example/", "", "https://unlicensed.example/"});
        return new PatternCuration.Licences(rows);
    }

    /** IAO's and SKOS's definition, both called "definition" by their home ontologies. */
    private static Map<String, PatternCuration.Given> definitions() {
        Map<String, PatternCuration.Given> known = new HashMap<String, PatternCuration.Given>();
        known.put("http://purl.obolibrary.org/obo/IAO_0000115",
                new PatternCuration.Given("definition", "en", "http://purl.obolibrary.org/obo/iao.owl"));
        known.put(SKOS + "definition", new PatternCuration.Given("definition", "en", SKOS));
        return known;
    }

    private static Map<String, PatternCuration.Given> none() {
        return Collections.emptyMap();
    }

    private static List<PatternCuration.Fix> noFixes() {
        return Collections.emptyList();
    }

    private File stubs() {
        File stubs = new File(root, "stubs");
        assertTrue(stubs.isDirectory() || stubs.mkdirs());
        return stubs;
    }

    private static String prefixes() {
        return "@prefix : <" + EX + "> .\n@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n";
    }

    /** A pattern folder as the library has one: pattern.owl (RDF/XML), pattern.ttl, metadata.json. */
    private File pattern(String id, String turtle) throws Exception {
        File dir = new File(root, id);
        assertTrue(dir.mkdirs());
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new StringDocumentSource(
                prefixes() + "<http://example.org/" + id + "> a owl:Ontology .\n" + turtle + "\n"));
        try (OutputStream owl = new FileOutputStream(new File(dir, "pattern.owl"));
                OutputStream ttl = new FileOutputStream(new File(dir, "pattern.ttl"))) {
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), owl);
            manager.saveOntology(ontology, new TurtleDocumentFormat(), ttl);
        }
        metadata(dir, id);
        return dir;
    }

    private static void metadata(File dir, String id) throws Exception {
        Files.write(new File(dir, "metadata.json").toPath(), ("{\n  \"id\": \"" + id + "\",\n  \"name\": \""
                + id + " pattern\",\n  \"description\": \"About " + id + ".\",\n  \"source\":"
                + " \"https://unlicensed.example/" + id + "\"\n}\n").getBytes(StandardCharsets.UTF_8));
    }

    /** A file read as the library reads one: from its bytes, imports silently missing. */
    private static OWLOntology read(File dir, String name) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        return manager.loadOntologyFromOntologyDocument(new StringDocumentSource(new String(
                Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8)));
    }

    private static List<String> labelsOf(OWLOntology ontology, String iri) {
        return annotationsOf(ontology, iri, "http://www.w3.org/2000/01/rdf-schema#label");
    }

    private static List<String> annotationsOf(OWLOntology ontology, String iri, String property) {
        List<String> found = new ArrayList<String>();
        for (OWLAnnotationAssertionAxiom assertion : ontology.getAnnotationAssertionAxioms(IRI.create(iri))) {
            if (assertion.getProperty().getIRI().toString().equals(property)) {
                OWLLiteral value = (OWLLiteral) assertion.getValue();
                found.add(value.getLiteral() + (value.getLang().isEmpty() ? "" : "@" + value.getLang()));
            }
        }
        Collections.sort(found);
        return found;
    }

    private static List<String> headerValues(OWLOntology ontology, String property) {
        List<String> found = new ArrayList<String>();
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().getIRI().toString().equals(property)) {
                found.add(annotation.getValue() instanceof OWLLiteral
                        ? ((OWLLiteral) annotation.getValue()).getLiteral() : annotation.getValue().toString());
            }
        }
        return found;
    }

    private static Set<OWLAxiom> logical(OWLOntology ontology) {
        Set<OWLAxiom> axioms = new HashSet<OWLAxiom>();
        for (OWLAxiom axiom : ontology.getLogicalAxioms()) {
            axioms.add(axiom.getAxiomWithoutAnnotations());
        }
        return axioms;
    }

    private static Set<OWLAxiom> allAxioms(OWLOntology ontology) {
        return new HashSet<OWLAxiom>(ontology.getAxioms());
    }
}
