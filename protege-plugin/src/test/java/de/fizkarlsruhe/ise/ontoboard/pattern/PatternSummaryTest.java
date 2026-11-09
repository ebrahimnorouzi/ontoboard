package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;

/** What the chooser says about a pattern. Every claim in it is an assertion about the file. */
class PatternSummaryTest {

    /** The numbers shown are the ones read from the file. */
    @Test
    void theSummaryQuotesTheFile() throws Exception {
        DesignPattern affordance = PatternLibrary.find("affordance");
        PatternLibrary.Contents contents = PatternLibrary.contentsOf(affordance);

        String html = PatternSummary.asHtml(affordance, contents);

        // Seven: eight in its signature, one of which is owl:Nothing.
        assertTrue(html.contains("7 classes"), "metadata.json says 2: " + html);
        assertFalse(html.contains("2 classes"));
    }

    /** A duplicate says so, and names what it duplicates. */
    @Test
    void aDuplicateIsCalledOut() throws Exception {
        DesignPattern copy = PatternLibrary.find("collectionentity");

        String html = PatternSummary.asHtml(copy, PatternLibrary.contentsOf(copy));

        assertTrue(html.contains("collection"), html);
        assertTrue(html.contains("second name"), html);

        // Agentrole declares agent-role's IRI with twice its terms: another version, not a copy.
        DesignPattern agentrole = PatternLibrary.find("agentrole");
        String version = PatternSummary.asHtml(agentrole, PatternLibrary.contentsOf(agentrole));
        assertTrue(version.contains("agent-role</b> declares the same ontology IRI with other"
                + " terms: this one has 8, agent-role has 4"), version);
    }

    /** A pattern that is nobody's duplicate does not mention duplication. */
    @Test
    void aUniquePatternSaysNothingAboutDuplicates() throws Exception {
        DesignPattern componency = PatternLibrary.find("componency");

        String html = PatternSummary.asHtml(componency, PatternLibrary.contentsOf(componency));

        assertFalse(html.contains("second name"), html);
    }

    /** The imports are explained, because what arrives is smaller than the file. */
    @Test
    void theImportsAreExplained() {
        List<IRI> one = new ArrayList<IRI>();
        one.add(IRI.create("http://example.org/a.owl"));
        assertTrue(PatternSummary.importsSentence(one).contains("1 other ontology"));

        List<IRI> four = new ArrayList<IRI>();
        for (int at = 0; at < 4; at++) {
            four.add(IRI.create("http://example.org/" + at + ".owl"));
        }
        String sentence = PatternSummary.importsSentence(four);
        assertTrue(sentence.contains("4 other ontologies"), sentence);
        assertTrue(sentence.contains("does not bring these in"), sentence);
    }

    /** A long import list is cut off rather than scrolled through. */
    @Test
    void aLongImportListIsTruncated() throws Exception {
        DesignPattern affordance = PatternLibrary.find("affordance");

        String html = PatternSummary.asHtml(affordance, PatternLibrary.contentsOf(affordance));

        int items = html.split("<li>", -1).length - 1;
        assertTrue(items <= PatternSummary.MOST_IMPORTS_SHOWN + 1,
                items + " list items for four imports");
    }

    /**
     * What is said first - already imported, found by a term - comes before what the pattern says
     * about itself, escaped, and nothing is said when there is nothing to say.
     */
    @Test
    void whatIsSaidFirstComesFirst() throws Exception {
        DesignPattern componency = PatternLibrary.find("componency");
        PatternLibrary.Contents contents = PatternLibrary.contentsOf(componency);

        String html = PatternSummary.asHtml(componency, contents, null,
                "Already imported into this ontology. Found by its terms: a <part>.");

        assertTrue(html.contains("Already imported into this ontology."), html);
        assertTrue(html.contains("a &lt;part&gt;"), html);
        assertTrue(html.indexOf("Already imported") < html.indexOf("Contains:"), html);
        assertTrue(PatternSummary.asHtml(componency, contents, null, null)
                .equals(PatternSummary.asHtml(componency, contents)));
    }

    /** Markup in the data is escaped, not rendered. */
    @Test
    void theDataCannotWriteHtml() {
        assertTrue(PatternSummary.escape("a <b> & c").equals("a &lt;b&gt; &amp; c"),
                PatternSummary.escape("a <b> & c"));
    }
}
