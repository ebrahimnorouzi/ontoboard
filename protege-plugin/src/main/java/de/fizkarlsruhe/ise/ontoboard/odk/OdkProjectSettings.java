package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an existing ODK project's own settings back into an {@link OdkProjectConfig}.
 *
 * <p>The precondition for regenerating anything. Without it every capability the plugin gains
 * reaches only <em>newly created</em> projects, and a project made last month can never acquire a
 * fixed Makefile except by being created again from scratch.
 *
 * <p><b>A line scan over a closed key set, not a YAML library.</b> This bundle already embeds 103
 * jars; a parser for six keys is not worth a 104th, and the keys are ones this plugin wrote itself.
 * The scan is deliberately strict about what it accepts rather than clever about YAML.
 *
 * <p><b>Where the base IRI comes from, and why not the YAML.</b> The YAML's {@code uribase} is
 * lossy: {@code uriBase} writes only the part before the last slash, so
 * {@code http://purl.obolibrary.org/obo/mwo.owl} is recorded as
 * {@code http://purl.obolibrary.org/obo} and cannot be reconstructed - the {@code .owl} is gone.
 * The full IRI does survive, in the edit file's {@code xml:base}, which is a file the regenerator
 * must not touch anyway. So it is read from there. An extension-free IRI round-trips through
 * {@code uribase} by luck, which is exactly the kind of accident that makes a bug look fixed.
 *
 * <p>No Protege types and no Swing.
 */
public final class OdkProjectSettings {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** {@code xml:base="..."} in the edit file - the only place the full base IRI survives. */
    private static final Pattern XML_BASE = Pattern.compile("xml:base\\s*=\\s*\"([^\"]+)\"");

    /** {@code <owl:Ontology rdf:about="..."> } - the fallback when there is no xml:base. */
    private static final Pattern ONTOLOGY_ABOUT =
            Pattern.compile("<owl:Ontology\\s+rdf:about\\s*=\\s*\"([^\"]+)\"");

    /**
     * {@code Ontology(<...>} - OWL functional syntax, which is what ODK itself writes.
     *
     * <p>Missing until 1.72.0, and its absence stopped Update project files on every real ODK
     * repository. ODK's own release recipe ends in {@code convert -f ofn}, and an editor who
     * saves from Protege in OWL Functional Syntax gets the same: a file whose first lines are
     * {@code Prefix(...)} declarations and then {@code Ontology(<iri>}, with no {@code xml:base}
     * and no {@code rdf:about} anywhere in it. Reported against MWO, whose edit file begins
     * exactly that way.
     *
     * <p>The IRI is optional in the grammar - {@code Ontology(} alone is a legal anonymous
     * ontology - so the group is only matched when an IRI is actually there, and an anonymous
     * one still falls through to the error below, which is the honest answer for it.
     */
    private static final Pattern FUNCTIONAL_ONTOLOGY =
            Pattern.compile("(?m)^\\s*Ontology\\s*\\(\\s*<([^>]+)>");

    private OdkProjectSettings() {
    }

    /** Signals that a project cannot be read well enough to regenerate it. */
    public static class UnreadableProjectException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreadableProjectException(String message) {
            super(message);
        }
    }

    /** An existing project's settings: enough to re-render every generated file. */
    public static final class Settings {
        private final OdkProjectConfig config;
        private final String robotVersion;

        Settings(OdkProjectConfig config, String robotVersion) {
            this.config = config;
            this.robotVersion = robotVersion;
        }

        public OdkProjectConfig getConfig() {
            return config;
        }

        /**
         * The ROBOT version this project's CI installs.
         *
         * <p>The project's, not the plugin's. A regenerator that substituted the version of
         * whatever OntoBoard happened to run would make the regenerated file depend on the tool
         * rather than on the project - and moving somebody's CI onto a different ROBOT is not a
         * side effect a "re-render the generated files" action is allowed to have.
         */
        public String getRobotVersion() {
            return robotVersion;
        }
    }

    /**
     * Reads {@code <id>-odk.yaml} and the edit file.
     *
     * @param projectRoot the directory holding {@code src/ontology}
     * @throws UnreadableProjectException when this is not a project, or the YAML is missing a key
     *     the generated files need - which is better than regenerating with a guessed value
     */
    public static Settings read(File projectRoot) {
        if (projectRoot == null || !projectRoot.isDirectory()) {
            throw new UnreadableProjectException("There is no project directory to read.");
        }
        File ontologyDir = new File(new File(projectRoot, "src"), "ontology");
        if (!ontologyDir.isDirectory()) {
            throw new UnreadableProjectException(projectRoot.getAbsolutePath()
                    + " has no src/ontology, so it is not an ODK project this can regenerate.");
        }

        File yaml = yamlIn(ontologyDir);
        String text = textOf(yaml);
        String id = required(text, "id", yaml);
        String title = required(text, "title", yaml);
        String description = value(text, "description");
        String license = value(text, "license");
        String robotVersion = value(text, "robot_version");

        File editFile = new File(ontologyDir, id + "-edit.owl");
        if (!editFile.isFile()) {
            throw new UnreadableProjectException("There is no " + editFile.getName()
                    + " beside " + yaml.getName() + ", and the base IRI is only recorded there.");
        }
        String baseIri = baseIriIn(editFile);

        // getProjectRoot() is targetDirectory/id, so the target directory is the parent.
        File targetDirectory = projectRoot.getParentFile();
        if (targetDirectory == null) {
            throw new UnreadableProjectException("Could not work out the parent directory of "
                    + projectRoot.getAbsolutePath());
        }

        OdkProjectConfig config = new OdkProjectConfig(id, title,
                description == null ? "" : description, baseIri,
                license == null ? "" : license, targetDirectory);

        // Deliberately not validate(): it refuses a target that already exists, which every
        // existing project does. The point here is to read one, not to create one.
        return new Settings(config, robotVersion == null || robotVersion.trim().isEmpty()
                ? null : robotVersion.trim());
    }

    /** The project's {@code <id>-odk.yaml}, whatever the id turns out to be. */
    /** Package-visible so the configuration editor finds the same file this reader does. */
    static File yamlIn(File ontologyDir) {
        File[] candidates = ontologyDir.listFiles();
        if (candidates != null) {
            java.util.List<File> found = new java.util.ArrayList<File>();
            for (File candidate : candidates) {
                if (candidate.isFile() && candidate.getName().endsWith("-odk.yaml")) {
                    found.add(candidate);
                }
            }
            // Sorted, so two of them produce the same complaint every time rather than depending on
            // the order the filesystem happened to list them in.
            java.util.Collections.sort(found);
            if (found.size() == 1) {
                return found.get(0);
            }
            if (found.size() > 1) {
                throw new UnreadableProjectException("There are " + found.size()
                        + " *-odk.yaml files in " + ontologyDir.getAbsolutePath()
                        + ", so which project this is cannot be decided. Leave one.");
            }
        }
        throw new UnreadableProjectException("No *-odk.yaml in " + ontologyDir.getAbsolutePath()
                + ", so there is nothing to regenerate from.");
    }

    /**
     * One top-level scalar.
     *
     * <p>Top-level only - anchored at the start of a line - so a nested key of the same name under
     * {@code robot_report:} cannot be mistaken for it. Surrounding double quotes are stripped,
     * because that is how the scaffold writes title and description.
     */
    static String value(String yaml, String key) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + ":[ \\t]*(.*)$")
                .matcher(yaml);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1).trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String required(String yaml, String key, File from) {
        String found = value(yaml, key);
        if (found == null || found.trim().isEmpty()) {
            throw new UnreadableProjectException(from.getName() + " has no '" + key
                    + ":', which every generated file depends on. Add it, or create the project "
                    + "again.");
        }
        return found.trim();
    }

    /** The base IRI, from the edit file rather than from the lossy {@code uribase}. */
    static String baseIriIn(File editFile) {
        String owl = textOf(editFile);
        Matcher base = XML_BASE.matcher(owl);
        if (base.find()) {
            return base.group(1);
        }
        Matcher about = ONTOLOGY_ABOUT.matcher(owl);
        if (about.find()) {
            return about.group(1);
        }
        Matcher functional = FUNCTIONAL_ONTOLOGY.matcher(owl);
        if (functional.find()) {
            return functional.group(1);
        }
        throw new UnreadableProjectException(editFile.getName() + " declares no ontology IRI that "
                + "can be read back: no xml:base, no owl:Ontology rdf:about, and no "
                + "Ontology(<...>) header. The project's base IRI cannot be recovered from it, "
                + "and the YAML's uribase is not enough because it drops the last segment. If "
                + "the ontology is anonymous, give it an IRI in the ontology header and save.");
    }

    private static String textOf(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), UTF8);
        } catch (IOException cannotRead) {
            throw new UnreadableProjectException("Could not read " + file.getAbsolutePath() + ": "
                    + cannotRead.getMessage());
        }
    }
}
