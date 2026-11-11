package de.fizkarlsruhe.ise.ontoboard.assistant.project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The clone's own {@code .git/info/exclude}: the same two rules as the shared block, written
 * where nobody can commit them away (1.144.0). A colleague who deleted the shared
 * assistant/.gitignore and pushed exposed everyone's private conversations from their next pull
 * until their OntoBoard wrote the block again (the git probe of the 1.144.0 design, x6).
 *
 * <p>Found by walking up from the conversations folder to a {@code .git} directory, or to a
 * {@code .git} file that names the git directory of a worktree; a worktree's git directory names
 * the repository's own in {@code commondir}, and that one's {@code info/exclude} is the file git
 * reads. Only a real git directory ends the walk, as only one makes git stop there (1.149.0,
 * review 1.147.0 recorder-02). Outside a git working tree, nothing is written. The rules match
 * the path literally (1.146.0, review 1.144.0 privacy-06): in a folder named "onto [v2]", "[v2]"
 * was a set of characters, so the rules held nothing. They go into each clone found from the
 * folder as opened, from its real path (1.147.0, review 1.146.0 recorder-01) and from every view
 * in between (1.149.0, review 1.147.0 recorder-03), each tried on its own (1.148.0, review
 * 1.147.0 recorder-01). The real path's clone - the project's own, where the person commits - is
 * tried first, and every exclude that could not be written is said, not the first alone (1.150.0,
 * review 1.149.0 recorder-02). Git does not go into a directory symbolic link - Git for Windows
 * goes into a junction, not into a symbolic link - so the path as opened is taken with each such
 * link replaced by where it leads, and the clone that holds one gets no rules from it: it was
 * warned about, and given rules it does not need (1.150.0, review 1.149.0 recorder-03). Where it
 * leads is its target as written in it, which Java reads, walked as the path as opened is: a
 * junction on that way is a view, so the clone that holds the junction gets the rules - the fully
 * resolved target alone passed that clone by (1.151.0, review 1.150.0 recorder-02). A clone that
 * holds a link to the project, which the person did not open it through, is not known here and
 * gets no rules. Nor is a clone that a junction's target passes through only as written in it - a
 * junction made to B\x\j2\sub, where B\x\j2 is itself a junction - for Java reads no junction's
 * target: it is known here only fully resolved.
 */
final class GitExclude {

    static final String COMMENT = "# OntoBoard: saved conversations stay out of git unless their"
            + " author released them.";

    private GitExclude() {
    }

    /** Follows a folder to where it really is. */
    interface Follow {
        Path of(Path folder) throws IOException;
    }

    /**
     * How a folder is followed. A test replaces it: a folder whose parent may be entered but not
     * listed - which makes toRealPath fail on Windows - cannot be made here, where the session may
     * read every folder.
     */
    static Follow follow = folder -> folder.toRealPath();

    /**
     * Told of each folder whose {@code .git} is looked for, so that a test can count them: each
     * view was walked to the top of its clone, or to the root, looking again at the folders an
     * earlier view had looked at - on every save, on the Swing thread (1.150.0, review 1.149.0
     * recorder-04).
     */
    static java.util.function.Consumer<File> looking = folder -> {
    };

    /**
     * How many symbolic links' targets as written one call walks: a path through more links than
     * that, or a circle, is followed to where it really is.
     */
    static final int MOST_WRITTEN = 8;

    /** Told of each symbolic link whose target as written is walked, so that a test can count. */
    static java.util.function.Consumer<Path> reading = link -> {
    };

    /**
     * Adds the rules for this conversations folder, unless they are there already, to each clone
     * found from a view of it: its real path, the folder as opened, and each prefix of it followed
     * to where it really is with the rest as opened. A link outside a clone pointing in has its
     * clone only on the real path; a junction inside a clone pointing out has it only on the path
     * as opened, and walking from the real path alone wrote no rule there while git, going into
     * the junction, saw the private files (1.147.0, review 1.146.0 recorder-01). The clone in the
     * middle of a chain of junctions lies on neither (1.149.0, review 1.147.0 recorder-03).
     *
     * @throws IOException once every clone was tried: the first exclude that could not be
     *     written, naming it, with each other one added to it as suppressed - the real path's
     *     clone first - and after them a {@link ProjectFolder.NotFollowed} for a link or a
     *     junction on the way that could not be followed, as a clone it leads into may have been
     *     missed (1.151.0, review 1.150.0 recorder-01); or that NotFollowed alone, when each
     *     exclude found could be written
     */
    static void keepOutOfGit(File conversations) throws IOException {
        File absolute = conversations.getAbsoluteFile();
        IOException unresolved = null;
        // Worked out inside a try of its own: thrown before any clone was tried, its failure
        // cost the real path's clone its rules too (1.149.0, review 1.148.0, the recorder
        // reviewer's declined notes). The views below then start from the path as written.
        Path opened;
        try {
            opened = absolute.getCanonicalFile().toPath();
        } catch (IOException | RuntimeException failed) {
            unresolved = unresolved(absolute, failed);
            opened = absolute.toPath().normalize();
        }
        List<File> views = new ArrayList<File>();
        IOException unfollowed = null;
        // How many leading names of the path as opened are neither a link nor a junction: the
        // way a symbolic link leads is walked from where it parts from them, as those were
        // looked at already.
        int plain = 0;
        // Each symbolic link whose target as written was walked: one met again leads in a circle.
        // A circle through a junction meets its link under a longer name each time - W\J\S,
        // W\J\J\S - so the walks are counted too: past MOST_WRITTEN a link is followed to where
        // it really is, which fails in a circle and is said. Uncounted, the walk ran to Windows'
        // limit of reparse points, 18 s at every save (the review of the 1.151.0 recorder fix).
        Set<Path> walkedLinks = new HashSet<Path>();
        Path root = opened.getRoot();
        for (int i = 1; root != null && i <= opened.getNameCount(); i++) {
            Path prefix = root.resolve(opened.subpath(0, i));
            try {
                BasicFileAttributes seen;
                try {
                    // One look per folder: Files.exists first was a second one, on every save,
                    // on the Swing thread (1.150.0, review 1.149.0 recorder-04).
                    seen = Files.readAttributes(prefix, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                } catch (IOException notThere) {
                    // NoSuchFileException: the rest is not made yet, so none of it leads
                    // elsewhere. Any other reason ended the walk too, when Files.exists said no.
                    break;
                }
                // Only a link or a junction leads elsewhere: a folder that is neither is where
                // the view of its parent puts it. Following every folder, one whose parent may be
                // entered but not listed - toRealPath lists each parent on Windows - was said on
                // every save and every tick as rules that could not be written, though each
                // clone had them (1.149.0, the review of the recorder-01 fix).
                if (!seen.isSymbolicLink() && !seen.isOther()) {
                    if (plain == i - 1) {
                        plain = i;
                    }
                    continue;
                }
                String rest = i < opened.getNameCount()
                        ? opened.subpath(i, opened.getNameCount()).toString() : null;
                if (seen.isSymbolicLink()) {
                    // Git does not go into a directory symbolic link, so no clone sees the
                    // folder through it: the path as opened goes on from where it leads, and the
                    // views before it, which led through it, are dropped. Kept, the clone that
                    // holds it was warned about though git there never lists a conversation, and
                    // given rules it does not need. getCanonicalFile resolves such links
                    // elsewhere already; on Windows it keeps them, as it keeps junctions, which
                    // Git for Windows does go into (1.150.0, review 1.149.0 recorder-03).
                    views.clear();
                    unfollowed = null;
                    // Where it leads as written in it, walked as the path as opened is: a junction
                    // on that way is a view, and a symbolic link on it is followed in turn. Only
                    // the fully resolved target was walked, and clone C, whose junction C\c\J
                    // the link B\S names, listed the conversations once assistant/.gitignore was
                    // gone, with nothing said (1.151.0, review 1.150.0 recorder-02). Read first:
                    // it needs no toRealPath, which fails on a link Java made as a file link -
                    // as it makes one to a relative target it cannot find from where the link is.
                    Path written = null;
                    IOException unread = null;
                    if (walkedLinks.size() < MOST_WRITTEN && walkedLinks.add(prefix)) {
                        reading.accept(prefix);
                        try {
                            written = asWritten(prefix);
                        } catch (IOException | RuntimeException cannot) {
                            unread = unresolved(prefix.toFile(), cannot);
                        }
                    }
                    if (written != null) {
                        opened = rest != null ? written.resolve(rest) : written;
                        i = shared(opened, prefix, plain);
                    } else {
                        Path target;
                        try {
                            target = follow.of(prefix);
                        } catch (NoSuchFileException dangling) {
                            // A link to nothing, which Files.exists said was not there.
                            break;
                        }
                        // Where it really is, as before; a clone on the way as written may be
                        // missed, so that is said.
                        unfollowed = unread;
                        opened = rest != null ? target.resolve(rest) : target;
                        i = target.getNameCount();
                    }
                    plain = i;
                    root = opened.getRoot();
                    continue;
                }
                Path target;
                try {
                    target = follow.of(prefix);
                } catch (NoSuchFileException dangling) {
                    // A link to nothing, which Files.exists said was not there.
                    break;
                }
                views.add((rest != null ? target.resolve(rest) : target).toFile());
            } catch (IOException | RuntimeException failed) {
                unfollowed = unfollowed != null ? unfollowed : unresolved(prefix.toFile(), failed);
            }
        }
        unresolved = unresolved != null ? unresolved : unfollowed;
        // The real path's clone first: the project's own, where the person commits. Tried last,
        // its failure stayed behind the first one, which was all that was said (1.150.0, review
        // 1.149.0 recorder-02).
        Set<File> starts = new LinkedHashSet<File>();
        starts.add(ProjectFolder.real(conversations));
        starts.add(opened.toFile());
        starts.addAll(views);
        // Each clone on its own: one whose exclude cannot be written - read-only, another
        // account's - stopped the walk, and the project's own clone got no rules (1.148.0,
        // review 1.147.0 recorder-01). Each clone and its rules are tried once, however many
        // views lead there, and each folder is looked at once.
        Set<String> tried = new HashSet<String>();
        Map<File, File[]> walked = new HashMap<File, File[]>();
        List<IOException> notWritten = new ArrayList<IOException>();
        Set<String> reasons = new HashSet<String>();
        for (File start : starts) {
            try {
                keepOutOfGitFrom(start, tried, walked);
            } catch (IOException | RuntimeException failed) {
                IOException each = failed instanceof IOException ? (IOException) failed
                        : new IOException(failed.toString(), failed);
                // Every one, each file and reason once: only the first was said, and the
                // project's own clone could stay unnamed behind another account's (1.150.0,
                // review 1.149.0 recorder-02).
                if (reasons.add(each.getClass().getName() + ": " + each.getMessage())) {
                    notWritten.add(each);
                }
            }
        }
        if (!notWritten.isEmpty()) {
            IOException first = notWritten.get(0);
            for (IOException more : notWritten.subList(1, notWritten.size())) {
                first.addSuppressed(more);
            }
            // After them, a link or a junction that could not be followed. Thrown only when every
            // exclude could be written, it went unsaid while one could not - at every save, as
            // that one kept failing - and the clone it leads into kept no rules, unknown to
            // anyone (1.151.0, review 1.150.0 recorder-01).
            if (unresolved != null) {
                first.addSuppressed(unresolved);
            }
            throw first;
        }
        if (unresolved != null) {
            throw unresolved;
        }
    }

    /** Why a view of the folder could not be worked out, naming where. */
    private static IOException unresolved(File where, Throwable failed) {
        return new ProjectFolder.NotFollowed(where, failed);
    }

    /**
     * Where a symbolic link leads as written in it, with "." and ".." taken out lexically. A
     * relative target is resolved from the folder that holds the link as the path names it, as
     * Windows does: ..\t in W\J0\S, J0 a junction to B, is W\t, not B's sibling (the 1.151.0
     * probe). Java reads no junction's target, only a symbolic link's.
     */
    private static Path asWritten(Path link) throws IOException {
        Path target = Files.readSymbolicLink(link);
        Path parent = link.getParent();
        return (parent == null ? target : parent.resolve(target)).normalize();
    }

    /** How many leading names two paths share, at most {@code most}. */
    private static int shared(Path one, Path other, int most) {
        if (one.getRoot() == null || !one.getRoot().equals(other.getRoot())) {
            return 0;
        }
        int names = 0;
        while (names < most && names < one.getNameCount() && names < other.getNameCount()
                && one.getName(names).equals(other.getName(names))) {
            names++;
        }
        return names;
    }

    /** What a walk found from a folder that leads to no clone. */
    private static final File[] NO_CLONE = new File[0];

    /**
     * Walks up from this view to its clone and adds the rules there.
     *
     * @param walked what each folder walked in this call led to: its clone's top folder and git
     *     directory, or {@link #NO_CLONE}. A folder walked by an earlier view leads where it did
     *     then, so the walk stops there: each view was walked to the top of its clone, or to the
     *     root, looking at the same folders again (1.150.0, review 1.149.0 recorder-04).
     */
    private static void keepOutOfGitFrom(File folder, Set<String> tried, Map<File, File[]> walked)
            throws IOException {
        List<File> passed = new ArrayList<File>();
        File[] found = NO_CLONE;
        for (File up = folder; up != null; up = up.getParentFile()) {
            File[] known = walked.get(up);
            if (known != null) {
                found = known;
                break;
            }
            passed.add(up);
            File gitDir = gitDirectory(up);
            if (gitDir != null) {
                found = new File[] {up, gitDir};
                break;
            }
        }
        for (File each : passed) {
            walked.put(each, found);
        }
        if (found == NO_CLONE) {
            return;
        }
        File root = found[0];
        File gitDir = found[1];
        File common = common(gitDir);
        try {
            // One name for one exclude: through a junction to the clone's top folder, or above
            // it, two walks reached it under two names, and it was tried, and said, twice
            // (1.150.0, the review of the recorder-02 fix).
            common = common.toPath().toRealPath().toFile();
        } catch (IOException | RuntimeException unresolved) {
            // The name it was found under, as before.
        }
        StringBuilder relative = new StringBuilder();
        for (Path name : root.toPath().relativize(folder.toPath())) {
            relative.append(relative.length() == 0 ? "" : "/").append(literal(name.toString()));
        }
        List<String> rules = Arrays.asList("/" + relative + "/*",
                "!/" + relative + "/" + ProjectFolder.RELEASED + "/");
        File exclude = new File(new File(common, "info"), "exclude");
        if (!tried.add(exclude.getAbsolutePath() + "\n" + rules)) {
            return;
        }
        try {
            String text = exclude.isFile() ? new String(Files.readAllBytes(exclude.toPath()),
                    StandardCharsets.UTF_8) : "";
            List<String> lines = Arrays.asList(text.split("\\r?\\n", -1));
            if (lines.containsAll(rules)) {
                return;
            }
            String ending = text.contains("\r\n") ? "\r\n" : "\n";
            StringBuilder updated = new StringBuilder(text);
            if (!text.isEmpty() && !text.endsWith("\n")) {
                updated.append(ending);
            }
            updated.append(COMMENT).append(ending);
            for (String rule : rules) {
                updated.append(rule).append(ending);
            }
            Files.createDirectories(exclude.getParentFile().toPath());
            Files.write(exclude.toPath(), updated.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            if (failed instanceof FileSystemException
                    && ((FileSystemException) failed).getFile() != null) {
                throw failed;
            }
            // Reading or writing a file that another program holds locked fails with a reason
            // that names no file, and only the person can free the one it is (1.149.0, review
            // 1.148.0 recorder-01).
            FileSystemException named = new FileSystemException(exclude.getPath(), null,
                    failed.getMessage() == null ? failed.toString() : failed.getMessage());
            named.initCause(failed);
            throw named;
        }
    }

    /**
     * The git directory of the clone whose top folder this is, or null. A {@code .git} that is
     * none does not make git stop: seen through a junction, a submodule's {@code .git} file names
     * {@code ../.git/modules/x} from the junction's side, where there is no repository, and git in
     * the clone that holds the junction goes into it as into any folder - the walk returned there,
     * and that clone got no rules (1.149.0, review 1.147.0 recorder-02).
     */
    private static File gitDirectory(File folder) throws IOException {
        looking.accept(folder);
        File dotGit = new File(folder, ".git");
        // One look where there is none, as in nearly every folder walked: isDirectory and then
        // isFile were two (1.150.0, review 1.149.0 recorder-04).
        if (!dotGit.exists()) {
            return null;
        }
        File gitDir = dotGit.isDirectory() ? dotGit
                : dotGit.isFile() ? named(dotGit, "gitdir:", folder) : null;
        return gitDir != null && isGitDirectory(gitDir) ? gitDir : null;
    }

    /** What git asks of a git directory: a HEAD, and objects/ and refs/ where it keeps them. */
    private static boolean isGitDirectory(File gitDir) throws IOException {
        if (!new File(gitDir, "HEAD").isFile()) {
            return false;
        }
        File common = common(gitDir);
        return new File(common, "objects").isDirectory() && new File(common, "refs").isDirectory();
    }

    /** The repository's own git directory, which a worktree's names in commondir. */
    private static File common(File gitDir) throws IOException {
        File commonDir = new File(gitDir, "commondir");
        if (commonDir.isFile()) {
            File named = named(commonDir, "", gitDir);
            if (named != null && named.isDirectory()) {
                return named;
            }
        }
        return gitDir;
    }

    /** A folder's name as a gitignore pattern matches it: each wildcard and backslash escaped. */
    static String literal(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c == '\\' || c == '[' || c == ']' || c == '*' || c == '?') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The path a one-line git file names after its prefix, resolved against a base folder. */
    private static File named(File file, String prefix, File base) throws IOException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        if (!text.startsWith(prefix)) {
            return null;
        }
        File path = new File(text.substring(prefix.length()).trim());
        return (path.isAbsolute() ? path : new File(base, path.getPath())).getCanonicalFile();
    }
}
