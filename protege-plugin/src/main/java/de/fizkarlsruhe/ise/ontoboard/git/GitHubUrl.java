package de.fizkarlsruhe.ise.ontoboard.git;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whatever a user pasted, understood as a GitHub repository.
 *
 * <p>Nobody pastes a clone URL. They paste the address bar - which, depending on what they were
 * looking at, is the repository, a branch, a directory, or a single file three levels down. They
 * paste the "raw" link. They paste the SSH remote out of a README. They type
 * {@code owner/repo}. All seven forms name the same repository, and refusing six of them with
 * "that is not a valid GitHub URL" is a tool telling a user they are wrong when they are not.
 *
 * <p>The branch and path are kept when they are there, because they are usually the point: someone
 * who pasted a link to {@code src/ontology/mwo-edit.owl} on branch {@code issue-42} has said which
 * file on which branch they want, and opening the default branch's default ontology instead is not
 * a smaller version of what they asked for - it is a different thing.
 *
 * <p>No network, no Swing.
 */
public final class GitHubUrl {

    /** github.com/owner/repo, with the rest of the path if there is any. */
    private static final Pattern WEB = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s?#]+?)(?:\\.git)?"
                    + "(?:/(.*))?$", Pattern.CASE_INSENSITIVE);

    /** raw.githubusercontent.com/owner/repo/ref/path. */
    private static final Pattern RAW = Pattern.compile(
            "^(?:https?://)?raw\\.githubusercontent\\.com/([^/\\s]+)/([^/\\s]+)/([^/\\s]+)"
                    + "(?:/(.*))?$", Pattern.CASE_INSENSITIVE);

    /** git@github.com:owner/repo.git. */
    private static final Pattern SSH = Pattern.compile(
            "^git@github\\.com:([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);

    /** owner/repo, typed. */
    private static final Pattern SHORTHAND = Pattern.compile(
            "^([A-Za-z0-9][A-Za-z0-9._-]*)/([A-Za-z0-9][A-Za-z0-9._-]*?)(?:\\.git)?/?$");

    private final String owner;
    private final String repository;
    private final String branch;
    private final String path;

    private GitHubUrl(String owner, String repository, String branch, String path) {
        this.owner = owner;
        this.repository = repository;
        this.branch = branch;
        this.path = path;
    }

    /**
     * Reads whatever was pasted.
     *
     * @throws IllegalArgumentException naming what was given and showing a form that works,
     *     because "invalid URL" on its own leaves a user with nothing to try next
     */
    public static GitHubUrl parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("No repository was named.");
        }
        // Trailing punctuation from a copy out of prose, and a fragment from a deep link.
        trimmed = trimmed.replaceAll("[),.;]+$", "");
        int hash = trimmed.indexOf('#');
        if (hash > 0) {
            trimmed = trimmed.substring(0, hash);
        }

        Matcher raw = RAW.matcher(trimmed);
        if (raw.matches()) {
            return new GitHubUrl(raw.group(1), stripGit(raw.group(2)), raw.group(3),
                    emptyToNull(raw.group(4)));
        }
        Matcher ssh = SSH.matcher(trimmed);
        if (ssh.matches()) {
            return new GitHubUrl(ssh.group(1), ssh.group(2), null, null);
        }
        Matcher web = WEB.matcher(trimmed);
        if (web.matches()) {
            return fromWebPath(web.group(1), web.group(2), web.group(3));
        }
        Matcher shorthand = SHORTHAND.matcher(trimmed);
        if (shorthand.matches()) {
            return new GitHubUrl(shorthand.group(1), shorthand.group(2), null, null);
        }
        throw new IllegalArgumentException("'" + text.trim() + "' is not a GitHub repository. "
                + "Paste the address of the repository, for example "
                + "https://github.com/ISE-FIZKarlsruhe/mwo");
    }

    /**
     * The rest of a github.com path, which is {@code tree/branch/dir} or {@code blob/branch/file}
     * when it is anything at all.
     */
    private static GitHubUrl fromWebPath(String owner, String repository, String rest) {
        if (rest == null || rest.trim().isEmpty()) {
            return new GitHubUrl(owner, repository, null, null);
        }
        String[] parts = rest.split("/");
        String first = parts[0].toLowerCase(Locale.ROOT);
        if (("tree".equals(first) || "blob".equals(first) || "raw".equals(first))
                && parts.length >= 2) {
            StringBuilder path = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (path.length() > 0) {
                    path.append('/');
                }
                path.append(parts[i]);
            }
            return new GitHubUrl(owner, repository, parts[1], emptyToNull(path.toString()));
        }
        // Anything else under the repository - /issues, /pull/3, /settings - still names the
        // repository, which is what was asked for.
        return new GitHubUrl(owner, repository, null, null);
    }

    private static String stripGit(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".git")
                ? name.substring(0, name.length() - 4) : name;
    }

    private static String emptyToNull(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    public String getOwner() {
        return owner;
    }

    public String getRepository() {
        return repository;
    }

    /** The branch named in the link, or null for whatever the repository's default is. */
    public String getBranch() {
        return branch;
    }

    /** The file or directory named in the link, or null. */
    public String getPath() {
        return path;
    }

    /**
     * The HTTPS clone URL.
     *
     * <p>HTTPS rather than SSH even when an SSH remote was pasted: HTTPS clones a public
     * repository with no setup, and SSH fails for anyone without a key on the machine - which
     * for someone opening a link they were sent is most people.
     */
    public String getCloneUrl() {
        return "https://github.com/" + owner + "/" + repository + ".git";
    }

    /** {@code owner/repo}, for a directory name and for saying what is being opened. */
    public String getFullName() {
        return owner + "/" + repository;
    }

    @Override
    public String toString() {
        return getFullName() + (branch == null ? "" : " @" + branch)
                + (path == null ? "" : " " + path);
    }
}
