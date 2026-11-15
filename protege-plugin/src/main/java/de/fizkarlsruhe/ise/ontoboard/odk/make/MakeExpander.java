package de.fizkarlsruhe.ise.ontoboard.odk.make;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Expands text as GNU make 4.3's {@code expand.c} and {@code function.c} do - for the variables
 * and the functions ODK's template uses - and refuses the rest.
 *
 * <p>Variables are looked up innermost first: the loop variables of the {@code foreach}es being
 * expanded, then the automatic variables of the recipe being expanded, then the makefile's.
 */
final class MakeExpander {

    /** The functions of ODK's template (and {@code realpath}): name, fewest and most arguments. */
    private static final Map<String, int[]> FUNCTIONS = new HashMap<String, int[]>();
    /** The functions whose arguments are expanded before the call ({@code foreach}'s are not). */
    private static final Set<String> RAW_ARGUMENTS = new HashSet<String>(Arrays.asList("foreach"));

    static {
        FUNCTIONS.put("foreach", new int[] {3, 3});
        FUNCTIONS.put("patsubst", new int[] {3, 3});
        FUNCTIONS.put("addprefix", new int[] {2, 2});
        FUNCTIONS.put("word", new int[] {2, 2});
        FUNCTIONS.put("shell", new int[] {0, 1});
        FUNCTIONS.put("wildcard", new int[] {0, 1});
        FUNCTIONS.put("notdir", new int[] {0, 1});
        FUNCTIONS.put("sort", new int[] {0, 1});
        FUNCTIONS.put("strip", new int[] {0, 1});
        FUNCTIONS.put("realpath", new int[] {0, 1});
        FUNCTIONS.put("eval", new int[] {0, 1});
    }

    /** GNU make's other functions: named in the refusal, so that it says what it saw. */
    private static final Set<String> OTHER_FUNCTIONS = new HashSet<String>(Arrays.asList(
            "abspath", "addsuffix", "and", "basename", "call", "dir", "error", "file", "filter",
            "filter-out", "findstring", "firstword", "flavor", "guile", "if", "info", "join",
            "lastword", "let", "load", "or", "origin", "subst", "suffix", "value", "warning",
            "wordlist", "words", "intcmp"));

    /** The automatic variables ODK's template uses. */
    static final Set<String> AUTOMATIC = new HashSet<String>(Arrays.asList("@", "<", "^", "*",
            "|"));

    /**
     * Variables make defines or reads itself that ODK's template never mentions: reading one
     * would be a guess at what make would have there.
     */
    private static final Set<String> SPECIAL = new HashSet<String>(Arrays.asList("MAKEFLAGS",
            "MFLAGS", "MAKEOVERRIDES", "MAKEFILE_LIST", "MAKEFILES", ".VARIABLES", ".FEATURES",
            ".INCLUDE_DIRS", ".DEFAULT_GOAL", ".RECIPEPREFIX", ".SHELLFLAGS", ".EXTRA_PREREQS",
            ".LOADED", "MAKE_HOST", "MAKE_RESTARTS", "MAKE_TERMOUT", "MAKE_TERMERR", "VPATH",
            "GPATH", "SUFFIXES", ".LIBPATTERNS", "CURDIR", "MAKELEVEL", "MAKE_VERSION",
            "MAKE_COMMAND", "MAKECMDGOALS"));

    /** GNU make's built-in variables for its built-in rules ({@code make -p}). */
    private static final Set<String> BUILT_IN = new HashSet<String>(Arrays.asList("AR",
            "ARFLAGS", "AS", "ASFLAGS", "CC", "CFLAGS", "CO", "COFLAGS", "CPP", "CPPFLAGS",
            "CTANGLE", "CWEAVE", "CXX", "CXXFLAGS", "F77", "F77FLAGS", "FC", "FFLAGS", "GET",
            "GFLAGS", "LD", "LDFLAGS", "LDLIBS", "LEX", "LFLAGS", "LINT", "LINTFLAGS", "M2C",
            "MAKEINFO", "OBJC", "OUTPUT_OPTION", "PC", "PFLAGS", "RFLAGS", "RM", "TANGLE", "TEX",
            "TEXI2DVI", "WEAVE", "YACC", "YFLAGS"));

    private final MakeDatabase db;
    private final Deque<Map<String, String>> loops = new ArrayDeque<Map<String, String>>();
    private Map<String, String> automatic;
    private String file;
    private int line;
    private MakeReader reader;

    MakeExpander(MakeDatabase db) {
        this.db = db;
    }

    void reader(MakeReader reader) {
        this.reader = reader;
    }

    /** Where the text being expanded is written, for messages. */
    void at(String file, int line) {
        this.file = file;
        this.line = line;
    }

    /** The automatic variables of the recipe being expanded; null outside a recipe. */
    void automatic(Map<String, String> automatic) {
        this.automatic = automatic;
    }

    MakeFile.MakeException error(String message) {
        return MakeFile.MakeException.at(file, line, message);
    }

    // ------------------------------------------------------------- expansion

    /** {@code variable_expand_string}. */
    String expand(String text) {
        StringBuilder out = new StringBuilder();
        int length = text.length();
        int p = 0;
        while (true) {
            int dollar = text.indexOf('$', p);
            if (dollar < 0) {
                out.append(text, p, length);
                break;
            }
            out.append(text, p, dollar);
            p = dollar + 1;
            if (p >= length) {
                break;
            }
            char c = text.charAt(p);
            if (c == '$') {
                out.append('$');
                p++;
                continue;
            }
            if (c == '(' || c == '{') {
                int end = function(text, p, out);
                if (end >= 0) {
                    p = end + 1;
                    continue;
                }
                char close = c == '(' ? ')' : '}';
                int begin = p + 1;
                int first = text.indexOf(close, begin);
                if (first < 0) {
                    throw error("unterminated variable reference");
                }
                String name;
                int stop;
                if (text.substring(begin, first).indexOf('$') >= 0) {
                    int count = 0;
                    int q;
                    for (q = begin; q < length; q++) {
                        char ch = text.charAt(q);
                        if (ch == c) {
                            count++;
                        } else if (ch == close && --count < 0) {
                            break;
                        }
                    }
                    if (count >= 0) {
                        throw error("unterminated variable reference");
                    }
                    name = expand(text.substring(begin, q));
                    stop = q;
                } else {
                    name = text.substring(begin, first);
                    stop = first;
                }
                if (name.indexOf(':') >= 0) {
                    throw error("a substitution reference $(" + name + ") is not part of what"
                            + " ODK's template uses");
                }
                for (int k = 0; k < name.length(); k++) {
                    if (Character.isWhitespace(name.charAt(k))) {
                        throw error("$(" + name + ") calls no function this reader knows, and a"
                                + " variable name with a space is not something ODK's template"
                                + " has");
                    }
                }
                out.append(reference(name));
                p = stop + 1;
                continue;
            }
            out.append(reference(String.valueOf(c)));
            p++;
        }
        return out.toString();
    }

    /** The value a variable reference reads, expanded. */
    private String reference(String name) {
        for (Map<String, String> loop : loops) {
            if (loop.containsKey(name)) {
                return loop.get(name);
            }
        }
        if (AUTOMATIC.contains(name)) {
            String value = automatic == null ? null : automatic.get(name);
            return value == null ? "" : value;
        }
        if (SPECIAL.contains(name)) {
            throw error("$(" + name + ") is set by make itself, and ODK's template does not"
                    + " read it");
        }
        MakeDatabase.Var var = db.variable(name);
        if (var == null) {
            if (builtIn(name)) {
                throw error("$(" + name + ") is one of make's built-in variables, which this"
                        + " reader does not know");
            }
            return "";
        }
        if (var.value.isEmpty()) {
            return "";
        }
        if (!var.recursive) {
            return var.value;
        }
        if (var.expanding) {
            throw error("Recursive variable '" + name + "' references itself (eventually)");
        }
        var.expanding = true;
        try {
            return expand(var.value);
        } finally {
            var.expanding = false;
        }
    }

    static boolean builtIn(String name) {
        return BUILT_IN.contains(name) || name.startsWith("COMPILE.") || name.startsWith("LINK.")
                || name.startsWith("PREPROCESS.") || name.startsWith("LEX.")
                || name.startsWith("YACC.") || name.startsWith("LINT.");
    }

    /**
     * {@code handle_function}: if a function call starts at the paren, expands it into
     * {@code out} and returns the index of its closing paren; -1 if it is no function.
     */
    private int function(String text, int open, StringBuilder out) {
        char openParen = text.charAt(open);
        char closeParen = openParen == '(' ? ')' : '}';
        int length = text.length();
        int begin = open + 1;
        int e = begin;
        while (e < length && functionNameChar(text.charAt(e))) {
            e++;
        }
        if (e == begin || e >= length || !Character.isWhitespace(text.charAt(e))) {
            return -1;
        }
        String name = text.substring(begin, e);
        int[] arity = FUNCTIONS.get(name);
        if (arity == null) {
            if (OTHER_FUNCTIONS.contains(name)) {
                throw error("the function '" + name + "' is not one ODK's template uses");
            }
            return -1;
        }
        int args = e;
        while (args < length && Character.isWhitespace(text.charAt(args))) {
            args++;
        }
        int count = 0;
        int end;
        for (end = args; end < length; end++) {
            char c = text.charAt(end);
            if (c == openParen) {
                count++;
            } else if (c == closeParen && --count < 0) {
                break;
            }
        }
        if (end >= length) {
            throw error("unterminated call to function '" + name + "': missing '" + closeParen
                    + "'");
        }
        List<String> raw = new ArrayList<String>();
        int position = args;
        while (position <= end) {
            int next = raw.size() + 1 == arity[1] ? -1
                    : nextArgument(text, openParen, closeParen, position, end);
            if (next < 0) {
                next = end;
            }
            raw.add(text.substring(position, next));
            position = next + 1;
        }
        if (raw.size() < arity[0]) {
            throw error("insufficient number of arguments (" + raw.size() + ") to function '"
                    + name + "'");
        }
        List<String> arguments = raw;
        if (!RAW_ARGUMENTS.contains(name)) {
            arguments = new ArrayList<String>();
            for (String argument : raw) {
                arguments.add(expand(argument));
            }
        }
        out.append(call(name, arguments));
        return end;
    }

    /** {@code find_next_argument}: the next comma at this level, or -1. */
    private static int nextArgument(String text, char open, char close, int from, int end) {
        int count = 0;
        for (int p = from; p < end; p++) {
            char c = text.charAt(p);
            if (c == open) {
                count++;
            } else if (c == close) {
                if (--count < 0) {
                    return -1;
                }
            } else if (c == ',' && count == 0) {
                return p;
            }
        }
        return -1;
    }

    private static boolean functionNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
    }

    private String call(String name, List<String> a) {
        if (name.equals("foreach")) {
            return foreach(a.get(0), a.get(1), a.get(2));
        } else if (name.equals("patsubst")) {
            return patsubst(a.get(0), a.get(1), a.get(2));
        } else if (name.equals("addprefix")) {
            StringBuilder out = new StringBuilder();
            for (String word : words(a.get(1))) {
                out.append(a.get(0)).append(word).append(' ');
            }
            return killLastSpace(out);
        } else if (name.equals("word")) {
            return word(a.get(0), a.get(1));
        } else if (name.equals("shell")) {
            return foldNewlines(db.shell.run(a.get(0)));
        } else if (name.equals("wildcard")) {
            return wildcard(a.get(0));
        } else if (name.equals("notdir")) {
            StringBuilder out = new StringBuilder();
            for (String word : words(a.get(0))) {
                out.append(word.substring(word.lastIndexOf('/') + 1)).append(' ');
            }
            return killLastSpace(out);
        } else if (name.equals("sort")) {
            TreeSet<String> sorted = new TreeSet<String>(CODE_POINTS);
            sorted.addAll(words(a.get(0)));
            return String.join(" ", sorted);
        } else if (name.equals("strip")) {
            return String.join(" ", words(a.get(0)));
        } else if (name.equals("realpath")) {
            StringBuilder out = new StringBuilder();
            for (String word : words(a.get(0))) {
                String real = realpath(word);
                if (real != null) {
                    out.append(real).append(' ');
                }
            }
            return killLastSpace(out);
        } else if (name.equals("eval")) {
            if (reader == null) {
                throw error("$(eval) where nothing can be read");
            }
            reader.evalText(a.get(0), file, line);
            return "";
        }
        throw error("the function '" + name + "' is not one ODK's template uses");
    }

    private String foreach(String variable, String list, String body) {
        List<String> names = words(expand(variable));
        String name = names.isEmpty() ? "" : names.get(0);
        Map<String, String> loop = new HashMap<String, String>();
        loops.addFirst(loop);
        StringBuilder out = new StringBuilder();
        try {
            for (String word : words(expand(list))) {
                loop.put(name, word);
                out.append(expand(body)).append(' ');
            }
        } finally {
            loops.removeFirst();
        }
        return killLastSpace(out);
    }

    /** {@code patsubst_expand_pat}. */
    private String patsubst(String pattern, String replace, String text) {
        checkNoEscapedPercent(pattern);
        checkNoEscapedPercent(replace);
        int percent = pattern.indexOf('%');
        if (percent < 0) {
            return substituteWords(text, pattern, replace);
        }
        String before = pattern.substring(0, percent);
        String after = pattern.substring(percent + 1);
        int replacePercent = replace.indexOf('%');
        String replaceBefore = replacePercent < 0 ? replace : replace.substring(0, replacePercent);
        String replaceAfter = replacePercent < 0 ? "" : replace.substring(replacePercent + 1);
        StringBuilder out = new StringBuilder();
        boolean doneAny = false;
        for (String word : words(text)) {
            boolean fail = word.length() < before.length() + after.length()
                    || !word.startsWith(before) || !word.endsWith(after);
            if (fail) {
                out.append(word);
            } else {
                out.append(replaceBefore);
                if (replacePercent >= 0) {
                    out.append(word, before.length(), word.length() - after.length());
                    out.append(replaceAfter);
                }
            }
            if (fail || replaceBefore.length() > 0
                    || (replacePercent >= 0 && word.length() + replaceAfter.length() > 0)) {
                out.append(' ');
                doneAny = true;
            }
        }
        if (doneAny) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private void checkNoEscapedPercent(String text) {
        if (text.contains("\\%")) {
            throw error("an escaped % in patsubst is not part of what ODK's template uses");
        }
    }

    /** {@code subst_expand} by word: patsubst with a pattern without {@code %}. */
    private static String substituteWords(String text, String subst, String replace) {
        StringBuilder out = new StringBuilder();
        int t = 0;
        if (subst.isEmpty()) {
            return text;
        }
        while (t < text.length()) {
            int p = text.indexOf(subst, t);
            if (p < 0) {
                out.append(text, t, text.length());
                return out.toString();
            }
            out.append(text, t, p);
            int after = p + subst.length();
            boolean wholeWord = (p == 0 || Character.isWhitespace(text.charAt(p - 1)))
                    && (after >= text.length() || Character.isWhitespace(text.charAt(after)));
            out.append(wholeWord ? replace : subst);
            t = after;
        }
        return out.toString();
    }

    private String word(String number, String text) {
        String n = number.trim();
        if (n.isEmpty() || !n.matches("[0-9]+")) {
            throw error("non-numeric first argument to 'word' function: '" + number + "'");
        }
        int index = Integer.parseInt(n);
        if (index == 0) {
            throw error("first argument to 'word' function must be greater than 0");
        }
        List<String> words = words(text);
        return index <= words.size() ? words.get(index - 1) : "";
    }

    /** {@code $(wildcard)}: each word's matches through the disk, sorted; a plain word if it exists. */
    private String wildcard(String patterns) {
        List<String> found = new ArrayList<String>();
        for (String word : words(patterns)) {
            if (word.indexOf('[') >= 0 || word.startsWith("~")) {
                throw error("the wildcard '" + word + "' is not of the forms ODK's template uses");
            }
            if (word.indexOf('*') < 0 && word.indexOf('?') < 0) {
                if (db.disk.exists(word)) {
                    found.add(word);
                }
                continue;
            }
            int slash = word.lastIndexOf('/');
            String prefix = slash < 0 ? "" : word.substring(0, slash + 1);
            String folder = slash < 0 ? "." : (slash == 0 ? "/" : word.substring(0, slash));
            String name = word.substring(slash + 1);
            if (prefix.indexOf('*') >= 0 || prefix.indexOf('?') >= 0) {
                throw error("the wildcard '" + word + "' has a pattern in a folder name, which"
                        + " ODK's template does not");
            }
            TreeSet<String> matches = new TreeSet<String>(CODE_POINTS);
            for (String candidate : db.disk.list(folder)) {
                if (globMatches(name, candidate)) {
                    matches.add(prefix + candidate);
                }
            }
            found.addAll(matches);
        }
        return String.join(" ", found);
    }

    /** {@code *} and {@code ?} as glob(3) without GLOB_PERIOD: a leading dot must be matched. */
    static boolean globMatches(String pattern, String name) {
        if (name.startsWith(".") && !pattern.startsWith(".")) {
            return false;
        }
        return globFrom(pattern, 0, name, 0);
    }

    private static boolean globFrom(String pattern, int p, String name, int n) {
        while (p < pattern.length()) {
            char c = pattern.charAt(p);
            if (c == '*') {
                for (int k = n; k <= name.length(); k++) {
                    if (globFrom(pattern, p + 1, name, k)) {
                        return true;
                    }
                }
                return false;
            }
            if (n >= name.length() || (c != '?' && c != name.charAt(n))) {
                return false;
            }
            p++;
            n++;
        }
        return n == name.length();
    }

    /**
     * {@code realpath(3)} through the disk: every folder on the way must exist; the result is
     * absolute, without {@code .} and {@code ..}. Symbolic links are not looked at.
     */
    private String realpath(String word) {
        boolean absolute = word.startsWith("/");
        StringBuilder spelled = new StringBuilder(absolute ? "/" : "");
        for (String part : word.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (spelled.length() > 0 && spelled.charAt(spelled.length() - 1) != '/') {
                spelled.append('/');
            }
            spelled.append(part);
            if (!part.equals("..") && !db.disk.exists(spelled.toString())) {
                return null;
            }
        }
        String full = absolute ? word : db.disk.folder() + "/" + word;
        List<String> parts = new ArrayList<String>();
        for (String part : full.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(part);
        }
        boolean rooted = full.startsWith("/");
        String joined = String.join("/", parts);
        return rooted ? "/" + joined : joined;
    }

    /** {@code fold_newlines}: line breaks become spaces, the trailing ones go. */
    static String foldNewlines(String output) {
        String text = output.replace("\r\n", "\n");
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end).replace('\n', ' ');
    }

    /** Words as make's {@code find_next_token} finds them: split at whitespace. */
    static List<String> words(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(Arrays.asList(trimmed.split("\\s+")));
    }

    private static String killLastSpace(StringBuilder out) {
        if (out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /** Strings by code point, as {@code strcmp} on UTF-8 (and Python's {@code sorted}) order them. */
    static final Comparator<String> CODE_POINTS = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            int i = 0;
            int j = 0;
            while (i < a.length() && j < b.length()) {
                int x = a.codePointAt(i);
                int y = b.codePointAt(j);
                if (x != y) {
                    return x < y ? -1 : 1;
                }
                i += Character.charCount(x);
                j += Character.charCount(y);
            }
            return (a.length() - i) - (b.length() - j);
        }
    };

    // ------------------------------------------------------------- refusing, when reading

    /**
     * Refuses, with the makefile and line, any reference in the text that ODK's template has no
     * like of: a function outside its list, a substitution reference, an automatic variable
     * other than {@code $@ $< $^ $* $|}, a one-letter variable, a variable make sets itself.
     */
    void scan(String text, String file, int line) {
        String keepFile = this.file;
        int keepLine = this.line;
        at(file, line);
        try {
            scan(text);
        } finally {
            at(keepFile, keepLine);
        }
    }

    private void scan(String text) {
        int length = text.length();
        int p = 0;
        while (true) {
            int dollar = text.indexOf('$', p);
            if (dollar < 0) {
                return;
            }
            if (dollar + 1 >= length) {
                throw error("a '$' at the end of a line");
            }
            char c = text.charAt(dollar + 1);
            if (c == '$') {
                p = dollar + 2;
                continue;
            }
            if (c != '(' && c != '{') {
                if (!AUTOMATIC.contains(String.valueOf(c))) {
                    throw error("$" + c + " is not one of the automatic variables ODK's template"
                            + " uses ($@ $< $^ $* $|), nor a reference in parentheses");
                }
                p = dollar + 2;
                continue;
            }
            char close = c == '(' ? ')' : '}';
            int count = 0;
            int end;
            for (end = dollar + 2; end < length; end++) {
                char ch = text.charAt(end);
                if (ch == c) {
                    count++;
                } else if (ch == close && --count < 0) {
                    break;
                }
            }
            if (end >= length) {
                throw error("unterminated variable reference");
            }
            String inner = text.substring(dollar + 2, end);
            int e = 0;
            while (e < inner.length() && functionNameChar(inner.charAt(e))) {
                e++;
            }
            if (e > 0 && e < inner.length() && Character.isWhitespace(inner.charAt(e))) {
                String name = inner.substring(0, e);
                if (!FUNCTIONS.containsKey(name)) {
                    throw error("the function '" + name + "' is not one ODK's template uses");
                }
                scan(inner.substring(e));
            } else {
                checkReference(inner);
                scan(inner);
            }
            p = end + 1;
        }
    }

    private void checkReference(String inner) {
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '$' && i + 1 < inner.length()
                    && (inner.charAt(i + 1) == '(' || inner.charAt(i + 1) == '{')) {
                depth++;
                i++;
            } else if ((ch == ')' || ch == '}') && depth > 0) {
                depth--;
            } else if (depth == 0 && ch == ':') {
                throw error("a substitution reference $(" + inner + ") is not part of what ODK's"
                        + " template uses");
            } else if (depth == 0 && Character.isWhitespace(ch)) {
                throw error("$(" + inner + ") calls no function this reader knows, and a"
                        + " variable name with a space is not something ODK's template has");
            }
        }
        if (inner.isEmpty()) {
            throw error("$() names no variable");
        }
        if (inner.indexOf('$') < 0) {
            if (inner.length() == 2 && "@<^*|?+%".indexOf(inner.charAt(0)) >= 0
                    && (inner.charAt(1) == 'D' || inner.charAt(1) == 'F')) {
                throw error("$(" + inner + ") is not one of the automatic variables ODK's"
                        + " template uses");
            }
            if (inner.length() == 1 && !AUTOMATIC.contains(inner)
                    && !Character.isLetterOrDigit(inner.charAt(0)) && inner.charAt(0) != '_') {
                throw error("$(" + inner + ") is not one of the automatic variables ODK's"
                        + " template uses");
            }
            if (SPECIAL.contains(inner)) {
                throw error("$(" + inner + ") is set by make itself, and ODK's template does"
                        + " not read it");
            }
        }
    }

    // ------------------------------------------------------------- what text reads

    /** Every variable expanding the text reads, through recursive variables' values too. */
    Set<String> variablesReadBy(String text) {
        Set<String> read = new TreeSet<String>(CODE_POINTS);
        walk(text, Collections.<String>emptySet(), read, new HashSet<String>());
        return read;
    }

    private void walk(String text, Set<String> local, Set<String> read, Set<String> visited) {
        int length = text.length();
        int p = 0;
        while (true) {
            int dollar = text.indexOf('$', p);
            if (dollar < 0 || dollar + 1 >= length) {
                return;
            }
            char c = text.charAt(dollar + 1);
            if (c == '$') {
                p = dollar + 2;
                continue;
            }
            if (c != '(' && c != '{') {
                note(String.valueOf(c), local, read, visited);
                p = dollar + 2;
                continue;
            }
            char close = c == '(' ? ')' : '}';
            int count = 0;
            int end;
            for (end = dollar + 2; end < length; end++) {
                char ch = text.charAt(end);
                if (ch == c) {
                    count++;
                } else if (ch == close && --count < 0) {
                    break;
                }
            }
            if (end >= length) {
                return;
            }
            String inner = text.substring(dollar + 2, end);
            int e = 0;
            while (e < inner.length() && functionNameChar(inner.charAt(e))) {
                e++;
            }
            if (e > 0 && e < inner.length() && Character.isWhitespace(inner.charAt(e))
                    && FUNCTIONS.containsKey(inner.substring(0, e))) {
                String arguments = inner.substring(e);
                if (inner.substring(0, e).equals("foreach")) {
                    List<String> parts = splitArguments(arguments.trim(), c, close, 3);
                    if (parts.size() == 3) {
                        walk(parts.get(0), local, read, visited);
                        walk(parts.get(1), local, read, visited);
                        Set<String> inside = new HashSet<String>(local);
                        List<String> names = words(parts.get(0));
                        if (!names.isEmpty()) {
                            inside.add(parts.get(0).indexOf('$') >= 0
                                    ? words(expand(parts.get(0))).get(0) : names.get(0));
                        }
                        walk(parts.get(2), inside, read, visited);
                    } else {
                        walk(arguments, local, read, visited);
                    }
                } else {
                    walk(arguments, local, read, visited);
                }
            } else if (inner.indexOf('$') >= 0) {
                walk(inner, local, read, visited);
                note(expand(inner), local, read, visited);
            } else {
                note(inner, local, read, visited);
            }
            p = end + 1;
        }
    }

    private void note(String name, Set<String> local, Set<String> read, Set<String> visited) {
        if (local.contains(name) || AUTOMATIC.contains(name)) {
            return;
        }
        read.add(name);
        MakeDatabase.Var var = db.variable(name);
        if (var != null && var.recursive && visited.add(name)) {
            walk(var.value, local, read, visited);
        }
    }

    private static List<String> splitArguments(String text, char open, char close, int most) {
        List<String> parts = new ArrayList<String>();
        int position = 0;
        int end = text.length();
        while (position <= end) {
            int next = parts.size() + 1 == most ? -1
                    : nextArgument(text, open, close, position, end);
            if (next < 0) {
                next = end;
            }
            parts.add(text.substring(position, next));
            position = next + 1;
        }
        return parts;
    }

    // ------------------------------------------------------------- recipes

    /**
     * {@code new_job}'s first step: a backslash-newline inside a variable or function reference
     * of a recipe line is eaten with the whitespace around it, so the function does not see it;
     * outside references it stays, for the echo and the shell.
     */
    static String collapseInReferences(String line) {
        StringBuilder out = new StringBuilder();
        int in = 0;
        int length = line.length();
        while (true) {
            int ref = line.indexOf('$', in);
            if (ref < 0) {
                break;
            }
            ref++;
            out.append(line, in, ref);
            in = ref;
            if (ref < length && (line.charAt(ref) == '(' || line.charAt(ref) == '{')) {
                char open = line.charAt(ref);
                char close = open == '(' ? ')' : '}';
                out.append(open);
                in++;
                int outRef = out.length();
                int count = 0;
                while (in < length) {
                    char c = line.charAt(in);
                    if (c == close && --count < 0) {
                        break;
                    } else if (c == '\\' && in + 1 < length && line.charAt(in + 1) == '\n') {
                        boolean quoted = false;
                        for (int p = in - 1; p > ref && line.charAt(p) == '\\'; p--) {
                            quoted = !quoted;
                        }
                        if (quoted) {
                            out.append(c);
                            in++;
                        } else {
                            in += 2;
                            while (in < length && Character.isWhitespace(line.charAt(in))) {
                                in++;
                            }
                            while (out.length() > outRef && (out.charAt(out.length() - 1) == ' '
                                    || out.charAt(out.length() - 1) == '\t')) {
                                out.setLength(out.length() - 1);
                            }
                            out.append(' ');
                        }
                    } else {
                        if (c == open) {
                            count++;
                        }
                        out.append(c);
                        in++;
                    }
                }
            }
        }
        out.append(line, in, length);
        return out.toString();
    }
}
