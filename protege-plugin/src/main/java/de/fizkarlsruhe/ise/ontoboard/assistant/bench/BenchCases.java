package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.RenderedItem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

/** Cases as JSON lines: one case per line, fields in a fixed order, so a diff reads cleanly. */
public final class BenchCases {

    static final ObjectMapper JSON = new ObjectMapper();

    private BenchCases() {
    }

    public static void write(List<BenchCase> cases, File file) throws IOException {
        StringBuilder out = new StringBuilder();
        for (BenchCase c : cases) {
            out.append(JSON.writeValueAsString(toJson(c))).append('\n');
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static List<BenchCase> read(File file) throws IOException {
        List<BenchCase> cases = new ArrayList<BenchCase>();
        int number = 0;
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                cases.add(fromJson(JSON.readTree(line)));
            } catch (IOException | RuntimeException unreadable) {
                throw new IOException(file.getName() + " line " + number + " is not a case: "
                        + unreadable.getMessage(), unreadable);
            }
        }
        return cases;
    }

    static ObjectNode toJson(BenchCase c) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", c.getId());
        node.put("task", c.getTask());
        node.put("ontology", c.getOntology());
        node.put("ontologySha256", c.getOntologySha256());
        if (c.getProblem() == null) {
            node.putNull("problem");
        } else {
            node.put("problem", c.getProblem());
        }
        strings(node.putArray("fault"), c.getFault());
        node.put("control", c.isControl());
        node.put("header", c.getHeader());
        ArrayNode context = node.putArray("context");
        for (RenderedItem item : c.getContext()) {
            context.addObject().put("title", item.getTitle()).put("text", item.getText());
        }
        node.put("message", c.getMessage());
        ObjectNode gold = node.putObject("gold");
        strings(gold.putArray("terms"), c.getTerms());
        strings(gold.putArray("clash"), c.getClash());
        strings(gold.putArray("need"), c.getNeed());
        strings(gold.putArray("needIris"), c.getNeedIris());
        // Round-2 gold only where a task has it, so older tasks' lines read as they did.
        if (!c.getSubjectIri().isEmpty()) {
            gold.put("subject", c.getSubject());
            gold.put("subjectIri", c.getSubjectIri());
            strings(gold.putArray("genus"), c.getGenus());
            gold.put("reference", c.getReference());
        }
        if (!c.getTargetIri().isEmpty()) {
            gold.put("targetIri", c.getTargetIri());
        }
        if (!c.getFaultOntology().isEmpty()) {
            node.put("faultOntology", c.getFaultOntology());
        }
        if (!c.getGoldAxiom().isEmpty()) {
            node.put("goldAxiom", c.getGoldAxiom());
        }
        return node;
    }

    /** For text-to-axiom: the held-back axiom, read back from its functional syntax. */
    public static OWLAxiom goldAxiom(BenchCase c) {
        if (c.getGoldAxiom().isEmpty()) {
            throw new IllegalArgumentException("case " + c.getId() + " has no gold axiom");
        }
        try {
            for (OWLAxiom axiom : OWLManager.createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(new StringDocumentSource(c.getGoldAxiom()))
                    .getLogicalAxioms()) {
                return axiom;
            }
        } catch (OWLOntologyCreationException unreadable) {
            throw new IllegalArgumentException("case " + c.getId() + " has a gold axiom that"
                    + " cannot be read: " + unreadable.getMessage(), unreadable);
        }
        throw new IllegalArgumentException("case " + c.getId() + " has no logical gold axiom");
    }

    /** The case's injected axioms, read back from its functional syntax; empty for none. */
    public static Set<OWLAxiom> faultAxioms(BenchCase c) {
        if (c.getFaultOntology().isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return new LinkedHashSet<OWLAxiom>(OWLManager.createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(new StringDocumentSource(
                            c.getFaultOntology())).getAxioms());
        } catch (OWLOntologyCreationException unreadable) {
            throw new IllegalArgumentException("case " + c.getId() + " has a fault that cannot"
                    + " be read: " + unreadable.getMessage(), unreadable);
        }
    }

    private static void strings(ArrayNode array, List<String> values) {
        for (String value : values) {
            array.add(value);
        }
    }

    static BenchCase fromJson(JsonNode node) {
        List<RenderedItem> context = new ArrayList<RenderedItem>();
        for (JsonNode item : node.path("context")) {
            context.add(new RenderedItem(item.path("title").asText(""),
                    item.path("text").asText("")));
        }
        JsonNode gold = node.path("gold");
        return new BenchCase(node.path("id").asText(""), node.path("task").asText(""),
                node.path("ontology").asText(""), node.path("ontologySha256").asText(""),
                node.path("problem").isTextual() ? node.path("problem").asText() : null,
                strings(node.path("fault")), node.path("control").asBoolean(false),
                node.path("header").asText(""), context, node.path("message").asText(""),
                strings(gold.path("terms")), strings(gold.path("clash")),
                strings(gold.path("need")), strings(gold.path("needIris")))
                .with(new BenchCase.Extra(node.path("faultOntology").asText(""),
                        gold.path("subject").asText(""), gold.path("subjectIri").asText(""),
                        strings(gold.path("genus")), gold.path("reference").asText(""),
                        gold.path("targetIri").asText(""), node.path("goldAxiom").asText("")));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<String>();
        for (JsonNode value : array) {
            values.add(value.asText());
        }
        return values;
    }
}
