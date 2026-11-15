package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A project's {@code .gitignore} after an update, as ODK's own update writes it.
 *
 * <p>ODK owns a block of the file, between two marker lines, and replaces it whole; what
 * stands outside the block is the project's and is kept, below ODK's part. This is
 * {@code update_gitignore} of ODK v1.6.1's odk.py, line for line - including what one might
 * wish otherwise: a line somebody put inside ODK's block is gone, the project's lines lose the
 * blanks around them, and one that ODK's part has too is not written again.
 */
public final class GitignoreMerge {

    private static final String START = "# ODK-managed rules, do not modify";
    private static final String END = "# End of ODK-managed rules";

    private GitignoreMerge() {
    }

    /**
     * @param rendered       ODK's template for the file, rendered for the project
     * @param existingOrNull the file as the project has it, or null when it has none
     * @return the file to write
     */
    public static String merge(String rendered, String existingOrNull) {
        List<String> theirs = new ArrayList<String>();
        if (existingOrNull != null) {
            boolean inside = false;
            for (String raw : PythonText.lines(existingOrNull)) {
                String line = PythonText.strip(raw);
                if (line.equals(START)) {
                    inside = true;
                } else if (line.equals(END)) {
                    inside = false;
                } else if (!inside) {
                    theirs.add(line);
                }
            }
        }
        StringBuilder merged = new StringBuilder();
        Set<String> written = new HashSet<String>();
        // As Python's split: every piece, an empty last one too, each with a line break after.
        for (String line : rendered.split("\n", -1)) {
            if (!line.isEmpty()) {
                written.add(line);
            }
            merged.append(line).append('\n');
        }
        for (String line : theirs) {
            if (!written.contains(line)) {
                merged.append(line).append('\n');
            }
        }
        return merged.toString();
    }
}
