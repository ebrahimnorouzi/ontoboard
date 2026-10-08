package de.fizkarlsruhe.ise.ontoboard.git;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Opening and listing pull requests without leaving Prot&eacute;g&eacute;.
 *
 * <p>The last gap in the git surface. <i>Git…</i> has done status, stage, commit, pull, push and
 * branch since 1.19.0, and <i>Open from GitHub…</i> clones - so the one step that still sent a
 * curator to a browser was the step that ends the work: proposing it.
 *
 * <p><b>This runs {@code gh}, it does not reimplement it.</b> The same decision as everywhere else
 * in this plugin: ROBOT runs as robot-core, a build runs as {@code make} in ODK's own container,
 * and git runs as {@code git}. A pull request needs an authenticated GitHub session, and {@code
 * gh} already holds one - in the system keyring, refreshed by {@code gh auth login}, scoped by the
 * user. The alternative was to ask for a personal access token and store it, which would make this
 * plugin a place credentials live, and a worse place than the one that already exists.
 *
 * <p><b>Everything here is a command line and a parser.</b> Nothing in this class runs anything;
 * {@code PullRequestAction} does, through the same {@code ProcessRunner} a build goes through. It
 * is split that way so the thing worth testing - what gets run, and what the output means - is
 * testable without a network, a GitHub account or a process.
 *
 * <p><b>When {@code gh} is absent there is still an answer.</b> GitHub's compare URL opens the
 * "open a pull request" form for a branch with no authentication and no install, so a user without
 * {@code gh} is given that rather than an apology. It is the one case where leaving
 * Prot&eacute;g&eacute; is the honest recommendation.
 */
public final class PullRequest {

    private PullRequest() {
    }

    /** The executable. On PATH or not at all - this does not go looking in install directories. */
    public static final String GH = "gh";

    /** How many pull requests to list. Enough to see what is open, not a backlog browser. */
    public static final int MOST_LISTED = 25;

    /** The fields {@link #parseList} reads, in the order {@code gh} is asked for them. */
    public static final String LIST_FIELDS =
            "number,title,state,headRefName,baseRefName,isDraft,url,author";

    // ---------------------------------------------------------------- commands

    /**
     * Whether {@code gh} exists and holds a session.
     *
     * <p>One command for both questions, because they fail the same way from the user's side and
     * the remedy differs: {@code gh} missing means install it, {@code gh} present and logged out
     * means {@code gh auth login}. {@link #whyUnavailable} tells them apart from the outcome.
     */
    public static List<String> authCommand() {
        return Collections.unmodifiableList(Arrays.asList(GH, "auth", "status"));
    }

    /** The open pull requests of the repository the working directory belongs to. */
    public static List<String> listCommand() {
        return Collections.unmodifiableList(Arrays.asList(GH, "pr", "list",
                "--limit", String.valueOf(MOST_LISTED), "--json", LIST_FIELDS));
    }

    /**
     * The pull request for the current branch, if there is one.
     *
     * <p>Asked before offering to create one, because {@code gh pr create} against a branch that
     * already has an open pull request fails with a message about the branch rather than about
     * what the user was trying to do.
     */
    public static List<String> currentCommand() {
        return Collections.unmodifiableList(Arrays.asList(GH, "pr", "view",
                "--json", LIST_FIELDS));
    }

    /**
     * Creating one.
     *
     * <p>{@code --head} is deliberately not passed: {@code gh} takes the current branch, and
     * naming it here would let the dialog and the checkout disagree. {@code --base} is passed,
     * because the default is the repository's default branch and an ODK project's is not always
     * {@code main}.
     *
     * @param base the branch to merge into
     * @param title the pull request title, which must not be empty
     * @param body the description, which may be empty
     * @param draft whether to open it as a draft
     */
    public static List<String> createCommand(String base, String title, String body,
            boolean draft) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("a pull request needs a title");
        }
        List<String> command = new ArrayList<String>(Arrays.asList(
                GH, "pr", "create", "--title", title.trim()));
        // Always passed, even empty: without --body gh opens an editor, and an editor inside a
        // process this plugin is waiting on is a hang with no window to close.
        command.add("--body");
        command.add(body == null ? "" : body);
        if (base != null && !base.trim().isEmpty()) {
            command.add("--base");
            command.add(base.trim());
        }
        if (draft) {
            command.add("--draft");
        }
        return Collections.unmodifiableList(command);
    }

    /**
     * The same command with {@code --dry-run}, which is what the confirmation is built from.
     *
     * <p>{@code gh} validates the branch, the base and the remote and prints what it would open,
     * without opening it. Creating a pull request is the one operation here that other people see
     * the moment it happens, so it is worth finding out that the branch is unpushed before it is
     * announced rather than after.
     */
    public static List<String> dryRunOf(List<String> createCommand) {
        List<String> dry = new ArrayList<String>(createCommand);
        dry.add("--dry-run");
        return Collections.unmodifiableList(dry);
    }

    /** Opening one in a browser, by number. */
    public static List<String> openCommand(int number) {
        return Collections.unmodifiableList(Arrays.asList(GH, "pr", "view",
                String.valueOf(number), "--web"));
    }

    // ---------------------------------------------------------------- results

    /** One pull request, as {@code gh pr list --json} describes it. */
    public static final class Summary {
        private final int number;
        private final String title;
        private final String state;
        private final String head;
        private final String base;
        private final boolean draft;
        private final String url;
        private final String author;

        Summary(int number, String title, String state, String head, String base, boolean draft,
                String url, String author) {
            this.number = number;
            this.title = title;
            this.state = state;
            this.head = head;
            this.base = base;
            this.draft = draft;
            this.url = url;
            this.author = author;
        }

        public int getNumber() {
            return number;
        }

        public String getTitle() {
            return title;
        }

        /** {@code OPEN}, {@code MERGED} or {@code CLOSED}. */
        public String getState() {
            return state;
        }

        /** The branch being proposed. */
        public String getHead() {
            return head;
        }

        /** The branch it would merge into. */
        public String getBase() {
            return base;
        }

        public boolean isDraft() {
            return draft;
        }

        public String getUrl() {
            return url;
        }

        /** The login, or empty. A bot is named like any other author. */
        public String getAuthor() {
            return author;
        }

        /** How the state reads in a column, with draft folded in. */
        public String describeState() {
            return draft && "OPEN".equalsIgnoreCase(state) ? "draft" : state.toLowerCase(
                    Locale.ROOT);
        }

        @Override
        public String toString() {
            return "#" + number + " " + title;
        }
    }

    /**
     * Reads {@code gh pr list --json}.
     *
     * <p>Hand-parsed rather than through a JSON library, for the reason the rest of this plugin
     * embeds what it needs: the bundle already carries ROBOT and its dependencies, and adding a
     * parser to read six fields of a document this code asked for the shape of is weight for
     * nothing. The shape is fixed by {@link #LIST_FIELDS}, and anything that does not match is
     * skipped rather than guessed at.
     *
     * @param json the whole of {@code gh}'s standard output
     * @return the pull requests, in the order they were listed; empty when there are none
     */
    public static List<Summary> parseList(String json) {
        List<Summary> found = new ArrayList<Summary>();
        if (json == null) {
            return found;
        }
        String text = json.trim();
        if (text.isEmpty() || "[]".equals(text)) {
            return found;
        }
        // One object per pull request. Objects nest - author is one - so the split is on the
        // boundary between top-level objects rather than on every brace.
        for (String object : topLevelObjects(text)) {
            Integer number = intField(object, "number");
            if (number == null) {
                continue;
            }
            found.add(new Summary(number.intValue(),
                    stringField(object, "title"),
                    stringField(object, "state"),
                    stringField(object, "headRefName"),
                    stringField(object, "baseRefName"),
                    Boolean.parseBoolean(rawField(object, "isDraft")),
                    stringField(object, "url"),
                    stringField(object, "login")));
        }
        return found;
    }

    /** The top-level objects of a JSON array, with their nested objects intact. */
    private static List<String> topLevelObjects(String text) {
        List<String> objects = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    start = at;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(text.substring(start, at + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    /** A string field's value, unescaped, or empty. */
    private static String stringField(String object, String name) {
        String raw = rawField(object, name);
        if (raw == null || !raw.startsWith("\"")) {
            return "";
        }
        String body = raw.substring(1, raw.length() - 1);
        return body.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\/", "/");
    }

    private static Integer intField(String object, String name) {
        String raw = rawField(object, name);
        try {
            return raw == null ? null : Integer.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** The text of {@code "name": <value>}, quotes included for a string, or null. */
    private static String rawField(String object, String name) {
        String key = "\"" + name + "\":";
        int at = object.indexOf(key);
        if (at < 0) {
            return null;
        }
        int from = at + key.length();
        while (from < object.length() && Character.isWhitespace(object.charAt(from))) {
            from++;
        }
        if (from >= object.length()) {
            return null;
        }
        if (object.charAt(from) == '"') {
            boolean escaped = false;
            for (int to = from + 1; to < object.length(); to++) {
                char c = object.charAt(to);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    return object.substring(from, to + 1);
                }
            }
            return null;
        }
        int to = from;
        while (to < object.length() && ",}]".indexOf(object.charAt(to)) < 0) {
            to++;
        }
        return object.substring(from, to).trim();
    }

    // ---------------------------------------------------------------- readiness

    /**
     * Why a pull request cannot be opened from here, or null when it can.
     *
     * <p>Each answer names the command that fixes it. "gh is not available" is a dead end; "gh is
     * not installed - get it from cli.github.com, or use the browser link below" is not.
     *
     * @param exitCode what {@code gh auth status} returned, or -1 when it could not be started
     * @param output everything it printed
     */
    public static String whyUnavailable(int exitCode, List<String> output) {
        String said = output == null ? "" : join(output).toLowerCase(Locale.ROOT);
        if (exitCode < 0 || said.contains("cannot run program")
                || said.contains("is not recognized") || said.contains("command not found")) {
            return "The GitHub CLI (gh) is not on this machine's PATH. OntoBoard runs gh rather "
                    + "than asking you for a token, so that your GitHub credentials stay where "
                    + "you already keep them. Install it from cli.github.com and run "
                    + "'gh auth login', or use the browser link below, which needs neither.";
        }
        if (exitCode != 0 || said.contains("not logged") || said.contains("auth login")) {
            return "The GitHub CLI is installed but not logged in. Run 'gh auth login' in a "
                    + "terminal once; OntoBoard does not store credentials of its own. The "
                    + "browser link below works without it.";
        }
        return null;
    }

    /**
     * GitHub's own "open a pull request" form for a branch.
     *
     * <p>The fallback, and the only part of this that needs nothing installed. GitHub resolves the
     * base itself when the URL names only the branch, which is what makes this a link rather than
     * a form to fill in twice.
     *
     * @param remote any GitHub URL for the repository - a clone URL is one
     * @param branch the branch to propose
     * @return the URL, or null when the remote is not a GitHub repository
     */
    public static String compareUrl(String remote, String branch) {
        if (branch == null || branch.trim().isEmpty()) {
            return null;
        }
        GitHubUrl parsed;
        try {
            parsed = GitHubUrl.parse(remote);
        } catch (RuntimeException notGitHub) {
            // GitHubUrl.parse throws for anything that is not a GitHub address, because its own
            // caller is a dialog where the user typed one and wants to be told. Here the remote
            // was read from the checkout rather than typed, and a project on GitLab is not a
            // mistake - it simply has no GitHub form to offer. Caught rather than pre-checked,
            // because the one authority on what GitHub accepts is that method.
            return null;
        }
        if (parsed == null) {
            return null;
        }
        return "https://github.com/" + parsed.getFullName() + "/compare/"
                + branch.trim().replace(" ", "%20") + "?expand=1";
    }

    /**
     * Whether this branch can be proposed at all.
     *
     * <p>A pull request is a request to merge a branch somebody else can fetch. The default branch
     * has nothing to merge into itself, and an unpushed branch does not exist for anyone else -
     * both produce a {@code gh} error about refs that does not say which of the two happened.
     *
     * @return the reason it cannot, or null when it can
     */
    public static String whyBranchCannotBeProposed(String branch, String base, boolean pushed) {
        if (branch == null || branch.trim().isEmpty()) {
            return "This checkout is not on a branch - a detached HEAD has nothing to propose.";
        }
        if (base != null && branch.trim().equals(base.trim())) {
            return "You are on " + branch + ", which is the branch a pull request would merge "
                    + "into. Make a branch for the change first - Git... has 'Create a branch'.";
        }
        if (!pushed) {
            return "The branch " + branch + " has not been pushed, so nobody else can see the "
                    + "commits a pull request would propose. Push it from Git... first.";
        }
        return null;
    }

    private static String join(List<String> lines) {
        StringBuilder all = new StringBuilder();
        for (String line : lines) {
            all.append(line).append('\n');
        }
        return all.toString();
    }
}
