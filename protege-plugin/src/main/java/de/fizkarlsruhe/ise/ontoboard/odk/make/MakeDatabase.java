package de.fizkarlsruhe.ise.ontoboard.odk.make;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What make knows after reading the makefiles - variables, files, pattern rules - and what a
 * run changes in it (deps found by implicit rules, times, which files were considered). A run
 * works on a database of its own, read afresh.
 *
 * <p>Names and fields follow GNU make 4.3's {@code struct file}, {@code struct dep},
 * {@code struct rule} and {@code struct variable}, so the code that uses them can be read
 * beside make's {@code remake.c} and {@code implicit.c}.
 */
final class MakeDatabase {

    /** {@code UNKNOWN_MTIME}: not looked at yet. */
    static final long UNKNOWN = 0L;
    /** {@code NONEXISTENT_MTIME}. */
    static final long NONEXISTENT = 1L;
    /** {@code NEW_MTIME}: newer than anything; what a dry run gives a file it "remade". */
    static final long NEW = Long.MAX_VALUE;
    /** Real times are stored above make's three special values. */
    static final long ORDINARY_BASE = 3L;

    /** GNU make 4.3's default {@code .SUFFIXES}, in its order: {@code $*} of an explicit rule. */
    static final List<String> DEFAULT_SUFFIXES = Arrays.asList(".out", ".a", ".ln", ".o", ".c",
            ".cc", ".C", ".cpp", ".p", ".f", ".F", ".m", ".r", ".y", ".l", ".ym", ".yl", ".s",
            ".S", ".mod", ".sym", ".def", ".h", ".info", ".dvi", ".tex", ".texinfo", ".texi",
            ".txinfo", ".w", ".ch", ".web", ".sh", ".elc", ".el");

    final Map<String, Var> variables = new LinkedHashMap<String, Var>();
    final Map<String, FileEntry> files = new LinkedHashMap<String, FileEntry>();
    final List<PatternRule> patternRules = new ArrayList<PatternRule>();
    final List<MakeFile.Rule> rules = new ArrayList<MakeFile.Rule>();
    /** The makefiles read, in the order make read them. */
    final List<String> makefiles = new ArrayList<String>();
    /** {@code file_impossible}: names an implicit rule search found no way to make. */
    final Set<String> impossible = new HashSet<String>();
    final MakeFile.Disk disk;
    final MakeFile.Shell shell;
    String defaultGoal;

    MakeDatabase(MakeFile.Disk disk, MakeFile.Shell shell) {
        this.disk = disk;
        this.shell = shell;
    }

    FileEntry lookup(String name) {
        return files.get(name);
    }

    /** {@code enter_file}: the entry for the name, made if there is none. */
    FileEntry enter(String name) {
        FileEntry file = files.get(name);
        if (file == null) {
            file = new FileEntry(name);
            files.put(name, file);
        }
        return file;
    }

    Var variable(String name) {
        return variables.get(name);
    }

    // ------------------------------------------------------------- matching patterns

    /**
     * The stem by which a target pattern matches a file name, or null. A pattern without a slash
     * matches the part of a name after its last slash, and the stem then begins with that
     * folder - as {@code pattern_search} does with {@code check_lastslash}.
     */
    static String stemOf(String pattern, String name) {
        Match match = match(pattern, name);
        return match == null ? null : match.fullStem();
    }

    /** How a target pattern matches a name: the stem, and the folder that was set aside. */
    static final class Match {
        final String stem;
        final String dir;

        Match(String stem, String dir) {
            this.stem = stem;
            this.dir = dir;
        }

        String fullStem() {
            return dir + stem;
        }

        /** The stem's length as {@code tryrules[].stemlen} counts it, folder included. */
        int length() {
            return dir.length() + stem.length();
        }
    }

    static Match match(String pattern, String name) {
        int percent = pattern.indexOf('%');
        if (percent < 0) {
            return null;
        }
        String prefix = pattern.substring(0, percent);
        String suffix = pattern.substring(percent + 1);
        // rule->lens[ti] > namelen: the % matches at least one character of the whole name.
        if (pattern.length() > name.length()) {
            return null;
        }
        // memrchr (filename, '/', namelen - 1): a slash at the very end does not count.
        int lastSlash = name.length() > 1 ? name.lastIndexOf('/', name.length() - 2) : -1;
        boolean checkLastSlash = lastSlash >= 0 && pattern.indexOf('/') < 0;
        String dir = checkLastSlash ? name.substring(0, lastSlash + 1) : "";
        String rest = name.substring(dir.length());
        int stemLength = rest.length() - prefix.length() - suffix.length();
        if (stemLength < 0 || !rest.startsWith(prefix) || !rest.endsWith(suffix)) {
            return null;
        }
        return new Match(rest.substring(prefix.length(), prefix.length() + stemLength), dir);
    }

    // ------------------------------------------------------------- the structures

    /** {@code struct variable}. */
    static final class Var {
        final String name;
        String value = "";
        boolean recursive = true;
        MakeFile.Origin origin = MakeFile.Origin.FILE;
        String file;
        int line;
        boolean exported;
        boolean expanding;
        final List<MakeFile.Line> assignments = new ArrayList<MakeFile.Line>();

        Var(String name) {
            this.name = name;
        }

        MakeFile.Variable view() {
            return new MakeFile.Variable(name, value, recursive, origin, file, line, exported,
                    assignments);
        }
    }

    /** {@code struct file}. */
    static final class FileEntry {
        final String name;
        List<Dep> deps = new ArrayList<Dep>();
        Recipe cmds;
        String stem;
        boolean isTarget;
        boolean phony;
        boolean intermediate;
        boolean triedImplicit;
        long lastMtime = UNKNOWN;
        long mtimeBeforeUpdate = UNKNOWN;
        int consideredPass = -1;
        boolean updated;
        boolean updating;
        FileEntry parent;

        FileEntry(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** {@code struct dep}: a prerequisite; {@code orderOnly} is make's {@code ignore_mtime}. */
    static final class Dep {
        String name;
        FileEntry file;
        boolean orderOnly;
        boolean changed;

        Dep(String name, FileEntry file, boolean orderOnly) {
            this.name = name;
            this.file = file;
            this.orderOnly = orderOnly;
        }

        String name() {
            return file != null ? file.name : name;
        }

        Dep copy() {
            return new Dep(name, file, orderOnly);
        }
    }

    /** A recipe: its lines as written and, once chopped, each line's flags. */
    static final class Recipe {
        static final int SILENT = 1;
        static final int RECURSE = 2;
        static final int NOERROR = 4;

        final List<MakeFile.Line> lines;
        int[] flags;

        Recipe(List<MakeFile.Line> lines) {
            this.lines = new ArrayList<MakeFile.Line>(lines);
        }

        /**
         * {@code chop_commands}: each line's flags from its prefixes as written, and RECURSE
         * where its text says {@code $(MAKE)} or {@code ${MAKE}}.
         */
        void chop() {
            if (flags != null) {
                return;
            }
            flags = new int[lines.size()];
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i).text();
                int p = 0;
                int flag = 0;
                while (p < text.length()) {
                    char c = text.charAt(p);
                    if (c == '+') {
                        flag |= RECURSE;
                    } else if (c == '@') {
                        flag |= SILENT;
                    } else if (c == '-') {
                        flag |= NOERROR;
                    } else if (c != ' ' && c != '\t') {
                        break;
                    }
                    p++;
                }
                String rest = text.substring(p);
                if ((flag & RECURSE) == 0 && (rest.contains("$(MAKE)") || rest.contains("${MAKE}"))) {
                    flag |= RECURSE;
                }
                flags[i] = flag;
            }
        }

        /** Whether the recipe is nothing but whitespace and prefixes ({@code execute_file_commands}). */
        boolean blank() {
            for (MakeFile.Line line : lines) {
                for (char c : line.text().toCharArray()) {
                    if (!Character.isWhitespace(c) && c != '-' && c != '@' && c != '+') {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /** {@code struct rule}: a pattern rule; one target pattern (ODK's have one). */
    static final class PatternRule {
        final String target;
        final List<Dep> deps;
        final Recipe cmds;
        final String file;
        final int line;
        boolean inUse;

        PatternRule(String target, List<Dep> deps, Recipe cmds, String file, int line) {
            this.target = target;
            this.deps = deps;
            this.cmds = cmds;
            this.file = file;
            this.line = line;
        }
    }

}
