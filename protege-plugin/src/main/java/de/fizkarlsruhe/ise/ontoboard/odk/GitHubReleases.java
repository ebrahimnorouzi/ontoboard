package de.fizkarlsruhe.ise.ontoboard.odk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.fizkarlsruhe.ise.ontoboard.tools.Download;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Releases kept on GitHub only. ODK v1.6.1's .gitignore keeps a project's release files out of
 * git, and ODK's release puts them on the GitHub release as assets: History, which reads git
 * tags and releases/ folders, found none of them.
 *
 * <p>Nothing here is asked of the network unless the person asks (1.155.0, the user: fetching
 * the release history "should be asked from the user ..., also later in the history, this should
 * be available that user can click and all the history is retrieved"). What was fetched is kept
 * outside the project - {@code ~/.ontoboard/github-releases/<owner>/<repo>/<tag>/<file>} - and
 * History reads it from there afterwards, without the network.
 */
public final class GitHubReleases {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** https://github.com/o/r(.git), git@github.com:o/r(.git), ssh://git@github.com/o/r(.git). */
    private static final Pattern GITHUB = Pattern.compile(
            "^(?:https?://(?:[^@/]+@)?github\\.com/|git@github\\.com:|ssh://git@github\\.com/)"
                    + "([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");

    private GitHubReleases() {
    }

    /** One release's ontology file on GitHub. */
    public static final class Asset {
        private final String tag;
        private final String name;
        private final long size;
        private final String url;

        Asset(String tag, String name, long size, String url) {
            this.tag = tag;
            this.name = name;
            this.size = size;
            this.url = url;
        }

        public String tag() {
            return tag;
        }

        /** The version, without the tag's {@code v}. */
        public String version() {
            return GitTags.versionOf(tag);
        }

        public String name() {
            return name;
        }

        /** In bytes, as GitHub says. */
        public long size() {
            return size;
        }

        public String url() {
            return url;
        }

        @Override
        public String toString() {
            return tag + "/" + name;
        }
    }

    /** One release's ontology file fetched earlier, on disk. */
    public static final class Cached {
        private final String tag;
        private final File file;

        Cached(String tag, File file) {
            this.tag = tag;
            this.file = file;
        }

        public String tag() {
            return tag;
        }

        public String version() {
            return GitTags.versionOf(tag);
        }

        public File file() {
            return file;
        }
    }

    /** {@code owner/repo} of a github.com remote URL, or null for any other. */
    public static String repositoryOfUrl(String remoteUrl) {
        if (remoteUrl == null) {
            return null;
        }
        Matcher match = GITHUB.matcher(remoteUrl.trim());
        return match.matches() ? match.group(1) + "/" + match.group(2) : null;
    }

    /**
     * {@code owner/repo} of the project's remote "origin" on github.com, read from the
     * repository's configuration - no git process, no network - or null.
     */
    public static String repositoryOf(File projectRoot) {
        File dotGit = projectRoot == null ? null : ReleaseTags.dotGitAbove(projectRoot);
        File config = dotGit == null || !dotGit.isDirectory() ? null : new File(dotGit, "config");
        if (config == null || !config.isFile()) {
            return null;
        }
        try {
            boolean origin = false;
            for (String line : Files.readAllLines(config.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    origin = trimmed.replace(" ", "").equals("[remote\"origin\"]");
                } else if (origin && trimmed.startsWith("url")) {
                    int equals = trimmed.indexOf('=');
                    if (equals > 0) {
                        return repositoryOfUrl(trimmed.substring(equals + 1));
                    }
                }
            }
        } catch (IOException unreadable) {
            return null;
        }
        return null;
    }

    /**
     * Each release's ontology file in GitHub's answer for a page of releases: the first asset
     * named {@code <id>.owl}, else {@code .ttl}, {@code .obo}, {@code .ofn}, {@code .owx} -
     * a product such as {@code <id>-base.owl} is not the ontology; no draft; no tag that is no
     * version.
     */
    static List<Asset> listed(String releasesJson, String id) throws IOException {
        List<Asset> assets = new ArrayList<Asset>();
        JsonNode releases = JSON.readTree(releasesJson);
        if (releases == null || !releases.isArray()) {
            return assets;
        }
        for (JsonNode release : releases) {
            String tag = release.path("tag_name").asText("");
            if (release.path("draft").asBoolean(false) || GitTags.versionOf(tag) == null) {
                continue;
            }
            Asset chosen = null;
            int rank = Integer.MAX_VALUE;
            for (JsonNode asset : release.path("assets")) {
                String name = asset.path("name").asText("");
                int at = ReleaseTags.SUFFIXES.indexOf(name.startsWith(id + ".")
                        ? name.substring(id.length()) : "");
                if (at >= 0 && at < rank) {
                    rank = at;
                    chosen = new Asset(tag, name, asset.path("size").asLong(-1),
                            asset.path("browser_download_url").asText(""));
                }
            }
            if (chosen != null && !chosen.url().isEmpty()) {
                assets.add(chosen);
            }
        }
        return assets;
    }

    /**
     * Asks api.github.com for every release of the repository, a hundred at a time, and gives
     * each one's ontology file. Only on the person's word.
     */
    public static List<Asset> list(String repository, String id, Download.Opener opener)
            throws IOException {
        List<Asset> assets = new ArrayList<Asset>();
        for (int page = 1; page <= 20; page++) {
            String text;
            Download.Opened opened = opener.open("https://api.github.com/repos/" + repository
                    + "/releases?per_page=100&page=" + page);
            try (InputStream body = opened.getBody()) {
                text = new String(readAll(body), StandardCharsets.UTF_8);
            }
            JsonNode releases = JSON.readTree(text);
            if (releases == null || !releases.isArray() || releases.size() == 0) {
                break;
            }
            assets.addAll(listed(text, id));
            if (releases.size() < 100) {
                break;
            }
        }
        return assets;
    }

    /** The folder fetched releases are kept in, in the person's home. */
    public static File cacheFolder() {
        return new File(new File(System.getProperty("user.home"), ".ontoboard"),
                "github-releases");
    }

    /** Where an asset of a repository is kept: {@code <cache>/<owner>/<repo>/<tag>/<file>}. */
    public static File cached(File cache, String repository, Asset asset) {
        return new File(new File(new File(cache, repository), asset.tag()), asset.name());
    }

    /** The releases of the repository fetched earlier - each tag's ontology file - newest last. */
    public static List<Cached> cachedAssets(File cache, String repository, String id) {
        List<Cached> found = new ArrayList<Cached>();
        File[] tags = repository == null ? null : new File(cache, repository).listFiles();
        if (tags == null) {
            return found;
        }
        java.util.Arrays.sort(tags);
        for (File tag : tags) {
            if (!tag.isDirectory() || GitTags.versionOf(tag.getName()) == null) {
                continue;
            }
            for (String suffix : ReleaseTags.SUFFIXES) {
                File file = new File(tag, id + suffix);
                if (file.isFile()) {
                    found.add(new Cached(tag.getName(), file));
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Downloads one asset to {@code target}, through a {@code .part} file that is renamed when it
     * is whole; stopped or failed, neither is left.
     *
     * @throws IOException when it could not be fetched, or was stopped
     */
    public static File fetch(Asset asset, File target, Download.Opener opener,
            Download.Progress progress) throws IOException {
        File folder = target.getAbsoluteFile().getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Could not make the folder " + folder);
        }
        File part = new File(target.getPath() + ".part");
        boolean whole = false;
        try {
            Download.Opened opened = opener.open(asset.url());
            long total = opened.getLength() > 0 ? opened.getLength() : asset.size();
            try (InputStream in = opened.getBody();
                    OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    if (progress.isCancelled()) {
                        throw new IOException("Stopped.");
                    }
                    out.write(buffer, 0, read);
                    done += read;
                    progress.bytes(done, total);
                }
            }
            if (progress.isCancelled()) {
                throw new IOException("Stopped.");
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            whole = true;
            return target;
        } finally {
            if (!whole) {
                part.delete();
                if (!target.isFile()) {
                    target.delete();
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
