package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.semanticweb.owlapi.model.OWLDocumentFormat;

/**
 * The formats a project's {@code export_formats} asks a release to be written in.
 *
 * <p>The gap this closes is sharper than "a key OntoBoard does not read". OntoBoard's own scaffold
 * writes {@code export_formats} into every project it creates, and NFDIcore - a real project built
 * on this - declares
 *
 * <pre>
 * export_formats:
 *   - owl
 *   - ttl
 * </pre>
 *
 * <p>and until now <i>Project &gt; Release…</i> wrote one file, in RDF/XML, whichever formats the
 * project asked for. So the plugin wrote a configuration into a project and then disagreed with it,
 * and a release made from the menu was missing artefacts the project's own CI produces.
 *
 * <p><b>An unknown format is reported, never guessed at.</b> ODK's own list is longer than what an
 * OWL API writer can produce, and a release that silently skipped a format would be a release
 * somebody believes is complete. What this cannot write, it names.
 */
public final class ExportFormats {

    private ExportFormats() {
    }

    /** What a release is written in when the project says nothing: RDF/XML, as before. */
    public static final String DEFAULT = "owl";

    /** One format ODK can name, and what the OWL API calls it. */
    public enum Format {
        OWL("owl", ".owl", "RDF/XML"),
        TTL("ttl", ".ttl", "Turtle"),
        OFN("ofn", ".ofn", "OWL Functional Syntax"),
        OWX("owx", ".owx", "OWL/XML"),
        OMN("omn", ".omn", "Manchester Syntax"),
        JSON("json", ".json", "OBO Graphs JSON");

        private final String key;
        private final String extension;
        private final String description;

        Format(String key, String extension, String description) {
            this.key = key;
            this.extension = extension;
            this.description = description;
        }

        /** As ODK spells it in the YAML. */
        public String getKey() {
            return key;
        }

        /** What the file is called, so a release has one name per format. */
        public String getExtension() {
            return extension;
        }

        public String getDescription() {
            return description;
        }

        /**
         * The writer, or null when the OWL API cannot produce this one.
         *
         * <p>{@code json} is the case: OBO Graphs is produced by ROBOT's own converter rather than
         * by an OWL API document format, and this plugin writes releases through the OWL API. A
         * null here is what makes the caller say so rather than write an RDF/XML file with a
         * {@code .json} name on it.
         */
        public OWLDocumentFormat newWriter() {
            switch (this) {
                case OWL:
                    return new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat();
                case TTL:
                    return new org.semanticweb.owlapi.formats.TurtleDocumentFormat();
                case OFN:
                    return new org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat();
                case OWX:
                    return new org.semanticweb.owlapi.formats.OWLXMLDocumentFormat();
                case OMN:
                    return new org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat();
                case JSON:
                default:
                    return null;
            }
        }
    }

    /** The format ODK's key names, or null when it names none of them. */
    public static Format byKey(String key) {
        String wanted = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        for (Format format : Format.values()) {
            if (format.getKey().equals(wanted)) {
                return format;
            }
        }
        return null;
    }

    /** What a project asked for, and what of it can be written. */
    public static final class Wanted {
        private final List<Format> writable;
        private final List<String> unsupported;
        private final boolean declared;

        Wanted(List<Format> writable, List<String> unsupported, boolean declared) {
            this.writable = Collections.unmodifiableList(writable);
            this.unsupported = Collections.unmodifiableList(unsupported);
            this.declared = declared;
        }

        /** The formats to write, always at least one. */
        public List<Format> getWritable() {
            return writable;
        }

        /**
         * Formats the project asked for that cannot be written here, as it spelled them.
         *
         * <p>Reported to the user rather than dropped. A release missing a file somebody's
         * pipeline consumes is worse when nothing said it was missing.
         */
        public List<String> getUnsupported() {
            return unsupported;
        }

        /** Whether the project declared the key at all, as against falling back. */
        public boolean wasDeclared() {
            return declared;
        }
    }

    /**
     * What this project's {@code export_formats} asks for.
     *
     * <p>Falls back to RDF/XML on anything at all - no project, no file, unreadable YAML, a key
     * that is not a list, a list of nothing recognisable. The release is the one operation that
     * must not refuse to run because a configuration file has a problem somewhere else in it, and
     * writing what it has always written is the behaviour nobody can be surprised by.
     */
    public static Wanted wantedBy(File ontologyDirectory) {
        List<Format> writable = new ArrayList<Format>();
        List<String> unsupported = new ArrayList<String>();
        boolean declared = false;
        try {
            File yaml = OdkBuildSettings.yamlIn(ontologyDirectory);
            if (yaml != null) {
                String text = new String(java.nio.file.Files.readAllBytes(yaml.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                LinkedHashSet<Format> seen = new LinkedHashSet<Format>();
                for (OdkYaml.Entry entry : OdkYaml.entriesIn(text)) {
                    if (!entry.getPath().startsWith("export_formats[")) {
                        continue;
                    }
                    declared = true;
                    Format format = byKey(entry.getValue());
                    if (format == null) {
                        unsupported.add(entry.getValue().trim() + " (not a format this writes)");
                    } else if (format.newWriter() == null) {
                        unsupported.add(entry.getValue().trim() + " (" + format.getDescription()
                                + ", which ROBOT produces and the OWL API does not)");
                    } else {
                        seen.add(format);
                    }
                }
                writable.addAll(seen);
            }
        } catch (RuntimeException unreadable) {
            // A configuration problem must never stop a release being written.
            writable.clear();
        } catch (java.io.IOException unreadable) {
            writable.clear();
        }
        if (writable.isEmpty()) {
            writable.add(Format.OWL);
        }
        return new Wanted(writable, unsupported, declared);
    }

    /** The same file name with this format's extension instead of its current one. */
    public static File named(File file, Format format) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return new File(file.getParentFile(), stem + format.getExtension());
    }
}
