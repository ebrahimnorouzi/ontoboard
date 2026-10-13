package de.fizkarlsruhe.ise.ontoboard.sheet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reading an Excel workbook, without adding a dependency to do it.
 *
 * <p>People keep these templates in Excel. That is not a preference to be argued with: a
 * spreadsheet with twenty-six tabs is shared by people who have Excel and have never heard of
 * TSV, and "export each tab by hand first" is a step that gets skipped and then gets skipped
 * wrongly. So an {@code .xlsx} opens here.
 *
 * <p>An {@code .xlsx} is a zip of XML, and Java 8 has both. {@link ZipFile} and a SAX parser do
 * the whole job in one class, against a bundle that is already 66 MB and already imports
 * {@code javax.xml.parsers}. Apache POI would be 12 MB of jars, a second XML stack inside an
 * OSGi bundle, and a thing to keep upgrading - for a format whose four relevant parts are
 * listed below.
 *
 * <h2>What it reads</h2>
 *
 * <ul>
 *   <li>{@code xl/workbook.xml} - the tab names and their order, and whether the file uses the
 *       1904 date system.</li>
 *   <li>{@code xl/_rels/workbook.xml.rels} - which part each tab's data is in. Sheet order in
 *       the workbook is not the order of the files, and matching {@code sheet1.xml} to the
 *       first tab is wrong for any workbook whose tabs have ever been reordered.</li>
 *   <li>{@code xl/sharedStrings.xml} - where nearly every text cell actually lives. A cell
 *       holding {@code <v>12</v>} with {@code t="s"} means "the thirteenth shared string", not
 *       the number twelve, and reading it as the latter is how a column of names becomes a
 *       column of small integers.</li>
 *   <li>{@code xl/styles.xml} - only to find which cells are formatted as dates. Excel stores a
 *       date as a number, so without this a provenance column of dates imports as
 *       {@code 45123}. That is not a format question; it is the wrong value, silently.</li>
 * </ul>
 *
 * <h2>What it does not do</h2>
 *
 * <p><b>It does not write.</b> Reading a workbook is four parts; writing one that Excel will
 * open is the whole format. So an import converts the tabs to {@code .tsv} files, which is
 * what ROBOT reads anyway and what git can merge - and the editor then owns them. A workbook
 * the editor could open but never save back to would be a trap.
 *
 * <p><b>It does not evaluate formulas.</b> A formula cell carries the value Excel last
 * calculated, and that is what is read. A workbook saved by something that did not calculate
 * leaves the value absent, and the cell comes through empty rather than as {@code =A1+1}.
 */
public final class Workbook {

    /** One tab of a workbook. */
    public static final class Tab {
        private final String name;
        private final List<List<String>> rows;

        Tab(String name, List<List<String>> rows) {
            this.name = name;
            this.rows = rows;
        }

        /** The tab's name as Excel shows it. */
        public String getName() {
            return name;
        }

        /** Its cells, row by row, with trailing empty rows and columns dropped. */
        public List<List<String>> getRows() {
            return rows;
        }

        @Override
        public String toString() {
            return name + " (" + rows.size() + " rows)";
        }
    }

    private Workbook() {
    }

    /**
     * Whether this really is a workbook, rather than something named like one.
     *
     * <p>Asked because the common case is not a corrupt file: it is a tab-separated file that
     * somebody - or some earlier version of this plugin - named {@code .xlsx}. Excel refuses
     * those with a message about the format not matching the extension, which reads as the file
     * being broken. It is not; it is text, and this can say so.
     */
    public static boolean looksLikeAWorkbook(File file) {
        if (file == null || !file.isFile() || file.length() < 4) {
            return false;
        }
        byte[] magic = new byte[4];
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            if (in.read(magic) != 4) {
                return false;
            }
        } catch (IOException cannotRead) {
            return false;
        }
        // "PK\003\004": every zip, and therefore every xlsx, starts with it.
        return magic[0] == 0x50 && magic[1] == 0x4B && magic[2] == 0x03 && magic[3] == 0x04;
    }

    /**
     * Why this file cannot be imported, or empty when it can.
     *
     * <p>Each refusal names what the file actually is, because "could not read it" sends
     * somebody to look for corruption that is not there.
     */
    public static String whyNot(File file) {
        if (file == null || !file.isFile()) {
            return "There is no file there.";
        }
        if (looksLikeAWorkbook(file)) {
            return "";
        }
        if (isProbablyText(file)) {
            return file.getName() + " is not an Excel workbook - it is plain text, with "
                    + "tab-separated or comma-separated columns, saved under an .xlsx name. "
                    + "That is why Excel refuses it. Rename it to .tsv and open the folder it "
                    + "is in; the content is fine.";
        }
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".xls")) {
            return file.getName() + " is in the old binary .xls format, which this does not "
                    + "read. Open it in Excel and save it as .xlsx.";
        }
        return file.getName() + " is not a readable workbook: it does not begin like a zip "
                + "file, which every .xlsx does.";
    }

    /** Whether the first bytes look like text rather than a container. */
    private static boolean isProbablyText(File file) {
        byte[] head = new byte[Math.min(512, (int) file.length())];
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            int read = in.read(head);
            if (read <= 0) {
                return false;
            }
            for (int at = 0; at < read; at++) {
                int value = head[at] & 0xFF;
                if (value == 0 || (value < 0x09 && value != 0x00)
                        || (value > 0x0D && value < 0x20)) {
                    return false;
                }
            }
            return true;
        } catch (IOException cannotRead) {
            return false;
        }
    }

    /**
     * Every tab of a workbook, in the order Excel shows them.
     *
     * @throws IOException when the file is not a readable workbook, with {@link #whyNot} as the
     *     message - so the reason reaches a user rather than a stack trace
     */
    public static List<Tab> read(File file) throws IOException {
        String refusal = whyNot(file);
        if (!refusal.isEmpty()) {
            throw new IOException(refusal);
        }
        try (ZipFile zip = new ZipFile(file)) {
            List<String> shared = sharedStrings(zip);
            Set<Integer> dateStyles = dateStyles(zip);
            Map<String, String> partForRelationship = relationships(zip);
            WorkbookPart workbook = workbookPart(zip);
            List<Tab> tabs = new ArrayList<Tab>();
            for (Map.Entry<String, String> sheet : workbook.sheets.entrySet()) {
                String part = partForRelationship.get(sheet.getValue());
                if (part == null) {
                    continue;
                }
                ZipEntry entry = zip.getEntry(part.startsWith("xl/") ? part : "xl/" + part);
                if (entry == null) {
                    continue;
                }
                tabs.add(new Tab(sheet.getKey(),
                        rowsOf(zip, entry, shared, dateStyles, workbook.date1904)));
            }
            if (tabs.isEmpty()) {
                throw new IOException(file.getName() + " has no readable sheets in it.");
            }
            return tabs;
        }
    }

    /**
     * Imports a workbook into a folder as one {@code .tsv} per tab.
     *
     * <p>Converted rather than opened in place, for the reason in the class comment: this reads
     * the format and does not write it, and an editor that could open a file but never save it
     * would be worse than one that refuses. TSV is also what ROBOT reads and what git can show
     * a diff of.
     *
     * <p>An empty tab is skipped. Workbooks collect them - Sheet2, Sheet3 - and a template file
     * with no columns is one more thing in the folder for every build to warn about.
     *
     * @return the files written, in tab order
     */
    public static List<File> importInto(File workbook, File folder) throws IOException {
        List<File> written = new ArrayList<File>();
        for (Tab tab : read(workbook)) {
            if (tab.getRows().isEmpty()) {
                continue;
            }
            File file = SheetLocation.freeNameIn(folder,
                    tab.getName().replaceAll("[^A-Za-z0-9_-]", "-"));
            if (file == null) {
                throw new IOException("Could not find a free name for " + tab.getName() + " in "
                        + folder.getAbsolutePath());
            }
            StringBuilder text = new StringBuilder();
            for (List<String> row : tab.getRows()) {
                // Flattened, because a cell holding two lines is a row holding two rows
                // everywhere downstream - and then escaped, because a cell holding a
                // quote was written out raw and read back as the start of a quoted field,
                // taking the next tab, the next newline, and every column after it.
                List<String> flat = new ArrayList<String>(row.size());
                for (String cell : row) {
                    flat.add(Delimited.flatten(cell));
                }
                text.append(Delimited.line(flat, '\t'));
            }
            java.nio.file.Files.write(file.toPath(),
                    text.toString().getBytes(java.nio.charset.Charset.forName("UTF-8")));
            written.add(file);
        }
        if (written.isEmpty()) {
            throw new IOException(workbook.getName() + " has no tab with anything in it.");
        }
        return written;
    }

    // ---------------------------------------------------------------- the parts

    /** What workbook.xml says: the tabs, in order, and the date system. */
    private static final class WorkbookPart {
        private final Map<String, String> sheets = new LinkedHashMap<String, String>();
        private boolean date1904;
    }

    private static WorkbookPart workbookPart(ZipFile zip) throws IOException {
        final WorkbookPart part = new WorkbookPart();
        ZipEntry entry = zip.getEntry("xl/workbook.xml");
        if (entry == null) {
            throw new IOException("This file has no xl/workbook.xml, so it is a zip but not a "
                    + "workbook.");
        }
        parse(zip, entry, new DefaultHandler() {
            @Override
            public void startElement(String uri, String local, String name, Attributes at) {
                if ("sheet".equals(local) || name.endsWith(":sheet") || "sheet".equals(name)) {
                    String sheetName = value(at, "name");
                    String relationship = value(at, "id");
                    if (!sheetName.isEmpty() && !relationship.isEmpty()) {
                        part.sheets.put(sheetName, relationship);
                    }
                } else if ("workbookPr".equals(local) || "workbookPr".equals(name)) {
                    String setting = value(at, "date1904");
                    part.date1904 = "1".equals(setting) || "true".equalsIgnoreCase(setting);
                }
            }
        });
        return part;
    }

    /**
     * Relationship id to part name.
     *
     * <p>Needed because sheet order and file order are different things: a workbook whose tabs
     * have been reordered still has {@code sheet1.xml} first on disk, and matching by position
     * would hand the second tab's data to the first tab's name.
     */
    private static Map<String, String> relationships(ZipFile zip) throws IOException {
        final Map<String, String> parts = new LinkedHashMap<String, String>();
        ZipEntry entry = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (entry == null) {
            return parts;
        }
        parse(zip, entry, new DefaultHandler() {
            @Override
            public void startElement(String uri, String local, String name, Attributes at) {
                if ("Relationship".equals(local) || "Relationship".equals(name)) {
                    String id = value(at, "Id");
                    String target = value(at, "Target");
                    if (!id.isEmpty() && !target.isEmpty()) {
                        parts.put(id, target.startsWith("/xl/") ? target.substring(4) : target);
                    }
                }
            }
        });
        return parts;
    }

    /** Every shared string, in index order, with rich-text runs joined. */
    private static List<String> sharedStrings(ZipFile zip) throws IOException {
        final List<String> strings = new ArrayList<String>();
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return strings;
        }
        parse(zip, entry, new DefaultHandler() {
            private final StringBuilder current = new StringBuilder();
            private boolean inside;
            private boolean reading;

            @Override
            public void startElement(String uri, String local, String name, Attributes at) {
                String tag = local.isEmpty() ? name : local;
                if ("si".equals(tag)) {
                    inside = true;
                    current.setLength(0);
                } else if ("t".equals(tag) && inside) {
                    reading = true;
                }
            }

            @Override
            public void characters(char[] text, int from, int length) {
                if (reading) {
                    current.append(text, from, length);
                }
            }

            @Override
            public void endElement(String uri, String local, String name) {
                String tag = local.isEmpty() ? name : local;
                if ("t".equals(tag)) {
                    reading = false;
                } else if ("si".equals(tag)) {
                    // Rich text is several <r><t> runs in one <si>; joining them is what Excel
                    // shows. Keeping only the last run would silently truncate every cell
                    // anybody had bolded a word in.
                    strings.add(current.toString());
                    inside = false;
                }
            }
        });
        return strings;
    }

    /**
     * The style indexes that mean "this number is a date".
     *
     * <p>Excel has no date type: 2026-10-12 is the number 46307 with a format applied. Reading
     * the number is not a formatting difference, it is the wrong value - and a provenance
     * column of dates is exactly the kind of column these templates have.
     */
    private static Set<Integer> dateStyles(ZipFile zip) throws IOException {
        final Set<Integer> dates = new LinkedHashSet<Integer>();
        ZipEntry entry = zip.getEntry("xl/styles.xml");
        if (entry == null) {
            return dates;
        }
        final Set<String> dateFormats = new LinkedHashSet<String>(Arrays.asList(
                // The built-in date and time formats, which carry no formatCode of their own.
                "14", "15", "16", "17", "18", "19", "20", "21", "22",
                "45", "46", "47", "27", "30", "36", "50", "57"));
        parse(zip, entry, new DefaultHandler() {
            private boolean inCellXfs;
            private int index;

            @Override
            public void startElement(String uri, String local, String name, Attributes at) {
                String tag = local.isEmpty() ? name : local;
                if ("numFmt".equals(tag)) {
                    String code = value(at, "formatCode").toLowerCase(Locale.ROOT);
                    // A custom format counts when it formats a date at all: y, d, or a month
                    // "mmm". A bare "m" is ambiguous with minutes, so it is only taken with a
                    // day or a year beside it, which is what every real date format has.
                    boolean looksLikeADate = code.contains("y") || code.contains("d")
                            || code.contains("mmm");
                    if (looksLikeADate && !code.contains("[")) {
                        dateFormats.add(value(at, "numFmtId"));
                    }
                } else if ("cellXfs".equals(tag)) {
                    inCellXfs = true;
                    index = 0;
                } else if ("xf".equals(tag) && inCellXfs) {
                    if (dateFormats.contains(value(at, "numFmtId"))) {
                        dates.add(index);
                    }
                    index++;
                }
            }

            @Override
            public void endElement(String uri, String local, String name) {
                if ("cellXfs".equals(local.isEmpty() ? name : local)) {
                    inCellXfs = false;
                }
            }
        });
        return dates;
    }

    /** One worksheet, as rows of text. */
    private static List<List<String>> rowsOf(ZipFile zip, ZipEntry entry,
            final List<String> shared, final Set<Integer> dateStyles, final boolean date1904)
            throws IOException {
        final Map<Integer, Map<Integer, String>> cells =
                new LinkedHashMap<Integer, Map<Integer, String>>();
        parse(zip, entry, new DefaultHandler() {
            private int row;
            private int column;
            private String type = "";
            private int style = -1;
            private final StringBuilder value = new StringBuilder();
            private boolean readingValue;
            private boolean readingInline;

            @Override
            public void startElement(String uri, String local, String name, Attributes at) {
                String tag = local.isEmpty() ? name : local;
                if ("row".equals(tag)) {
                    row = number(value(at, "r"), row + 1);
                    column = 0;
                } else if ("c".equals(tag)) {
                    String reference = value(at, "r");
                    column = reference.isEmpty() ? column + 1 : columnOf(reference);
                    type = value(at, "t");
                    style = number(value(at, "s"), -1);
                    value.setLength(0);
                } else if ("v".equals(tag)) {
                    readingValue = true;
                } else if ("is".equals(tag)) {
                    readingInline = true;
                } else if ("t".equals(tag) && readingInline) {
                    readingValue = true;
                }
            }

            @Override
            public void characters(char[] text, int from, int length) {
                if (readingValue) {
                    value.append(text, from, length);
                }
            }

            @Override
            public void endElement(String uri, String local, String name) {
                String tag = local.isEmpty() ? name : local;
                if ("v".equals(tag) || ("t".equals(tag) && readingInline)) {
                    readingValue = false;
                } else if ("is".equals(tag)) {
                    readingInline = false;
                } else if ("c".equals(tag)) {
                    String text = resolve(value.toString(), type, style, shared, dateStyles,
                            date1904);
                    if (!text.isEmpty()) {
                        Map<Integer, String> line = cells.get(row);
                        if (line == null) {
                            line = new LinkedHashMap<Integer, String>();
                            cells.put(row, line);
                        }
                        line.put(column, text);
                    }
                }
            }
        });
        return asTable(cells);
    }

    /** A sparse map of cells as a rectangular table, with no trailing emptiness. */
    private static List<List<String>> asTable(Map<Integer, Map<Integer, String>> cells) {
        int lastRow = 0;
        int lastColumn = 0;
        for (Map.Entry<Integer, Map<Integer, String>> row : cells.entrySet()) {
            lastRow = Math.max(lastRow, row.getKey());
            for (Integer column : row.getValue().keySet()) {
                lastColumn = Math.max(lastColumn, column);
            }
        }
        List<List<String>> table = new ArrayList<List<String>>();
        for (int row = 1; row <= lastRow; row++) {
            List<String> line = new ArrayList<String>();
            Map<Integer, String> found = cells.get(row);
            for (int column = 1; column <= lastColumn; column++) {
                String text = found == null ? null : found.get(column);
                line.add(text == null ? "" : text);
            }
            table.add(line);
        }
        return table;
    }

    /** What a cell actually holds, given its type and style. */
    private static String resolve(String raw, String type, int style, List<String> shared,
            Set<Integer> dateStyles, boolean date1904) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return "";
        }
        if ("s".equals(type)) {
            // The index into sharedStrings, not the number. Reading it as a number turns a
            // column of names into a column of small integers, and every one of them is a
            // plausible-looking value.
            int at = number(text, -1);
            return at >= 0 && at < shared.size() ? shared.get(at) : "";
        }
        if ("inlineStr".equals(type) || "str".equals(type)) {
            return text;
        }
        if ("b".equals(type)) {
            return "1".equals(text) ? "true" : "false";
        }
        if ("e".equals(type)) {
            // #REF!, #N/A and friends. Kept as Excel wrote them: dropping them would hide a
            // broken formula, and inventing a value for one would be worse.
            return text;
        }
        if (style >= 0 && dateStyles.contains(style)) {
            String asDate = dateFrom(text, date1904);
            if (!asDate.isEmpty()) {
                return asDate;
            }
        }
        return tidyNumber(text);
    }

    /**
     * An Excel serial number as {@code YYYY-MM-DD}, or with a time when it has one.
     *
     * <p>The epoch is 1899-12-30, not 1900-01-01, because Excel believes 1900 was a leap year -
     * a deliberate bug kept for compatibility with Lotus 1-2-3. Serial 60 is that day that
     * never existed, so anything below it is a day further back again. A 1904 workbook, which
     * is what Excel for Mac used to write, counts from 1904-01-01 instead.
     */
    static String dateFrom(String serial, boolean date1904) {
        double value;
        try {
            value = Double.parseDouble(serial);
        } catch (NumberFormatException notANumber) {
            return "";
        }
        long whole = (long) Math.floor(value);
        double fraction = value - whole;
        java.time.LocalDate epoch = date1904 ? java.time.LocalDate.of(1904, 1, 1)
                : whole < 60 ? java.time.LocalDate.of(1899, 12, 31)
                        : java.time.LocalDate.of(1899, 12, 30);
        java.time.LocalDate date;
        try {
            date = epoch.plusDays(date1904 ? whole : whole);
        } catch (RuntimeException outOfRange) {
            return "";
        }
        if (fraction < 1.0 / 86400) {
            return date.toString();
        }
        int seconds = (int) Math.round(fraction * 86400);
        return date.toString() + "T" + String.format("%02d:%02d:%02d", seconds / 3600,
                (seconds % 3600) / 60, seconds % 60);
    }

    /**
     * A number as a person wrote it rather than as a double.
     *
     * <p>Excel stores every number as a double, so an identifier typed as 1225 comes back as
     * "1225" and not "1225.0" only if something trims it. Getting that wrong puts
     * {@code MWO_1225.0} in an identifier column.
     */
    static String tidyNumber(String text) {
        if (!text.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
            return text;
        }
        try {
            double value = Double.parseDouble(text);
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return String.valueOf((long) value);
            }
            java.math.BigDecimal exact = new java.math.BigDecimal(text);
            return exact.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notANumber) {
            return text;
        }
    }

    /** {@code B3} to 2: the column, 1-based. */
    static int columnOf(String reference) {
        int column = 0;
        for (int at = 0; at < reference.length(); at++) {
            char letter = Character.toUpperCase(reference.charAt(at));
            if (letter < 'A' || letter > 'Z') {
                break;
            }
            column = column * 26 + (letter - 'A' + 1);
        }
        return Math.max(1, column);
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException | NullPointerException notANumber) {
            return fallback;
        }
    }

    private static String value(Attributes attributes, String name) {
        String found = attributes.getValue(name);
        if (found != null) {
            return found;
        }
        // Namespaced, as r:id is. Matching on the local name keeps this working whether or not
        // the parser was told about namespaces.
        for (int at = 0; at < attributes.getLength(); at++) {
            String qualified = attributes.getQName(at);
            if (qualified.equals(name) || qualified.endsWith(":" + name)) {
                return attributes.getValue(at);
            }
        }
        return "";
    }

    private static void parse(ZipFile zip, ZipEntry entry, DefaultHandler handler)
            throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            // A workbook is a document from outside, and an XML document can name an external
            // entity that reads a local file or hangs on a URL. None of these parts needs one.
            trySet(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySet(factory, "http://xml.org/sax/features/external-general-entities", false);
            trySet(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            SAXParser parser = factory.newSAXParser();
            parser.parse(in, handler);
        } catch (SAXException | javax.xml.parsers.ParserConfigurationException broken) {
            throw new IOException("Could not read " + entry.getName() + " out of the workbook: "
                    + broken.getMessage(), broken);
        }
    }

    private static void trySet(SAXParserFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception notSupported) {
            // An older parser without the feature is not a reason to refuse the file; the
            // others still apply.
        }
    }

    /** The tab names a workbook has, without reading any of their cells. */
    public static List<String> tabNamesIn(File file) throws IOException {
        String refusal = whyNot(file);
        if (!refusal.isEmpty()) {
            throw new IOException(refusal);
        }
        try (ZipFile zip = new ZipFile(file)) {
            return Collections.unmodifiableList(
                    new ArrayList<String>(workbookPart(zip).sheets.keySet()));
        }
    }
}
