package de.fizkarlsruhe.ise.ontoboard.menu;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.view.mxGraph;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportsGraph;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * OntoBoard &gt; Project &gt; Imports graph... - the {@code owl:imports} structure as a picture.
 *
 * <p>The one genuinely different <em>view</em> OntoGraf has that OntoBoard did not
 * ({@code OntoGrafImportView}). <em>Project &rarr; Imports&hellip;</em> remains the better table
 * - it says what each import resolved to and which release it was cut from - but it lists only
 * the active ontology's direct imports, and an ODK project's import structure is a shape. Five
 * modules importing one mirror that imports upstream is one picture and twelve rows, and the
 * rows never say the mirror is shared.
 *
 * <p>Drawn with the same graph library the canvas uses, laid out hierarchically, and read-only:
 * this is for seeing the structure, not for editing it. The model behind it is
 * {@link ImportsGraph}, which is where anything that can be wrong lives.
 */
public class ImportsGraphAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    /** Wide enough for a long module name without the box swallowing it. */
    static final int NODE_WIDTH = 190;
    static final int NODE_HEIGHT = 34;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        OWLOntology ontology = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        if (ontology == null) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "Open an ontology first - the imports graph is drawn from the one you have "
                            + "open.",
                    "Nothing is open", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        final ImportsGraph imports = ImportsGraph.of(ontology,
                getOWLModelManager().getOWLOntologyManager());
        if (imports.isEmpty()) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "This ontology imports nothing, so there is no structure to draw.",
                    "No imports", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        show(imports);
    }

    private void show(final ImportsGraph imports) {
        final JDialog dialog = new JDialog(
                javax.swing.SwingUtilities.getWindowAncestor(getOWLWorkspace()),
                "Imports graph", JDialog.ModalityType.APPLICATION_MODAL);

        mxGraphComponent component = new mxGraphComponent(drawn(imports));
        component.setConnectable(false);
        component.getGraph().setCellsEditable(false);
        component.getGraph().setCellsMovable(true);
        component.getGraph().setCellsResizable(false);
        component.setPreferredSize(new Dimension(760, 520));
        component.setBorder(BorderFactory.createEmptyBorder());

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        buttons.add(new JLabel(summarise(imports)));
        buttons.add(javax.swing.Box.createHorizontalGlue());

        JButton copyDot = new JButton("Copy as DOT");
        copyDot.setToolTipText("GraphViz source, for your own layout engine or a LaTeX pipeline");
        copyDot.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new java.awt.datatransfer.StringSelection(imports.toDot()), null);
                JOptionPane.showMessageDialog(dialog,
                        "Copied " + imports.getNodes().size() + " nodes as GraphViz DOT.",
                        "Copied", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        JButton close = new JButton("Close");
        close.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dialog.dispose();
            }
        });
        buttons.add(copyDot);
        buttons.add(javax.swing.Box.createHorizontalStrut(6));
        buttons.add(close);

        JPanel content = new JPanel(new BorderLayout());
        content.add(component, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        if (!imports.getUnresolved().isEmpty()) {
            JLabel warning = new JLabel(unresolvedSentence(imports), SwingConstants.LEADING);
            warning.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
            content.add(warning, BorderLayout.NORTH);
        }
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(getOWLWorkspace());
        dialog.setVisible(true);
    }

    /** One line under the picture, naming the thing the table cannot show. */
    static String summarise(ImportsGraph imports) {
        StringBuilder text = new StringBuilder(imports.getNodes().size() + " ontologies, "
                + imports.getEdges().size() + " imports");
        if (!imports.shared().isEmpty()) {
            text.append(" · ").append(imports.shared().size())
                .append(imports.shared().size() == 1
                        ? " is imported by more than one" : " are imported by more than one");
        }
        return text.toString();
    }

    static String unresolvedSentence(ImportsGraph imports) {
        int count = imports.getUnresolved().size();
        return "<html><b>" + count + (count == 1 ? " import did" : " imports did")
                + " not resolve</b>, drawn dashed and red. Project → Imports… says why."
                + "</html>";
    }

    /** The graph, laid out. Read-only: this view is for seeing the shape. */
    private static mxGraph drawn(ImportsGraph imports) {
        mxGraph graph = new mxGraph();
        Object parent = graph.getDefaultParent();
        graph.getModel().beginUpdate();
        try {
            Map<IRI, Object> cells = new LinkedHashMap<IRI, Object>();
            for (ImportsGraph.Node node : imports.getNodes()) {
                cells.put(node.getIri(), graph.insertVertex(parent, node.getIri().toString(),
                        node.getShortName(), 0, 0, NODE_WIDTH, NODE_HEIGHT, styleFor(node)));
            }
            for (ImportsGraph.Edge edge : imports.getEdges()) {
                Object from = cells.get(edge.getFrom());
                Object to = cells.get(edge.getTo());
                if (from != null && to != null) {
                    graph.insertEdge(parent, null, "", from, to,
                            "endArrow=open;strokeColor=#78849A;");
                }
            }
            new mxHierarchicalLayout(graph, SwingConstants.NORTH).execute(parent);
        } finally {
            graph.getModel().endUpdate();
        }
        return graph;
    }

    static String styleFor(ImportsGraph.Node node) {
        if (node.isRoot()) {
            return "rounded=1;fillColor=#DCE9F7;strokeColor=#2C5F8E;fontStyle=1;";
        }
        if (!node.isResolved()) {
            return "rounded=1;fillColor=#FDEDED;strokeColor=#B00020;fontColor=#B00020;"
                    + "dashed=1;";
        }
        return "rounded=1;fillColor=#FFFFFF;strokeColor=#78849A;";
    }
}
