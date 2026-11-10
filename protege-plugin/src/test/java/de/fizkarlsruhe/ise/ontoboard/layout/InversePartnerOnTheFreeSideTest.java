package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An inverse partner stands beside a sub-property on the side their line can reach straight: its
 * right. A sub-property's is-a line leaves its left side for the trunk of its tree.
 *
 * <p>Found by the Linux build of 1.135.1, whose board is drawn in DejaVu Sans: its boxes are wider
 * than Segoe UI's, so the block had room right of "capability of" - the tree label order places
 * first - and the tree of "has characteristic" went there. "capability of" then stood left of "has
 * capability", across that tree's is-a line, and their line went over the top of the box with
 * three bends, 226px for a gap of 160px. In Segoe UI's widths the block had no such room, the tree
 * went to the left and the line was straight; so the board test passed on Windows only.
 */
class InversePartnerOnTheFreeSideTest {

    private static BoardSections.Term property(String id, String label, double w) {
        return new BoardSections.Term(id, BoardSections.Kind.OBJECT_PROPERTY, label, w, 40);
    }

    @Test
    void aSubPropertysPartnerStandsToItsRightWhateverWidthTheFontGivesTheBoxes() {
        // The widths DejaVu Sans gives these labels on the board: sub-property, then its partner.
        String[][] kinds = {
            {"quality", "180", "160"}, {"role", "160", "160"}, {"disposition", "200", "200"},
            {"function", "180", "180"}, {"capability", "200", "180"}, {"tendency", "200", "180"},
            {"power", "180", "160"}, {"potential", "200", "180"}};
        List<BoardSections.Term> terms = new ArrayList<BoardSections.Term>();
        List<BoardSections.Edge> edges = new ArrayList<BoardSections.Edge>();
        terms.add(property("HC", "has characteristic", 220));
        for (int i = 0; i < kinds.length; i++) {
            terms.add(property("has" + i, "has " + kinds[i][0], Double.parseDouble(kinds[i][1])));
            terms.add(property("of" + i, kinds[i][0] + " of", Double.parseDouble(kinds[i][2])));
            edges.add(new BoardSections.Edge("has" + i, "HC", BoardSections.Link.SUB_PROPERTY));
            edges.add(new BoardSections.Edge("has" + i, "of" + i, BoardSections.Link.INVERSE));
        }
        for (int i = 0; i < 10; i++) {
            terms.add(property("x" + i, "m other " + i, 160));
        }

        BoardSections.Plan plan = BoardSections.plan(terms, edges, 40, 40);

        List<String> left = new ArrayList<String>();
        for (int i = 0; i < kinds.length; i++) {
            double[] sub = plan.positions.get("has" + i);
            double[] partner = plan.positions.get("of" + i);
            if (partner[0] < sub[0]) {
                left.add(kinds[i][0] + " of stands left of has " + kinds[i][0]);
            }
        }
        assertEquals(new ArrayList<String>(), left);
    }
}
