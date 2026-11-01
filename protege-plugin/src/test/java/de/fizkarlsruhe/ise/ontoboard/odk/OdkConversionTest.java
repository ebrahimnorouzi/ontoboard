package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * An ontology with its history, turned into an ODK repository - and the same again, to show the
 * repository is reproduced.
 */
class OdkConversionTest {

    private static final String TURTLE_HEAD = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
            + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
            + "@prefix dcterms: <http://purl.org/dc/terms/> .\n";

    /** An upper ontology to import, the current version, and two earlier ones. */
    private static String[] inputs(File folder) throws Exception {
        File upper = new File(folder, "upper.ttl");
        Files.write(upper.toPath(), (TURTLE_HEAD
                + "<http://example.org/upper> a owl:Ontology .\n"
                + "<http://example.org/upper#Entity> a owl:Class ; rdfs:label \"entity\" .\n"
                + "<http://example.org/upper#Object> a owl:Class ; rdfs:label \"object\" ;\n"
                + "  rdfs:subClassOf <http://example.org/upper#Entity> .\n"
                + "<http://example.org/upper#Process> a owl:Class ; rdfs:label \"process\" .\n")
                .getBytes(StandardCharsets.UTF_8));
        File current = new File(folder, "shop.ttl");
        Files.write(current.toPath(), (TURTLE_HEAD
                + "<https://example.org/shop.owl> a owl:Ontology ; rdfs:label \"Shop\" ;\n"
                + "  owl:versionInfo \"1.0.0\" ; owl:imports <" + upper.toURI() + "> .\n"
                + "<https://example.org/shop#Pizza> a owl:Class ; rdfs:label \"pizza\" ;\n"
                + "  rdfs:subClassOf <http://example.org/upper#Object> .\n"
                + "<https://example.org/shop#Base> a owl:Class ; rdfs:label \"base\" ;\n"
                + "  rdfs:subClassOf <http://example.org/upper#Object> .\n")
                .getBytes(StandardCharsets.UTF_8));
        String first = earlier(folder, "0.1.0", "2026-01-15", new RDFXMLDocumentFormat(),
                "shop-0.1.0.owl");
        String second = earlier(folder, "0.2.0", "2026-03-01", new OWLXMLDocumentFormat(),
                "shop-0.2.0.owx");
        return new String[] {current.getAbsolutePath(), second, first};
    }

    private static String earlier(File folder, String version, String date,
            org.semanticweb.owlapi.model.OWLDocumentFormat format, String name) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("https://example.org/shop.owl"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(o,
                df.getOWLAnnotation(df.getOWLVersionInfo(), df.getOWLLiteral(version))));
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(o,
                df.getOWLAnnotation(df.getOWLAnnotationProperty(
                        IRI.create("http://purl.org/dc/terms/date")), df.getOWLLiteral(date))));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(
                IRI.create("https://example.org/shop#Pizza"))));
        File file = new File(folder, name);
        manager.saveOntology(o, format, IRI.create(file.toURI()));
        return file.getAbsolutePath();
    }

    private static OdkConversion.Result convert(String[] in, File into, List<String> log) {
        OdkConversion.Request request = new OdkConversion.Request().current(in[0])
                .earlier(in[1]).earlier(in[2]).into(into);
        return OdkConversion.run(request, new OdkConversion.Log() {
            @Override
            public void step(int index) {
                log.add("STEP " + OdkConversion.STEPS.get(index));
            }

            @Override
            public void line(String text) {
                log.add(text);
            }

            @Override
            public void warn(String text) {
                log.add("WARN " + text);
            }

            @Override
            public void done(int index, boolean ok) {
                log.add("DONE " + index + " " + ok);
            }
        }, null);
    }

    @Test
    void anOntologyAndItsHistoryBecomeAnOdkRepositoryAndAreReproduced(@TempDir File folder)
            throws Exception {
        String[] in = inputs(folder);
        List<String> log = new ArrayList<String>();

        OdkConversion.Result one = convert(in, new File(folder, "one"), log);

        File root = one.getRoot();
        assertEquals("shop", root.getName());
        assertEquals(Arrays.asList("0.1.0", "0.2.0", "1.0.0"), one.getVersions(),
                "oldest first, whatever order they were given in: " + log);
        assertEquals(Arrays.asList("upper"), one.getModules(), log.toString());
        assertTrue(one.isBuildPassed(), log.toString());
        for (String version : one.getVersions()) {
            assertTrue(new File(root, "releases/" + version + "/shop.owl").isFile(), version);
        }
        String edit = new String(Files.readAllBytes(
                new File(root, "src/ontology/shop-edit.owl").toPath()), StandardCharsets.UTF_8);
        assertTrue(edit.contains("https://example.org/shop/imports/upper_import.owl"),
                "the import now names the module");
        String terms = new String(Files.readAllBytes(
                new File(root, "src/ontology/imports/upper_terms.txt").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(terms.contains("http://example.org/upper#Object # object"), terms);
        assertTrue(!terms.contains("Process"), "only the terms the ontology uses: " + terms);
        assertEquals(9, log.stream().filter(l -> l.startsWith("STEP ")).count(),
                "every step said");

        if (!OdkConversion.gitIsInstalled()) {
            return;
        }
        assertEquals(Arrays.asList("v0.1.0", "v0.2.0", "v1.0.0"), one.getTags());
        assertEquals(Arrays.asList("v0.1.0", "v0.2.0", "v1.0.0"), git(root, "tag"));
        assertTrue(new String(Files.readAllBytes(new File(root, "README.md").toPath()),
                StandardCharsets.UTF_8).contains("## History"));
        List<String> signer = OdkConversion.knowsWho(root) ? git(root, "config", "user.name")
                : Arrays.asList("OntoBoard");
        assertEquals(signer, git(root, "log", "-1", "--format=%an", "v0.1.0"),
                "signed by whoever git knows, as any commit of yours would be");

        // Again, into another folder: the same repository.
        OdkConversion.Result two = convert(in, new File(folder, "two"), new ArrayList<String>());
        assertEquals(files(root), files(two.getRoot()), "the same files, dates aside");
        for (String tag : one.getTags()) {
            assertEquals(git(root, "rev-parse", tag + "^{tree}"),
                    git(two.getRoot(), "rev-parse", tag + "^{tree}"), tag + ": the same tree");
        }
        assertEquals(git(root, "rev-parse", "v0.2.0^{commit}"),
                git(two.getRoot(), "rev-parse", "v0.2.0^{commit}"),
                "an earlier version's commit carries its own date, so it is the same commit");
        assertEquals(Arrays.asList("2026-01-15"), git(root, "log", "-1", "--format=%ad",
                "--date=short", "v0.1.0"));
    }

    /** Every file but git's own, its text with any date taken out. */
    private static Map<String, String> files(File root) throws Exception {
        Map<String, String> files = new TreeMap<String, String>();
        try (Stream<Path> walk = Files.walk(root.toPath())) {
            for (Path path : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String relative = root.toPath().relativize(path).toString();
                if (relative.startsWith(".git" + File.separator)) {
                    continue;
                }
                files.put(relative, new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                        .replaceAll("\\d{4}-\\d{2}-\\d{2}", "DATE"));
            }
        }
        return files;
    }

    private static List<String> git(File root, String... arguments) throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList("git"));
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).directory(root).start();
        List<String> lines = new ArrayList<String>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line.trim());
            }
        }
        process.waitFor();
        return lines;
    }
}
