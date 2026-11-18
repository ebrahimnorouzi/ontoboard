package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.ScalarToken;
import org.yaml.snakeyaml.tokens.Token;

/**
 * {@code dosdp validate -i}, ODK's check of a project's DOSDP patterns, as OntoBoard's own engine
 * runs it: each pattern is held against DOSDP's format, and is said to keep its rules where ODK
 * v1.6.1's checker says so and not elsewhere - held against that checker's verdicts on
 * OntoBoard's own cases ({@code dosdp/verdicts.txt}). The words are OntoBoard's.
 *
 * <p>What decides, as ODK's checker decides it:
 * <ul>
 * <li>A folder: each of its own files whose name ends in {@code .yaml} or {@code .yml} - not
 * those in folders inside it; one of those names that is a folder fails. A file: its name ends
 * so. Neither there fails.
 * <li>The file: UTF-8, one YAML document, read as YAML 1.2 reads it - {@code yes} and {@code on}
 * are words, a date is a date - with no key twice.
 * <li>The pattern: a mapping of DOSDP's fields, each holding what DOSDP's format says, read as
 * JSON Schema draft 7 reads DOSDP's schema ({@link #PATTERN}).
 * <li>Where the pattern has {@code vars}: each list of vars below its top, under any key
 * {@code vars}, names only vars it declares - in vars, list_vars, data_vars, data_list_vars,
 * or as an internal var, each of which then has a name.
 * <li>The text of each OWL axiom - equivalentTo, subClassOf, GCI, disjointWith, each of
 * logical_axioms, and the clauses of each - has an even number of single quotes, and each name
 * it quotes is one of the pattern's classes or relations.
 * <li>Each of the pattern's annotations names an annotation property it declares.
 * </ul>
 * A pattern that keeps all of that passes; the status is 1 when one does not.
 */
final class DosdpCheck implements RecipeShell.Tool {

    @Override
    public String whyNot(List<String> arguments) {
        return inputOf(arguments) != null ? null
                : "only 'dosdp validate -i <folder or file>' is run here, as ODK's test runs it, "
                        + "not 'dosdp " + String.join(" ", arguments) + "'";
    }

    /** What {@code validate -i} names, or null when the call is not that. */
    static String inputOf(List<String> arguments) {
        if (arguments.size() == 2 && arguments.get(0).equals("validate")
                && arguments.get(1).startsWith("--input=")
                && arguments.get(1).length() > "--input=".length()) {
            return arguments.get(1).substring("--input=".length());
        }
        if (arguments.size() == 3 && arguments.get(0).equals("validate")
                && (arguments.get(1).equals("-i") || arguments.get(1).equals("--input"))) {
            return arguments.get(2);
        }
        return null;
    }

    @Override
    public int run(RecipeShell.Call call) throws IOException {
        String input = inputOf(call.arguments());
        File given = call.file(input);
        List<File> patterns = new ArrayList<File>();
        if (given.isDirectory()) {
            File[] entries = given.listFiles();
            List<File> sorted = entries == null ? new ArrayList<File>()
                    : new ArrayList<File>(Arrays.asList(entries));
            sorted.sort((a, b) -> a.getName().compareTo(b.getName()));
            for (File entry : sorted) {
                if (isPatternName(entry.getName())) {
                    patterns.add(entry);
                }
            }
        } else if (!given.isFile()) {
            call.complain("dosdp: " + input + ": there is no such file or folder");
            return 1;
        } else if (!isPatternName(given.getName())) {
            call.complain("dosdp: " + input + ": is not a .yaml or .yml file, so it is not "
                    + "read as a pattern");
            return 1;
        } else {
            patterns.add(given);
        }
        int broken = 0;
        for (File pattern : patterns) {
            String shown = given.isDirectory()
                    ? input + (input.endsWith("/") ? "" : "/") + pattern.getName() : input;
            List<String> wrong = pattern.isDirectory()
                    ? Collections.singletonList("is a folder, not a pattern")
                    : check(Files.readAllBytes(pattern.toPath()));
            if (!wrong.isEmpty()) {
                broken++;
                for (String what : wrong) {
                    call.complain("dosdp: " + shown + ": " + what);
                }
            }
        }
        if (given.isDirectory()) {
            int all = patterns.size();
            call.complain("dosdp: " + (all == 0 ? input + " has no patterns"
                    : broken == 0 ? "the " + all + " pattern" + (all == 1 ? " in " + input
                            + " keeps" : "s in " + input + " keep") + " DOSDP's rules"
                    : broken + " of the " + all + " patterns in " + input + " do not keep "
                            + "DOSDP's rules"));
        } else if (broken == 0) {
            call.complain("dosdp: " + input + " keeps DOSDP's rules");
        }
        return broken == 0 ? 0 : 1;
    }

    private static boolean isPatternName(String name) {
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    // ------------------------------------------------------------------ one pattern

    /** What is wrong with one pattern file, each in a sentence; empty when nothing is. */
    static List<String> check(byte[] bytes) {
        List<String> wrong = new ArrayList<String>();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            wrong.add("is not UTF-8, which a pattern is read as");
            return wrong;
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        Object pattern;
        try {
            pattern = read(text);
        } catch (Unreadable unreadable) {
            wrong.add("cannot be read as YAML: " + unreadable.getMessage());
            return wrong;
        }
        follows(pattern, PATTERN, "", wrong);
        if (pattern instanceof Map) {
            Map<?, ?> fields = (Map<?, ?>) pattern;
            varsNamed(fields, wrong);
            axiomTexts(fields, wrong);
            annotationProperties(fields, wrong);
        }
        return wrong;
    }

    // ------------------------------------------------------------------ YAML, as 1.2 reads it

    /** Why a file is not one YAML document a pattern can be read from. */
    private static final class Unreadable extends Exception {
        private static final long serialVersionUID = 1L;

        Unreadable(String why) {
            super(why);
        }
    }

    /**
     * A value YAML reads as something other than text - a number, true or false, a date - which
     * a field that holds text does not take. Equal by what it is and how it is written.
     */
    private static final class Other {
        final String kind;
        final String written;

        Other(String kind, String written) {
            this.kind = kind;
            this.written = written;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Other && ((Other) other).kind.equals(kind)
                    && ((Other) other).written.equals(written);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, written);
        }

        @Override
        public String toString() {
            return written;
        }
    }

    private static final String NUMBER = "a number";
    private static final String TRUTH = "true or false";
    private static final String DATE = "a date";
    private static final String BYTES = "binary data";

    /**
     * Plain words as YAML 1.2 reads them, the way ODK's checker's YAML does: only true and false
     * in their three spellings are booleans, so {@code yes}, {@code on} and {@code 1:30} are
     * words; numbers may have underscores and a leading zero; a date or a time is one.
     */
    private static final class Yaml12 extends Resolver {
        private static final Pattern TRUTHS =
                Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");
        private static final Pattern INTEGERS = Pattern.compile("^(?:[-+]?0b[0-1_]+"
                + "|[-+]?0o?[0-7_]+|[-+]?[0-9_]+|[-+]?0x[0-9a-fA-F_]+)$");
        private static final Pattern DECIMALS = Pattern.compile("^(?:"
                + "[-+]?(?:[0-9][0-9_]*)\\.[0-9_]*(?:[eE][-+]?[0-9]+)?"
                + "|[-+]?(?:[0-9][0-9_]*)(?:[eE][-+]?[0-9]+)"
                + "|\\.[0-9_]+(?:[eE][-+][0-9]+)?"
                + "|[-+]?\\.(?:inf|Inf|INF)"
                + "|\\.(?:nan|NaN|NAN))$");
        private static final Pattern NOTHING = Pattern.compile("^(?:~|null|Null|NULL| )$");

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, TRUTHS, "tTfF");
            addImplicitResolver(Tag.INT, INTEGERS, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, DECIMALS, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, Resolver.MERGE, "<");
            addImplicitResolver(Tag.NULL, NOTHING, "~nN\0");
            addImplicitResolver(Tag.NULL, Resolver.EMPTY, null);
            addImplicitResolver(Tag.TIMESTAMP, Resolver.TIMESTAMP, "0123456789");
        }
    }

    /**
     * The one document of a file, as maps, lists, words and {@link Other}s: null when it holds
     * nothing.
     */
    static Object read(String text) throws Unreadable {
        Yaml yaml = new Yaml(new SafeConstructor(), new Representer(), new DumperOptions(),
                new LoaderOptions(), new Yaml12());
        Node root;
        try {
            root = yaml.compose(new StringReader(asYaml12(text)));
        } catch (YAMLException notYaml) {
            throw new Unreadable(oneLine(notYaml.getMessage()));
        }
        return root == null ? null : value(root, new IdentityHashMap<Node, Object>(),
                Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
    }

    /**
     * The text with each word between [ ] or { } that holds a colon put in quotes, as YAML 1.2
     * reads such a word and SnakeYAML's YAML 1.1 does not: the colon is part of the word unless
     * a space, a tab or the line's end follows it. Two cases: SnakeYAML stops at the colon
     * ({@code [BFO:0000050]}), or reads it as a key's when , ] or } follows it ({@code [a:]}).
     * The quoted word keeps what it resolved to: a date stays a date.
     */
    static String asYaml12(String text) {
        String now = text;
        int colons = 0;
        for (char letter : text.toCharArray()) {
            colons += letter == ':' ? 1 : 0;
        }
        for (int round = 0; round <= colons; round++) {
            String next;
            try {
                next = colonBeforeAnEnd(now);
            } catch (MarkedYAMLException stopped) {
                if (!"while scanning a plain scalar".equals(stopped.getContext())
                        || !"found unexpected ':'".equals(stopped.getProblem())
                        || stopped.getContextMark() == null) {
                    return now;
                }
                next = wordQuoted(now, at(now, stopped.getContextMark().getIndex()));
            }
            if (next == null || next.equals(now)) {
                return now;
            }
            now = next;
        }
        return now;
    }

    /** The second case quoted, or null where there is none. */
    private static String colonBeforeAnEnd(String text) {
        ScannerImpl scanner = new ScannerImpl(new StreamReader(text));
        List<Token> tokens = new ArrayList<Token>();
        while (!scanner.checkToken(Token.ID.StreamEnd)) {
            tokens.add(scanner.getToken());
        }
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            Token.ID id = token.getTokenId();
            depth += id == Token.ID.FlowSequenceStart || id == Token.ID.FlowMappingStart ? 1
                    : id == Token.ID.FlowSequenceEnd || id == Token.ID.FlowMappingEnd ? -1 : 0;
            if (depth <= 0 || !(token instanceof ScalarToken) || !((ScalarToken) token).getPlain()
                    || i + 2 >= tokens.size()) {
                continue;
            }
            Token colon = tokens.get(i + 1);
            Token after = tokens.get(i + 2);
            Token.ID next = after.getTokenId();
            if (colon.getTokenId() == Token.ID.Value
                    && colon.getStartMark().getIndex() == token.getEndMark().getIndex()
                    && after.getStartMark().getIndex() == colon.getEndMark().getIndex()
                    && (next == Token.ID.FlowEntry || next == Token.ID.FlowSequenceEnd
                            || next == Token.ID.FlowMappingEnd || next == Token.ID.FlowSequenceStart
                            || next == Token.ID.FlowMappingStart)) {
                int from = at(text, token.getStartMark().getIndex());
                int to = at(text, colon.getEndMark().getIndex());
                return text.substring(0, from) + quoted(text.substring(from, to))
                        + text.substring(to);
            }
        }
        return null;
    }

    /** The first case: the word that starts here, to where YAML 1.2 ends it, quoted. */
    private static String wordQuoted(String text, int from) {
        int to = from;
        while (to < text.length()) {
            char letter = text.charAt(to);
            char next = to + 1 < text.length() ? text.charAt(to + 1) : '\n';
            if (letter == '\n' || letter == '\r' || ",[]{}".indexOf(letter) >= 0
                    || letter == ':' && (next == ' ' || next == '\t' || next == '\n'
                            || next == '\r')
                    || letter == '#' && to > from
                            && (text.charAt(to - 1) == ' ' || text.charAt(to - 1) == '\t')) {
                break;
            }
            to++;
        }
        while (to > from && (text.charAt(to - 1) == ' ' || text.charAt(to - 1) == '\t')) {
            to--;
        }
        return text.substring(0, from) + quoted(text.substring(from, to)) + text.substring(to);
    }

    /** A plain word in single quotes, tagged with what it resolves to when that is not text. */
    private static String quoted(String word) {
        Tag tag = new Yaml12().resolve(NodeId.scalar, word, true);
        String inQuotes = "'" + word.replace("'", "''") + "'";
        return tag.equals(Tag.STR) ? inQuotes
                : "!!" + tag.getValue().substring(Tag.PREFIX.length()) + " " + inQuotes;
    }

    /** Where in the text a mark's index - counted in code points - is. */
    private static int at(String text, int codePoints) {
        return text.offsetByCodePoints(0, Math.min(codePoints,
                text.codePointCount(0, text.length())));
    }

    private static String oneLine(String message) {
        String flat = message == null ? "it is not YAML" : message.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 197) + "..." : flat;
    }

    private static Object value(Node node, Map<Node, Object> made, Set<Node> open)
            throws Unreadable {
        if (made.containsKey(node)) {
            return made.get(node);
        }
        if (!open.add(node)) {
            throw new Unreadable("a value refers to itself (line "
                    + (node.getStartMark().getLine() + 1) + ")");
        }
        Object value;
        if (node instanceof ScalarNode) {
            value = scalar((ScalarNode) node);
        } else if (node instanceof SequenceNode) {
            List<Object> items = new ArrayList<Object>();
            for (Node item : ((SequenceNode) node).getValue()) {
                items.add(value(item, made, open));
            }
            value = items;
        } else {
            value = mapping((MappingNode) node, made, open);
        }
        open.remove(node);
        made.put(node, value);
        return value;
    }

    private static Object scalar(ScalarNode node) throws Unreadable {
        Tag tag = node.getTag();
        if (tag.equals(Tag.STR)) {
            return node.getValue();
        }
        if (tag.equals(Tag.NULL)) {
            return null;
        }
        if (tag.equals(Tag.INT) || tag.equals(Tag.FLOAT)) {
            return new Other(NUMBER, node.getValue());
        }
        if (tag.equals(Tag.BOOL)) {
            return new Other(TRUTH, node.getValue());
        }
        if (tag.equals(Tag.TIMESTAMP)) {
            return new Other(DATE, node.getValue());
        }
        if (tag.equals(Tag.BINARY)) {
            return new Other(BYTES, node.getValue());
        }
        throw new Unreadable("'" + tag.getValue() + "' is not a tag YAML's safe reading "
                + "knows (line " + (node.getStartMark().getLine() + 1) + ")");
    }

    private static Map<Object, Object> mapping(MappingNode node, Map<Node, Object> made,
            Set<Node> open) throws Unreadable {
        Map<Object, Object> fields = new LinkedHashMap<Object, Object>();
        List<Node> merged = new ArrayList<Node>();
        for (NodeTuple tuple : node.getValue()) {
            Node keyNode = tuple.getKeyNode();
            if (keyNode instanceof ScalarNode && keyNode.getTag().equals(Tag.MERGE)) {
                merged.add(tuple.getValueNode());
                continue;
            }
            Object key = value(keyNode, made, open);
            if (key instanceof Map || key instanceof List) {
                throw new Unreadable("a key is a list or a mapping (line "
                        + (keyNode.getStartMark().getLine() + 1) + ")");
            }
            if (fields.containsKey(key)) {
                throw new Unreadable("'" + key + "' is a key twice in one mapping (line "
                        + (keyNode.getStartMark().getLine() + 1) + ")");
            }
            fields.put(key, value(tuple.getValueNode(), made, open));
        }
        for (Node source : merged) {
            List<Node> sources = source instanceof SequenceNode
                    ? ((SequenceNode) source).getValue() : Collections.singletonList(source);
            for (Node one : sources) {
                Object more = value(one, made, open);
                if (!(more instanceof Map)) {
                    throw new Unreadable("'<<' merges a mapping, and here it is given "
                            + "something else (line " + (one.getStartMark().getLine() + 1) + ")");
                }
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) more).entrySet()) {
                    fields.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        return fields;
    }

    // ------------------------------------------------------------------ DOSDP's format

    private static final String TEXT = "string";
    private static final String LIST = "array";
    private static final String MAPPING = "object";
    private static final String BOOLEAN = "boolean";

    /** The OWL axioms written as one block each. Before {@link #PATTERN}, which names them. */
    private static final List<String> AXIOMS = Arrays.asList("equivalentTo", "subClassOf",
            "GCI", "disjointWith");

    /** One rule of DOSDP's format for a value, as JSON Schema draft 7 reads such a rule. */
    private static final class Rule {
        private String type;
        private Set<String> among;
        private Set<String> required = Collections.emptySet();
        private final Map<String, Rule> fields = new LinkedHashMap<String, Rule>();
        private boolean closed;
        private Pattern names;
        private Rule items;
        private int least = -1;
        private int most = -1;
        private List<Rule> oneOf;
        private String forms;

        Rule type(String kind) {
            type = kind;
            return this;
        }

        Rule among(String... words) {
            among = new LinkedHashSet<String>(Arrays.asList(words));
            return this;
        }

        Rule required(String... names) {
            required = new LinkedHashSet<String>(Arrays.asList(names));
            return this;
        }

        Rule field(String name, Rule rule) {
            fields.put(name, rule);
            return this;
        }

        Rule closed() {
            closed = true;
            return this;
        }

        Rule names(String pattern) {
            names = Pattern.compile(pattern);
            return this;
        }

        Rule items(Rule rule) {
            items = rule;
            return this;
        }

        Rule length(int at, int to) {
            least = at;
            most = to;
            return this;
        }

        /** Exactly one of these holds; {@code said} is what they are, for a person. */
        Rule oneOf(String said, Rule... rules) {
            forms = said;
            oneOf = Arrays.asList(rules);
            return this;
        }
    }

    private static Rule text() {
        return new Rule().type(TEXT);
    }

    private static Rule texts() {
        return new Rule().type(LIST).items(text());
    }

    private static Rule listOf(Rule rule) {
        return new Rule().type(LIST).items(rule);
    }

    private static Rule needs(String... names) {
        return new Rule().required(names);
    }

    /** A pattern, field by field: DOSDP's schema as its facts are. */
    static final Rule PATTERN;

    static {
        Rule clause = new Rule();
        Rule clauses = new Rule().required("clauses").closed().field("sep", text())
                .field("clauses", listOf(clause));
        clause.required("text", "vars").closed().field("text", text()).field("vars", texts())
                .field("sub_clauses", listOf(clauses));
        Rule multiClause = new Rule().items(clauses);

        Rule annotation = new Rule();
        Rule annotations = listOf(annotation);
        Rule printf = new Rule().type(MAPPING).closed()
                .field("annotationProperty", text()).field("annotations", annotations)
                .field("text", text()).field("vars", texts()).field("multi_clause", multiClause)
                .oneOf("annotationProperty and text, or multi_clause",
                        needs("annotationProperty", "text"), needs("multi_clause"));
        Rule fixed = new Rule().type(MAPPING).required("annotationProperty", "value").closed()
                .field("annotationProperty", text()).field("value", text());
        Rule iri = new Rule().type(MAPPING).required("annotationProperty", "var").closed()
                .field("annotationProperty", text()).field("var", text())
                .field("annotations", annotations);
        annotation.oneOf("annotationProperty with text (and vars), with a value, or with a var",
                printf, fixed, iri);

        Rule axiom = new Rule().type(MAPPING).closed().field("annotations", annotations)
                .field("axiom_type", text().among("equivalentTo", "subClassOf",
                        "disjointWith", "GCI"))
                .field("text", text()).field("vars", texts()).field("multi_clause", multiClause)
                .oneOf("axiom_type, text and vars, or multi_clause",
                        needs("axiom_type", "text", "vars"), needs("multi_clause"));
        Rule owl = new Rule().type(MAPPING).closed().field("annotations", annotations)
                .field("text", text()).field("vars", texts()).field("multi_clause", multiClause)
                .oneOf("text and vars, or multi_clause", needs("text", "vars"),
                        needs("multi_clause"));
        Rule obo = new Rule().type(MAPPING).closed().field("annotations", annotations)
                .field("xrefs", text()).field("text", text()).field("vars", texts())
                .field("multi_clause", multiClause)
                .oneOf("text and vars, or multi_clause", needs("text", "vars"),
                        needs("multi_clause"));
        Rule oboValue = new Rule().type(MAPPING).required("value").closed()
                .field("value", text()).field("xrefs", text());
        Rule substitution = new Rule().type(MAPPING).required("in", "out", "match", "sub")
                .closed().field("in", text()).field("out", text()).field("match", text())
                .field("sub", text());
        Rule edge = new Rule().type(MAPPING).required("edge").closed()
                .field("edge", texts().length(3, 3)).field("annotations", annotations)
                .field("not", new Rule().type(BOOLEAN));

        String varName = "^[A-Za-z_][A-Za-z0-9_]*$";
        Rule pattern = new Rule().type(MAPPING).closed();
        for (String field : new String[] {"pattern_name", "pattern_iri", "base_IRI",
            "description"}) {
            pattern.field(field, text());
        }
        for (String field : new String[] {"contributors", "examples", "tags",
            "readable_identifiers"}) {
            pattern.field(field, texts());
        }
        pattern.field("status", text().among("development", "published"));
        for (String field : new String[] {"classes", "objectProperties", "relations",
            "dataProperties", "annotationProperties"}) {
            pattern.field(field, new Rule().type(MAPPING));
        }
        for (String field : new String[] {"vars", "list_vars", "data_vars", "data_list_vars"}) {
            pattern.field(field, new Rule().type(MAPPING).names(varName));
        }
        pattern.field("internal_vars", new Rule().type(LIST))
                .field("substitutions", listOf(substitution))
                .field("annotations", annotations)
                .field("logical_axioms", listOf(axiom));
        for (String field : AXIOMS) {
            pattern.field(field, owl);
        }
        for (String field : new String[] {"name", "comment", "def", "namespace"}) {
            pattern.field(field, obo);
        }
        for (String field : new String[] {"exact_synonym", "narrow_synonym", "related_synonym",
            "broad_synonym", "xref"}) {
            pattern.field(field, oboValue);
        }
        for (String field : new String[] {"generated_synonyms", "generated_narrow_synonyms",
            "generated_broad_synonyms", "generated_related_synonyms"}) {
            pattern.field(field, listOf(obo));
        }
        pattern.field("instance_graph", new Rule().type(MAPPING).required("nodes", "edges")
                .closed().field("nodes", new Rule().type(MAPPING)).field("edges", listOf(edge)));
        PATTERN = pattern;
    }

    /** Whether a value keeps a rule, said nowhere. */
    private static boolean keeps(Object value, Rule rule) {
        List<String> wrong = new ArrayList<String>();
        follows(value, rule, "", wrong);
        return wrong.isEmpty();
    }

    /** What of a rule a value does not keep, each in a sentence; {@code where} names it. */
    private static void follows(Object value, Rule rule, String where, List<String> wrong) {
        String it = where.isEmpty() ? "the file" : where;
        if (rule.oneOf != null) {
            int kept = 0;
            for (Rule form : rule.oneOf) {
                kept += keeps(value, form) ? 1 : 0;
            }
            if (kept != 1) {
                wrong.add(it + (kept == 0 ? " is none of what it can be: " : " is more than one "
                        + "of what it can be, which are: ") + rule.forms);
            }
        }
        if (rule.type != null && !isA(value, rule.type)) {
            wrong.add(it + " is " + described(value) + ", and is to be " + named(rule.type)
                    + (where.isEmpty() ? " of DOSDP's fields" : ""));
            return;
        }
        if (rule.among != null && !rule.among.contains(value)) {
            wrong.add(it + " is '" + value + "', and is one of " + String.join(", ", rule.among));
        }
        if (value instanceof Map) {
            Map<?, ?> fields = (Map<?, ?>) value;
            for (String name : rule.required) {
                if (!fields.containsKey(name)) {
                    wrong.add(it + " has no " + name);
                }
            }
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                Object key = field.getKey();
                if (rule.names != null && key instanceof String
                        && !rule.names.matcher((String) key).find()) {
                    wrong.add(it + ": '" + key + "' is not a name a var can have - a letter or "
                            + "_, then letters, digits or _");
                }
                Rule inner = key instanceof String ? rule.fields.get(key) : null;
                if (inner != null) {
                    follows(field.getValue(), inner, where.isEmpty() ? (String) key
                            : where + "." + key, wrong);
                } else if (rule.closed) {
                    wrong.add((where.isEmpty() ? "" : where + ": ") + "'" + key + "' is not a "
                            + "field " + (where.isEmpty() ? "of a DOSDP pattern" : "there"));
                }
            }
        }
        if (value instanceof List) {
            List<?> items = (List<?>) value;
            if (rule.least >= 0 && items.size() < rule.least
                    || rule.most >= 0 && items.size() > rule.most) {
                wrong.add(it + " has " + items.size() + " items, and has " + rule.least
                        + (rule.most == rule.least ? "" : " to " + rule.most));
            }
            if (rule.items != null) {
                for (int i = 0; i < items.size(); i++) {
                    follows(items.get(i), rule.items, it + "[" + i + "]", wrong);
                }
            }
        }
    }

    private static boolean isA(Object value, String type) {
        switch (type) {
            case TEXT:
                return value instanceof String;
            case LIST:
                return value instanceof List;
            case MAPPING:
                return value instanceof Map;
            default:
                return value instanceof Other && ((Other) value).kind.equals(TRUTH);
        }
    }

    private static String named(String type) {
        switch (type) {
            case TEXT:
                return "text";
            case LIST:
                return "a list";
            case MAPPING:
                return "a mapping";
            default:
                return "true or false";
        }
    }

    private static String described(Object value) {
        if (value == null) {
            return "empty";
        }
        if (value instanceof Map) {
            return "a mapping";
        }
        if (value instanceof List) {
            return "a list";
        }
        if (value instanceof Other) {
            return ((Other) value).kind + " (" + value + ")";
        }
        return "text";
    }

    // ------------------------------------------------------------------ what the format leaves

    /** A name an axiom's text quotes: as short a run as there is between two quotes. */
    private static final Pattern QUOTED = Pattern.compile("'(.+?)'", Pattern.UNIX_LINES);

    /** Where the pattern has vars: each list of vars below its top names declared ones only. */
    private static void varsNamed(Map<?, ?> pattern, List<String> wrong) {
        if (!pattern.containsKey("vars")) {
            return;
        }
        Set<Object> declared = new HashSet<Object>();
        for (String kind : new String[] {"vars", "list_vars", "data_vars", "data_list_vars"}) {
            Object vars = pattern.get(kind);
            if (vars instanceof Map) {
                declared.addAll(((Map<?, ?>) vars).keySet());
            }
        }
        Object internal = pattern.get("internal_vars");
        if (internal instanceof List) {
            List<?> items = (List<?>) internal;
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                Object name = item instanceof Map ? ((Map<?, ?>) item).get("var_name") : null;
                if (name == null || name instanceof Map || name instanceof List) {
                    wrong.add("internal_vars[" + i + "] has no var_name, so the vars the pattern "
                            + "declares cannot be told");
                    return;
                }
                declared.add(name);
            }
        }
        for (Map.Entry<?, ?> field : pattern.entrySet()) {
            varLists(field.getValue(), String.valueOf(field.getKey()), declared, wrong);
        }
    }

    /** Each value under a key {@code vars}, at any depth below a field. */
    private static void varLists(Object value, String where, Set<Object> declared,
            List<String> wrong) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> field : ((Map<?, ?>) value).entrySet()) {
                String here = where + "." + field.getKey();
                if ("vars".equals(field.getKey())) {
                    namesDeclared(field.getValue(), here, declared, wrong);
                }
                varLists(field.getValue(), here, declared, wrong);
            }
        } else if (value instanceof List) {
            List<?> items = (List<?>) value;
            for (int i = 0; i < items.size(); i++) {
                varLists(items.get(i), where + "[" + i + "]", declared, wrong);
            }
        }
    }

    private static void namesDeclared(Object vars, String where, Set<Object> declared,
            List<String> wrong) {
        List<Object> names = new ArrayList<Object>();
        if (vars instanceof List) {
            names.addAll((List<?>) vars);
        } else if (vars instanceof Map) {
            names.addAll(((Map<?, ?>) vars).keySet());
        } else if (vars instanceof String) {
            for (char letter : ((String) vars).toCharArray()) {
                names.add(String.valueOf(letter));
            }
        } else {
            wrong.add(where + " is " + described(vars) + ", not a list of vars");
            return;
        }
        for (Object name : names) {
            if (name instanceof Map || name instanceof List) {
                wrong.add(where + " holds " + described(name) + ", which is not a var");
            } else if (!declared.contains(name)) {
                wrong.add(where + ": '" + name + "' is not one of the pattern's vars");
            }
        }
    }

    /** Each OWL axiom's text: its quotes paired, and what it quotes a class or a relation. */
    private static void axiomTexts(Map<?, ?> pattern, List<String> wrong) {
        Set<Object> entities = new HashSet<Object>();
        for (String dictionary : new String[] {"classes", "relations"}) {
            Object names = pattern.get(dictionary);
            if (names instanceof Map) {
                entities.addAll(((Map<?, ?>) names).keySet());
            }
        }
        for (String block : AXIOMS) {
            if (pattern.get(block) instanceof Map) {
                axiomText((Map<?, ?>) pattern.get(block), block, entities, wrong);
            }
        }
        if (pattern.get("logical_axioms") instanceof List) {
            List<?> axioms = (List<?>) pattern.get("logical_axioms");
            for (int i = 0; i < axioms.size(); i++) {
                if (axioms.get(i) instanceof Map) {
                    axiomText((Map<?, ?>) axioms.get(i), "logical_axioms[" + i + "]", entities,
                            wrong);
                }
            }
        }
    }

    private static void axiomText(Map<?, ?> axiom, String where, Set<Object> entities,
            List<String> wrong) {
        if (axiom.containsKey("text")) {
            quotes(axiom.get("text"), where + ".text", entities, wrong);
        }
        if (axiom.containsKey("multi_clause")) {
            clauseTexts(axiom.get("multi_clause"), where + ".multi_clause", entities, wrong);
        }
    }

    /** Every text at any depth of a block's clauses. */
    private static void clauseTexts(Object value, String where, Set<Object> entities,
            List<String> wrong) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> field : ((Map<?, ?>) value).entrySet()) {
                String here = where + "." + field.getKey();
                if ("text".equals(field.getKey())) {
                    quotes(field.getValue(), here, entities, wrong);
                }
                clauseTexts(field.getValue(), here, entities, wrong);
            }
        } else if (value instanceof List) {
            List<?> items = (List<?>) value;
            for (int i = 0; i < items.size(); i++) {
                clauseTexts(items.get(i), where + "[" + i + "]", entities, wrong);
            }
        }
    }

    private static void quotes(Object text, String where, Set<Object> entities,
            List<String> wrong) {
        if (!(text instanceof String)) {
            return;
        }
        String words = (String) text;
        int quotes = 0;
        for (char letter : words.toCharArray()) {
            quotes += letter == '\'' ? 1 : 0;
        }
        if (quotes % 2 != 0) {
            wrong.add(where + " has an odd number of single quotes, so what it quotes cannot "
                    + "be told");
            return;
        }
        Matcher quoted = QUOTED.matcher(words);
        while (quoted.find()) {
            if (!entities.contains(quoted.group(1))) {
                wrong.add(where + ": '" + quoted.group(1) + "' is neither one of the pattern's "
                        + "classes nor one of its relations");
            }
        }
    }

    /** Each of the pattern's annotations names one of its annotation properties. */
    private static void annotationProperties(Map<?, ?> pattern, List<String> wrong) {
        if (!(pattern.get("annotations") instanceof List)) {
            return;
        }
        Set<Object> declared = pattern.get("annotationProperties") instanceof Map
                ? new HashSet<Object>(((Map<?, ?>) pattern.get("annotationProperties")).keySet())
                : Collections.emptySet();
        List<?> annotations = (List<?>) pattern.get("annotations");
        for (int i = 0; i < annotations.size(); i++) {
            Object annotation = annotations.get(i);
            if (annotation instanceof Map
                    && ((Map<?, ?>) annotation).containsKey("annotationProperty")) {
                Object property = ((Map<?, ?>) annotation).get("annotationProperty");
                if (!declared.contains(property)) {
                    wrong.add("annotations[" + i + "]: '" + property + "' is not one of the "
                            + "annotation properties the pattern declares");
                }
            }
        }
    }
}
