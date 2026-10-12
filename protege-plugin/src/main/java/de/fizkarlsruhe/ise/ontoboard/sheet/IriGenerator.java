package de.fizkarlsruhe.ise.ontoboard.sheet;

import de.fizkarlsruhe.ise.ontoboard.odk.IdRanges;
import de.fizkarlsruhe.ise.ontoboard.odk.TermMinter;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /**
     * How an identifier is formed.
     *
     * <p>Six, and the list is measured rather than imagined. Two were here already. The
     * timestamp ones exist because <b>a timestamp is the incumbent scheme in the knowledge graph
     * this was built against</b>: 8,406 of the 8,471 identifiers in the live MatWerk workbook are
     * a 13-digit epoch-millisecond base followed by a row counter. The UUID ones exist because
     * that project's own harvester already mints UUIDs into the same namespace. The label one is
     * last because it is the one that can quietly merge two different things.
     */
    public enum Source {
        /** The project's {@code -idranges.owl}: prefix, digit width, and a block per editor. */
        ID_RANGES("A block from this project's ID ranges", true),
        /** The shape the sheets already use, continued. */
        SHEET_PATTERN("Continue the numbering in these sheets", true),
        /** {@code <stem>1760258400000001}: epoch milliseconds, then a counter. */
        TIMESTAMP("A timestamp", false),
        /** The same, with who is editing in it. */
        TIMESTAMP_AND_EDITOR("A timestamp and who is editing", false),
        /** A random UUID. Unique without coordination, and impossible to re-derive. */
        UUID_RANDOM("A random UUID", false),
        /** A UUID derived from the row's label, so the same label always gives the same one. */
        UUID_FROM_LABEL("A UUID derived from the label", false),
        /** The label itself, made safe for an IRI. Readable, and the easiest to collide. */
        LABEL_SLUG("The label, as a readable identifier", false);

        private final String label;
        private final boolean numeric;

        Source(String label, boolean numeric) {
            this.label = label;
            this.numeric = numeric;
        }

        public String getLabel() {
            return label;
        }

        /** Whether the local part is a number this class counts upwards. */
        public boolean isNumeric() {
            return numeric;
        }

        /** Whether it needs the row's label, and so cannot answer {@link #mint(int)}. */
        public boolean needsALabel() {
            return this == UUID_FROM_LABEL || this == LABEL_SLUG;
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

    /**
     * The parts of an identifier that cannot be reproduced, fixed once per generator.
     *
     * <p>This is what lets a timestamp or a UUID strategy keep the promise {@link #mint(int)}
     * makes: <em>calling it twice gives the same answers, so what a dialog previews is what gets
     * written</em>. The clock is read when the generator is built, not when a value is formed, so
     * a preview and the write that follows it agree. Reading the clock inside {@code mint} would
     * have made the dialog show a value that is never written.
     *
     * <p>It is also the project's own rule about clocks, stated in {@code Provenance.today()}:
     * every method that writes a date takes it as a parameter, so the ones under test can pass
     * their own. {@link #at} is that seam, and it makes a run reproducible as well as testable.
     */
    public static final class Moment {

        private final long millis;
        private final boolean real;
        private final List<String> uuids = new ArrayList<String>();

        private Moment(long millis, boolean real) {
            this.millis = millis;
            this.real = real;
        }

        /** Now, with real random UUIDs. */
        public static Moment now() {
            return new Moment(System.currentTimeMillis(), true);
        }

        /**
         * A fixed instant, with UUIDs derived from it.
         *
         * <p>Reproducible on purpose: the same instant yields the same identifiers, which is
         * what a test needs and what re-running a fill after an undo should do.
         */
        public static Moment at(long millis) {
            return new Moment(millis, false);
        }

        /** The instant, as epoch milliseconds. */
        public long millis() {
            return millis;
        }

        /**
         * The {@code index}-th UUID of this moment, the same every time it is asked.
         *
         * <p>Memoised rather than regenerated, which is what keeps a generator idempotent while
         * still producing genuinely random values in a real run.
         */
        public String uuid(int index) {
            while (uuids.size() <= index) {
                uuids.add(real ? java.util.UUID.randomUUID().toString()
                        : new java.util.UUID(millis, uuids.size()).toString());
            }
            return uuids.get(index);
        }

        /**
         * A UUID derived from {@code key}, the same for the same key for ever.
         *
         * <p>Version 5, name-based, SHA-1, under a fixed namespace - the scheme the MatWerk
         * harvester already uses to mint into this very namespace
         * ({@code uuid5(12345678-1234-5678-1234-567812345678, key)}), so a row filled in by hand
         * and the same row harvested later agree instead of becoming two things.
         */
        public String uuidFor(String key) {
            return nameBased(NAMESPACE, key == null ? "" : key);
        }

        /** The namespace the MatWerk harvester uses, so the two agree. */
        private static final java.util.UUID NAMESPACE =
                java.util.UUID.fromString("12345678-1234-5678-1234-567812345678");

        /**
         * RFC 4122 version 5: SHA-1 of the namespace bytes followed by the name, with the
         * version and variant bits set.
         *
         * <p>Written out because Java has no {@code UUID.nameUUIDFromBytes} for version 5 - its
         * only name-based helper is version 3, which is MD5 and a different value.
         */
        private static String nameBased(java.util.UUID namespace, String name) {
            try {
                java.security.MessageDigest sha1 =
                        java.security.MessageDigest.getInstance("SHA-1");
                byte[] prefix = new byte[16];
                long high = namespace.getMostSignificantBits();
                long low = namespace.getLeastSignificantBits();
                for (int at = 0; at < 8; at++) {
                    prefix[at] = (byte) (high >>> (8 * (7 - at)));
                    prefix[at + 8] = (byte) (low >>> (8 * (7 - at)));
                }
                sha1.update(prefix);
                sha1.update(name.getBytes("UTF-8"));
                byte[] digest = sha1.digest();
                digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
                digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
                long most = 0;
                long least = 0;
                for (int at = 0; at < 8; at++) {
                    most = (most << 8) | (digest[at] & 0xff);
                    least = (least << 8) | (digest[at + 8] & 0xff);
                }
                return new java.util.UUID(most, least).toString();
            } catch (java.security.NoSuchAlgorithmException | java.io.UnsupportedEncodingException
                    impossible) {
                throw new IllegalStateException("SHA-1 and UTF-8 are required of every JRE",
                        impossible);
            }
        }

        /**
         * The instant as {@code yyyyMMddTHHmmss} in UTC.
         *
         * <p>UTC and {@code Locale.ROOT}, because an identifier that depends on the machine that
         * wrote it is not an identifier. No colons: they are legal in an IRI path and they read
         * as a CURIE separator to everything that handles one.
         */
        public String stamp() {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.ROOT);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return format.format(new java.util.Date(millis));
        }
    }

    /**
     * How many spellings to try before giving up on one row.
     *
     * <p>Bounded because the candidates are no longer monotonic. A numeric source cannot repeat
     * itself, so it needed no limit; a label slug can collide for ever against a sheet that
     * already holds the same label, and an unbounded loop there is a frozen Protege rather than
     * a message.
     */
    private static final int MOST_ATTEMPTS = 500;

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
    private final Moment moment;

    private IriGenerator(Source source, SheetBook book, Set<String> inTheOntology,
            IdRanges ranges, String editor, String prefix, int width, long firstNumber,
            long lastNumber, String refusal, Moment moment) {
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
        this.moment = moment == null ? Moment.now() : moment;
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

    /** Which source to offer first for a sheet, given what its identifiers already look like. */
    public static Source preferredFor(SheetBook book, String sheetName, File ontologyFile) {
        if (TermMinter.findRangesFile(ontologyFile) != null) {
            return Source.ID_RANGES;
        }
        return looksLikeTimestamps(book, sheetName) ? Source.TIMESTAMP : Source.SHEET_PATTERN;
    }

    /**
     * Whether a sheet's identifiers are timestamps rather than a numbering.
     *
     * <p>They very often are, and it changes the right answer completely. In the live MatWerk
     * workbook 8,406 of 8,471 identifiers are a 13-digit epoch-millisecond base plus a counter,
     * and for those "continue the numbering" is not continuing anything: the numerically largest
     * value is an <em>older</em> row than the lexically largest on 22 of the 26 tabs, so
     * highest+1 mints into the middle of the range and produces a string that reads as a date it
     * was not made on.
     *
     * <p>Recognised by the shape the data actually has: a local part of 13 to 17 digits whose
     * first 13 are a plausible recent instant. Three quarters of the sheet has to look like that
     * before the sheet does.
     */
    public static boolean looksLikeTimestamps(SheetBook book, String sheetName) {
        Set<String> identifiers = sheetName == null ? everyIdentifierIn(book)
                : identifiersIn(book, sheetName);
        if (identifiers.size() < 4) {
            return false;
        }
        int timestamped = 0;
        for (String each : identifiers) {
            int end = each.length();
            while (end > 0 && Character.isDigit(each.charAt(end - 1))) {
                end--;
            }
            String digits = each.substring(end);
            if (digits.length() < 13 || digits.length() > 17) {
                continue;
            }
            try {
                long base = Long.parseLong(digits.substring(0, 13));
                // 2001-09-09 to 2286-11-20, which is every 13-digit epoch-millisecond value.
                if (base >= 1000000000000L && base <= 9999999999999L) {
                    timestamped++;
                }
            } catch (NumberFormatException notANumber) {
                continue;
            }
        }
        return timestamped * 4 >= identifiers.size() * 3;
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
        return using(source, book, ontology, ontologyFile, editor, null, Moment.now());
    }

    /**
     * As {@link #using(Source, SheetBook, OWLOntology, File, String)}, scoped to one sheet and
     * with the instant fixed.
     *
     * @param onlyThisSheet the sheet whose shape to read, or null for the whole folder
     * @param moment when this is happening and which UUIDs it gets - {@link Moment#at} makes a
     *     run reproducible, which is how the timestamp and UUID sources are tested
     */
    public static IriGenerator using(Source source, SheetBook book, OWLOntology ontology,
            File ontologyFile, String editor, String onlyThisSheet, Moment moment) {
        Set<String> inTheOntology = everyIriIn(ontology);
        String who = editor == null || editor.trim().isEmpty() ? editorName() : editor.trim();
        Moment when = moment == null ? Moment.now() : moment;
        if (source == Source.ID_RANGES) {
            return fromRanges(book, inTheOntology, ontologyFile, who, when);
        }
        if (source == Source.SHEET_PATTERN) {
            return fromTheSheets(book, inTheOntology, who, onlyThisSheet, when);
        }
        return underANamespace(source, book, inTheOntology, ontologyFile, ontology, who,
                onlyThisSheet, when);
    }

    /**
     * The sources whose local part is not a number: they need only a namespace to hang it under.
     *
     * <p>The namespace is taken from the same three places, in the same order of authority, as
     * everything else here: the project's {@code idprefix}, then the stem the sheets already use,
     * then the ontology's own IRI. It is never invented, because a strategy that minted under a
     * namespace nobody owns would produce IRIs that resolve to nothing - which is exactly what
     * the measured CURIE case does (0 axioms, 1 problem, and the build succeeds).
     */
    private static IriGenerator underANamespace(Source source, SheetBook book,
            Set<String> inTheOntology, File ontologyFile, OWLOntology ontology, String editor,
            String onlyThisSheet, Moment moment) {
        String namespace = namespaceFor(book, ontologyFile, ontology, onlyThisSheet);
        if (namespace.isEmpty()) {
            return refused(source, book, inTheOntology, editor, "There is nothing to say what "
                    + "namespace a new identifier should be under: these sheets have no "
                    + "identifier to copy the shape of, this project has no -idranges.owl, and "
                    + "the ontology has no IRI of its own. Give the ontology an IRI, or type one "
                    + "identifier by hand and the rest can follow it.", moment);
        }
        if (source == Source.TIMESTAMP_AND_EDITOR && isAnonymous(editor)) {
            return refused(source, book, inTheOntology, editor, "This identifier is supposed to "
                    + "carry who made the row, and nobody has said who that is - the name falls "
                    + "back to \"" + ANONYMOUS + "\", which every editor would share. Set a "
                    + "display name in OntoBoard > Collaboration..., or choose a different "
                    + "source.", moment);
        }
        return new IriGenerator(source, book, inTheOntology, null, editor, namespace, 0, 0, 0,
                "", moment);
    }

    /** The fallback name {@link #editorName()} returns when nothing has said who is editing. */
    static final String ANONYMOUS = "FirstEditor";

    private static boolean isAnonymous(String editor) {
        return editor == null || editor.trim().isEmpty() || ANONYMOUS.equals(editor.trim());
    }

    /**
     * What a new identifier should start with: the project's prefix, the sheets' own stem, or
     * the ontology's namespace - in that order.
     */
    static String namespaceFor(SheetBook book, File ontologyFile, OWLOntology ontology,
            String onlyThisSheet) {
        File rangesFile = TermMinter.findRangesFile(ontologyFile);
        if (rangesFile != null) {
            try {
                IdRanges parsed = IdRanges.parse(new String(java.nio.file.Files.readAllBytes(
                        rangesFile.toPath()), java.nio.charset.Charset.forName("UTF-8")));
                if (parsed.getIdPrefix() != null && !parsed.getIdPrefix().trim().isEmpty()) {
                    return parsed.getIdPrefix().trim();
                }
            } catch (java.io.IOException | RuntimeException unreadable) {
                // Fall through to the sheets, the same way fromRanges does.
            }
        }
        String stem = commonestStem(book, onlyThisSheet);
        if (!stem.isEmpty()) {
            return stem;
        }
        if (ontology != null && ontology.getOntologyID().getOntologyIRI().isPresent()) {
            String iri = de.fizkarlsruhe.ise.ontoboard.odk.ProjectIri
                    .stemOf(ontology.getOntologyID().getOntologyIRI().get().toString());
            return iri == null || iri.isEmpty() ? "" : iri + "/";
        }
        return "";
    }

    /**
     * The stem the identifiers in these sheets share, digits and all stripped from the end.
     *
     * <p>Used by the sources that have no number of their own, so that a UUID or a timestamp
     * lands under the same prefix the sheet's other identifiers use rather than beside it.
     */
    private static String commonestStem(SheetBook book, String onlyThisSheet) {
        Set<String> identifiers = onlyThisSheet == null ? everyIdentifierIn(book)
                : identifiersIn(book, onlyThisSheet);
        if (identifiers.isEmpty()) {
            identifiers = everyIdentifierIn(book);
        }
        Map<String, Integer> countFor = new LinkedHashMap<String, Integer>();
        for (String each : identifiers) {
            int end = each.length();
            while (end > 0 && Character.isDigit(each.charAt(end - 1))) {
                end--;
            }
            if (end == 0) {
                continue;
            }
            String stem = each.substring(0, end);
            Integer seen = countFor.get(stem);
            countFor.put(stem, seen == null ? 1 : seen + 1);
        }
        String best = "";
        int most = 0;
        for (Map.Entry<String, Integer> each : countFor.entrySet()) {
            if (each.getValue() > most) {
                most = each.getValue();
                best = each.getKey();
            }
        }
        return best;
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
        Moment when = Moment.now();
        IriGenerator fromRanges = fromRanges(book, inTheOntology, ontologyFile, who, when);
        return fromRanges.canMint() ? fromRanges
                : fromTheSheets(book, inTheOntology, who, sheetName, when);
    }

    private static IriGenerator fromRanges(SheetBook book, Set<String> inTheOntology,
            File ontologyFile, String editor, Moment moment) {
        File rangesFile = TermMinter.findRangesFile(ontologyFile);
        if (rangesFile == null) {
            return refused(Source.ID_RANGES, book, inTheOntology, editor,
                    "This project has no -idranges.owl, so "
                    + "there are no allocated blocks to mint from. An ODK project keeps one "
                    + "beside the ontology; Project > ID ranges... can show or create it.", moment);
        }
        IdRanges parsed;
        try {
            parsed = IdRanges.parse(new String(java.nio.file.Files.readAllBytes(
                    rangesFile.toPath()), java.nio.charset.Charset.forName("UTF-8")));
        } catch (java.io.IOException | RuntimeException unreadable) {
            return refused(Source.ID_RANGES, book, inTheOntology, editor,
                    rangesFile.getName() + " could not be read, so its blocks cannot be used: "
                    + unreadable.getMessage(), moment);
        }
        IdRanges.Range mine = parsed.rangeFor(editor);
        if (mine == null) {
            StringBuilder who = new StringBuilder();
            for (String owner : parsed.owners()) {
                who.append(who.length() == 0 ? "" : ", ").append(owner);
            }
            return refused(Source.ID_RANGES, book, inTheOntology, editor,
                    "No ID range is allocated to '" + editor
                    + "' in " + rangesFile.getName() + ", and minting outside an allocated block "
                    + "is how two editors end up giving one identifier to two different things. "
                    + "Allocated so far: " + (who.length() == 0 ? "nobody" : who)
                    + ". Project > ID ranges... will allocate you one.", moment);
        }
        return new IriGenerator(Source.ID_RANGES, book, inTheOntology, parsed, editor,
                parsed.getIdPrefix(), parsed.getIdDigits(), mine.getLower(), mine.getUpper(), "",
                moment);
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
            String editor, String onlyThisSheet, Moment moment) {
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
            return refused(Source.SHEET_PATTERN, book, inTheOntology, editor,
                    "These sheets have no identifier ending "
                    + "in a number, so there is no numbering to continue. Type the first one and "
                    + "the rest can follow it, or allocate a block in "
                    + "Project > ID ranges...", moment);
        }
        int width = commonestKey(widthsFor.get(stem));
        return new IriGenerator(Source.SHEET_PATTERN, book, inTheOntology, null, editor, stem,
                width, highestFor.get(stem) + 1, Long.MAX_VALUE - 1, "", moment);
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

    /**
      * A generator that cannot mint, and says why.
      *
      * <p>It carries the source that refused. The previous version hard-coded SHEET_PATTERN for
      * every refusal, which was invisible while the dialog kept two generators in two variables
      * and would have mis-attributed every refusal the moment it offered a list of them.
      */
    private static IriGenerator refused(Source source, SheetBook book, Set<String> inTheOntology,
            String editor, String why, Moment moment) {
        return new IriGenerator(source, book, inTheOntology, null, editor, "", 0, 0, 0, why,
                moment);
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

    /**
     * One line for the dialog: where the identifier comes from, and what the next one would be.
     *
     * <p>The example is real, not illustrative - it is the value {@link #mint} would return, and
     * since the instant and the UUIDs are fixed when the generator is built, it is also the value
     * that gets written. A preview that showed a different value from the one written would be
     * worse than no preview.
     */
    public String describe() {
        if (!canMint()) {
            return refusal;
        }
        String next = source.needsALabel() ? exampleFromALabel() : mint(1).get(0);
        switch (source) {
            case ID_RANGES:
                IdRanges.Range mine = ranges.rangeFor(editor);
                return "From " + prefix + ", " + width + " digits, block " + mine.getLower()
                        + " to " + mine.getUpper() + " allocated to " + editor + ". Next: "
                        + next;
            case SHEET_PATTERN:
                return "Continuing " + prefix + " at " + width + " digits, from the sheets "
                        + "themselves. Next: " + next;
            case TIMESTAMP:
                return "The moment the row is made, then a counter: " + next;
            case TIMESTAMP_AND_EDITOR:
                return "The moment, and " + editor + ": " + next;
            case UUID_RANDOM:
                return "A random UUID, which nothing can derive again: " + next;
            case UUID_FROM_LABEL:
                return "A UUID derived from each row's label, so the same label always gives the "
                        + "same identifier. For \"" + EXAMPLE_LABEL + "\": " + next;
            case LABEL_SLUG:
                return "Each row's label, made safe for an IRI. For \"" + EXAMPLE_LABEL + "\": "
                        + next;
            default:
                return prefix;
        }
    }

    /** A label to show the shape with, when the real one depends on the row. */
    private static final String EXAMPLE_LABEL = "sintering process";

    private String exampleFromALabel() {
        return prefix + localPart(0, 0, EXAMPLE_LABEL);
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
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, got " + count);
        }
        if (source.needsALabel()) {
            throw new IllegalStateException(source.getLabel() + " needs each row's label, so it "
                    + "cannot mint without knowing the rows. Use mintFor or fillBlanks.");
        }
        return mintFor(java.util.Collections.nCopies(count, ""));
    }

    /**
     * One identifier per label, none of them used by anything.
     *
     * <p>The labels are what the label-derived sources need and what the others ignore; an empty
     * string means "this row has no label", which is a real case - roughly 37 rows of the live
     * MatWerk workbook carry an identifier and no label at all.
     *
     * <p><b>Every value is added to the taken set as it is produced.</b> The old loop did not
     * need that, because a counter cannot produce the same number twice; a timestamp can, and
     * without this {@code mintFor} would have returned the same identifier for every row of a
     * batch and {@code fillBlanks} would have written it into all of them without a word -
     * nothing downstream looks. {@code SheetAudit} reports duplicate identifiers only within one
     * sheet, so there is no net behind this.
     *
     * @throws IllegalStateException when {@link #canMint()} is false, because an identifier
     *     invented in spite of the refusal is the collision the refusal exists to prevent
     */
    public List<String> mintFor(List<String> labels) {
        if (!canMint()) {
            throw new IllegalStateException(refusal);
        }
        Set<String> taken = new LinkedHashSet<String>(inTheOntology);
        taken.addAll(everyIdentifierIn(book));
        List<String> minted = new ArrayList<String>();
        long cursor = firstNumber;
        for (int index = 0; index < labels.size(); index++) {
            String label = labels.get(index) == null ? "" : labels.get(index);
            String chosen = null;
            for (int attempt = 0; attempt < MOST_ATTEMPTS && chosen == null; attempt++) {
                String candidate;
                if (source.isNumeric()) {
                    if (cursor > lastNumber) {
                        throw new IllegalStateException("The block " + firstNumber + " to "
                                + lastNumber + " allocated to " + editor + " has no room for "
                                + labels.size() + " more identifier"
                                + (labels.size() == 1 ? "" : "s") + ". Allocate a further block "
                                + "in Project > ID ranges...");
                    }
                    candidate = iriFor(cursor++);
                } else {
                    candidate = prefix + localPart(index, attempt, label);
                }
                if (candidate.equals(prefix)) {
                    throw new IllegalStateException(whyThisRowHasNoIdentifier(index));
                }
                if (!taken.contains(candidate)) {
                    chosen = candidate;
                }
            }
            if (chosen == null) {
                throw new IllegalStateException("Could not find an unused identifier for row "
                        + (index + 1) + " after " + MOST_ATTEMPTS + " tries. "
                        + (source == Source.LABEL_SLUG
                                ? "\"" + label + "\" is already the identifier of something "
                                        + "else; two rows cannot share one."
                                : "Something is wrong with the namespace " + prefix + "."));
            }
            taken.add(chosen);
            minted.add(chosen);
        }
        return minted;
    }

    private String whyThisRowHasNoIdentifier(int index) {
        return "Row " + (index + 1) + " of this batch has no label, and "
                + source.getLabel().toLowerCase(Locale.ROOT) + " is derived from it. Give the "
                + "row a label first, or choose a source that does not need one.";
    }

    /**
     * The part after the namespace, for the sources that are not a number.
     *
     * <p>{@code attempt} is how many times this row has already collided. Every source has to
     * answer it: a UUID takes a different one, a timestamp adds to its counter, a slug refuses by
     * returning the same thing again so the attempt loop runs out and says so.
     */
    private String localPart(int index, int attempt, String label) {
        switch (source) {
            case TIMESTAMP:
                return moment.millis() + counter(index + attempt);
            case TIMESTAMP_AND_EDITOR:
                return moment.millis() + counter(index + attempt) + "-" + slug(editor, 24);
            case UUID_RANDOM:
                return moment.uuid(index * 2 + attempt);
            case UUID_FROM_LABEL:
                return label.trim().isEmpty() ? ""
                        : moment.uuidFor(attempt == 0 ? label.trim()
                                : label.trim() + "#" + (attempt + 1));
            case LABEL_SLUG:
                return slug(label, 80);
            default:
                return "";
        }
    }

    /**
     * The row counter that follows a timestamp, padded so it sorts.
     *
     * <p>Three digits, which is the shape the live data already has - 8,406 of its identifiers
     * are a 13-digit epoch base plus a counter of one to three digits. Padded, which the existing
     * data is not: unpadded, {@code ...2051} and {@code ...20510} sort as text in an order that
     * is not their numeric order, and every tool that sorts identifiers gets it wrong. Padding
     * costs nothing and the result is still the same family of shapes.
     */
    private static String counter(int index) {
        String digits = Integer.toString(index + 1);
        while (digits.length() < 3) {
            digits = "0" + digits;
        }
        return digits;
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
        if (!canMint()) {
            throw new IllegalStateException(refusal);
        }
        // The ID column is resolved FIRST, and resolved again here rather than trusted from
        // construction time. The template row became editable in 1.118.0, so it can be moved,
        // renamed or deleted between the moment a dialog previewed this and the moment it
        // writes - and this is also the difference between "nothing needed doing" and "I could
        // not tell where to write", which returning 0 for both would have hidden.
        SheetBook.Sheet sheet = book.get(sheetName);
        TemplateColumns.Column id =
                sheet == null ? null : sheet.getColumns().firstOf(TemplateColumns.Kind.ID);
        if (id == null) {
            throw new IllegalStateException(sheetName + " has no ID column - its template row "
                    + "needs one cell that says exactly \"ID\". Nothing was written.");
        }
        List<Integer> rows = rowsNeedingAnIdentifier(book, sheetName);
        if (rows.isEmpty()) {
            return 0;
        }
        List<String> labels = new ArrayList<String>();
        for (Integer row : rows) {
            labels.add(labelIn(sheetName, row));
        }
        List<String> minted = mintFor(labels);
        for (int at = 0; at < rows.size(); at++) {
            book.setCell(sheetName, rows.get(at), id.getNumber(), minted.get(at));
        }
        return rows.size();
    }

    /**
     * What a row calls itself, for the sources derived from it.
     *
     * <p>The LABEL column first, then the first language-tagged or plain annotation that is
     * {@code rdfs:label} - which is how these sheets are really written: the live MatWerk tabs
     * use {@code A rdfs:label} and never {@code LABEL}, while every hand-written template uses
     * {@code LABEL} and never the other.
     */
    public String labelIn(String sheetName, int row) {
        SheetBook.Sheet sheet = book.get(sheetName);
        if (sheet == null) {
            return "";
        }
        TemplateColumns columns = sheet.getColumns();
        TemplateColumns.Column label = columns.firstOf(TemplateColumns.Kind.LABEL);
        if (label != null) {
            return book.cell(sheetName, row, label.getNumber()).trim();
        }
        for (TemplateColumns.Column column : columns.getColumns()) {
            boolean annotation = column.getKind() == TemplateColumns.Kind.ANNOTATION
                    || column.getKind() == TemplateColumns.Kind.ANNOTATION_LANGUAGE;
            if (annotation && column.getProperty().endsWith("label")) {
                return book.cell(sheetName, row, column.getNumber()).trim();
            }
        }
        return "";
    }

    /**
     * Text made safe to put in an IRI, without merging things the sheets keep apart.
     *
     * <p>Two rules that look fussy and are not, both measured on the live data:
     *
     * <ul>
     *   <li><b>Underscores survive.</b> The {@code city} tab distinguishes a city from the city's
     *       name by a trailing underscore - {@code Karlsruhe} is the place, {@code Karlsruhe_} is
     *       its name, and they are different rows with different types. A slug that dropped or
     *       collapsed the underscore would merge 19 of that sheet's 38 rows.</li>
     *   <li><b>Runs are not collapsed.</b> One character in, one character out. Collapsing makes
     *       {@code "... and more…"} and {@code "... and more..."} - both real labels in the same
     *       sheet - the same identifier.</li>
     * </ul>
     *
     * <p>German transliteration before stripping, because this is a German materials-science
     * corpus and {@code J_lich} would be a worse answer than {@code juelich} for a label that
     * occurs in it.
     */
    static String slug(String text, int most) {
        if (text == null) {
            return "";
        }
        String folded = text.trim()
                .replace("\u00e4", "ae").replace("\u00c4", "Ae")
                .replace("\u00f6", "oe").replace("\u00d6", "Oe")
                .replace("\u00fc", "ue").replace("\u00dc", "Ue")
                .replace("\u00df", "ss");
        folded = java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        StringBuilder out = new StringBuilder(folded.length());
        for (int at = 0; at < folded.length(); at++) {
            char each = folded.charAt(at);
            if ((each >= 'a' && each <= 'z') || (each >= '0' && each <= '9') || each == '_') {
                out.append(each);
            } else if (each >= 'A' && each <= 'Z') {
                out.append(Character.toLowerCase(each));
            } else {
                out.append('_');
            }
        }
        String slug = out.toString();
        while (slug.startsWith("_")) {
            slug = slug.substring(1);
        }
        return slug.length() > most ? slug.substring(0, most) : slug;
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
