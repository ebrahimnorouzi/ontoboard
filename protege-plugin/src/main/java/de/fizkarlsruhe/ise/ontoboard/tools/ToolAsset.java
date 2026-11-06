package de.fizkarlsruhe.ise.ontoboard.tools;

import java.util.Locale;

/**
 * One file OntoBoard can download for a tool it does not ship, pinned to what was measured.
 *
 * <p>Pinned by length and SHA-256, not trusted by name: a release asset can be replaced under the
 * same name, and nothing is loaded, run or remembered until the bytes on disk are the bytes that
 * were checked when this version of OntoBoard was built. GitHub publishes no digest for the
 * assets pinned here - they predate that - so each value was computed from a fresh download.
 */
public final class ToolAsset {

    private final String name;
    private final String url;
    private final long size;
    private final String sha256;
    private final String releasePage;
    private final boolean archive;

    /**
     * @param name the file name, exactly as the release names it
     * @param url where it downloads from
     * @param size its length in bytes
     * @param sha256 its SHA-256 in hex, either case
     * @param releasePage the release's page, for somebody downloading it by hand
     * @param archive whether it is a zip to unpack rather than the file to use
     */
    public ToolAsset(String name, String url, long size, String sha256, String releasePage,
            boolean archive) {
        this.name = name;
        this.url = url;
        this.size = size;
        this.sha256 = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
        this.releasePage = releasePage;
        this.archive = archive;
    }

    /** The file name, exactly as the release names it. */
    public String getName() {
        return name;
    }

    /** Where it downloads from. */
    public String getUrl() {
        return url;
    }

    /** Its length in bytes. */
    public long getSize() {
        return size;
    }

    /** Its SHA-256, lowercase hex. */
    public String getSha256() {
        return sha256;
    }

    /** The release's page, for somebody downloading it by hand. */
    public String getReleasePage() {
        return releasePage;
    }

    /** Whether it is a zip to unpack rather than the file to use. */
    public boolean isArchive() {
        return archive;
    }

    @Override
    public String toString() {
        return name;
    }
}
