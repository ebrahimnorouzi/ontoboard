package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Reads server-sent events, the format Anthropic's and OpenAI's streaming APIs answer in.
 *
 * <p>The WHATWG event-stream rules for the two fields those APIs use - {@code event} and
 * {@code data} - and for comments; {@code id} and {@code retry} are read and ignored, because a
 * model call is never resumed. A blank line dispatches the event; an event the server ends the
 * stream on without one is still returned, because a proxy that strips the last blank line must
 * not cost the end of an answer. {@link BufferedReader#readLine} accepts {@code \n},
 * {@code \r\n} and {@code \r}, which is exactly the set the specification allows.
 */
public final class SseReader {

    /** One event: its name ({@code "message"} unless the server named it) and its data. */
    public static final class Event {
        private final String event;
        private final String data;

        Event(String event, String data) {
            this.event = event;
            this.data = data;
        }

        public String getEvent() {
            return event;
        }

        /** Every {@code data:} line of the event, joined with {@code \n}. */
        public String getData() {
            return data;
        }
    }

    /** Where the lines come from: a reader, or a client's stoppable line source. */
    public interface Lines {
        /** The next line without its ending, or null at the end. */
        String readLine() throws IOException;
    }

    private final Lines lines;

    public SseReader(Reader in) {
        final BufferedReader buffered =
                in instanceof BufferedReader ? (BufferedReader) in : new BufferedReader(in);
        this.lines = buffered::readLine;
    }

    public SseReader(Lines lines) {
        this.lines = lines;
    }

    /** The next event, or null at the end of the stream. */
    public Event next() throws IOException {
        String name = null;
        StringBuilder data = null;
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.isEmpty()) {
                if (data != null) {
                    return new Event(name == null ? "message" : name, data.toString());
                }
                // A blank line after only comments or an event name dispatches nothing.
                name = null;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("event".equals(field)) {
                name = value;
            } else if ("data".equals(field)) {
                if (data == null) {
                    data = new StringBuilder(value);
                } else {
                    data.append('\n').append(value);
                }
            }
        }
        return data == null ? null : new Event(name == null ? "message" : name, data.toString());
    }
}
