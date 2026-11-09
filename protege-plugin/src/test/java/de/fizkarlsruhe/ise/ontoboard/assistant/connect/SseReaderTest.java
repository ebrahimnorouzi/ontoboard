package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

/** Server-sent events, the way Anthropic's and OpenAI's streaming APIs send them. */
class SseReaderTest {

    private static SseReader reader(String text) {
        return new SseReader(new StringReader(text));
    }

    @Test
    void oneEventPerBlankLine() throws IOException {
        SseReader events = reader("data: one\n\ndata: two\n\n");

        assertEquals("one", events.next().getData());
        assertEquals("two", events.next().getData());
        assertNull(events.next());
    }

    @Test
    void namedEventsKeepTheirName() throws IOException {
        SseReader events = reader("event: content_block_delta\ndata: {\"x\":1}\n\ndata: plain\n\n");

        SseReader.Event first = events.next();
        assertEquals("content_block_delta", first.getEvent());
        assertEquals("{\"x\":1}", first.getData());
        assertEquals("message", events.next().getEvent(), "an unnamed event is a message");
    }

    @Test
    void multiLineDataIsJoined() throws IOException {
        assertEquals("a\nb", reader("data: a\ndata: b\n\n").next().getData());
    }

    @Test
    void commentsAreIgnored() throws IOException {
        SseReader events = reader(": keep-alive\n\ndata: real\n\n");

        assertEquals("real", events.next().getData(), "a comment alone dispatches nothing");
        assertNull(events.next());
    }

    @Test
    void aFinalEventWithoutABlankLineIsReturned() throws IOException {
        SseReader events = reader("data: [DONE]");

        assertEquals("[DONE]", events.next().getData());
        assertNull(events.next());
    }

    @Test
    void crlfLineEndingsWork() throws IOException {
        SseReader events = reader("event: ping\r\ndata: x\r\n\r\ndata: y\r\n\r\n");

        SseReader.Event first = events.next();
        assertEquals("ping", first.getEvent());
        assertEquals("x", first.getData());
        assertEquals("y", events.next().getData());
    }

    @Test
    void aFieldWithoutASpaceAfterTheColonStillCounts() throws IOException {
        assertEquals("tight", reader("data:tight\n\n").next().getData());
    }
}
