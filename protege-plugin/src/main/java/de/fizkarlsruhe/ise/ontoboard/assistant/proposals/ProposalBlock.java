package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the proposals in an answer: fenced blocks of {@code key: value} lines, as the fixed
 * rules ask for them. A line format, because small local models keep to one more reliably than
 * to JSON.
 *
 * <pre>
 * ```ontoboard-proposal
 * action: set
 * subject: Concept
 * property: definition
 * value: A ... that ...
 * reason: ...
 * ```
 * </pre>
 *
 * <p>Keys are case-insensitive; a line that does not start with a known key continues the value
 * before it. Unlike {@code NeedBlock}, a block the answer was cut off inside does not count: a
 * change that may be missing its last line is not offered for applying.
 */
public final class ProposalBlock {

    static final String FENCE = "ontoboard-proposal";

    private static final Pattern BLOCK = Pattern.compile(
            "```" + FENCE + "[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);

    private static final Pattern KEY_LINE = Pattern.compile(
            "^\\s*(?:[-*]\\s+)?(action|subject|property|value|language|lang|axiom|with|reason)\\s*:"
                    + "\\s?(.*)$", Pattern.CASE_INSENSITIVE);

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private ProposalBlock() {
    }

    /** Every complete block, in order. */
    public static List<Proposal> parse(String reply) {
        List<Proposal> found = new ArrayList<Proposal>();
        if (reply == null) {
            return found;
        }
        Matcher matcher = BLOCK.matcher(reply);
        while (matcher.find()) {
            found.add(read(matcher.group(1)));
        }
        return found;
    }

    /** The answer without its complete blocks: they are shown as cards instead. */
    public static String withoutBlocks(String reply) {
        if (reply == null) {
            return "";
        }
        return BLOCK.matcher(reply).replaceAll("").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static Proposal read(String body) {
        Proposal.Builder b = new Proposal.Builder(NEXT_ID.getAndIncrement());
        String key = null;
        StringBuilder value = new StringBuilder();
        for (String line : body.split("\\r?\\n")) {
            Matcher matcher = KEY_LINE.matcher(line);
            if (matcher.matches()) {
                put(b, key, value.toString());
                key = matcher.group(1).toLowerCase(Locale.ROOT);
                value.setLength(0);
                value.append(matcher.group(2).trim());
            } else if (key != null && !line.trim().isEmpty()) {
                if (value.length() > 0) {
                    value.append('\n');
                }
                value.append(line.trim());
            }
        }
        put(b, key, value.toString());
        return b.build();
    }

    private static void put(Proposal.Builder b, String key, String raw) {
        if (key == null) {
            return;
        }
        String value = unquote(raw.trim());
        if ("action".equals(key)) {
            b.actionText = value;
        } else if ("subject".equals(key)) {
            b.subject = value.isEmpty() ? null : value;
        } else if ("property".equals(key)) {
            b.propertyText = value;
        } else if ("value".equals(key)) {
            b.value = value;
        } else if ("language".equals(key) || "lang".equals(key)) {
            b.language = value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
        } else if ("axiom".equals(key)) {
            b.axiom = value;
        } else if ("with".equals(key)) {
            b.with = value;
        } else if ("reason".equals(key)) {
            b.reason = value;
        }
    }

    /** {@code "x"} or {@code `x`} as x, when the marks enclose the whole value and nothing else. */
    static String unquote(String value) {
        String text = value;
        boolean changed = true;
        while (changed && text.length() >= 2) {
            changed = false;
            for (char mark : new char[] {'"', '`', '\''}) {
                if (text.charAt(0) == mark && text.charAt(text.length() - 1) == mark
                        && text.indexOf(mark, 1) == text.length() - 1) {
                    text = text.substring(1, text.length() - 1).trim();
                    changed = true;
                    break;
                }
            }
        }
        return text;
    }
}
