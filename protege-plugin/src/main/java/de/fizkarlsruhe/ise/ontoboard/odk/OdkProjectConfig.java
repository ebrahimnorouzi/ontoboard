package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;

/**
 * What the user answers before an ODK project is generated.
 *
 * <p>Validation lives here rather than in the dialog so it can be tested without a display,
 * and so a caller that skips the dialog cannot bypass it. The ontology id is the strictest
 * field because it becomes part of file names, Makefile variables and IRIs - a bad value
 * produces a project that looks fine until a build fails somewhere unrelated.
 */
public final class OdkProjectConfig {

    private final String ontologyId;
    private final String title;
    private final String description;
    private final String baseIri;
    private final String license;
    private final File targetDirectory;

    public OdkProjectConfig(String ontologyId, String title, String description,
            String baseIri, String license, File targetDirectory) {
        this.ontologyId = ontologyId == null ? "" : ontologyId.trim();
        this.title = title == null ? "" : title.trim();
        this.description = description == null ? "" : description.trim();
        this.baseIri = normaliseIri(baseIri, this.ontologyId);
        this.license = license == null ? "" : license.trim();
        this.targetDirectory = targetDirectory;
    }

    /**
     * @throws IllegalArgumentException naming the first problem, in words a user can act on
     */
    public void validate() {
        if (ontologyId.isEmpty()) {
            throw new IllegalArgumentException("Ontology ID is required, e.g. 'mwo'.");
        }
        // ODK uses the id in file names, Makefile variables and IRIs, so keep it to the
        // lowercase-alphanumeric shape OBO ontologies use.
        if (!ontologyId.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Ontology ID must start with a lowercase letter and contain only "
                            + "lowercase letters, digits or underscores. Got: '" + ontologyId + "'");
        }
        if (title.isEmpty()) {
            throw new IllegalArgumentException("Title is required.");
        }
        if (targetDirectory == null) {
            throw new IllegalArgumentException("Choose a folder for the new project.");
        }
        File projectRoot = getProjectRoot();
        if (projectRoot.exists()) {
            throw new IllegalArgumentException(
                    "'" + projectRoot.getAbsolutePath() + "' already exists. Choose another "
                            + "folder or ontology ID - refusing to write into it.");
        }
    }

    /** The directory that will be created, named after the ontology id. */
    public File getProjectRoot() {
        return new File(targetDirectory, ontologyId);
    }

    /** The file Protege should open after generation. */
    public File getEditFile() {
        return new File(new File(new File(getProjectRoot(), "src"), "ontology"),
                ontologyId + "-edit.owl");
    }

    public String getOntologyId() {
        return ontologyId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description.isEmpty() ? title : description;
    }

    /** Always ends without a trailing delimiter; {@link #getNamespace()} adds one. */
    public String getBaseIri() {
        return baseIri;
    }

    /** The IRI new terms are minted under. */
    public String getNamespace() {
        return baseIri + "#";
    }

    public String getLicense() {
        return license;
    }

    private static String normaliseIri(String value, String ontologyId) {
        String iri = value == null ? "" : value.trim();
        if (iri.isEmpty()) {
            // Matches the OBO convention rather than example.org, so a project generated
            // with defaults is closer to publishable.
            iri = "http://purl.obolibrary.org/obo/" + ontologyId + ".owl";
        }
        while (iri.endsWith("#") || iri.endsWith("/")) {
            iri = iri.substring(0, iri.length() - 1);
        }
        return iri;
    }
}
