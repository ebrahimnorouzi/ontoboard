package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model asking for context instead of guessing (spec D7): a fenced block, as the fixed rules
 * ask for it, naming one term per line.
 *
 * <pre>
 * ```ontoboard-need
 * hasTopping
 * cheese topping
 * ```
 * </pre>
 *
 * <p>Read forgivingly - bullets, quotes and backticks around a name are dropped, and a block the
 * answer was cut off inside still counts - because a model that asked for the right thing in a
 * slightly wrong shape has still asked.
 */
public final class NeedBlock {

    static final String FENCE = "ontoboard-need";

    private static final Pattern BLOCK = Pattern.compile(
            "```" + FENCE + "[ \\t]*\\r?\\n(.*?)(?:```|\\z)", Pattern.DOTALL);

    private NeedBlock() {
    }

    /** The names asked for, in order, without duplicates. */
    public static List<String> parse(String reply) {
        Set<String> names = new LinkedHashSet<String>();
        if (reply != null) {
            Matcher matcher = BLOCK.matcher(reply);
            while (matcher.find()) {
                for (String line : matcher.group(1).split("\\r?\\n")) {
                    String name = tidy(line);
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        }
        return new ArrayList<String>(names);
    }

    /** The answer without its need blocks, for showing: the request appears as a button instead. */
    public static String withoutBlocks(String reply) {
        if (reply == null) {
            return "";
        }
        return BLOCK.matcher(reply).replaceAll("").trim();
    }

    private static String tidy(String line) {
        String name = line.trim();
        if (name.startsWith("- ") || name.startsWith("* ")) {
            name = name.substring(2).trim();
        }
        while (name.length() >= 2 && (quoted(name, '`') || quoted(name, '"')
                || quoted(name, '\''))) {
            name = name.substring(1, name.length() - 1).trim();
        }
        return name;
    }

    private static boolean quoted(String name, char mark) {
        return name.charAt(0) == mark && name.charAt(name.length() - 1) == mark;
    }
}
