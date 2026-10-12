package de.fizkarlsruhe.ise.ontoboard.sheet;

import de.fizkarlsruhe.ise.ontoboard.odk.IdRanges;
import de.fizkarlsruhe.ise.ontoboard.odk.TermMinter;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * The identifiers new rows get, and the one rule that decides them.
 *
 * <p>A row in a ROBOT template needs an identifier before it is anything, and typing one by hand
 * is the step where a knowledge graph acquires its duplicates: somebody reads the last row, adds
 * one, and two people do that in the same week. ODK's answer is an {@code -idranges.owl} - a
 * prefix, a digit width, and a numeric block per editor - and it is worth nothing if using it
 * means leaving the spreadsheet.
 *
 * <h2>Where the number comes from</h2>
 *
 * <ul>
 *   <li><b>{@link Source#ID_RANGES}</b> - the project's own {@code -idranges.owl}, when there is
 *       one and this editor has a block in it. MatWerk's says
 *       {@code idprefix: http://purls.helmholtz-metadaten.de/mwo/MWO_}, {@code iddigits: 7} and
 *       allocates 1000-9999, 10000-19999 and 20000-29999 to three names, so a minted identifier
 *       is {@code .../MWO_0001227} - which is exactly the shape its sheets already hold. This is
 *       the one that makes two editors safe.</li>
 *   <li><b>{@link Source#SHEET_PATTERN}</b> - the shape the sheets already use: the commonest
 *       stem before the trailing digits, the same digit width, and the next free number. For a
 *       project with no ranges file this is the only honest answer, and it is the behaviour the
 *       right-click "create what this refers to" already had.</li>
 * </ul>
 *
 * <h2>Two things it will not do</h2>
 *
 * <p><b>It will not drop the padding.</b> {@code MWO_0001225} to {@code MWO_1226} was the
 * behaviour this replaces - the stem was taken as everything before the trailing digits and the
 * next number appended unpadded, so a sheet whose identifiers were seven digits wide got an
 * eighth row four digits wide. Every one of them sorts wrongly, none matches the project's
 * {@code iddigits}, and the file still builds, so nothing says so. {@link #identifierWidth()} is
 * why that cannot happen again.
 *
 * <p><b>It will not mint over something that exists.</b> Taken means every identifier in every
 * open sheet <em>and</em> every IRI in the ontology and its imports. A new term that reuses an
 * imported IRI is not a new term; it is an edit to somebody else's, made by accident.
 */
public final class IriGenerator {

    /** Where the numbers come from. */
    public enum Source {
        /** The project's {@code -idranges.owl}: prefix, digit width, and a block per editor. */
        ID_RANGES("This project's ID ranges"),
        /** The shape the sheets already use, continued. */
        SHEET_PATTERN("Continue the numbering in these sheets");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /** The source a label names, or null. */
        public static Source byLabel(String label) {
            for (Source source : values()) {
                if (source.getLabel().equals(label)) {
                    return source;
                }
            }
            return null;
        }
    }

    private final Source source;
    private final SheetBook book;
    private final Set<String> inTheOntology;
    private final IdRanges ranges;
    private final String editor;
    private final String prefix;
    private final int width;
    private final long firstNumber;
    private final long lastNumber;
    private final String refusal;

    private IriGenerator(Source source, SheetBook book, Set<String> inTheOntology,
            IdRanges ranges, String editor, String prefix, int width, long firstNumber,
            long lastNumber, String refusal) {
        this.source = source;
        this.book = book;
        this.inTheOntology = inTheOntology;
        this.ranges = ranges;
        this.editor = editor;
        this.prefix = prefix;
        this.width = width;
        this.firstNumber = firstNumber;
        this.lastNumber = lastNumber;
        this.refusal = refusal;
    }

    // ---------------------------------------------------------------- building one

    /**
     * The generator for a project: its ID ranges when they can be used, the sheets' own shape
     * otherwise.
     *
     * @param ontologyFile the ontology's own file, used only to find a sibling
     *     {@code *-idranges.owl}; null is fine and means there is none
     * @param editor who is minting, matched against the {@code allocatedto} annotations
     */
    public static IriGenerator forSheets(SheetBook book, OWLOntology ontology, File ontologyFile,
            String editor) {
        IriGenerator fromRanges = using(Source.ID_RANGES, book, ontology, ontologyFile, editor);
        return fromRanges.canMint() ? fromRanges
                : using(Source.SHEET_PATTERN, book, ontology, ontologyFile, editor);
    }

    /**
     * The generator for one named source, whether or not it can be used.
     *
     * <p>Asked for explicitly by the dialog, which offers both and says why the unusable one is
     * unusable - "no range is allocated to you" has a remedy one menu item away, and silently
     * falling back to guessing from the sheets would hide it.
     */
    public static IriGenerator using(Source source, SheetBook book, OWLOntology ontology,
            File ontologyFile, String editor) {
        Set<String> inTheOntology = everyIriIn(ontology);
        String who = editor == null || editor.trim().isEmpty() ? editorName() : editor.trim();
        if (source == Source.ID_RANGES) {
            return fromRanges(book, inTheOntology, ontologyFile, who);
        }
        return fromTheSheets(book, inTheOntology, who, null);
    }

    /**
     * The generator for one sheet: its ranges when usable, otherwise <em>that sheet's</em> own
     * numbering rather than the whole folder's.
     *
     * <p>Which is what "the shape its neighbours use" has always meant for a new row. The
     * MatWerk graph has twenty-six sheets under one namespace, and several of them number from
     * their own base - continuing the folder's highest number would put a new city in among the
     * instrument identifiers, which builds and reads as a mistake for ever afterwards.
     */
    public static IriGenerator forSheet(SheetBook book, String sheetName, OWLOntology ontology,
            File ontologyFile, String editor) {
        String who = editor == null || editor.trim().isEmpty() ? editorName() : editor.trim();
        Set<String> inTheOntology = everyIriIn(ontology);
        IriGenerator fromRanges = fromRanges(book, inTheOntology, ontologyFile, who);
        return fromRanges.canMint() ? fromRanges
                : fromTheSheets(book, inTheOntology, who, sheetName);
    }

    private static IriGenerator fromRanges(SheetBook book, Set<String> inTheOntology,
            File ontologyFile, String editor) {
        File rangesFile = TermMinter.findRangesFile(ontologyFile);
        if (rangesFile == null) {
            return refused(book, inTheOntology, editor, "This project has no -idranges.owl, so "
                    + "there are no allocated blocks to mint from. An ODK project keeps one "
                    + "beside the ontology; Project > ID ranges... can show or create it.");
        }
        IdRanges parsed;
        try {
            parsed = IdRanges.parse(new String(java.nio.file.Files.readAllBytes(
                    rangesFile.toPath()), java.nio.charset.Charset.forName("UTF-8")));
        } catch (java.io.IOException | RuntimeException unreadable) {
            return refused(book, inTheOntology, editor, rangesFile.getName()
                    + " could not be read, so its blocks cannot be used: "
                    + unreadable.getMessage());
        }
        IdRanges.Range mine = parsed.rangeFor(editor);
        if (mine == null) {
            StringBuilder who = new StringBuilder();
            for (String owner : parsed.owners()) {
                who.append(who.length() == 0 ? "" : ", ").append(owner);
            }
            return refused(book, inTheOntology, editor, "No ID range is allocated to '" + editor
                    + "' in " + rangesFile.getName() + ", and minting outside an allocated block "
                    + "is how two editors end up giving one identifier to two different things. "
                    + "Allocated so far: " + (who.length() == 0 ? "nobody" : who)
                    + ". Project > ID ranges... will allocate you one.");
        }
        return new IriGenerator(Source.ID_RANGES, book, inTheOntology, parsed, editor,
                parsed.getIdPrefix(), parsed.getIdDigits(), mine.getLower(), mine.getUpper(), "");
    }

    /**
     * The shape the sheets already use.
     *
     * <p>The commonest stem, not the longest common prefix of everything. Taking the common
     * prefix across a sheet failed on all 123 cases the real sheets offer: one identifier of a
     * different shape - a bare NFDIcore class IRI among five thousand
     * {@code .../matwerk/msekg/<digits>} - drags the prefix back to
     * {@code https://nfdi.fiz-karlsruhe.de/}, after which no remainder is all digits and there is
     * no next number to find. Grouping by the part before the trailing digits is immune to an
     * outlier, and "the shape its neighbours use" is what was wanted.
     *
     * <p>The width is the commonest width among the identifiers sharing that stem, which is what
     * keeps {@code MWO_0001226} from becoming {@code MWO_1226}.
     */
    private static IriGenerator fromTheSheets(SheetBook book, Set<String> inTheOntology,
            String editor, String onlyThisSheet) {
        Map<String, Integer> countFor = new LinkedHashMap<String, Integer>();
        Map<String, Long> highestFor = new LinkedHashMap<String, Long>();
        Map<String, Map<Integer, Integer>> widthsFor =
                new LinkedHashMap<String, Map<Integer, Integer>>();
        Set<String> readFrom = onlyThisSheet == null ? everyIdentifierIn(book)
                : identifiersIn(book, onlyThisSheet);
        if (readFrom.isEmpty() && onlyThisSheet != null) {
            // An empty sheet has no shape of its own, and refusing there would make the first
            // row of every new sheet impossible. The folder it sits in is the next best
            // evidence, and it is the same namespace.
            readFrom = everyIdentifierIn(book);
        }
        for (String each : readFrom) {
            int end = each.length();
            while (end > 0 && Character.isDigit(each.charAt(end - 1))) {
                end--;
            }
            if (end == each.length() || end == 0) {
                continue;
            }
            String stem = each.substring(0, end);
            String digits = each.substring(end);
            long value;
            try {
                value = Long.parseLong(digits);
            } catch (NumberFormatException tooBig) {
                continue;
            }
            Integer seen = countFor.get(stem);
            countFor.put(stem, seen == null ? 1 : seen + 1);
            Long best = highestFor.get(stem);
            if (best == null || value > best) {
                highestFor.put(stem, value);
            }
            Map<Integer, Integer> widths = widthsFor.get(stem);
            if (widths == null) {
                widths = new LinkedHashMap<Integer, Integer>();
                widthsFor.put(stem, widths);
            }
            Integer times = widths.get(digits.length());
            widths.put(digits.length(), times == null ? 1 : times + 1);
        }
        String stem = "";
        int most = 0;
        for (Map.Entry<String, Integer> each : countFor.entrySet()) {
            if (each.getValue() > most) {
                most = each.getValue();
                stem = each.getKey();
            }
        }
        if (stem.isEmpty()) {
            return refused(book, inTheOntology, editor, "These sheets have no identifier ending "
                    + "in a number, so there is no numbering to continue. Type the first one and "
                    + "the rest can follow it, or allocate a block in "
                    + "Project > ID ranges...");
        }
        int width = commonestKey(widthsFor.get(stem));
        return new IriGenerator(Source.SHEET_PATTERN, book, inTheOntology, null, editor, stem,
                width, highestFor.get(stem) + 1, Long.MAX_VALUE - 1, "");
    }

    private static int commonestKey(Map<Integer, Integer> counts) {
        int best = 1;
        int most = 0;
        for (Map.Entry<Integer, Integer> each : counts.entrySet()) {
            // Ties go to the wider one: padding that is too wide is visible and wrong in one
            // place, padding that is too narrow is wrong in every future row.
            if (each.getValue() > most
                    || (each.getValue() == most && each.getKey() > best)) {
                most = each.getValue();
                best = each.getKey();
            }
        }
        return best;
    }

    private static IriGenerator refused(SheetBook book, Set<String> inTheOntology, String editor,
            String why) {
        return new IriGenerator(Source.SHEET_PATTERN, book, inTheOntology, null, editor, "", 0, 0,
                0, why);
    }

    /**
     * Who is minting, by the same rule the canvas uses.
     *
     * <p>The collaboration display name when one is configured, since that is the name a team has
     * already agreed on and the one their ranges are likely allocated to; otherwise the OS
     * account, which is what the scaffold allocates the first range to.
     */
    public static String editorName() {
        try {
            String configured = de.fizkarlsruhe.ise.ontoboard.collab.CollabSettingsStore.load()
                    .getDisplayName();
            if (configured != null && !configured.trim().isEmpty()) {
                return configured.trim();
            }
        } catch (RuntimeException noPreferences) {
            // No preferences store - a test, or a first run. The OS account is the fallback the
            // scaffold itself assumes.
        }
        String account = System.getProperty("user.name", "");
        return account.trim().isEmpty() ? "FirstEditor" : account.trim();
    }

    // ---------------------------------------------------------------- what it is

    public Source getSource() {
        return source;
    }

    /** Whether identifiers can be minted at all; {@link #getRefusal()} says why not. */
    public boolean canMint() {
        return refusal.isEmpty();
    }

    /** Why nothing can be minted, with what to do about it, or empty. */
    public String getRefusal() {
        return refusal;
    }

    /** What every identifier starts with: a full IRI stem, or a CURIE stem. */
    public String getPrefix() {
        return prefix;
    }

    /** How many digits the number is padded to. */
    public int identifierWidth() {
        return width;
    }

    /** The editor the ranges are matched against. */
    public String getEditor() {
        return editor;
    }

    /** One line for the dialog: where the numbers come from and what the next one is. */
    public String describe() {
        if (!canMint()) {
            return refusal;
        }
        List<String> next = mint(1);
        if (source == Source.ID_RANGES) {
            IdRanges.Range mine = ranges.rangeFor(editor);
            return "From " + prefix + ", " + width + " digits, block " + mine.getLower() + " to "
                    + mine.getUpper() + " allocated to " + editor + ". Next: " + next.get(0);
        }
        return "Continuing " + prefix + " at " + width + " digits, from the sheets themselves. "
                + "Next: " + next.get(0);
    }

    // ---------------------------------------------------------------- minting

    /**
     * {@code count} identifiers nothing has used, in order.
     *
     * <p>Pure: nothing is written and calling it twice gives the same answers, so what a dialog
     * previews is what gets written. Taken is recomputed each call, which is what makes filling
     * a column one row at a time correct.
     *
     * @throws IllegalStateException when {@link #canMint()} is false, because an identifier
     *     invented in spite of the refusal is the collision the refusal exists to prevent
     */
    public List<String> mint(int count) {
        if (!canMint()) {
            throw new IllegalStateException(refusal);
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, got " + count);
        }
        Set<String> taken = new LinkedHashSet<String>(inTheOntology);
        taken.addAll(everyIdentifierIn(book));
        List<String> minted = new ArrayList<String>();
        for (long candidate = firstNumber; candidate <= lastNumber; candidate++) {
            String iri = iriFor(candidate);
            if (!taken.contains(iri)) {
                minted.add(iri);
                if (minted.size() == count) {
                    return minted;
                }
            }
        }
        throw new IllegalStateException("The block " + firstNumber + " to " + lastNumber
                + " allocated to " + editor + " has no room for " + count + " more identifier"
                + (count == 1 ? "" : "s") + ". Allocate a further block in "
                + "Project > ID ranges...");
    }

    /** The identifier for a number, padded to {@link #identifierWidth()}. */
    public String iriFor(long number) {
        StringBuilder digits = new StringBuilder(Long.toString(number));
        while (digits.length() < width) {
            digits.insert(0, '0');
        }
        return prefix + digits;
    }

    // ---------------------------------------------------------------- filling a sheet

    /**
     * The rows of a sheet that have content but no identifier.
     *
     * <p>Content, not emptiness: a trailing blank row is not a row waiting for an identifier, and
     * minting one for it would put a declaration of nothing into the graph. A row counts when any
     * cell outside the identifier column has something in it.
     */
    public static List<Integer> rowsNeedingAnIdentifier(SheetBook book, String sheetName) {
        List<Integer> rows = new ArrayList<Integer>();
        SheetBook.Sheet sheet = book.get(sheetName);
        if (sheet == null) {
            return rows;
        }
        TemplateColumns.Column id = sheet.getColumns().firstOf(TemplateColumns.Kind.ID);
        if (id == null) {
            return rows;
        }
        for (int row = SheetBook.FIRST_DATA_ROW; row <= sheet.getLastRow(); row++) {
            if (!book.cell(sheetName, row, id.getNumber()).trim().isEmpty()) {
                continue;
            }
            boolean hasContent = false;
            for (int column = 1; column <= book.width(sheetName); column++) {
                if (column != id.getNumber()
                        && !book.cell(sheetName, row, column).trim().isEmpty()) {
                    hasContent = true;
                    break;
                }
            }
            if (hasContent) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Mints an identifier into every row of one sheet that needs one.
     *
     * <p>Through the book, so it is one undo step per cell and nothing reaches the file until
     * the sheet is saved. Rows are filled in order, which keeps the numbers ascending down the
     * sheet - the opposite looks like a mistake even when it is not.
     *
     * @return how many were written
     */
    public int fillBlanks(String sheetName) {
        List<Integer> rows = rowsNeedingAnIdentifier(book, sheetName);
        if (rows.isEmpty() || !canMint()) {
            return 0;
        }
        TemplateColumns.Column id =
                book.get(sheetName).getColumns().firstOf(TemplateColumns.Kind.ID);
        List<String> minted = mint(rows.size());
        for (int at = 0; at < rows.size(); at++) {
            book.setCell(sheetName, rows.get(at), id.getNumber(), minted.get(at));
        }
        return rows.size();
    }

    // ---------------------------------------------------------------- what is taken

    /** Every identifier in one sheet. */
    static Set<String> identifiersIn(SheetBook book, String sheetName) {
        Set<String> taken = new LinkedHashSet<String>();
        SheetBook.Sheet sheet = book == null ? null : book.get(sheetName);
        TemplateColumns.Column id =
                sheet == null ? null : sheet.getColumns().firstOf(TemplateColumns.Kind.ID);
        if (id == null) {
            return taken;
        }
        for (int row = SheetBook.FIRST_DATA_ROW; row <= sheet.getLastRow(); row++) {
            String value = book.cell(sheetName, row, id.getNumber()).trim();
            if (!value.isEmpty()) {
                taken.add(value);
            }
        }
        return taken;
    }

    /** Every identifier in every open sheet. */
    static Set<String> everyIdentifierIn(SheetBook book) {
        Set<String> taken = new LinkedHashSet<String>();
        if (book == null) {
            return taken;
        }
        for (SheetBook.Sheet sheet : book.getSheets()) {
            TemplateColumns.Column id = sheet.getColumns().firstOf(TemplateColumns.Kind.ID);
            if (id == null) {
                continue;
            }
            for (int row = SheetBook.FIRST_DATA_ROW; row <= sheet.getLastRow(); row++) {
                String value = book.cell(sheet.getName(), row, id.getNumber()).trim();
                if (!value.isEmpty()) {
                    taken.add(value);
                }
            }
        }
        return taken;
    }

    /**
     * Every IRI the ontology knows, imports included.
     *
     * <p>Imports included deliberately. Minting an identifier that an imported ontology already
     * uses does not create a term - it silently adds axioms to somebody else's, and the build
     * succeeds.
     */
    static Set<String> everyIriIn(OWLOntology ontology) {
        Set<String> iris = new LinkedHashSet<String>();
        if (ontology == null) {
            return iris;
        }
        try {
            for (OWLEntity entity : ontology.getSignature(Imports.INCLUDED)) {
                iris.add(entity.getIRI().toString());
            }
        } catch (RuntimeException | LinkageError cannotRead) {
            // An ontology whose imports will not resolve still has its own signature; failing to
            // read it must not stop somebody minting an identifier.
            for (OWLEntity entity : ontology.getSignature()) {
                iris.add(entity.getIRI().toString());
            }
        }
        return iris;
    }
}
