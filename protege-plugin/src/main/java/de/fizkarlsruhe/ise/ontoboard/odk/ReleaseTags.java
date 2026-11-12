package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which release tags of a project hold its ontology, and the file as git names it - what
 * {@link ReleaseHistory#releasesOf} asks git - remembered while the repository's tags have not
 * changed.
 *
 * <p>Asking took two git processes, about 0.1 s each where it was measured, and every new term on
 * the board, every focus of the guide and every History asked, on the Swing thread. So:
 *
 * <ul>
 *   <li>No {@code .git} in the project's folder or any folder above it: no repository, and no
 *       process - the folders are all there is.
 *   <li>A {@code .git} folder git would take for a repository ({@code HEAD}, {@code objects/},
 *       {@code refs/}) whose refs are files: the answer is kept, per project root and ontology
 *       id, with the state of the tag refs it was given in - every name and modification time
 *       under {@code refs/tags} (that folder's and its sub folders' own times too), and
 *       {@code packed-refs}' time and size. It is used again while that state is the same: a tag
 *       made, deleted or moved writes a ref file or rewrites {@code packed-refs}, and either
 *       changes a name or a time.
 *   <li>Anything else - a {@code .git} file (a linked worktree, a submodule: where its refs are
 *       is for git to say), a {@code .git} folder that is no repository (git looks further up),
 *       refs in a reftable - and git is asked each time, as before.
 * </ul>
 *
 * <p>A time says nothing within the file system's resolution of it: a ref changed in the same
 * tick as one just read keeps that time. So an answer is kept only when every time in its state
 * was at least {@link #SETTLED_MILLIS} old when the state was read - which is before git was
 * asked - as git distrusts an index entry as new as the index. Nor is an answer kept when git
 * could not be asked or did not answer.
 *
 * <p>A commit's files never change, so what a tag holds changes only when the tag does.
 */
final class ReleaseTags {

    /** How old every time of a state must be when it is read for an answer to be kept. */
    static final long SETTLED_MILLIS = 3000;

    /** By project root and ontology id; a few dozen projects in a session at the very most. */
    private static final Map<String, Kept> KEPT = Collections.synchronizedMap(
            new LinkedHashMap<String, Kept>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) {
                    return size() > 64;
                }
            });

    private ReleaseTags() {
    }

    /** An answer, and the state of the tag refs it was given in. */
    private static final class Kept {
        final String state;
        final Map<String, String> held;

        Kept(String state, Map<String, String> held) {
            this.state = state;
            this.held = held;
        }
    }

    /**
     * The release tags that hold {@code <id>.owl} at the project's root, each with its blob's id,
     * in GitTags' order (newest first); empty where there is no repository, or no git.
     */
    static Map<String, String> holding(File projectRoot, String id, ProcessRunner.Runner git) {
        File dotGit = dotGitAbove(projectRoot);
        if (dotGit == null) {
            return new LinkedHashMap<String, String>();
        }
        String key = projectRoot.getAbsolutePath() + '|' + id;
        long read = System.currentTimeMillis();
        long[] newest = {Long.MIN_VALUE};
        String state = isRepositoryWithRefFiles(dotGit) ? stateOf(dotGit, newest) : null;
        if (state != null) {
            Kept kept = KEPT.get(key);
            if (kept != null && kept.state.equals(state)) {
                return new LinkedHashMap<String, String>(kept.held);
            }
        }
        Map<String, String> held = ask(projectRoot, id, git);
        if (state != null && held != null && newest[0] <= read - SETTLED_MILLIS) {
            KEPT.put(key, new Kept(state, Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(held))));
        } else {
            KEPT.remove(key);
        }
        return held == null ? new LinkedHashMap<String, String>() : held;
    }

    /** What git says, or null when it could not be asked or did not answer. */
    private static Map<String, String> ask(File projectRoot, String id,
            ProcessRunner.Runner git) {
        List<String> named = GitTags.releasesOrNull(projectRoot, git);
        if (named == null) {
            return null;
        }
        return named.isEmpty() ? new LinkedHashMap<String, String>()
                : GitTags.heldOrNull(projectRoot, named, id + ".owl");
    }

    /** The {@code .git} - folder or file - in the project's folder or the nearest above it. */
    static File dotGitAbove(File projectRoot) {
        for (File folder = projectRoot.getAbsoluteFile(); folder != null;
                folder = folder.getParentFile()) {
            File dotGit = new File(folder, ".git");
            if (dotGit.exists()) {
                return dotGit;
            }
        }
        return null;
    }

    /**
     * A folder git takes for a repository - HEAD, objects/ and refs/ in it, as git checks - that
     * keeps its refs in files: not a reftable, whose refs are not files under refs/tags.
     */
    private static boolean isRepositoryWithRefFiles(File dotGit) {
        return dotGit.isDirectory() && new File(dotGit, "HEAD").isFile()
                && new File(dotGit, "objects").isDirectory()
                && new File(dotGit, "refs").isDirectory()
                && !new File(dotGit, "reftable").exists();
    }

    /**
     * The state of a repository's tag refs: every folder and file under {@code refs/tags} by
     * name and modification time, the folder's own included, and {@code packed-refs}' time and
     * size. {@code newest} gets the newest of those times.
     */
    private static String stateOf(File gitDir, long[] newest) {
        StringBuilder state = new StringBuilder(gitDir.getAbsolutePath()).append('\n');
        File tags = new File(new File(gitDir, "refs"), "tags");
        Deque<File> pending = new ArrayDeque<File>();
        pending.push(tags);
        while (!pending.isEmpty()) {
            File file = pending.pop();
            long time = file.lastModified();
            boolean folder = file.isDirectory();
            if (!folder && !file.isFile()) {
                state.append("none ").append(file.getPath()).append('\n');
                continue;
            }
            newest[0] = Math.max(newest[0], time);
            state.append(folder ? "folder " : "file ").append(file.getPath()).append(' ')
                    .append(time).append('\n');
            File[] children = folder ? file.listFiles() : null;
            if (children != null) {
                Arrays.sort(children);
                for (int at = children.length - 1; at >= 0; at--) {
                    pending.push(children[at]);
                }
            }
        }
        File packed = new File(gitDir, "packed-refs");
        if (packed.isFile()) {
            newest[0] = Math.max(newest[0], packed.lastModified());
            state.append("packed-refs ").append(packed.lastModified()).append(' ')
                    .append(packed.length()).append('\n');
        } else {
            state.append("no packed-refs\n");
        }
        return state.toString();
    }
}
