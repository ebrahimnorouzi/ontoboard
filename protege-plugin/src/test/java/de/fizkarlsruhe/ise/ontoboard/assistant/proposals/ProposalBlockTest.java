package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The line format the fixed rules ask for, read as models actually write it. */
class ProposalBlockTest {

    /** The maintainer's own case: a definition for pizza's Concept, as a card to accept. */
    @Test
    void parsesTheConceptExample() {
        String reply = "Here is a definition in genus-differentia form.\n\n"
                + "```ontoboard-proposal\n"
                + "action: set\n"
                + "subject: Concept\n"
                + "property: definition\n"
                + "value: An entity that classifies other entities by a shared meaning.\n"
                + "reason: Concept has no definition yet.\n"
                + "```\n";

        List<Proposal> found = ProposalBlock.parse(reply);

        assertEquals(1, found.size());
        Proposal proposal = found.get(0);
        assertEquals(Proposal.Action.SET, proposal.getAction());
        assertEquals("Concept", proposal.getSubject());
        assertEquals(Proposal.Property.DEFINITION, proposal.getProperty());
        assertEquals("An entity that classifies other entities by a shared meaning.",
                proposal.getValue());
        assertEquals("Concept has no definition yet.", proposal.getReason());
        assertNull(proposal.getLanguage());
        assertEquals(Proposal.Status.OFFERED, proposal.getStatus());
    }

    @Test
    void aBlockCutOffIsIgnored() {
        String reply = "```ontoboard-proposal\naction: add\nsubject: x\nproperty: synonym\n"
                + "value: y\n```\nand\n```ontoboard-proposal\naction: set\nsubject: z\n";

        List<Proposal> found = ProposalBlock.parse(reply);

        assertEquals(1, found.size());
        assertEquals("x", found.get(0).getSubject());
    }

    @Test
    void twoBlocksGiveTwoProposals() {
        String reply = "```ontoboard-proposal\naction: remove-axiom\n"
                + "axiom: Naples hasPrice \"9.50\"^^xsd:decimal\n```\n"
                + "```ontoboard-proposal\naction: add\nsubject: x\nproperty: example\n"
                + "value: y\n```";

        List<Proposal> found = ProposalBlock.parse(reply);

        assertEquals(2, found.size());
        assertEquals(Proposal.Action.REMOVE_AXIOM, found.get(0).getAction());
        assertEquals("Naples hasPrice \"9.50\"^^xsd:decimal", found.get(0).getAxiom());
        assertEquals(Proposal.Property.EXAMPLE, found.get(1).getProperty());
    }

    @Test
    void keysAreCaseInsensitiveAndAValueContinuesOverLines() {
        String reply = "```ontoboard-proposal\nAction: SET\nSubject: vegetarian pizza\n"
                + "Property: Label\nLanguage: de\nValue: first line\n  second line\n```";

        Proposal proposal = ProposalBlock.parse(reply).get(0);

        assertEquals(Proposal.Action.SET, proposal.getAction());
        assertEquals(Proposal.Property.LABEL, proposal.getProperty());
        assertEquals("de", proposal.getLanguage());
        assertEquals("first line\nsecond line", proposal.getValue());
    }

    @Test
    void quotesAroundAValueAreDropped() {
        String reply = "```ontoboard-proposal\naction: add\nsubject: `cheese topping`\n"
                + "property: synonym\nvalue: \"cheese\"\n```";

        Proposal proposal = ProposalBlock.parse(reply).get(0);

        assertEquals("cheese topping", proposal.getSubject());
        assertEquals("cheese", proposal.getValue());
    }

    @Test
    void anUnknownActionOrPropertyIsKeptAsWritten() {
        String reply = "```ontoboard-proposal\naction: rename\nsubject: x\nproperty: colour\n"
                + "value: y\n```";

        Proposal proposal = ProposalBlock.parse(reply).get(0);

        assertNull(proposal.getAction());
        assertEquals("rename", proposal.getActionText());
        assertNull(proposal.getProperty());
        assertEquals("colour", proposal.getPropertyText());
    }

    /** 1.139.0: a replacement names the axiom it replaces and the one it puts in its place. */
    @Test
    void aReplacementReadsItsTwoAxioms() {
        Proposal proposal = ProposalBlock.parse("```ontoboard-proposal\naction: replace-axiom\n"
                + "axiom: hasPrice Range: xsd:float\nwith: hasPrice Range: xsd:decimal\n"
                + "reason: the prices are decimals\n```").get(0);

        assertEquals(Proposal.Action.REPLACE_AXIOM, proposal.getAction());
        assertEquals("hasPrice Range: xsd:float", proposal.getAxiom());
        assertEquals("hasPrice Range: xsd:decimal", proposal.getWith());
    }

    @Test
    void anAdditionIsRead() {
        Proposal proposal = ProposalBlock.parse("```ontoboard-proposal\naction: add-axiom\n"
                + "axiom: 'vegetarian pizza' SubClassOf hasTopping some 'cheese topping'\n```")
                .get(0);

        assertEquals(Proposal.Action.ADD_AXIOM, proposal.getAction());
        assertEquals("'vegetarian pizza' SubClassOf hasTopping some 'cheese topping'",
                proposal.getAxiom());
    }

    @Test
    void eachProposalHasItsOwnId() {
        String block = "```ontoboard-proposal\naction: add\nsubject: x\nproperty: example\n"
                + "value: y\n```\n";

        List<Proposal> found = ProposalBlock.parse(block + block);

        assertFalse(found.get(0).getId() == found.get(1).getId());
    }

    @Test
    void withoutBlocksKeepsTheProseAndACutOffBlock() {
        String reply = "Before.\n```ontoboard-proposal\naction: add\nsubject: x\n"
                + "property: example\nvalue: y\n```\nAfter.\n```ontoboard-proposal\naction: se";

        String shown = ProposalBlock.withoutBlocks(reply);

        assertTrue(shown.startsWith("Before."), shown);
        assertTrue(shown.contains("After."), shown);
        assertFalse(shown.contains("subject: x"), shown);
        assertTrue(shown.contains("action: se"), shown);
    }
}
