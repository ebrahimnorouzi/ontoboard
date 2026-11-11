package de.fizkarlsruhe.ise.ontoboard.assistant.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NeedBlock;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rules every request carries: their examples are what OntoBoard itself reads. */
class FixedRulesTest {

    /** A model copies the example; the example must therefore be a block OntoBoard parses. */
    @Test
    void theProposalExamplesAreBlocksOntoBoardReads() {
        List<Proposal> examples = ProposalBlock.parse(FixedRules.text());

        assertEquals(4, examples.size());
        assertEquals(Proposal.Action.SET, examples.get(0).getAction());
        assertEquals(Proposal.Property.DEFINITION, examples.get(0).getProperty());
        assertEquals(Proposal.Action.ADD_AXIOM, examples.get(1).getAction());
        assertEquals(Proposal.Action.REMOVE_AXIOM, examples.get(2).getAction());
        assertEquals(Proposal.Action.REPLACE_AXIOM, examples.get(3).getAction());
        assertTrue(examples.get(3).getWith() != null && !examples.get(3).getWith().isEmpty());
    }

    /** 1.139.0: the repair question allows a change, not only a removal. */
    @Test
    void theRepairQuestionAllowsAReplacement() {
        String asked = de.fizkarlsruhe.ise.ontoboard.assistant.session.Questions.whyInconsistent();

        assertTrue(asked.contains("replace-axiom"), asked);
        assertTrue(asked.contains("remove-axiom"), asked);
    }

    @Test
    void theNeedExampleIsStillABlockOntoBoardReads() {
        assertTrue(NeedBlock.parse(FixedRules.text()).contains("hasTopping"));
    }

    @Test
    void theRulesSayAProposalIsNotAChange() {
        String rules = FixedRules.text();

        assertTrue(rules.contains("ontoboard-proposal"), rules);
        assertTrue(rules.contains("never say it is done"), rules);
    }
}
