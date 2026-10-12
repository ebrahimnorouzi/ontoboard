package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Whether ROBOT will accept a template row, in ROBOT's own terms.
 *
 * <p>Separate from {@link TemplateColumns} on purpose, and the distinction is the whole point of
 * the class. {@code TemplateColumns} answers <em>what does this column mean</em> and is written
 * never to reject: an unrecognised spec is {@link TemplateColumns.Kind#OTHER}, because this
 * plugin does not get to decide what ROBOT understands. This answers a different question -
 * <em>will the sheet build at all</em> - and that one has a short, exact answer that belongs to
 * robot-core.
 *
 * <p>Using {@code Kind != OTHER} for both would be wrong in both directions. It accepts four
 * strings robot-core refuses outright ({@code A} with no property, {@code > A rdfs:label},
 * {@code >>>A x}, {@code >I <prop>}), and it calls sixteen perfectly legal forms OTHER
 * ({@code AP}, {@code CHARACTERISTIC}, {@code SP}, {@code PI}, {@code SI} and the rest).
 *
 * <h2>Why this matters now</h2>
 *
 * <p>The template row became editable in 1.118.0. Before that nothing in this plugin could write
 * it, so a spec ROBOT refuses could only arrive from a spreadsheet program. Now one keystroke can
 * produce it - and the failure is not a bad column, it is the <b>whole sheet</b>:
 * {@code Template.addTable} throws {@code ColumnException} and nothing at all is built. An
 * editable cell without this check would be a new way to destroy a day's work silently.
 *
 * <h2>Transcribed, not interpreted</h2>
 *
 * <p>{@link #isAccepted} is a literal transcription of
 * {@code TemplateHelper.validateTemplateString} in robot-core 1.9.8 - eight {@code equals} and
 * seven {@code matches}, in that order - read out of the constant pool of the jar this plugin
 * embeds rather than from the documentation, which omits {@code II}, {@code PI} and {@code >>}
 * and describes {@code CI} as legacy. The regexes below are the same strings, character for
 * character. If robot-core is upgraded, this is the one place to re-read.
 *
 * <p>The documentation is not the authority here and neither is this class: the authority is the
 * jar, and {@code TemplateSpecTest} pins these against it.
 */
public final class TemplateSpec {

    /**
     * The eight forms robot-core matches with {@code String.equals}.
     *
     * <p>{@code equals}, so none of them takes {@code SPLIT=} or {@code %}. Adding either breaks
     * the sheet, and that is easy to do by accident on {@code DOMAIN} and {@code RANGE}, whose
     * neighbours in a real sheet all carry one.
     */
    private static final List<String> EXACTLY = Collections.unmodifiableList(Arrays.asList(
            "ID", "LABEL", "CLASS_TYPE", "PROPERTY_TYPE", "INDIVIDUAL_TYPE", "DOMAIN", "RANGE",
            "AP"));

    /** The seven regexes, verbatim from {@code TemplateHelper.validateTemplateString}. */
    private static final List<String> PATTERNS = Collections.unmodifiableList(Arrays.asList(
            "^TYPE( SPLIT=.+)?$",
            "^CHARACTERISTIC( SPLIT=.+)?$",
            "^>{0,2}A[LTI]? .*",
            "^>?(C .*|CI.?|[SED]C .*)",
            "^(P .*|PI.?|[SEDI]P .*)",
            "^AP SPLIT=.+$",
            "^(I .*|II.?|[TSD]I .*)"));

    private TemplateSpec() {
    }

    /**
     * Whether a template cell is one robot-core will accept.
     *
     * <p>A blank cell is accepted here because {@code Template.addTable} skips an empty template
     * string <em>before</em> it validates anything - clearing the cell is the supported way to
     * turn a column off, and the column's heading and data are then ignored whatever they are.
     * Passing a blank string to {@code validateTemplateString} itself returns false, which is why
     * the order matters and why this is stated rather than inherited.
     */
    public static boolean isAccepted(String spec) {
        String trimmed = spec == null ? "" : spec.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (EXACTLY.contains(trimmed)) {
            return true;
        }
        for (String pattern : PATTERNS) {
            if (trimmed.matches(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What ROBOT would say about this cell, or empty when it would say nothing.
     *
     * <p>ROBOT's own wording, because somebody who has seen the build fail should be able to
     * match the two messages without translating between them.
     */
    public static String refusal(String spec) {
        if (isAccepted(spec)) {
            return "";
        }
        return "UNKNOWN TEMPLATE ERROR could not interpret template string \"" + spec.trim()
                + "\". ROBOT refuses the whole sheet over this, not just the column.";
    }

    // ---------------------------------------------------------------- the whole header

    /** How badly wrong something is. */
    public enum Severity {
        /** ROBOT refuses the sheet. Nothing is built. */
        BREAKS_THE_SHEET,
        /** ROBOT accepts it and then quietly produces nothing from the column. */
        DOES_NOTHING
    }

    /** Something wrong with the header rows. */
    public static final class Problem {
        private final int column;
        private final Severity severity;
        private final String message;
        private final String suggestion;

        Problem(int column, Severity severity, String message, String suggestion) {
            this.column = column;
            this.severity = severity;
            this.message = message;
            this.suggestion = suggestion;
        }

        /** 1-based, or 0 when it is about the header as a whole. */
        public int getColumn() {
            return column;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }

        /** What to put in the cell instead, or empty when there is no one answer. */
        public String getSuggestion() {
            return suggestion;
        }

        @Override
        public String toString() {
            return (column > 0 ? "column " + column + ": " : "") + message;
        }
    }

    /**
     * Everything wrong with a pair of header rows.
     *
     * <p>Whole-header rather than per-cell, because three of robot-core's rules span columns and
     * a per-cell check cannot see any of them: a heading is required beside a template string, an
     * axiom-annotation column has to sit next to the column it annotates, and a sheet needs an
     * {@code ID} or a {@code LABEL} somewhere. Re-running this on every keystroke is cheap - it
     * is string work over at most a few dozen columns.
     *
     * @param headings row 1
     * @param specs row 2
     */
    /**
     * Everything wrong with a sheet's header rows, from both of the things that can judge them.
     *
     * <p>Two different questions, answered in one list because an editor showing them has to show
     * both. {@link #check(List, List)} asks whether ROBOT will accept the row at all;
     * {@link TemplateColumns#getFaults()} asks what ROBOT quietly builds from a row it does
     * accept - which is how {@code A rdfs:label@en} gets reported, since robot-core is perfectly
     * happy with it and then makes an annotation property whose IRI ends {@code #label@en}.
     *
     * @param rows the whole sheet; only the first two are read
     */
    public static List<Problem> check(List<List<String>> rows) {
        if (rows == null || rows.size() < 2) {
            return new ArrayList<Problem>();
        }
        List<Problem> problems = new ArrayList<Problem>(check(rows.get(0), rows.get(1)));
        for (TemplateColumns.Fault fault : TemplateColumns.of(rows).getFaults()) {
            problems.add(new Problem(fault.getColumn().getNumber(), Severity.DOES_NOTHING,
                    fault.getMessage(), fault.getSuggestion()));
        }
        return problems;
    }

    public static List<Problem> check(List<String> headings, List<String> specs) {
        List<Problem> problems = new ArrayList<Problem>();
        if (specs == null) {
            return problems;
        }
        boolean identified = false;
        for (int at = 0; at < specs.size(); at++) {
            String spec = at < specs.size() && specs.get(at) != null ? specs.get(at).trim() : "";
            if (spec.isEmpty()) {
                continue;
            }
            int column = at + 1;
            if ("ID".equals(spec) || "LABEL".equals(spec)) {
                identified = true;
            }
            if (!isAccepted(spec)) {
                problems.add(new Problem(column, Severity.BREAKS_THE_SHEET, refusal(spec),
                        suggestionFor(spec)));
                continue;
            }
            // A heading is not decoration: Template.addTable throws COLUMN MISMATCH ERROR when a
            // column has a template string and no heading, and an IndexOutOfBounds - caught and
            // turned into the same error - when row 1 is shorter than row 2.
            String heading = headings != null && at < headings.size() && headings.get(at) != null
                    ? headings.get(at).trim() : "";
            if (heading.isEmpty()) {
                problems.add(new Problem(column, Severity.BREAKS_THE_SHEET,
                        "COLUMN MISMATCH ERROR the template string in column " + column
                                + " must have a corresponding header. ROBOT refuses the whole "
                                + "sheet over this; clear the template string instead if you "
                                + "meant to turn the column off.", ""));
            }
            problems.addAll(whatThisColumnQuietlyDoesNotDo(column, spec, specs));
        }
        if (!identified && anyNonBlank(specs)) {
            problems.add(new Problem(0, Severity.BREAKS_THE_SHEET,
                    "Template row must include an \"ID\" or \"LABEL\" column, which is how ROBOT "
                            + "knows what each row is about. Without one it refuses the sheet.",
                    "ID"));
        }
        return problems;
    }

    private static boolean anyNonBlank(List<String> specs) {
        for (String spec : specs) {
            if (spec != null && !spec.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The forms robot-core accepts and then does nothing with.
     *
     * <p>Each of these is legal, so the build succeeds and the column disappears. That is a worse
     * failure than a refusal, which is why they are worth saying out loud.
     */
    private static List<Problem> whatThisColumnQuietlyDoesNotDo(int column, String spec,
            List<String> specs) {
        List<Problem> problems = new ArrayList<Problem>();
        if (spec.equals("II") || spec.startsWith("II ")) {
            // Accepted by ^(I .*|II.?|[TSD]I .*) and then dropped: addIndividualAxioms reaches
            // it through startsWith("I") and the named-individual branch tests startsWith("I "),
            // which "II" fails. No axiom, no error, undocumented.
            problems.add(new Problem(column, Severity.DOES_NOTHING,
                    "ROBOT accepts \"II\" and then produces nothing from this column - no axiom "
                            + "and no error. It is undocumented. Use \"SI\" for same-individual "
                            + "or \"I <property>\" for an assertion.", ""));
        }
        if (spec.startsWith(">") && !annotationFamily(spec)) {
            // ^>?(C .*|CI.?|[SED]C .*) lets >SC through the validator, and then getAnnotations
            // recognises only the A family and returns an empty set.
            problems.add(new Problem(column, Severity.DOES_NOTHING,
                    "An axiom annotation only works in front of A, AL, AT or AI. ROBOT accepts "
                            + "\"" + spec + "\" and then discards the column.", ""));
        }
        if (spec.startsWith(">")) {
            int depth = 0;
            while (depth < spec.length() && spec.charAt(depth) == '>') {
                depth++;
            }
            if (depth >= 2 && !aLevelOneAnnotationPrecedes(column, specs)) {
                // Template.maybeGetAxiomAnnotations does map.get(-1).add(...) with no null
                // check, so this is a NullPointerException out of the build rather than a
                // message.
                problems.add(new Problem(column, Severity.BREAKS_THE_SHEET,
                        "\">>\" annotates the annotation in the column before it, and there is "
                                + "no \">\" column before this one. ROBOT fails with a bare "
                                + "NullPointerException that names nothing.", ""));
            }
            if (!annotatesSomething(column, specs)) {
                problems.add(new Problem(column, Severity.DOES_NOTHING,
                        "An axiom-annotation column has to follow the column it annotates, with "
                                + "only blank or other \">\" columns in between. This one "
                                + "follows nothing, so ROBOT produces no annotation and says "
                                + "nothing.", ""));
            }
        }
        return problems;
    }

    /** Whether a {@code >}-prefixed spec is one of the four that actually annotate. */
    private static boolean annotationFamily(String spec) {
        String rest = spec;
        while (rest.startsWith(">")) {
            rest = rest.substring(1);
        }
        return rest.matches("^A[LTI]? .*");
    }

    /** The nearest earlier column that is neither blank nor another {@code >} column. */
    private static boolean annotatesSomething(int column, List<String> specs) {
        for (int at = column - 2; at >= 0; at--) {
            String earlier = specs.get(at) == null ? "" : specs.get(at).trim();
            if (earlier.isEmpty() || earlier.startsWith(">")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean aLevelOneAnnotationPrecedes(int column, List<String> specs) {
        for (int at = column - 2; at >= 0; at--) {
            String earlier = specs.get(at) == null ? "" : specs.get(at).trim();
            if (earlier.isEmpty()) {
                continue;
            }
            if (!earlier.startsWith(">")) {
                return false;
            }
            if (!earlier.startsWith(">>")) {
                return true;
            }
        }
        return false;
    }

    /** The nearest legal spelling of something ROBOT refused, or empty. */
    private static String suggestionFor(String spec) {
        String trimmed = spec.trim();
        // The commonest way to write a bare keyword wrongly is to add an argument to one that
        // takes none. Those seven are matched with equals, so the argument is the whole fault.
        for (String exact : EXACTLY) {
            if (trimmed.startsWith(exact + " ") || trimmed.startsWith(exact + "\t")) {
                return exact;
            }
        }
        if (trimmed.equals("A") || trimmed.equals("AL") || trimmed.equals("AT")
                || trimmed.equals("AI") || trimmed.equals("I") || trimmed.equals("C")) {
            return trimmed + " <property>";
        }
        if (trimmed.startsWith("> ")) {
            return ">" + trimmed.substring(2);
        }
        if (trimmed.matches("^>{3,}.*")) {
            return trimmed.replaceAll("^>+", ">>");
        }
        return "";
    }

    // ---------------------------------------------------------------- what to offer

    /** One legal template string, for somebody choosing rather than remembering. */
    public static final class Form {
        private final String spec;
        private final String meaning;
        private final boolean takesSplit;
        private final String group;

        Form(String group, String spec, String meaning, boolean takesSplit) {
            this.group = group;
            this.spec = spec;
            this.meaning = meaning;
            this.takesSplit = takesSplit;
        }

        /** The template string, with {@code <...>} where something has to be filled in. */
        public String getSpec() {
            return spec;
        }

        /** One line saying what the column would then do. */
        public String getMeaning() {
            return meaning;
        }

        /** Whether {@code SPLIT=} may be appended. On the others it breaks the sheet. */
        public boolean takesSplit() {
            return takesSplit;
        }

        /** Which part of the menu it belongs under. */
        public String getGroup() {
            return group;
        }

        /** Whether anything still has to be filled in before it is a real spec. */
        public boolean needsFillingIn() {
            return spec.indexOf('<') >= 0;
        }

        @Override
        public String toString() {
            return spec;
        }
    }

    /**
     * The template strings worth offering, in the order worth offering them.
     *
     * <p>Ordered by what people actually write. Measured over both corpora this plugin has: the
     * 26 live MatWerk tabs use {@code I <property>} 164 times, {@code ID} and {@code TYPE} 26
     * each, {@code A <property>} 24, {@code AT} 3 - and <em>nothing</em> else. The hand-written
     * templates beside them use an almost disjoint set: {@code LABEL}, {@code SC %},
     * {@code AL rdfs:label@en}, {@code AT <prop>^^<datatype>} and {@code >A <prop>}. A list drawn
     * from either corpus alone would be missing half of what gets typed, so this is drawn from
     * robot-core's own whitelist and then ordered by those counts.
     *
     * <p>{@code II} is deliberately absent: it is legal, undocumented, and produces nothing.
     * {@code PI} is absent for the same reason on a data property. Both still parse and are left
     * alone where a sheet already has them.
     */
    public static List<Form> forms() {
        List<Form> forms = new ArrayList<Form>();
        forms.add(new Form("The row itself", "ID",
                "The row's IRI. Every sheet needs this or LABEL.", false));
        forms.add(new Form("The row itself", "LABEL",
                "An rdfs:label, untagged. Also identifies the row.", false));
        forms.add(new Form("The row itself", "TYPE",
                "What the row is - a class by default, or whatever CLASS_TYPE says.", true));

        forms.add(new Form("Annotations", "A <property>",
                "A plain literal annotation, as xsd:string.", true));
        forms.add(new Form("Annotations", "AL <property>@<language>",
                "A language-tagged annotation. The @tag belongs here, never on A.", true));
        forms.add(new Form("Annotations", "AT <property>^^<datatype>",
                "A typed literal annotation, e.g. ^^xsd:date.", true));
        forms.add(new Form("Annotations", "AI <property>",
                "An annotation whose value is an IRI.", true));
        forms.add(new Form("Annotations", "AP",
                "The cell holds both the property and the value.", true));

        forms.add(new Form("About other terms", "I <property>",
                "An assertion about this individual. The cell names another term, or holds a "
                        + "literal when the property is a data property.", true));
        forms.add(new Form("About other terms", "SC %",
                "A subclass-of expression; % is where the cell's value goes.", true));
        forms.add(new Form("About other terms", "EC %", "An equivalent-class expression.", true));
        forms.add(new Form("About other terms", "DC %", "A disjoint-class expression.", true));
        forms.add(new Form("About other terms", "SI <expression>",
                "The cell names an individual this one is the same as.", true));
        forms.add(new Form("About other terms", "DI <expression>",
                "The cell names an individual this one differs from.", true));
        forms.add(new Form("About other terms", "TI <expression>",
                "A class expression this individual is an instance of.", true));

        forms.add(new Form("Properties", "SP <expression>", "A sub-property axiom.", true));
        forms.add(new Form("Properties", "EP <expression>",
                "An equivalent-property axiom.", true));
        forms.add(new Form("Properties", "DP <expression>", "A disjoint-property axiom.", true));
        forms.add(new Form("Properties", "IP <expression>", "An inverse-property axiom.", true));
        forms.add(new Form("Properties", "DOMAIN",
                "The property's domain. Bare - SPLIT= or % here breaks the sheet.", false));
        forms.add(new Form("Properties", "RANGE",
                "The property's range. Bare, and the cell's language depends on the row's type.",
                false));
        forms.add(new Form("Properties", "CHARACTERISTIC",
                "functional, transitive, symmetric and the rest.", true));

        forms.add(new Form("How the column is read", "CLASS_TYPE",
                "Per row: subclass, equivalent or disjoint.", false));
        forms.add(new Form("How the column is read", "PROPERTY_TYPE",
                "Per row: subproperty, equivalent, disjoint or inverse.", false));
        forms.add(new Form("How the column is read", "INDIVIDUAL_TYPE",
                "Per row: named, same or different.", false));

        forms.add(new Form("Annotating an axiom", ">A <property>",
                "Annotates the axiom the column before it makes.", true));
        forms.add(new Form("Annotating an axiom", ">AL <property>@<language>",
                "The same, language-tagged.", true));
        forms.add(new Form("Annotating an axiom", ">AT <property>^^<datatype>",
                "The same, with a datatype.", true));
        forms.add(new Form("Annotating an axiom", ">AI <property>",
                "The same, IRI-valued.", true));
        forms.add(new Form("Annotating an axiom", ">>A <property>",
                "Annotates the annotation before it. Two levels is ROBOT's limit.", true));

        forms.add(new Form("Nothing", "",
                "An empty template string turns the column off. Its heading and data are kept "
                        + "in the file and ignored by the build.", false));
        return forms;
    }

    /**
     * {@code SPLIT=} as ROBOT reads it, for a help line.
     *
     * <p>Worth saying because it is not what it looks like: the separator is everything after the
     * first {@code SPLIT=}, trimmed, and it is used as a literal string through
     * {@code Pattern.quote} - so {@code SPLIT=|,} means the two characters "|," and not "| or ,".
     * The one special case is the two characters {@code \n}, which mean a real newline.
     */
    public static String aboutSplit() {
        return "SPLIT= goes last and takes everything after it as the separator, literally - "
                + "SPLIT=| splits on a bar, SPLIT=|, splits on the two characters \"|,\" rather "
                + "than on either of them. SPLIT=\\n splits on a newline. It is legal on A, AL, "
                + "AT, AI, AP, TYPE, CHARACTERISTIC and the expression columns, and breaks the "
                + "sheet on ID, LABEL, DOMAIN, RANGE and the three _TYPE columns.";
    }
}
