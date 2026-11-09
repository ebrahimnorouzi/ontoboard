package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalBlock;

/**
 * An answer as a bubble shows it. Proposal blocks become cards and need blocks a button, so
 * neither is shown as text; the markdown models write by habit - headings, bold, bullets, code
 * fences - becomes plain text, since the column shows text, not HTML. Nothing the model said is
 * reworded.
 */
public final class Shown {

    private static final String OPENING = "```ontoboard-proposal";

    private Shown() {
    }

    /**
     * @param streaming whether the answer is still arriving: a proposal block not yet closed is
     *     then said ("Writing a proposal…") rather than spelled out line by line
     */
    public static String forReading(String reply, boolean streaming) {
        String text = ProposalBlock.withoutBlocks(NeedBlock.withoutBlocks(reply));
        String suffix = "";
        int open = text.indexOf(OPENING);
        if (streaming && open >= 0) {
            text = text.substring(0, open);
            suffix = "\n\n✎ Writing a proposal…";
        } else if (open >= 0) {
            suffix = "\n\n(The answer ended inside a proposal, so it cannot be offered as a"
                    + " card.)";
        }
        String plain = plain(text).trim();
        if (plain.isEmpty() && suffix.isEmpty() && reply != null && reply.contains(OPENING)) {
            return "(The proposal is below.)";
        }
        return (plain + suffix).trim();
    }

    static String plain(String markdown) {
        StringBuilder out = new StringBuilder();
        for (String line : markdown.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                continue;
            }
            String text = line;
            if (trimmed.matches("#{1,6}\\s+.*")) {
                text = trimmed.replaceFirst("#{1,6}\\s+", "");
            } else if (trimmed.matches("[-*+]\\s+.*")) {
                String indent = line.substring(0, line.indexOf(trimmed.charAt(0)));
                text = indent + "• " + trimmed.substring(1).trim();
            }
            text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("__(.+?)__", "$1")
                    .replaceAll("`([^`]+)`", "$1")
                    // *italic*, but not the star of "2 * 3": it must touch its words.
                    .replaceAll("(?<![*\\w])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![*\\w])", "$1");
            out.append(text).append('\n');
        }
        return out.toString().replaceAll("\\n{3,}", "\n\n");
    }
}
