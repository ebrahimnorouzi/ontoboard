package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.IRI;

/**
 * Editing an {@code <import>_terms.txt} without losing what is written in it.
 *
 * <p>The list of terms an ODK import is rebuilt from. OntoBoard has been able to read one since
 * 1.77.0 and to write one from scratch since 1.38.0, and those are different things: writing one
 * from scratch is what {@link ImportModules#writeTerms} does for a module it just extracted, and
 * it produces the file from a template.
 *
 * <p><b>Which is why editing cannot go through it.</b> Measured on a real repository's
 * {@code iao_terms.txt} - 18 lines, 13 terms - a read-then-write round trip leaves 15 of those
 * lines gone: every one of the 13 trailing {@code # label} comments, and the file's own two
 * header lines, replaced by OntoBoard's template and re-sorted into a different order. Across
 * that project's five populated lists, all 50 terms carry a trailing comment naming the term and
 * there are 11 standalone comment lines. A term list is a hand-maintained file, and an editor
 * that silently subtracts from one is the same defect this project has now fixed three times -
 * in the ID ranges, in the ODK YAML, and in reading these very files.
 *
 * <p>So the original text is spliced, line by line, exactly as {@link OdkYaml} splices the
 * configuration. A term is removed by dropping its line and added by appending one; every
 * comment, every blank line and the existing order survive untouched.
 */
public final class TermFile {

    /** What a line in the file turned out to be. */
    public enum Kind {
        /** A readable term, possibly with a trailing comment. */
        TERM,
        /** A whole-line comment. */
        COMMENT,
        /** Empty, or only whitespace. */
        BLANK,
        /**
         * Something that is neither blank, a comment, nor a readable term.
         *
         * <p>Reported rather than skipped. A line that was meant to be a term and is not means
         * the module is quietly missing it, and the person who typed it is the only one who can
         * say what it should have been.
         */
        MALFORMED
    }

    /** One line, as it appears in the file. */
    public static final class Line {
        private final int number;
        private final Kind kind;
        private final String text;
        private final IRI term;
        private final String comment;

        Line(int number, Kind kind, String text, IRI term, String comment) {
            this.number = number;
            this.kind = kind;
            this.text = text;
            this.term = term;
            this.comment = comment == null ? "" : comment;
        }

        /** 1-based, so it matches what an editor shows. */
        public int getNumber() {
            return number;
        }

        public Kind getKind() {
            return kind;
        }

        /** The line exactly as written. */
        public String getText() {
            return text;
        }

        /** The term, for a {@link Kind#TERM} line; null otherwise. */
        public IRI getTerm() {
            return term;
        }

        /** Whatever followed the {@code #}, trimmed, or empty. Usually the term's label. */
        public String getComment() {
            return comment;
        }

        @Override
        public String toString() {
            return number + ": " + kind + " " + text;
        }
    }

    private TermFile() {
    }

    /** Every line in the file, classified, in order. */
    public static List<Line> linesIn(String text) {
        List<Line> lines = new ArrayList<Line>();
        if (text == null) {
            return lines;
        }
        String[] raw = text.split("\n", -1);
        for (int at = 0; at < raw.length; at++) {
            String line = raw[at].endsWith("\r")
                    ? raw[at].substring(0, raw[at].length() - 1) : raw[at];
            // A trailing newline produces a final empty element that is not a line of the file.
            if (at == raw.length - 1 && line.isEmpty()) {
                break;
            }
            lines.add(classify(at + 1, line));
        }
        return Collections.unmodifiableList(lines);
    }

    private static Line classify(int number, String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return new Line(number, Kind.BLANK, line, null, "");
        }
        if (trimmed.startsWith("#")) {
            return new Line(number, Kind.COMMENT, line, null,
                    trimmed.substring(1).trim());
        }
        int hash = trimmed.indexOf('#');
        String comment = hash < 0 ? "" : trimmed.substring(hash + 1).trim();
        String candidate = hash < 0 ? trimmed : trimmed.substring(0, hash).trim();
        // The same rule ImportModules uses: the first whitespace-delimited token is the term,
        // so a comment written without a hash still leaves the term readable.
        String token = ImportModules.termOn(candidate);
        if (token == null || token.isEmpty()) {
            return new Line(number, Kind.MALFORMED, line, null, comment);
        }
        try {
            return new Line(number, Kind.TERM, line, IRI.create(token), comment);
        } catch (RuntimeException notAnIri) {
            return new Line(number, Kind.MALFORMED, line, null, comment);
        }
    }

    /** The readable terms, in file order. */
    public static List<IRI> termsIn(String text) {
        List<IRI> terms = new ArrayList<IRI>();
        for (Line line : linesIn(text)) {
            if (line.getKind() == Kind.TERM) {
                terms.add(line.getTerm());
            }
        }
        return Collections.unmodifiableList(terms);
    }

    /**
     * {@code text} with one line removed, and every other byte unchanged.
     *
     * <p>Removing rather than rewriting, so the comments either side of it stay where they are.
     *
     * @param number the 1-based line, as {@link Line#getNumber()} reports it
     */
    public static String without(String text, int number) {
        List<Line> lines = linesIn(text);
        if (number < 1 || number > lines.size()) {
            throw new IllegalArgumentException("There is no line " + number + " to remove.");
        }
        StringBuilder out = new StringBuilder();
        boolean crlf = text.contains("\r\n");
        for (Line line : lines) {
            if (line.getNumber() == number) {
                continue;
            }
            out.append(line.getText()).append(crlf ? "\r\n" : "\n");
        }
        return out.toString();
    }

    /**
     * {@code text} with a term appended after the last existing term.
     *
     * <p>After the last <em>term</em>, not at the end of the file, because a term list commonly
     * finishes with a comment and a new entry appended under it would look as though that
     * comment introduced it.
     *
     * <p>The trailing label comment is written only if the file already uses them. Measured on a
     * real repository every one of its 50 terms carries one, and a file whose convention is to
     * annotate every line should not acquire a bare entry; equally, a file with none should not
     * acquire the project's first.
     *
     * @param label the term's label, used as the trailing comment; may be null
     */
    public static String with(String text, IRI term, String label) {
        if (term == null) {
            throw new IllegalArgumentException("No term to add.");
        }
        List<Line> lines = linesIn(text == null ? "" : text);
        for (Line line : lines) {
            if (line.getKind() == Kind.TERM && term.equals(line.getTerm())) {
                throw new IllegalArgumentException(term
                        + " is already on line " + line.getNumber() + ".");
            }
        }
        boolean crlf = text != null && text.contains("\r\n");
        String newline = crlf ? "\r\n" : "\n";
        String entry = term.toString();
        if (annotatesEveryTerm(lines) && label != null && !label.trim().isEmpty()) {
            entry = entry + " # " + label.trim().replace("\r", " ").replace("\n", " ");
        }

        int afterLast = 0;
        for (Line line : lines) {
            if (line.getKind() == Kind.TERM || line.getKind() == Kind.MALFORMED) {
                afterLast = line.getNumber();
            }
        }
        StringBuilder out = new StringBuilder();
        if (lines.isEmpty()) {
            return entry + newline;
        }
        for (Line line : lines) {
            out.append(line.getText()).append(newline);
            if (line.getNumber() == afterLast) {
                out.append(entry).append(newline);
            }
        }
        if (afterLast == 0) {
            // A file of nothing but comments: the first term goes at the end.
            out.append(entry).append(newline);
        }
        return out.toString();
    }

    /** Whether every term in the file already carries a trailing comment. */
    static boolean annotatesEveryTerm(List<Line> lines) {
        int terms = 0;
        int annotated = 0;
        for (Line line : lines) {
            if (line.getKind() == Kind.TERM) {
                terms++;
                if (!line.getComment().isEmpty()) {
                    annotated++;
                }
            }
        }
        return terms > 0 && terms == annotated;
    }

    /** The lines that were meant to be terms and are not. */
    public static List<Line> malformedIn(String text) {
        List<Line> bad = new ArrayList<Line>();
        for (Line line : linesIn(text)) {
            if (line.getKind() == Kind.MALFORMED) {
                bad.add(line);
            }
        }
        return Collections.unmodifiableList(bad);
    }
}
