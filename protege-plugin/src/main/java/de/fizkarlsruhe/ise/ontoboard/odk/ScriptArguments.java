package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splitting a typed argument line into the arguments a process actually receives.
 *
 * <p>A field that says "arguments" and then splits on spaces is wrong the first time somebody
 * passes a path, because the paths on this platform contain spaces by default -
 * {@code C:\Users\someone\My Documents\x.owl} becomes three arguments and the script is handed
 * nonsense it will blame on itself. So the quoting rules a shell uses are implemented here
 * rather than approximated.
 *
 * <p><b>Nothing here is interpreted by a shell.</b> The parsed arguments go into a
 * {@code ProcessBuilder} command list as separate elements, so a semicolon, an ampersand or a
 * backtick in an argument is passed to the script as that character and cannot start a second
 * command. The one exception is the native-environment route, which genuinely does compose a
 * shell line, and {@link Toolchain#quote} is applied to every argument there for exactly this
 * reason.
 *
 * <p>An unterminated quote is refused rather than guessed at. Closing it silently would run a
 * command the user did not write, and the command they are about to approve is the whole basis
 * on which they approve it.
 */
public final class ScriptArguments {

    /** Raised when the line cannot be split, with a message meant for a dialog. */
    public static final class Malformed extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }

    private ScriptArguments() {
    }

    /**
     * The arguments in {@code line}, in order. An empty or blank line gives an empty list.
     *
     * <p>The rules are the ones a POSIX shell uses for word splitting, because these are shell
     * scripts and that is what the person typing expects:
     *
     * <ul>
     *   <li>whitespace separates arguments;
     *   <li>{@code "..."} groups, and a backslash inside escapes {@code "} and {@code \};
     *   <li>{@code '...'} groups literally, escaping nothing;
     *   <li>a backslash outside quotes escapes the next character.
     * </ul>
     *
     * @throws Malformed if a quote is never closed, or the line ends on a backslash
     */
    public static List<String> parse(String line) {
        List<String> arguments = new ArrayList<String>();
        if (line == null || line.trim().isEmpty()) {
            return Collections.unmodifiableList(arguments);
        }
        StringBuilder current = new StringBuilder();
        boolean inArgument = false;
        char quote = 0;

        for (int at = 0; at < line.length(); at++) {
            char c = line.charAt(at);

            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (quote == '"') {
                if (c == '\\' && at + 1 < line.length()
                        && (line.charAt(at + 1) == '"' || line.charAt(at + 1) == '\\')) {
                    // Only these two. In a double-quoted shell word a backslash before anything
                    // else is a literal backslash, and a Windows path is mostly backslashes.
                    current.append(line.charAt(++at));
                } else if (c == '"') {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                quote = c;
                // An empty quoted string is still an argument: "" means one empty argument.
                inArgument = true;
                continue;
            }
            if (c == '\\') {
                if (at + 1 >= line.length()) {
                    throw new Malformed("The arguments end with a backslash, which would escape "
                            + "nothing. Remove it, or double it to pass one backslash.");
                }
                current.append(line.charAt(++at));
                inArgument = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inArgument) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    inArgument = false;
                }
                continue;
            }
            current.append(c);
            inArgument = true;
        }

        if (quote != 0) {
            throw new Malformed("There is an unclosed " + (quote == '"' ? "double" : "single")
                    + " quote in the arguments. OntoBoard will not guess where it ends, because "
                    + "the command you approve has to be the command that runs.");
        }
        if (inArgument) {
            arguments.add(current.toString());
        }
        return Collections.unmodifiableList(arguments);
    }

    /**
     * The arguments rendered back as a line, for showing what was understood.
     *
     * <p>Shown next to the command rather than trusted silently: "three arguments" is the fact
     * that tells somebody their quoting did what they meant, and it is cheaper to read than to
     * debug afterwards.
     */
    public static String describe(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "no arguments";
        }
        StringBuilder text = new StringBuilder(arguments.size() == 1
                ? "1 argument: " : arguments.size() + " arguments: ");
        for (int at = 0; at < arguments.size(); at++) {
            if (at > 0) {
                text.append(' ');
            }
            text.append('[').append(arguments.get(at)).append(']');
        }
        return text.toString();
    }
}
