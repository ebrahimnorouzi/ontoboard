package de.fizkarlsruhe.ise.ontoboard.odk;

/**
 * The YAML block at the top of a Jekyll-style Markdown file, and the prose under it.
 *
 * <p>Needed because {@code src/metadata/<id>.md} is a registry entry whose facts live in a
 * fenced YAML header, and {@link OdkYaml} refuses the file whole - snakeyaml reads {@code ---} as
 * a document separator and reports "expected a single document in the stream". Split here, the
 * header reads like any other ODK YAML and every reader this project already has works on it.
 *
 * <p><b>The closing fence is found by scanning forward from the opening one</b>, not by searching
 * the whole file. A registry entry's prose is Markdown, and {@code ---} is also how Markdown
 * writes a horizontal rule or underlines a heading; taking the last one, or any one, would put
 * half the description inside the header.
 *
 * <p><b>Line endings are left exactly as they were.</b> All three real {@code .md} files in the
 * projects this was measured against use CRLF, and the point of every splice in this package is
 * that a one-word change stays a one-line diff.
 */
public final class FrontMatter {

    private FrontMatter() {
    }

    /** The fence, which must be the first thing in the file for there to be a header at all. */
    private static final String FENCE = "---";

    /**
     * The YAML between the fences, or null when the file has no front matter.
     *
     * <p>Null rather than empty, because "this file has no header" and "this file has an empty
     * header" are different things to report.
     */
    public static String yamlOf(String text) {
        int[] bounds = fences(text);
        return bounds == null ? null : text.substring(bounds[0], bounds[1]);
    }

    /** Everything after the closing fence, or the whole text when there is no front matter. */
    public static String bodyOf(String text) {
        int[] bounds = fences(text);
        if (bounds == null) {
            return text == null ? "" : text;
        }
        return text.substring(bounds[2]);
    }

    /** Whether this text begins with a front-matter fence at all. */
    public static boolean has(String text) {
        return fences(text) != null;
    }

    /**
     * The header's start, the header's end, and where the body begins.
     *
     * <p>Returns null unless the file opens with a fence and a later line is a fence of its own.
     * A fence has to be alone on its line: {@code ---------} underlining a heading is not one,
     * and neither is a line that merely starts with three dashes.
     */
    private static int[] fences(String text) {
        if (text == null) {
            return null;
        }
        int from = 0;
        // A leading byte-order mark is not an error in a file somebody's editor wrote.
        if (text.startsWith("﻿")) {
            from = 1;
        }
        int firstEnd = endOfFenceLineAt(text, from);
        if (firstEnd < 0) {
            return null;
        }
        int at = firstEnd;
        while (at < text.length()) {
            int lineEnd = text.indexOf('\n', at);
            int stop = lineEnd < 0 ? text.length() : lineEnd + 1;
            int closing = endOfFenceLineAt(text, at);
            if (closing >= 0) {
                return new int[] {firstEnd, at, closing};
            }
            at = stop;
        }
        return null;
    }

    /**
     * The index just past a line that is exactly a fence, or -1.
     *
     * @param at the start of the line to look at
     */
    private static int endOfFenceLineAt(String text, int at) {
        if (at >= text.length() || !text.startsWith(FENCE, at)) {
            return -1;
        }
        int after = at + FENCE.length();
        // Trailing spaces are tolerated; anything else on the line means it is not a fence.
        while (after < text.length() && (text.charAt(after) == ' ' || text.charAt(after) == '\t')) {
            after++;
        }
        if (after < text.length() && text.charAt(after) == '\r') {
            after++;
        }
        if (after >= text.length()) {
            return after;
        }
        if (text.charAt(after) != '\n') {
            return -1;
        }
        return after + 1;
    }
}
