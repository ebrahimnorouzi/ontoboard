package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.obolibrary.robot.IOHelper;
import org.semanticweb.owlapi.model.IRI;

/**
 * A term file, read the way ROBOT reads one - and honest about what it could not read.
 *
 * <p>An ODK project keeps a list of the terms it borrows from each external ontology, one per
 * line, as a full IRI or a CURIE, with {@code #} comments. This is the file
 * {@code robot extract --term-file} takes, and reading it correctly is the whole of "import terms
 * from another ontology".
 *
 * <p>ROBOT's own {@code parseTerms} does the reading, and <b>silently drops</b> any line whose
 * prefix it does not know: a file of forty terms with one typo'd prefix yields thirty-nine IRIs
 * and no complaint. For an import that means the module quietly comes back missing a term, which
 * is discovered much later as a dangling reference. So the resolution is done here, entry by
 * entry, and the ones that failed are kept and reported with their line numbers.
 *
 * <p>The splitting itself is ROBOT's {@code extractTerms}, applied a line at a time so that order
 * and line numbers survive. Line at a time is exactly equivalent - comments are line-scoped - and
 * it means the comment rules match ROBOT's byte for byte. Those rules are subtler than they look:
 * a {@code #} only starts a comment when whitespace precedes it, so
 * {@code http://example.org/o#Person} keeps its fragment while
 * {@code http://purl.obolibrary.org/obo/IAO_0000109 # measurement datum} loses its label.
 */
public final class TermList {

    /** One line of the file, resolved or not. */
    public static final class Entry {
        private final int line;
        private final String text;
        private final IRI iri;

        Entry(int line, String text, IRI iri) {
            this.line = line;
            this.text = text;
            this.iri = iri;
        }

        /** 1-based, so it matches what an editor shows. */
        public int getLine() {
            return line;
        }

        /** What was on the line, with any comment already removed. */
        public String getText() {
            return text;
        }

        /** The IRI it resolved to, or null when the prefix was not recognised. */
        public IRI getIri() {
            return iri;
        }

        @Override
        public String toString() {
            return "line " + line + ": " + text + (iri == null ? " (unresolved)" : "");
        }
    }

    private final List<IRI> iris;
    private final List<Entry> unresolved;
    private final int duplicates;

    private TermList(List<IRI> iris, List<Entry> unresolved, int duplicates) {
        this.iris = Collections.unmodifiableList(iris);
        this.unresolved = Collections.unmodifiableList(unresolved);
        this.duplicates = duplicates;
    }

    /**
     * Reads a term list.
     *
     * @param text the file's contents
     * @param prefixes extra prefix to expansion pairs, normally the editing ontology's own, so a
     *     project's own CURIEs resolve alongside the 277 OBO ones ROBOT knows. May be null.
     */
    public static TermList parse(String text, Map<String, String> prefixes) {
        IOHelper ioHelper;
        try {
            ioHelper = new IOHelper();
            if (prefixes != null) {
                for (Map.Entry<String, String> prefix : prefixes.entrySet()) {
                    if (prefix.getKey() == null || prefix.getValue() == null) {
                        continue;
                    }
                    // Protege spells a prefix "obo:", ROBOT's addPrefix wants "obo".
                    String name = prefix.getKey().endsWith(":")
                            ? prefix.getKey().substring(0, prefix.getKey().length() - 1)
                            : prefix.getKey();
                    if (!name.isEmpty()) {
                        ioHelper.addPrefix(name, prefix.getValue());
                    }
                }
            }
        } catch (IOException cannotStart) {
            throw new IllegalStateException(
                    "could not read the prefix map: " + cannotStart.getMessage(), cannotStart);
        }

        Set<IRI> seen = new LinkedHashSet<IRI>();
        List<Entry> unresolved = new ArrayList<Entry>();
        int duplicates = 0;
        String[] lines = (text == null ? "" : text).split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (String term : ioHelper.extractTerms(lines[i])) {
                IRI iri = ioHelper.createIRI(term);
                if (iri == null) {
                    unresolved.add(new Entry(i + 1, term, null));
                } else if (!seen.add(iri)) {
                    // Not an error - term files are hand-maintained and gain duplicates - but
                    // worth counting, because "40 lines, 38 terms" is otherwise alarming.
                    duplicates++;
                }
            }
        }
        return new TermList(new ArrayList<IRI>(seen), unresolved, duplicates);
    }

    /** Reads a term file, as UTF-8. */
    public static TermList fromFile(File file, Map<String, String> prefixes) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return parse(decode(bytes), prefixes);
    }

    /**
     * UTF-8, falling back rather than failing.
     *
     * <p>A term file is usually ASCII, but a label in a trailing comment can be anything. Refusing
     * to read a whole file of IRIs because a comment holds one stray byte would be absurd.
     */
    private static String decode(byte[] bytes) {
        try {
            return new String(bytes, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return new String(bytes, Charset.defaultCharset());
        }
    }

    /** The terms, in file order, without duplicates. */
    public List<IRI> getIris() {
        return iris;
    }

    /** Lines that named a term nothing could resolve, with their line numbers. */
    public List<Entry> getUnresolved() {
        return unresolved;
    }

    /** How many lines named a term an earlier line had already named. */
    public int getDuplicates() {
        return duplicates;
    }

    public boolean isEmpty() {
        return iris.isEmpty();
    }

    /** A sentence for a result, saying what was read and what was not. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(iris.size()).append(iris.size() == 1 ? " term" : " terms");
        if (duplicates > 0) {
            text.append(", ").append(duplicates).append(" duplicate line")
                    .append(duplicates == 1 ? "" : "s").append(" ignored");
        }
        if (!unresolved.isEmpty()) {
            text.append(", ").append(unresolved.size()).append(" not recognised");
        }
        return text.toString();
    }
}
