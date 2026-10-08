package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.ProjectMetadata;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; Check project metadata - where a project's statements about itself disagree.
 *
 * <p>An ontology says what it is in several places: its own annotations, the ODK YAML, and -
 * when the project has one - the OBO Foundry registry entry under {@code src/metadata}. Nothing
 * keeps them in step, and measured across four real projects they are not: three of four
 * disagree on the title, all four annotate a licence their ODK YAML does not declare, and every
 * registry entry declares CC-BY while its ontology annotates CC0.
 *
 * <p><b>Read-only, on purpose.</b> It reports and writes nothing. {@code src/metadata} is
 * generator-owned - MWO shows ODK putting all three files back after a maintainer deleted them -
 * so anything written there could be reverted by the next {@code update_repo} without a trace.
 *
 * <p><b>Invoked, never automatic.</b> It will have something to say about most real projects on
 * its first run, and a check that volunteers six findings before a release would be noise at
 * exactly the wrong moment.
 */
public class MetadataCheckAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    /** Where a title comes from, Dublin Core first. The order {@code ContributedPatterns} uses. */
    private static final List<String> TITLES = Arrays.asList(
            "http://purl.org/dc/terms/title",
            "http://purl.org/dc/elements/1.1/title",
            "http://www.w3.org/2000/01/rdf-schema#label");

    /**
     * Where a licence comes from.
     *
     * <p>Both Dublin Core namespaces, because ECTO annotates with {@code dc:} and a
     * {@code dcterms:}-only reader would report it as having none.
     */
    private static final List<String> LICENCES = Arrays.asList(
            "http://purl.org/dc/terms/license",
            "http://purl.org/dc/elements/1.1/license",
            "http://purl.org/dc/terms/rights");

    private static final List<String> DESCRIPTIONS = Arrays.asList(
            "http://purl.org/dc/terms/description",
            "http://purl.org/dc/elements/1.1/description",
            "http://www.w3.org/2000/01/rdf-schema#comment");

    @Override
    protected String operationName() {
        return "Check project metadata";
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("What", "The ontology says", "And this says", "Where");

        File editFile = fileOf(ontology);
        if (editFile == null) {
            return result.failed("This ontology has not been saved, so there is no project to "
                    + "check it against.").build();
        }
        File ontologyDirectory = editFile.getAbsoluteFile().getParentFile();
        File projectRoot = projectRootOf(ontology);
        String id = idOf(ontology, editFile);

        ProjectMetadata.Sources sources = new ProjectMetadata.Sources()
                .withId(id)
                // The ontology's OWN annotations. Not the imports closure: ECTO imports eighteen
                // ontologies, each with its own title and licence, and reading through them
                // would report hundreds of disagreements none of which are this project's.
                .fromOntology(firstOf(ontology, TITLES), firstOf(ontology, LICENCES),
                        firstOf(ontology, DESCRIPTIONS), iriOf(ontology));

        File yaml = de.fizkarlsruhe.ise.ontoboard.odk.OdkBuildSettings.yamlIn(ontologyDirectory);
        if (yaml != null) {
            sources.fromOdkYaml(read(yaml));
            result.note("Configuration: " + yaml.getName());
        }

        File metadata = ProjectMetadata.metadataDirectoryIn(projectRoot);
        if (metadata != null) {
            File registryYml = new File(metadata, id + ".yml");
            File registryMd = new File(metadata, id + ".md");
            if (registryYml.isFile()) {
                sources.fromRegistryYml(read(registryYml));
            }
            if (registryMd.isFile()) {
                sources.fromRegistryMd(read(registryMd));
            }
            result.note("Registry entry: src/metadata/" + id + ".{yml,md}");
        }

        List<ProjectMetadata.Finding> findings = ProjectMetadata.findingsIn(sources);
        for (ProjectMetadata.Finding one : findings) {
            result.row(one.getField().getLabel(), one.getSaid(),
                    one.getAgainst().isEmpty() ? "-" : one.getAgainst(),
                    one.getAgainstWhere().isEmpty() ? one.getSaidWhere()
                            : one.getAgainstWhere());
            result.warn(one.getField().getLabel() + ": " + one.explain());
        }

        if (findings.isEmpty()) {
            return result.summary("Everything this project says about itself agrees.").build();
        }
        result.note("Nothing was changed - this only reports. The ODK YAML is editable from "
                + "Project > Project configuration..., and the ontology's own annotations from "
                + "Protege's Active ontology tab.");
        return result.summary(findings.size()
                + (findings.size() == 1 ? " disagreement." : " disagreements.")).build();
    }

    /** The project id: the ODK YAML's, or the edit file's own name. */
    private String idOf(OWLOntology ontology, File editFile) {
        String name = editFile.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.endsWith("-edit") ? stem.substring(0, stem.length() - "-edit".length())
                : stem;
    }

    private static String iriOf(OWLOntology ontology) {
        com.google.common.base.Optional<IRI> iri =
                ontology.getOntologyID().getOntologyIRI();
        return iri.isPresent() ? iri.get().toString() : "";
    }

    /** The first of these annotation properties the ontology carries, or null. */
    private static String firstOf(OWLOntology ontology, List<String> properties) {
        for (String property : properties) {
            for (OWLAnnotation annotation : ontology.getAnnotations()) {
                if (!annotation.getProperty().getIRI().equals(IRI.create(property))) {
                    continue;
                }
                if (annotation.getValue() instanceof OWLLiteral) {
                    String value = ((OWLLiteral) annotation.getValue()).getLiteral().trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
                // A licence is as often a link as a string: dcterms:license pointing at
                // creativecommons.org is how all four of the measured projects write it.
                if (annotation.getValue() instanceof IRI) {
                    return annotation.getValue().toString();
                }
            }
        }
        return null;
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (java.io.IOException cannotRead) {
            return "";
        }
    }
}
