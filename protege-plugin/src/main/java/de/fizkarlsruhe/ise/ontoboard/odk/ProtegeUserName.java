package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * The name Protege's own ID ranges know the person by - so the board claims and mints under it.
 *
 * <p>Protege 5.6 and later read a project's {@code <id>-idranges.owl} themselves and, when the
 * person creates a term, look for the range allocated to them; where there is none they ask,
 * "No Id Range Found For Current User". Read from protege-editor-owl 5.6.9
 * ({@code IdPolicyEntityCreationPreferencesUpdater}, {@code DefaultUserNameProvider},
 * {@code UserNamePreferencesManager}): the name is git's user name for the ontology's
 * repository where Preferences &gt; User details asks for it ({@code use.git.user.name}, off
 * unless ticked), else the user name set there ({@code user.name} of the preferences
 * {@code org.protege.user}), else the account's ({@code user.name} of Java's properties); a
 * range is theirs when its {@code allocatedto} equals that name, case aside. Protege 5.5.0 has
 * no ID ranges of its own, and the same rule does no harm there.
 *
 * <p>OntoBoard claimed under the collaboration display name until 1.154.0, which Protege does
 * not know: a range claimed that way stays the board's while none is allocated to Protege's
 * name ({@link #forRanges}).
 */
public final class ProtegeUserName {

    /** The preferences Protege keeps the person's details in ({@code UserPreferences}). */
    static final String PREFERENCES = "org.protege.user";
    static final String USER_NAME = "user.name";
    static final String USE_GIT = "use.git.user.name";

    private ProtegeUserName() {
    }

    /**
     * The name as Protege decides it, from what it reads.
     *
     * @param useGit    whether Preferences &gt; User details asks for git's user name
     * @param gitName   git's user name for the ontology's repository, or null
     * @param preferred the user name set in Preferences &gt; User details, or null
     * @param login     the account's name
     */
    public static String decided(boolean useGit, String gitName, String preferred,
            String login) {
        if (useGit && gitName != null && !gitName.trim().isEmpty()) {
            return gitName.trim();
        }
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred.trim();
        }
        return login == null ? "" : login.trim();
    }

    /**
     * Who mints in a project: Protege's name - or the name an earlier claim was written under,
     * while the ranges allocate nothing to Protege's name and one range to that.
     *
     * @param earlierName the collaboration display name, or null
     * @param ranges      the project's ranges, or null where it has none
     */
    public static String forRanges(String protegesName, String earlierName, IdRanges ranges) {
        if (ranges != null && earlierName != null && !earlierName.trim().isEmpty()
                && ranges.rangeFor(protegesName) == null
                && ranges.rangeFor(earlierName) != null) {
            return earlierName.trim();
        }
        return protegesName;
    }

    /**
     * Protege's name for the person here: its preferences, git for the ontology's folder where
     * they ask for it, and the account's name. Outside Protege - a test - the account's.
     *
     * @param ontologyFile the ontology's file, for git's name; null where there is none
     */
    public static String here(File ontologyFile) {
        String preferred = null;
        boolean useGit = false;
        try {
            org.protege.editor.core.prefs.Preferences user =
                    org.protege.editor.core.prefs.PreferencesManager.getInstance()
                            .getApplicationPreferences(PREFERENCES);
            preferred = user.getString(USER_NAME, null);
            useGit = user.getBoolean(USE_GIT, false);
        } catch (RuntimeException | LinkageError noPreferences) {
            // No preferences of Protege's to read: the account's name, as Protege falls back to.
        }
        File folder = ontologyFile == null ? null
                : ontologyFile.getAbsoluteFile().getParentFile();
        return decided(useGit, useGit && folder != null ? gitsName(folder) : null, preferred,
                System.getProperty("user.name", ""));
    }

    /** Who mints in the project of this ontology, by {@link #forRanges}; reads its ranges. */
    public static String forProjectOf(File ontologyFile, String earlierName) {
        IdRanges ranges = null;
        File found = TermMinter.findRangesFile(ontologyFile);
        if (found != null) {
            try {
                ranges = IdRanges.parse(new String(Files.readAllBytes(found.toPath()),
                        StandardCharsets.UTF_8));
            } catch (java.io.IOException | RuntimeException unusable) {
                // A file that cannot be read has no range of anybody's to keep.
            }
        }
        return forRanges(here(ontologyFile), earlierName, ranges);
    }

    /** git's user name in that folder, as git config gives it; null where it gives none. */
    private static String gitsName(File folder) {
        try {
            ProcessRunner.Outcome outcome = ProcessRunner.real().run(folder,
                    Arrays.asList("git", "config", "user.name"), 1, null);
            if (outcome.isSuccess()) {
                for (String line : outcome.getOutput()) {
                    if (line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException noGit) {
            // No git, or no repository: Protege then has no git name either.
        }
        return null;
    }
}
