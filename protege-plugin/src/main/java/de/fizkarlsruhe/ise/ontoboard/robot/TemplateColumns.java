package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The second row of a ROBOT template, read as columns rather than as strings.
 *
 * <p>A template's second row says what each column means - {@code ID}, {@code LABEL},
 * {@code SC %}, {@code A IAO:0000115}, {@code AL rdfs:label@en}, {@code I RO:0001025 SPLIT=,}.
 * Nothing in this plugin parsed it until now: the whole table was handed to robot-core and
 * whatever came back was the answer. That is why a column could be malformed in a way that
 * silently produced the wrong axiom and nothing noticed.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Measured on robot-core 1.9.8 against the MatWerk knowledge graph's own sheets:
 * {@code A rdfs:label@en} produces an annotation whose <b>property</b> is
 * {@code http://www.w3.org/2000/01/rdf-schema#label@en} - the language tag glued onto the
 * property IRI - carrying an untagged {@code xsd:string}. It reports no problem. So the cell
 * is not a label at all, in any language: {@code isLabel()} is false and {@code getLang()} is
 * empty. The spelling that works is {@code AL rdfs:label@en}, which gives a real
 * {@code rdfs:label} tagged {@code en}.
 *
 * <p>In that project's {@code organization} sheet two columns are written the first way, across
 * 81 filled cells, so none of its organizations carries a label a reader can see. ROBOT
 * validates {@code AL} without a tag and rejects it, and does not validate {@code A} with one -
 * the asymmetry is the whole defect.
 *
 * <p>This class is also the thing the rest of the spreadsheet work needs. Knowing that a column
 * holds an entity reference rather than a string is what lets a sheet offer the right
 * completions, resolve a label to an IRI, or say which cells point at terms that do not exist.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not reject a spec it merely fails to recognise. ROBOT's own acceptance pattern is
 * {@code ^>{0,2}A[LTI]? .*} for annotation columns plus a handful of class-expression forms,
 * and it gains column types between versions; treating "unknown to me" as "wrong" would break
 * working sheets on the next upgrade. Unknown specs parse to {@link Kind#OTHER} and are left
 * alone. Only the one measured, certain mistake is reported.
 *
 * <p>Pure parsing - no OWL API, no robot-core, no Protege, so it is cheap enough to run on
 * every keystroke in an editor.
 */
public final class TemplateColumns {

    /** What a column does, as far as this plugin needs to care. */
    public enum Kind {
        /** {@code ID} - the row's identifier. */
        ID,
        /** {@code LABEL} - shorthand for an untagged {@code rdfs:label}. */
        LABEL,
        /** {@code TYPE} - what the row's entity is an instance or subclass of. */
        TYPE,
        /** {@code A <prop>} - an annotation whose value is a plain literal. */
        ANNOTATION,
        /** {@code AL <prop>@<lang>} - an annotation with a language tag. */
        ANNOTATION_LANGUAGE,
        /** {@code AT <prop>^^<datatype>} - an annotation with a datatype. */
        ANNOTATION_TYPED,
        /** {@code AI <prop>} - an annotation whose value is an IRI. */
        ANNOTATION_IRI,
        /** {@code C}, {@code SC}, {@code EC}, {@code DC} - a class expression. */
        CLASS_EXPRESSION,
        /** {@code I <prop>} - an individual-valued assertion. */
        INDIVIDUAL,
        /** Something this class does not recognise, which is not the same as something wrong. */
        OTHER,
        /** An empty cell in the template row: the column is ignored by ROBOT. */
        UNUSED
    }

    /** One column of a template. */
    public static final class Column {
        private final int number;
        private final String heading;
        private final String spec;
        private final Kind kind;
        private final String property;
        private final String language;
        private final String datatype;
        private final String split;
        private final int annotationDepth;

        Column(int number, String heading, String spec, Kind kind, String property,
                String language, String datatype, String split, int annotationDepth) {
            this.number = number;
            this.heading = heading;
            this.spec = spec;
            this.kind = kind;
            this.property = property;
            this.language = language;
            this.datatype = datatype;
            this.split = split;
            this.annotationDepth = annotationDepth;
        }

        /** 1-based, as a spreadsheet counts columns and as {@code Problem} reports them. */
        public int getNumber() {
            return number;
        }

        /** The author's own heading from row one, which is how they will find the column. */
        public String getHeading() {
            return heading;
        }

        /** The template cell, verbatim. */
        public String getSpec() {
            return spec;
        }

        public Kind getKind() {
            return kind;
        }

        /** The property or expression after the keyword, or empty. Not resolved to an IRI. */
        public String getProperty() {
            return property;
        }

        /** The language tag, without the {@code @}, or empty. */
        public String getLanguage() {
            return language;
        }

        /** The datatype after {@code ^^}, or empty. */
        public String getDatatype() {
            return datatype;
        }

        /** The separator from {@code SPLIT=}, or empty when the cell holds one value. */
        public String getSplit() {
            return split;
        }

        /** How many leading {@code >} the spec had: an annotation on an annotation. */
        public int getAnnotationDepth() {
            return annotationDepth;
        }

        /**
         * Whether cells in this column name another term rather than carrying a string.
         *
         * <p>The distinction a sheet editor needs: these are the columns where a label can be
         * completed, resolved to an IRI, or found to point at nothing.
         */
        public boolean namesATerm() {
            return kind == Kind.TYPE || kind == Kind.INDIVIDUAL || kind == Kind.ANNOTATION_IRI
                    || kind == Kind.CLASS_EXPRESSION;
        }

        /** Whether cells here carry text that is kept as text. */
        public boolean holdsALiteral() {
            return kind == Kind.LABEL || kind == Kind.ANNOTATION
                    || kind == Kind.ANNOTATION_LANGUAGE || kind == Kind.ANNOTATION_TYPED;
        }

        @Override
        public String toString() {
            return "column " + number + " " + kind + (property.isEmpty() ? "" : " " + property);
        }
    }

    /** Something wrong with the template row itself, as opposed to with a cell. */
    public static final class Fault {
        private final Column column;
        private final String message;
        private final String suggestion;

        Fault(Column column, String message, String suggestion) {
            this.column = column;
            this.message = message;
            this.suggestion = suggestion;
        }

        public Column getColumn() {
            return column;
        }

        public String getMessage() {
            return message;
        }

        /** The spec the author probably meant, ready to paste over the old one. */
        public String getSuggestion() {
            return suggestion;
        }

        @Override
        public String toString() {
            return "column \"" + column.getHeading() + "\": " + message;
        }
    }

    private final List<Column> columns;
    private final List<Fault> faults;

    private TemplateColumns(List<Column> columns, List<Fault> faults) {
        this.columns = Collections.unmodifiableList(columns);
        this.faults = Collections.unmodifiableList(faults);
    }

    /**
     * Reads the template row.
     *
     * @param headings row one, the author's own headings; may be null
     * @param template row two, ROBOT's; may be null, in which case nothing is parsed
     */
    public static TemplateColumns of(List<String> headings, List<String> template) {
        List<Column> columns = new ArrayList<Column>();
        List<Fault> faults = new ArrayList<Fault>();
        if (template == null) {
            return new TemplateColumns(columns, faults);
        }
        for (int index = 0; index < template.size(); index++) {
            String heading = headings != null && index < headings.size()
                    ? headings.get(index) : "";
            Column column = parse(index + 1, heading == null ? "" : heading.trim(),
                    template.get(index));
            columns.add(column);
            faultIn(column, faults);
        }
        return new TemplateColumns(columns, faults);
    }

    /** Convenience for a whole table: rows zero and one. */
    public static TemplateColumns of(List<List<String>> rows) {
        if (rows == null || rows.size() < 2) {
            return new TemplateColumns(new ArrayList<Column>(), new ArrayList<Fault>());
        }
        return of(rows.get(0), rows.get(1));
    }

    private static Column parse(int number, String heading, String cell) {
        String spec = cell == null ? "" : cell.trim();
        if (spec.isEmpty()) {
            return new Column(number, heading, "", Kind.UNUSED, "", "", "", "", 0);
        }

        int depth = 0;
        String rest = spec;
        while (rest.startsWith(">")) {
            depth++;
            rest = rest.substring(1).trim();
        }

        String split = "";
        int splitAt = rest.indexOf("SPLIT=");
        if (splitAt >= 0) {
            split = rest.substring(splitAt + "SPLIT=".length()).trim();
            rest = rest.substring(0, splitAt).trim();
        }

        String keyword = rest;
        String remainder = "";
        int space = rest.indexOf(' ');
        if (space > 0) {
            keyword = rest.substring(0, space);
            remainder = rest.substring(space + 1).trim();
        }

        String language = "";
        String datatype = "";
        Kind kind;
        if ("ID".equals(keyword) && remainder.isEmpty()) {
            kind = Kind.ID;
        } else if ("LABEL".equals(keyword) && remainder.isEmpty()) {
            kind = Kind.LABEL;
        } else if ("TYPE".equals(keyword) && remainder.isEmpty()) {
            kind = Kind.TYPE;
        } else if ("A".equals(keyword)) {
            kind = Kind.ANNOTATION;
        } else if ("AL".equals(keyword)) {
            kind = Kind.ANNOTATION_LANGUAGE;
        } else if ("AT".equals(keyword)) {
            kind = Kind.ANNOTATION_TYPED;
        } else if ("AI".equals(keyword)) {
            kind = Kind.ANNOTATION_IRI;
        } else if ("I".equals(keyword) || "TI".equals(keyword) || "DI".equals(keyword)) {
            kind = Kind.INDIVIDUAL;
        } else if ("C".equals(keyword) || "SC".equals(keyword) || "EC".equals(keyword)
                || "DC".equals(keyword) || "DOMAIN".equals(keyword) || "RANGE".equals(keyword)) {
            // DOMAIN and RANGE are here rather than under OTHER because the MatWerk sheets use
            // them and their cells hold a class, so a sheet editor should complete them the same
            // way it completes a parent.
            kind = Kind.CLASS_EXPRESSION;
        } else {
            kind = Kind.OTHER;
        }

        // The language tag and the datatype hang off the property, and which one is legal
        // depends on the keyword - that asymmetry is the defect this class was written for, so
        // they are pulled off whatever the keyword is and judged separately in faultIn.
        String property = remainder;
        int caret = property.indexOf("^^");
        if (caret >= 0) {
            datatype = property.substring(caret + 2).trim();
            property = property.substring(0, caret).trim();
        }
        // A tag is only split off when there is one. `A rdfs:label@` with nothing after the sign
        // is left whole on purpose, because that is what robot-core does with it - the property
        // it writes onto is literally named "rdfs:label@" - and a model that tidied it away here
        // would describe a column that does not exist.
        int at = property.lastIndexOf('@');
        if (at >= 0 && at < property.length() - 1 && property.indexOf(' ') < 0) {
            language = property.substring(at + 1).trim();
            property = property.substring(0, at).trim();
        }

        return new Column(number, heading, spec, kind, property, language, datatype, split, depth);
    }

    /**
     * The one mistake that is certain, measured, and silent.
     *
     * <p>Only this. A parser that guessed at more would reject sheets that work: ROBOT accepts
     * column types this class has never heard of, and {@code OTHER} exists so they pass through.
     */
    private static void faultIn(Column column, List<Fault> faults) {
        if (column.getKind() != Kind.ANNOTATION && column.getKind() != Kind.ANNOTATION_TYPED
                && column.getKind() != Kind.ANNOTATION_IRI) {
            return;
        }

        if (!column.getLanguage().isEmpty()) {
            String corrected = "AL " + column.getProperty() + "@" + column.getLanguage()
                    + (column.getSplit().isEmpty() ? "" : " SPLIT=" + column.getSplit());
            faults.add(new Fault(column,
                    "\"" + column.getSpec() + "\" does not produce a label in "
                            + column.getLanguage() + ", or an annotation on "
                            + column.getProperty() + " at all. Only AL takes a language tag, so "
                            + "the \"@" + column.getLanguage() + "\" is read as part of the "
                            + "property name: every cell in this column is written onto a "
                            + "property called \"" + column.getProperty() + "@"
                            + column.getLanguage() + "\", which nothing defines, and the text "
                            + "loses its language tag. Write it as \"" + corrected + "\".",
                    corrected));
            return;
        }

        // The same mistake with the tag left off. Degenerate, but it fails the same silent way,
        // and a column that ends in a bare @ is a typo somebody will otherwise never find.
        if (column.getProperty().endsWith("@")) {
            String bare = column.getProperty().substring(0, column.getProperty().length() - 1);
            String corrected = "AL " + bare + "@en"
                    + (column.getSplit().isEmpty() ? "" : " SPLIT=" + column.getSplit());
            faults.add(new Fault(column,
                    "\"" + column.getSpec() + "\" ends in an @ with no language after it, so the "
                            + "property this column writes onto is called \""
                            + column.getProperty() + "\" - including the @ - and nothing defines "
                            + "it. Either drop the @, or name the language and use AL, as in \""
                            + corrected + "\".",
                    corrected));
        }
    }

    /** Every column, in order, including the unused ones so the numbering is the sheet's. */
    public List<Column> getColumns() {
        return columns;
    }

    /** Everything wrong with the template row. Empty is the normal case. */
    public List<Fault> getFaults() {
        return faults;
    }

    /** The column at a 1-based position, or null. */
    public Column at(int number) {
        return number >= 1 && number <= columns.size() ? columns.get(number - 1) : null;
    }

    /** The first column of a kind, or null - there is normally at most one ID and one LABEL. */
    public Column firstOf(Kind kind) {
        for (Column column : columns) {
            if (column.getKind() == kind) {
                return column;
            }
        }
        return null;
    }

    /** Every column whose cells name another term. */
    public List<Column> referenceColumns() {
        List<Column> found = new ArrayList<Column>();
        for (Column column : columns) {
            if (column.namesATerm()) {
                found.add(column);
            }
        }
        return found;
    }
}
