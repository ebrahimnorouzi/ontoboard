package de.fizkarlsruhe.ise.ontoboard.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Resolves a path from a pasted link against a checkout, and refuses to leave it.
 *
 * <p>The path comes out of a GitHub blob URL, which is to say out of whatever the user pasted, and
 * it was handed straight to {@code new File(checkout, path)}. A URL like
 * {@code .../blob/main/../../../../some/other/project.owl} therefore cloned the repository the link
 * named and then opened an ontology from somewhere else entirely on the user's disk, while the
 * result said "Opening the file the link pointed at."
 *
 * <p>The damage is bounded - it opens a file the user already has, in their own editor - but the
 * claim is false, and a false claim about which file you are editing is the kind that costs an
 * afternoon. Two places did this, so the rule lives in one.
 */
public final class InsideRepository {

    private InsideRepository() {
    }

    /**
     * The file {@code relativePath} names inside {@code repository}, or null when it points out.
     *
     * <p>Normalised before the check, so {@code a/../b} is compared as {@code b} rather than
     * trusted for containing no {@code ..} at the front. Symlinks are deliberately not resolved:
     * {@code toRealPath} would refuse a path that does not exist yet, and the question here is what
     * the link asked for, not what the filesystem happens to have.
     *
     * @return the resolved file, or null when the path escapes the repository or cannot be read as
     *     a path at all
     */
    public static File resolve(File repository, String relativePath) {
        if (repository == null || relativePath == null || relativePath.trim().isEmpty()) {
            return null;
        }
        try {
            Path root = repository.getAbsoluteFile().toPath().normalize();
            Path resolved = root.resolve(relativePath.trim()).normalize();
            return resolved.startsWith(root) ? resolved.toFile() : null;
        } catch (RuntimeException notAPath) {
            // An invalid path on this platform - a Windows reserved name, a null byte. Treated the
            // same as one that escapes: unusable, so fall back to the layout rules.
            return null;
        }
    }

    /**
     * Whether {@code candidate} is inside {@code repository}.
     *
     * @throws IOException never; kept out of the signature deliberately so callers stay readable
     */
    public static boolean contains(File repository, File candidate) {
        if (repository == null || candidate == null) {
            return false;
        }
        Path root = repository.getAbsoluteFile().toPath().normalize();
        return candidate.getAbsoluteFile().toPath().normalize().startsWith(root);
    }
}
