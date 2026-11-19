package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.swing.mxGraphComponent;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * More &gt; Board settings... (1.155.0): each kind of box's fill, outline and shape, each kind of
 * line's colour and dash, how faded an imported term is, and the arrangement - for all of a
 * person's ontologies, kept in Prot&eacute;g&eacute;'s preferences, or for this project, kept in
 * the board's sidecar and shared through git. A project's settings are over a person's.
 *
 * <p>The two are edited apart: "this project" starts from the defaults and what the project sets,
 * never from the person's own look, so nothing personal goes into the shared file. The preview
 * shows the board as the person will see it: the defaults, theirs, the project's.
 */
public final class BoardSettingsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** The dashes a line can take: what each is called, and its pattern ("" solid). */
    private static final String[][] DASHES = {
        {"solid", ""}, {"dashed", "8 4"}, {"short dashes", "4 4"}, {"dotted", "2 3"},
        {"fine dots", "1 5"},
    };

    /** What each box kind is called here. */
    private static final Map<String, String> BOX_NAMES = new LinkedHashMap<String, String>();

    static {
        BOX_NAMES.put(SchemaStyles.CLASS, "Class");
        BOX_NAMES.put(SchemaStyles.INDIVIDUAL, "Individual");
        BOX_NAMES.put(SchemaStyles.DATATYPE, "Datatype");
        BOX_NAMES.put(SchemaStyles.LITERAL, "Literal");
        BOX_NAMES.put(SchemaStyles.EXPRESSION, "Class expression");
        BOX_NAMES.put(SchemaStyles.OBJECT_PROPERTY_NODE, "Object property box");
        BOX_NAMES.put(SchemaStyles.DATA_PROPERTY_NODE, "Data property box");
        BOX_NAMES.put(SchemaStyles.STICKY_NOTE, "Sticky note");
        BOX_NAMES.put(SchemaStyles.FRAME, "Frame");
    }

    private final Map<String, String> person;
    private final Map<String, String> project;
    private final boolean projectPossible;
    private boolean forProject;

    /** The control for each key, to show a value again after a change, a switch or a Reset. */
    private final Map<String, Object> controls = new HashMap<String, Object>();
    private boolean showing;

    private final JRadioButton mine = new JRadioButton("All my ontologies");
    private final JRadioButton theProject = new JRadioButton("This project (shared through git)");
    private final SchemaGraph preview = new SchemaGraph();
    private final Projection sample = sample();
    private final CanvasLayout sampleLayout = sampleLayout();

    public BoardSettingsPanel(Map<String, String> person, Map<String, String> project,
            boolean projectPossible) {
        super(new BorderLayout(8, 8));
        this.person = new LinkedHashMap<String, String>(
                BoardLook.defaults().withChanges(person).changes());
        this.project = new LinkedHashMap<String, String>(
                BoardLook.defaults().withChanges(project).changes());
        this.projectPossible = projectPossible;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(scopeBar(), BorderLayout.NORTH);
        JScrollPane form = new JScrollPane(form());
        form.setBorder(BorderFactory.createEmptyBorder());
        form.getVerticalScrollBar().setUnitIncrement(16);
        form.setPreferredSize(new Dimension(560, 560));
        add(form, BorderLayout.CENTER);
        add(previewPanel(), BorderLayout.EAST);
        showLook();
    }

    /** What the person's own look changes of the defaults, as edited. */
    public Map<String, String> personChanges() {
        return new LinkedHashMap<String, String>(person);
    }

    /** What the project's look changes of the defaults, as edited. */
    public Map<String, String> projectChanges() {
        return new LinkedHashMap<String, String>(project);
    }

    /** Whether the project's look is the one being edited. */
    public boolean forProject() {
        return forProject;
    }

    /** Edits the project's look, or the person's; a board with no project file has only theirs. */
    void setForProject(boolean edit) {
        forProject = edit && projectPossible;
        (forProject ? theProject : mine).setSelected(true);
        showLook();
    }

    /** Sets one value of the look being edited, as one of the controls does. */
    void set(String key, String value) {
        Map<String, String> edited = forProject ? project : person;
        Map<String, String> changes = BoardLook.defaults().withChanges(edited).with(key, value).changes();
        edited.clear();
        edited.putAll(changes);
        showLook();
    }

    /** The look being edited back to the defaults; the other is left as it is. */
    void reset() {
        (forProject ? project : person).clear();
        showLook();
    }

    /** A value of the look being edited. */
    String shownValue(String key) {
        return editing().get(key);
    }

    /** The board as the person will see it: the defaults, theirs, the project's. */
    BoardLook previewLook() {
        return BoardLook.effective(person, project);
    }

    private BoardLook editing() {
        return BoardLook.defaults().withChanges(forProject ? project : person);
    }

    // ---------------------------------------------------------------- what the panel is made of

    private JPanel scopeBar() {
        JPanel bar = new JPanel(new BorderLayout());
        JPanel scope = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scope.add(new JLabel("Apply to:"));
        ButtonGroup group = new ButtonGroup();
        group.add(mine);
        group.add(theProject);
        mine.setSelected(true);
        mine.setToolTipText("Every board you open, kept in Protégé's preferences.");
        theProject.setToolTipText(projectPossible
                ? "<html>This ontology's board, kept in its sidecar file beside it -<br>"
                        + "committed with it, everybody who opens it sees it.<br>"
                        + "What the project sets is over what each person sets.</html>"
                : "Save the ontology to a file first: a project's settings are kept beside it.");
        theProject.setEnabled(projectPossible);
        mine.addActionListener(a -> setForProject(false));
        theProject.addActionListener(a -> setForProject(true));
        scope.add(mine);
        scope.add(theProject);
        bar.add(scope, BorderLayout.WEST);
        JButton reset = new JButton("Reset to defaults");
        reset.setToolTipText("Back to the board OntoBoard draws, for what is being edited.");
        reset.addActionListener(a -> reset());
        bar.add(reset, BorderLayout.EAST);
        return bar;
    }

    private JPanel form() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints at = new GridBagConstraints();
        at.insets = new Insets(2, 4, 2, 4);
        at.anchor = GridBagConstraints.WEST;
        at.gridy = 0;

        heading(form, at, "Boxes", "Fill", "Outline", "Shape");
        for (String[] box : BoardLook.BOXES) {
            String kind = box[0];
            at.gridx = 0;
            form.add(new JLabel(BOX_NAMES.get(kind)), at);
            at.gridx = 1;
            // A frame is drawn unfilled, and a sticky note in the colour each note was given.
            form.add(SchemaStyles.FRAME.equals(kind) || SchemaStyles.STICKY_NOTE.equals(kind)
                    ? new JLabel("") : colourButton(kind + ".fill", BOX_NAMES.get(kind) + " fill"), at);
            at.gridx = 2;
            // On the board a class is outlined in its namespace's colour; the key shows this one.
            form.add(colourButton(kind + ".stroke", BOX_NAMES.get(kind) + " outline"), at);
            at.gridx = 3;
            // A class's rows and a defined class's badge are drawn in a rectangle.
            List<String> shapes = SchemaStyles.CLASS.equals(kind)
                    ? Arrays.asList("rectangle", "rounded") : BoardLook.SHAPES;
            form.add(choice(kind + ".shape", shapes, shapes), at);
            at.gridy++;
        }

        heading(form, at, "Lines", "Colour", "Line", "");
        Map<String, String> lineNames = new HashMap<String, String>();
        for (CanvasLegend.Entry entry : CanvasLegend.entries()) {
            if (entry.getForm() == CanvasLegend.Form.EDGE) {
                lineNames.put(entry.getStyleName(), entry.getLabel());
            }
        }
        for (String[] line : BoardLook.LINES) {
            String kind = line[0];
            String name = lineNames.containsKey(kind) ? lineNames.get(kind) : kind;
            at.gridx = 0;
            form.add(new JLabel(name), at);
            at.gridx = 1;
            form.add(colourButton(kind + ".colour", name), at);
            at.gridx = 2;
            List<String> names = new ArrayList<String>();
            List<String> patterns = new ArrayList<String>();
            for (String[] dash : DASHES) {
                names.add(dash[0]);
                patterns.add(dash[1]);
            }
            if (!patterns.contains(line[2])) {
                names.add("as drawn (" + line[2] + ")");
                patterns.add(line[2]);
            }
            form.add(choice(kind + ".line", names, patterns), at);
            at.gridy++;
        }

        heading(form, at, "Imported terms", "", "", "");
        at.gridx = 0;
        form.add(new JLabel("How solid, in %"), at);
        at.gridx = 1;
        JSpinner fade = new JSpinner(new SpinnerNumberModel(55, 20, 100, 5));
        fade.setToolTipText("An imported term is drawn faded: context, not your work.");
        fade.addChangeListener(e -> {
            if (!showing) {
                set(BoardLook.IMPORTED_OPACITY, String.valueOf(fade.getValue()));
            }
        });
        controls.put(BoardLook.IMPORTED_OPACITY, fade);
        form.add(fade, at);
        at.gridy++;

        heading(form, at, "Arrangement", "", "", "");
        List<String> algorithms = new ArrayList<String>();
        List<String> algorithmNames = new ArrayList<String>();
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            algorithms.add(algorithm.name());
            algorithmNames.add(algorithm.getDisplayName());
        }
        setting(form, at, "Lays itself out as", choice(BoardLook.ARRANGEMENT, algorithmNames, algorithms),
                "The arrangement a new board, and a board that arranges itself, is laid out in.");
        setting(form, at, "A tree runs", choice(BoardLook.DIRECTION,
                Arrays.asList("down", "to the right"), Arrays.asList("down", "right")),
                "Hierarchical: superclasses above their subclasses, or on their left.");
        setting(form, at, "Spacing", choice(BoardLook.SPACING,
                Arrays.asList("normal", "compact", "roomy"), Arrays.asList("normal", "compact", "roomy")),
                "How far apart Hierarchical, Organic, Circle and Grid stand boxes. The Outline keeps "
                        + "its own.");
        setting(form, at, "Properties", choice(BoardLook.PROPERTIES,
                Arrays.asList("rows and arrows (UML-like)", "boxes of their own"), Arrays.asList("rows", "boxes")),
                "A data property a row in its class's box and an object property its arrows, or "
                        + "every property a box.");
        // What is left of the column takes the slack, so the rows stay at the top.
        at.gridx = 0;
        at.weighty = 1;
        form.add(new JLabel(""), at);
        return form;
    }

    private static void heading(JPanel form, GridBagConstraints at, String title, String a, String b,
            String c) {
        at.gridx = 0;
        at.insets = new Insets(at.gridy == 0 ? 2 : 12, 4, 2, 4);
        String[] texts = {title, a, b, c};
        for (int i = 0; i < texts.length; i++) {
            at.gridx = i;
            JLabel label = new JLabel(texts[i]);
            label.setFont(label.getFont().deriveFont(i == 0 ? Font.BOLD : Font.PLAIN));
            form.add(label, at);
        }
        at.insets = new Insets(2, 4, 2, 4);
        at.gridy++;
    }

    private static void setting(JPanel form, GridBagConstraints at, String name, JComboBox<String> box,
            String tip) {
        at.gridx = 0;
        JLabel label = new JLabel(name);
        label.setToolTipText(tip);
        form.add(label, at);
        at.gridx = 1;
        at.gridwidth = 3;
        box.setToolTipText(tip);
        form.add(box, at);
        at.gridwidth = 1;
        at.gridy++;
    }

    /** A button showing a colour; a click asks for another. */
    private JButton colourButton(final String key, final String what) {
        final JButton button = new JButton();
        button.setMargin(new Insets(1, 1, 1, 1));
        button.setIcon(new Swatch(key));
        button.addActionListener(a -> {
            Color now = decode(shownValue(key));
            Color chosen = javax.swing.JColorChooser.showDialog(this, what, now == null ? Color.WHITE : now);
            if (chosen != null) {
                set(key, String.format("#%02X%02X%02X", chosen.getRed(), chosen.getGreen(),
                        chosen.getBlue()));
            }
        });
        controls.put(key, button);
        return button;
    }

    /** A list of choices, each shown by its name and kept by its value. */
    private JComboBox<String> choice(final String key, final List<String> names, final List<String> values) {
        final JComboBox<String> box = new JComboBox<String>(names.toArray(new String[0]));
        box.putClientProperty("values", values);
        box.addActionListener(a -> {
            if (!showing && box.getSelectedIndex() >= 0) {
                set(key, values.get(box.getSelectedIndex()));
            }
        });
        controls.put(key, box);
        return box;
    }

    /** Every control showing the look being edited, and the preview the board. */
    @SuppressWarnings("unchecked")
    private void showLook() {
        showing = true;
        try {
            BoardLook look = editing();
            for (Map.Entry<String, Object> control : controls.entrySet()) {
                String value = look.get(control.getKey());
                if (control.getValue() instanceof JComboBox) {
                    JComboBox<String> box = (JComboBox<String>) control.getValue();
                    List<String> values = (List<String>) box.getClientProperty("values");
                    box.setSelectedIndex(Math.max(0, values.indexOf(value)));
                } else if (control.getValue() instanceof JSpinner) {
                    ((JSpinner) control.getValue()).setValue(Integer.valueOf(value));
                } else if (control.getValue() instanceof JButton) {
                    ((JButton) control.getValue()).setToolTipText(value);
                    ((JButton) control.getValue()).repaint();
                }
            }
        } finally {
            showing = false;
        }
        SchemaStyles.install(preview, previewLook());
        preview.render(sample, sampleLayout);
        preview.routeEdges();
        preview.refresh();
    }

    private JPanel previewPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JLabel title = new JLabel("Preview");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        panel.add(title, BorderLayout.NORTH);
        mxGraphComponent board = new mxGraphComponent(preview);
        board.setEnabled(false);
        board.setConnectable(false);
        board.getViewport().setBackground(Color.decode(SchemaStyles.CANVAS_BACKGROUND));
        board.setBorder(BorderFactory.createLineBorder(new Color(0xD8, 0xDD, 0xE3)));
        board.setPreferredSize(new Dimension(380, 560));
        panel.add(board, BorderLayout.CENTER);
        return panel;
    }

    /** A small board of each kind the look changes most: classes, rows, a defined class, arrows. */
    private static Projection sample() {
        String u = "http://example.org/preview#";
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        nodes.add(new CanvasNode(u + "Food", NodeKind.CLASS, "Food").asImported());
        nodes.add(new CanvasNode(u + "Pizza", NodeKind.CLASS, "Pizza")
                .withRows(Arrays.asList("name : string [1]", "key: id")));
        nodes.add(new CanvasNode(u + "Topping", NodeKind.CLASS, "Topping"));
        nodes.add(new CanvasNode(u + "Margherita", NodeKind.CLASS, "≡ Margherita"));
        nodes.add(new CanvasNode(u + "mine", NodeKind.INDIVIDUAL, "my pizza"));
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        edges.add(new CanvasEdge("sub|p", u + "Pizza", u + "Food", "", CanvasEdge.Kind.SUBCLASS));
        edges.add(new CanvasEdge("sub|m", u + "Margherita", u + "Pizza", "", CanvasEdge.Kind.SUBCLASS));
        edges.add(new CanvasEdge("rest|some|p|t", u + "Pizza", u + "Topping", "∃ has topping",
                CanvasEdge.Kind.OBJECT_PROPERTY));
        edges.add(new CanvasEdge("rest|only|m|t", u + "Margherita", u + "Topping", "∀ has topping",
                CanvasEdge.Kind.OBJECT_PROPERTY));
        edges.add(new CanvasEdge("type|i|m", u + "mine", u + "Margherita", "", CanvasEdge.Kind.TYPE));
        return new Projection(nodes, edges);
    }

    private static CanvasLayout sampleLayout() {
        String u = "http://example.org/preview#";
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(u + "Food", new CanvasLayout.NodeLayout(20, 20));
        layout.nodes.put(u + "Pizza", new CanvasLayout.NodeLayout(20, 120));
        layout.nodes.put(u + "Topping", new CanvasLayout.NodeLayout(196, 300));
        layout.nodes.put(u + "Margherita", new CanvasLayout.NodeLayout(20, 300));
        layout.nodes.put(u + "mine", new CanvasLayout.NodeLayout(20, 440));
        return layout;
    }

    private static Color decode(String colour) {
        try {
            return colour == null || !colour.startsWith("#") ? null : Color.decode(colour);
        } catch (NumberFormatException notAColour) {
            return null;
        }
    }

    /** The colour a key has in the look being edited. */
    private final class Swatch implements Icon {
        private final String key;

        Swatch(String key) {
            this.key = key;
        }

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Color colour = decode(shownValue(key));
            graphics.setColor(colour == null ? Color.WHITE : colour);
            graphics.fillRect(x, y, getIconWidth(), getIconHeight());
            graphics.setColor(Color.GRAY);
            graphics.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
        }

        @Override
        public int getIconWidth() {
            return 34;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }
    }
}
