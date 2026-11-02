package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/** The annotation line GitHub reads, escaped so that it reads all of it. */
class GitHubAnnotationsTest {

    @Test
    void aFailureIsNamedByClassAndMethodWithWhatWasThrown() {
        AssertionError thrown = new AssertionError("expected: <1> but was: <2>\nsecond line, 100%");
        thrown.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("org.junit.Assert", "fail", "Assert.java", 1),
            new StackTraceElement("de.fizkarlsruhe.ise.ontoboard.SomeTest", "works",
                    "SomeTest.java", 42),
        });

        String line = GitHubAnnotations.line(GitHubAnnotations.name(
                MethodSource.from("de.fizkarlsruhe.ise.ontoboard.SomeTest", "works"), "works()"),
                thrown);

        // In the message only newlines and percent signs are escaped; colons and commas stay.
        assertEquals("::error title=SomeTest.works::java.lang.AssertionError: expected: <1> "
                + "but was: <2>%0Asecond line, 100%25"
                + "%0A    at de.fizkarlsruhe.ise.ontoboard.SomeTest.works(SomeTest.java:42)", line);
    }

    @Test
    void aFailureOutsideAnyMethodIsNamedByItsClass() {
        assertEquals("SomeTest", GitHubAnnotations.name(
                ClassSource.from("de.fizkarlsruhe.ise.ontoboard.SomeTest"), "SomeTest"));
    }

    @Test
    void theTitleEscapesWhatGitHubWouldReadAsItsOwnSyntax() {
        assertEquals("::error title=a%3Ab%2Cc::failed, with nothing thrown",
                GitHubAnnotations.line("a:b,c", null));
    }
}
