package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.tools.Download;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Releases kept on GitHub only: ODK v1.6.1's .gitignore keeps the release files out of git, and
 * its release puts them on the GitHub release as assets (1.155.0, asked by the user: fetched only
 * when the person asks, and History offers to fetch them).
 */
class GitHubReleasesTest {

    /** What api.github.com/repos/{owner}/{repo}/releases answers, in short. */
    private static final String RELEASES = "[\n"
            + "  {\"tag_name\": \"v1.1.0\", \"draft\": false, \"assets\": [\n"
            + "    {\"name\": \"egg-base.owl\", \"size\": 10,\n"
            + "     \"browser_download_url\": \"https://github.com/o/egg/releases/download/v1.1.0/egg-base.owl\"},\n"
            + "    {\"name\": \"egg.ttl\", \"size\": 20,\n"
            + "     \"browser_download_url\": \"https://github.com/o/egg/releases/download/v1.1.0/egg.ttl\"},\n"
            + "    {\"name\": \"egg.owl\", \"size\": 30,\n"
            + "     \"browser_download_url\": \"https://github.com/o/egg/releases/download/v1.1.0/egg.owl\"}]},\n"
            + "  {\"tag_name\": \"v1.0.0\", \"draft\": false, \"assets\": [\n"
            + "    {\"name\": \"egg.obo\", \"size\": 40,\n"
            + "     \"browser_download_url\": \"https://github.com/o/egg/releases/download/v1.0.0/egg.obo\"}]},\n"
            + "  {\"tag_name\": \"v0.9.0\", \"draft\": false, \"assets\": []},\n"
            + "  {\"tag_name\": \"v2.0.0\", \"draft\": true, \"assets\": [\n"
            + "    {\"name\": \"egg.owl\", \"size\": 50,\n"
            + "     \"browser_download_url\": \"https://github.com/o/egg/releases/download/v2.0.0/egg.owl\"}]},\n"
            + "  {\"tag_name\": \"nightly\", \"draft\": false, \"assets\": [\n"
            + "    {\"name\": \"egg.owl\", \"size\": 60,\n"
            + "     \"browser_download_url\": \"https://github.com/o/egg/releases/download/nightly/egg.owl\"}]}\n"
            + "]\n";

    @Test
    void aGitHubRemoteNamesItsRepository() {
        assertEquals("ISE-FIZKarlsruhe/nfdicore",
                GitHubReleases.repositoryOfUrl("https://github.com/ISE-FIZKarlsruhe/nfdicore.git"));
        assertEquals("ISE-FIZKarlsruhe/mwo",
                GitHubReleases.repositoryOfUrl("https://github.com/ISE-FIZKarlsruhe/mwo"));
        assertEquals("o/egg", GitHubReleases.repositoryOfUrl("git@github.com:o/egg.git"));
        assertEquals("o/egg", GitHubReleases.repositoryOfUrl("ssh://git@github.com/o/egg.git"));
        assertNull(GitHubReleases.repositoryOfUrl("https://gitlab.com/o/egg.git"));
        assertNull(GitHubReleases.repositoryOfUrl("C:/repos/egg"));
        assertNull(GitHubReleases.repositoryOfUrl(null));
    }

    /** Read from the repository's own configuration: no git process, no network. */
    @Test
    void theRepositoryIsReadFromGitsConfiguration(@TempDir File root) throws Exception {
        File git = new File(root, ".git");
        assertTrue(git.mkdirs());
        Files.write(new File(git, "config").toPath(), ("[core]\n\tbare = false\n"
                + "[remote \"upstream\"]\n\turl = https://github.com/someone/else.git\n"
                + "[remote \"origin\"]\n\turl = https://github.com/o/egg.git\n"
                + "\tfetch = +refs/heads/*:refs/remotes/origin/*\n")
                .getBytes(StandardCharsets.UTF_8));
        File project = new File(root, "egg");
        assertTrue(project.mkdirs());

        assertEquals("o/egg", GitHubReleases.repositoryOf(project));
        assertNull(GitHubReleases.repositoryOf(new File(root.getParentFile(), "no-such")));
    }

    /**
     * Each release's ontology file: <id>.owl, else .ttl, .obo, .ofn, .owx - not a product such
     * as egg-base.owl; no draft; no tag that is no version.
     */
    @Test
    void eachReleaseGivesItsOntologyFile() throws Exception {
        List<GitHubReleases.Asset> assets = GitHubReleases.listed(RELEASES, "egg");

        assertEquals(2, assets.size(), String.valueOf(assets));
        assertEquals("v1.1.0", assets.get(0).tag());
        assertEquals("1.1.0", assets.get(0).version());
        assertEquals("egg.owl", assets.get(0).name());
        assertEquals(30, assets.get(0).size());
        assertEquals("https://github.com/o/egg/releases/download/v1.1.0/egg.owl",
                assets.get(0).url());
        assertEquals("egg.obo", assets.get(1).name());
    }

    /** Fetched into a folder of its own per repository and tag, outside the project. */
    @Test
    void anAssetIsFetchedWhereHistoryFindsIt(@TempDir File cache) throws Exception {
        GitHubReleases.Asset asset = GitHubReleases.listed(RELEASES, "egg").get(0);
        byte[] owl = "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8);
        List<String> asked = new ArrayList<String>();

        File fetched = GitHubReleases.fetch(asset, GitHubReleases.cached(cache, "o/egg", asset),
                url -> {
                    asked.add(url);
                    return new Download.Opened(new ByteArrayInputStream(owl), owl.length);
                }, progress(false));

        assertEquals(new File(cache, "o/egg/v1.1.0/egg.owl").getAbsoluteFile(),
                fetched.getAbsoluteFile());
        assertArrayEquals(owl, Files.readAllBytes(fetched.toPath()));
        assertEquals(asset.url(), asked.get(0));
        assertEquals("[1.1.0]", versions(GitHubReleases.cachedAssets(cache, "o/egg", "egg")));
    }

    /** Stopped, nothing is left: no file History would take for the release. */
    @Test
    void aStoppedFetchLeavesNothing(@TempDir File cache) throws Exception {
        GitHubReleases.Asset asset = GitHubReleases.listed(RELEASES, "egg").get(0);
        File target = GitHubReleases.cached(cache, "o/egg", asset);

        assertThrows(IOException.class, () -> GitHubReleases.fetch(asset, target,
                url -> new Download.Opened(new ByteArrayInputStream(new byte[100]), 100),
                progress(true)));

        assertFalse(target.exists());
        assertFalse(new File(target.getPath() + ".part").exists());
        assertTrue(GitHubReleases.cachedAssets(cache, "o/egg", "egg").isEmpty());
    }

    private static Download.Progress progress(final boolean stop) {
        return new Download.Progress() {
            @Override
            public void bytes(long done, long total) {
            }

            @Override
            public boolean isCancelled() {
                return stop;
            }
        };
    }

    private static String versions(List<GitHubReleases.Cached> cached) {
        List<String> versions = new ArrayList<String>();
        for (GitHubReleases.Cached one : cached) {
            versions.add(one.version());
        }
        return versions.toString();
    }
}
