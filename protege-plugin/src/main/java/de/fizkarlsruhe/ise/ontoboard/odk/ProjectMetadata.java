package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a project's statements about itself disagree with each other.
 *
 * <p><b>Why this exists rather than an editor for {@code src/metadata}.</b> That directory was on
 * the roadmap as something to edit, and measuring it closed the item instead. Of the four real ODK
 * projects this plugin is developed against, exactly one - ECTO - is in the OBO Foundry; the
 * registry and the PURL config answer 404 for NFDIcore, MWO and PMDCO, whose ontology IRIs are
 * under their own domains. So the {@code base_url: /obo/<id>} their {@code src/metadata/<id>.yml}
 * declares configures a PURL namespace they do not own, and the file is scaffold nothing reads.
 * For ECTO, where it does apply, the committed copy has already drifted from the deployed one, so
 * editing it locally reaches nobody.
 *
 * <p>What measuring it <em>did</em> find is worth reporting. All three projects carrying a
 * {@code src/metadata/<id>.md} declare
 *
 * <pre>
 * license:
 *   url: http://creativecommons.org/licenses/by/3.0/
 *   label: CC-BY
 * </pre>
 *
 * <p>while their ontologies annotate CC0 1.0. A file whose purpose is to state the licence
 * contradicts the thing it describes, in every project that has one.
 *
 * <p><b>Read-only, and deliberately so.</b> This reports; it writes nothing. The files are
 * generator-owned - MWO shows ODK putting all three back after a maintainer deleted them - so an
 * edit made here could be reverted by the next {@code update_repo} without anybody noticing.
 *
 * <p><b>The ontology's own axioms, never the imports closure.</b> ECTO imports eighteen
 * ontologies, each with its own title and licence; reading through the closure would report
 * hundreds of disagreements, none of them this project's.
 */
public final class ProjectMetadata {

    private ProjectMetadata() {
    }

    /** What a disagreement is about. */
    public enum Field {
        TITLE("title"),
        LICENCE("licence"),
        DESCRIPTION("description"),
        NAMESPACE("namespace"),
        PLACEHOLDER("placeholder"),
        EXAMPLE_TERM("example term");

        private final String label;

        Field(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** One disagreement, with both sides and where each was read. */
    public static final class Finding {
        private final Field field;
        private final String said;
        private final String saidWhere;
        private final String against;
        private final String againstWhere;
        private final String explanation;

        Finding(Field field, String said, String saidWhere, String against, String againstWhere,
                String explanation) {
            this.field = field;
            this.said = said;
            this.saidWhere = saidWhere;
            this.against = against;
            this.againstWhere = againstWhere;
            this.explanation = explanation;
        }

        public Field getField() {
            return field;
        }

        public String getSaid() {
            return said;
        }

        public String getSaidWhere() {
            return saidWhere;
        }

        /** The competing value, or empty when the finding is about one file alone. */
        public String getAgainst() {
            return against;
        }

        public String getAgainstWhere() {
            return againstWhere;
        }

        /** What it means and what to do, in prose. */
        public String explain() {
            return explanation;
        }

        @Override
        public String toString() {
            return field.getLabel() + ": " + said + " (" + saidWhere + ")";
        }
    }

    /** Where each value came from, so a finding can name a file rather than a layer. */
    public static final class Sources {
        private final Map<String, String> ontology = new LinkedHashMap<String, String>();
        private final Map<String, String> odkYaml = new LinkedHashMap<String, String>();
        private final Map<String, String> registryYml = new LinkedHashMap<String, String>();
        private final Map<String, String> registryMd = new LinkedHashMap<String, String>();
        private String ontologyIri = "";
        private String id = "";

        /** What the open ontology annotates: title, licence, description. */
        public Sources fromOntology(String title, String licence, String description, String iri) {
            put(ontology, "title", title);
            put(ontology, "licence", licence);
            put(ontology, "description", description);
            this.ontologyIri = iri == null ? "" : iri;
            return this;
        }

        public Sources fromOdkYaml(String text) {
            readInto(odkYaml, text);
            return this;
        }

        public Sources fromRegistryYml(String text) {
            readInto(registryYml, text);
            return this;
        }

        /** The Markdown registry entry; only its front matter is read. */
        public Sources fromRegistryMd(String text) {
            String yaml = FrontMatter.yamlOf(text);
            if (yaml != null) {
                readInto(registryMd, yaml);
            }
            return this;
        }

        public Sources withId(String id) {
            this.id = id == null ? "" : id;
            return this;
        }

        private static void put(Map<String, String> into, String key, String value) {
            if (value != null && !value.trim().isEmpty()) {
                into.put(key, value.trim());
            }
        }

        private static void readInto(Map<String, String> into, String text) {
            if (text == null || text.trim().isEmpty()) {
                return;
            }
            try {
                for (OdkYaml.Entry entry : OdkYaml.entriesIn(text)) {
                    if (entry.getPath() != null && !entry.getPath().isEmpty()) {
                        into.put(entry.getPath(), entry.getValue());
                    }
                }
            } catch (RuntimeException unreadable) {
                // A file nobody can parse is a different problem, and not this one's to report.
                into.clear();
            }
        }
    }

    /** Every disagreement these sources contain, most consequential first. */
    public static List<Finding> findingsIn(Sources sources) {
        List<Finding> found = new ArrayList<Finding>();
        if (sources == null) {
            return found;
        }

        // Order matters, and so does what is left out. The checks on files the BUILD reads -
        // the ontology itself and the ODK YAML - come first and always run. The checks on
        // src/metadata only run when that directory is live, meaning the ontology really is in
        // the OBO namespace the file configures.
        //
        // Itemising an inert file would be noise on top of noise: saying "nothing reads this"
        // and then listing six things wrong inside it invites somebody to go and fix them, which
        // is exactly the work this check exists to tell them not to do. When the file is dead,
        // it gets one finding saying so.
        title(sources, found);
        licence(sources, found);
        namespace(sources, found);
        if (registryIsLive(sources)) {
            exampleTerm(sources, found);
            placeholders(sources, found);
        }
        return found;
    }

    /**
     * The licence, which is the one that matters most and the one that is wrong everywhere.
     *
     * <p>A licence is the statement a reuser acts on. Two files of the same project naming
     * different ones is not untidiness - it is a question nobody can answer from the repository.
     */
    private static void licence(Sources s, List<Finding> found) {
        String annotated = s.ontology.get("licence");
        String declared = firstOf(s.registryMd, "license.url", "license.label");
        if (registryIsLive(s) && annotated != null && declared != null
                && !sameLicence(annotated, declared)) {
            found.add(new Finding(Field.LICENCE, annotated, "the ontology's own annotation",
                    declared, "src/metadata/" + s.id + ".md",
                    "The ontology annotates one licence and its registry entry declares another. "
                            + "A licence is the statement somebody reuses the ontology on, so two "
                            + "answers in one repository is a question nobody can settle from it. "
                            + "Measured across the projects this check was built against, every "
                            + "src/metadata entry carried CC-BY while every ontology annotated "
                            + "CC0 - the registry entry is ODK scaffold nobody edited."));
        }
        if (annotated != null && !s.odkYaml.isEmpty() && !s.odkYaml.containsKey("license")) {
            found.add(new Finding(Field.LICENCE, annotated, "the ontology's own annotation",
                    "", "the ODK YAML",
                    "The ontology states a licence and the project configuration does not, so "
                            + "anything generated from the configuration cannot repeat it."));
        }
    }

    private static void title(Sources s, List<Finding> found) {
        String annotated = s.ontology.get("title");
        String configured = s.odkYaml.get("title");
        if (annotated != null && configured != null && !annotated.equalsIgnoreCase(configured)) {
            found.add(new Finding(Field.TITLE, annotated, "the ontology's own annotation",
                    configured, "the ODK YAML",
                    "The ontology's title and the project's configured title differ. The "
                            + "configuration is what generated documentation and a registry entry "
                            + "repeat, so the one people see may not be the one the file says."));
        }
    }

    /**
     * A registry entry configuring a namespace the ontology does not use.
     *
     * <p>The finding that closed the roadmap item. {@code base_url: /obo/<id>} configures a
     * redirect under {@code purl.obolibrary.org}, which only an ontology in the OBO Foundry has.
     */
    private static void namespace(Sources s, List<Finding> found) {
        String base = s.registryYml.get("base_url");
        if (base == null || s.ontologyIri.isEmpty()) {
            return;
        }
        if (base.startsWith("/obo/") && !s.ontologyIri.contains("purl.obolibrary.org/obo")) {
            found.add(new Finding(Field.NAMESPACE, base, "src/metadata/" + s.id + ".yml",
                    s.ontologyIri, "the ontology's own IRI",
                    "This file configures a redirect under purl.obolibrary.org, and the ontology "
                            + "is not in that namespace. Unless the project is in the OBO "
                            + "Foundry, nothing reads it: the file is ODK scaffold, the build "
                            + "does not use it, and the Foundry's own intake is now a GitHub "
                            + "issue form rather than these two files. Editing it changes "
                            + "nothing anybody sees."));
        }
    }

    /** An example term whose prefix does not match the idspace it is supposed to illustrate. */
    private static void exampleTerm(Sources s, List<Finding> found) {
        String idspace = s.registryYml.get("idspace");
        String example = s.registryYml.get("example_terms[0]");
        if (idspace == null || example == null) {
            return;
        }
        String prefix = example.indexOf('_') > 0 ? example.substring(0, example.indexOf('_'))
                : example;
        if (!prefix.equals(idspace)) {
            found.add(new Finding(Field.EXAMPLE_TERM, example, "src/metadata/" + s.id + ".yml",
                    idspace, "its own idspace",
                    "The example term does not carry the idspace declared beside it. Case "
                            + "counts in a CURIE prefix, so an example written in lower case "
                            + "does not resolve."));
        }
    }

    /** Scaffold nobody filled in, which reads as a fact until somebody checks. */
    private static void placeholders(Sources s, List<Finding> found) {
        String[][] suspect = {
            {"contact.email", "the contact address is empty"},
            {"contact.label", "the contact name is empty"},
            {"description", "still the generated description"},
        };
        for (String[] each : suspect) {
            String value = s.registryMd.get(each[0]);
            if (value != null && (value.trim().isEmpty()
                    || value.toLowerCase(Locale.ROOT).startsWith("enter a detailed"))) {
                found.add(new Finding(Field.PLACEHOLDER, each[1],
                        "src/metadata/" + s.id + ".md", "", "",
                        "Generated scaffold that was never filled in. It reads as a statement "
                                + "about the project to anybody who finds the file."));
            }
        }
    }

    /** Whether two licence statements mean the same thing, allowing for URL and label forms. */
    static boolean sameLicence(String one, String other) {
        String a = normaliseLicence(one);
        String b = normaliseLicence(other);
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private static String normaliseLicence(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("publicdomain/zero") || lower.contains("cc0")) {
            return "cc0";
        }
        if (lower.contains("licenses/by-sa")) {
            return "cc-by-sa";
        }
        if (lower.contains("licenses/by") || lower.contains("cc-by")) {
            return "cc-by";
        }
        return lower.replaceAll("[^a-z0-9]", "");
    }

    private static String firstOf(Map<String, String> from, String... keys) {
        for (String key : keys) {
            String value = from.get(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }


    /**
     * Whether this project's {@code src/metadata} is a file anything reads.
     *
     * <p>It configures a redirect under {@code purl.obolibrary.org}, which only an ontology in
     * the OBO Foundry has. Measured: of the four real projects this was built against, one is -
     * the other three answer 404 in both the registry and the PURL config while declaring an
     * {@code /obo/} base URL. For those three the directory is ODK scaffold, and every statement
     * inside it is a statement nobody acts on.
     */
    static boolean registryIsLive(Sources s) {
        return s != null && s.ontologyIri != null
                && s.ontologyIri.contains("purl.obolibrary.org/obo");
    }

    /** The project's {@code src/metadata} directory, or null. */
    public static File metadataDirectoryIn(File projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        File directory = new File(new File(projectRoot, "src"), "metadata");
        return directory.isDirectory() ? directory : null;
    }
}
