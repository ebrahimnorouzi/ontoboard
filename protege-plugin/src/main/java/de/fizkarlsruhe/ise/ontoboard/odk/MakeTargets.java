package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The targets a project's Makefile actually offers.
 *
 * <p>Replaces a method that took a project, opened its Makefile only to check the file existed, and
 * then returned six hardcoded names: {@code all test reason report prepare_release clean}. That is
 * fine for a Makefile this plugin generated and wrong for every other one. The reference project
 * {@code ISE-FIZKarlsruhe/mwo} has around thirty, including the {@code mirror-*} and {@code
 * all_imports} targets that are the whole point of an ODK build - so a menu built on the hardcoded
 * list would omit everything a user opened the project to run, while confidently offering six names
 * that might not exist.
 *
 * <p>Parsing a Makefile properly is a large job; parsing one well enough to list its targets is
 * not, because the shape of a rule is simple and the things that are <em>not</em> targets are
 * few and recognisable. What this deliberately excludes is set out in {@link #parse}.
 */
public final class MakeTargets {

    /**
     * A rule line: a name, a colon, and not an assignment.
     *
     * <p>The {@code [^=]} guard before the colon is what keeps {@code ONT_ID := mwo} out - GNU Make
     * assignment operators ({@code :=}, {@code ::=}, {@code +=}, {@code ?=}) all contain a colon or
     * an equals, and a naive {@code ^(\\w+):} matches the first of them.
     */
    private static final Pattern RULE =
            Pattern.compile("^([A-Za-z0-9][A-Za-z0-9_.@%/-]*)\\s*:(?!=)([^=].*|)$");

    /** Built-in targets that are directives about the build, not things to run. */
    private static final Set<String> DIRECTIVES = new LinkedHashSet<String>(java.util.Arrays.asList(
            ".PHONY", ".PRECIOUS", ".SECONDARY", ".SUFFIXES", ".DEFAULT", ".IGNORE", ".SILENT",
            ".EXPORT_ALL_VARIABLES", ".NOTPARALLEL", ".ONESHELL", ".POSIX", ".DELETE_ON_ERROR",
            ".INTERMEDIATE", ".LOW_RESOLUTION_TIME", ".SECONDEXPANSION"));

    private MakeTargets() {
    }

    /** The targets in the Makefile beside {@code editFile}, or an empty list if there is none. */
    public static List<String> of(File editFile) {
        if (editFile == null || editFile.getParentFile() == null) {
            return Collections.emptyList();
        }
        File makefile = new File(editFile.getParentFile(), "Makefile");
        if (!makefile.isFile()) {
            return Collections.emptyList();
        }
        try {
            return parse(new String(Files.readAllBytes(makefile.toPath()),
                    StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            // An unreadable Makefile means no targets to offer, which is honest. Guessing a
            // standard set is what the previous version did, and it was wrong.
            return Collections.emptyList();
        }
    }

    /**
     * Target names from Makefile text, in file order, without duplicates.
     *
     * <p>Excluded, each for a reason:
     *
     * <ul>
     *   <li><b>Variable assignments</b> - {@code ONT := mwo} looks like a rule to a naive pattern.
     *   <li><b>Built-in directives</b> - {@code .PHONY} and friends configure make; running one
     *       does nothing useful.
     *   <li><b>Pattern rules</b> - {@code %.owl: %.obo} is a recipe for building a file from
     *       another, not a name a person can invoke.
     *   <li><b>File targets containing a slash or a dot</b> - {@code imports/iao_import.owl} is a
     *       product. It <em>can</em> be built by name, but a menu of two hundred file paths is not
     *       a menu, and the phony targets that build them are already listed.
     *   <li><b>Recipe lines</b> - anything indented with a tab is a command, and a command
     *       containing a colon (a URL, a Java option) would otherwise read as a rule.
     * </ul>
     */
    public static List<String> parse(String makefile) {
        List<String> targets = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        if (makefile == null) {
            return targets;
        }
        boolean inDefine = false;
        for (String line : makefile.split("\r?\n")) {
            String trimmedStart = line.trim();
            // A define block's body is arbitrary text and may contain anything colon-shaped.
            if (trimmedStart.startsWith("define ")) {
                inDefine = true;
                continue;
            }
            if (inDefine) {
                if (trimmedStart.equals("endef")) {
                    inDefine = false;
                }
                continue;
            }
            if (line.startsWith("\t") || trimmedStart.isEmpty() || trimmedStart.startsWith("#")) {
                continue;
            }
            Matcher rule = RULE.matcher(line);
            if (!rule.matches()) {
                continue;
            }
            String name = rule.group(1);
            if (DIRECTIVES.contains(name) || name.indexOf('%') >= 0 || name.indexOf('/') >= 0
                    || name.indexOf('.') >= 0) {
                continue;
            }
            if (seen.add(name)) {
                targets.add(name);
            }
        }
        return Collections.unmodifiableList(targets);
    }

    /**
     * The targets most worth offering first, in the order a user wants them.
     *
     * <p>An ODK Makefile's target list is long and mostly internal. These are the ones a person
     * actually types, and putting them first is the difference between a usable menu and a wall.
     * Anything not in this list is still offered, after them.
     */
    public static List<String> ordered(List<String> targets) {
        String[] preferred = {"all", "test", "reason", "report", "prepare_release", "release",
            "all_imports", "refresh-imports", "update_repo", "clean"};
        List<String> ordered = new ArrayList<String>();
        for (String name : preferred) {
            if (targets.contains(name)) {
                ordered.add(name);
            }
        }
        for (String name : targets) {
            if (!ordered.contains(name)) {
                ordered.add(name);
            }
        }
        return Collections.unmodifiableList(ordered);
    }
}
