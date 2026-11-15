package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.util.ArrayList;
import java.util.List;

/**
 * Text as Python reads it, where what ODK's own update writes depends on that.
 *
 * <p>ODK's update is a Python program. Two of the files it writes are merged from what a project
 * already has, and what "a line" and "blank" mean there is Python's meaning, which is not quite
 * Java's.
 */
final class PythonText {

    private PythonText() {
    }

    /**
     * {@code str.strip()}: white space off both ends. Python's white space has the no-break
     * spaces and the "next line" character, which Java's leaves out.
     */
    static String strip(String text) {
        int from = 0;
        int to = text.length();
        while (from < to && isSpace(text.charAt(from))) {
            from++;
        }
        while (to > from && isSpace(text.charAt(to - 1))) {
            to--;
        }
        return text.substring(from, to);
    }

    /** Whether nothing is left of a text once it is stripped. */
    static boolean isBlank(String text) {
        return strip(text).isEmpty();
    }

    /**
     * The lines of a file opened for reading text: a line feed, a carriage return and the two
     * together each end a line, and a line break at the very end does not start another one.
     */
    static List<String> lines(String text) {
        List<String> lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static boolean isSpace(char c) {
        // Next line, no-break space, figure space, narrow no-break space: by their numbers,
        // since none of them can be seen in a source file.
        return Character.isWhitespace(c) || c == 0x85 || c == 0xa0 || c == 0x2007 || c == 0x202f;
    }
}
