package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ApiKeys;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionCheck;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionPreset;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelCallException;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelClients;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionKeys;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SettingsStore;
import de.fizkarlsruhe.ise.ontoboard.canvas.WrapLayout;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;

/**
 * One connection's form (spec D1, D2): the kind of server, its address, the model (picked from
 * the server's own list), the key and where it would be kept, and the two settings that matter
 * per call. "Test connection" asks for the model list and nothing else.
 *
 * <p>The key field never shows a stored key. A key typed here is kept for this Protégé session
 * only, unless "Remember the key on this computer" is ticked - and the words under that box say
 * where it then goes. Unticking it erases the stored key at once.
 */
public final class ConnectionEditor extends JPanel {

    private static final long serialVersionUID = 1L;

    private final SettingsStore store;
    private final Map<String, String> environment;
    private final Runnable onSaved;

    private static final String CHOOSE = "Choose a preset…";

    private final JComboBox<Object> preset = new JComboBox<Object>(presetItems());
    private final JComboBox<ConnectionKind> kind = new JComboBox<ConnectionKind>(
            ConnectionKind.values());
    private final JTextField name = new JTextField(18);
    private final JTextField baseUrl = new JTextField(24);
    private final JTextField keyVariable = new JTextField(18);
    private final JComboBox<String> model = new JComboBox<String>();
    private final JPasswordField key = new JPasswordField(18);
    private final JCheckBox remember = new JCheckBox();
    private final Note storage = new Note("", Color.GRAY);
    private final Note environmentLine = new Note(" ", Color.GRAY);
    private final JSpinner contextLength = new JSpinner(
            new SpinnerNumberModel(Connection.DEFAULT_CONTEXT_LENGTH, 512, 1_048_576, 1024));
    private final JLabel contextLabel = new JLabel("Context length");
    private final JSpinner maxOutput = new JSpinner(
            new SpinnerNumberModel(Connection.DEFAULT_MAX_OUTPUT_TOKENS, 64, 200_000, 256));
    private final JCheckBox plainHttp = new JCheckBox();
    private final Note plainHttpWarning = new Note(
            "Requests to this server, and its answers, are not encrypted.",
            new Color(0x9a, 0x4f, 0x00));
    private final Note result = new Note(" ", Color.BLACK);
    private final List<JLabel> rowLabels = new ArrayList<JLabel>();
    /** The widest row label, shared by the rows' layouts so the fields line up (1.144.0). */
    private final int[] labelWidth = {0};
    private String originalName;
    private ConnectionKind shownKind = ConnectionKind.OLLAMA;

    public ConnectionEditor(SettingsStore store, Map<String, String> environment,
            Runnable onSaved) {
        // Each row as wide as the column (1.142.0): a GridBagLayout narrower than its preferred
        // width falls back to its components' minimum sizes, and a text field's is a few pixels.
        super(new StackLayout(4));
        this.store = store;
        this.environment = environment;
        this.onSaved = onSaved;
        model.setEditable(true);
        // A server's model names are names: one starting "<html>" is not rendered (1.138.0 R12).
        // Set before the label takes any text: set after, the first name shown was rendered (the
        // review of the 1.151.0 ui fix).
        model.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            {
                putClientProperty("html.disable", Boolean.TRUE);
            }
        });
        storage.setText("Stored as plain text in " + store.keyStorageDescription()
                + ". Untick to erase it.");
        plainHttpWarning.setVisible(false);
        plainHttp.setToolTipText("Requests to this server, and its answers, are not encrypted:"
                + " only for a server on this computer or your own network.");

        kind.addActionListener(event -> kindChanged());
        preset.setToolTipText("Fills the kind, the address and the key variable; each can be"
                + " changed afterwards.");
        preset.addActionListener(event -> {
            if (preset.getSelectedItem() instanceof ConnectionPreset) {
                applyPreset((ConnectionPreset) preset.getSelectedItem());
            }
        });
        keyVariable.setToolTipText("The environment variable the key is read from when none is"
                + " typed. Empty for a server that needs no key.");
        keyVariable.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                showEnvironment();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                showEnvironment();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                showEnvironment();
            }
        });
        remember.addActionListener(event -> {
            if (!remember.isSelected() && originalName != null) {
                // The key stays known, so what is written later keeps it out (review 1.144.0
                // privacy-01).
                de.fizkarlsruhe.ise.ontoboard.assistant.history.KeyRedactor.noteKnownKeys(
                        store.connections(), environment, store::rememberedKey);
                store.forgetKey(originalName);
            }
        });
        plainHttp.addActionListener(event -> plainHttpWarning.setVisible(plainHttp.isSelected()));
        JButton fetch = new JButton("Fetch the list");
        fetch.setToolTipText("Ask the server which models it has. Nothing else is sent.");
        fetch.addActionListener(event -> fetchModels());
        JButton test = new JButton("Test connection");
        test.setToolTipText("Lists the models; spends no tokens and sends nothing about the"
                + " ontology.");
        test.addActionListener(event -> test());
        JButton save = new JButton("Save");
        save.addActionListener(event -> save());

        add(row(new JLabel("Preset"), preset));
        add(row(new JLabel("Kind"), kind));
        add(row(new JLabel("Name"), name));
        add(row(new JLabel("Address"), baseUrl));
        // "Fetch the list" under the model field, which gets the row's whole width: beside it
        // in a 300 px column the field was 29 px wide (1.144.0, review 1.143.0 I-4).
        add(row(new JLabel("Model"), model));
        add(line(fetch));
        add(row(new JLabel("Key"), key));
        add(row(new JLabel("Key variable"), keyVariable));
        add(Checks.labelled(remember, "Remember the key on this computer"));
        add(storage);
        add(environmentLine);
        add(row(contextLabel, contextLength));
        add(row(new JLabel("Max answer length"), maxOutput));
        add(Checks.labelled(plainHttp, "This server is on my network; send without encryption"));
        add(plainHttpWarning);
        add(line(save, test));
        add(result);
        for (JLabel label : rowLabels) {
            labelWidth[0] = Math.max(labelWidth[0], label.getPreferredSize().width);
        }
    }

    private static Object[] presetItems() {
        Object[] items = new Object[ConnectionPreset.values().length + 1];
        items[0] = CHOOSE;
        System.arraycopy(ConnectionPreset.values(), 0, items, 1, items.length - 1);
        return items;
    }

    /**
     * Kind, address and key variable from the preset; the name too, unless one was typed. Picked
     * while a saved connection is shown, the preset starts a new connection under its own label:
     * kept under the shown name, that connection's typed and remembered key went to the preset's
     * provider in "Test connection" and "Fetch the list", and in every chat after Save (1.146.0,
     * review 1.145.0 keys-01).
     *
     * <p>On a new form too, a key typed for an earlier preset's provider is emptied, and Remember
     * unticked: it went to the second preset's provider, and after Save into every chat (1.147.0,
     * review 1.146.0 keys-02). A name a preset gave is replaced, and made unique: the bare label
     * of a saved connection replaced it on Save, and erased its remembered key (1.147.0, review
     * 1.146.0 keys-04). A name the person typed is kept.
     */
    private void applyPreset(ConnectionPreset chosen) {
        if (originalName != null) {
            String fresh = uniqueName(chosen.getLabel());
            clearForm();
            name.setText(fresh);
        } else {
            key.setText("");
            remember.setSelected(false);
            if (isPresetName(name.getText().trim())) {
                name.setText(uniqueName(chosen.getLabel()));
            }
        }
        kind.setSelectedItem(chosen.getKind());
        shownKind = chosen.getKind();
        baseUrl.setText(chosen.getBaseUrl());
        keyVariable.setText(chosen.getKeyVariable());
        plainHttp.setSelected(false);
        plainHttpWarning.setVisible(false);
        showKind();
    }

    /** Empty, or a preset's label as a preset fills it in: "Mistral", or "Mistral 2". */
    private static boolean isPresetName(String typed) {
        if (typed.isEmpty()) {
            return true;
        }
        for (ConnectionPreset each : ConnectionPreset.values()) {
            if (typed.matches(java.util.regex.Pattern.quote(each.getLabel()) + "( [0-9]+)?")) {
                return true;
            }
        }
        return false;
    }

    /** The label, or the label with the first number that no saved connection's name has. */
    private String uniqueName(String label) {
        java.util.Set<String> taken = new java.util.HashSet<String>();
        for (Connection saved : store.connections()) {
            taken.add(saved.getName());
        }
        String candidate = label;
        for (int number = 2; taken.contains(candidate); number++) {
            candidate = label + " " + number;
        }
        return candidate;
    }

    /** The saved connection of this name, or null. */
    private Connection saved(String connectionName) {
        for (Connection saved : store.connections()) {
            if (saved.getName().equals(connectionName)) {
                return saved;
            }
        }
        return null;
    }

    /** Whether a key kept for this name was given for this connection's host. */
    private boolean isSavedHost(Connection connection) {
        Connection saved = saved(connection.getName());
        return saved != null && saved.getHost().equalsIgnoreCase(connection.getHost());
    }

    private static JComponent line(JComponent... parts) {
        JPanel line = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
        for (JComponent part : parts) {
            line.add(part);
        }
        return line;
    }

    /** A label and what it names: beside it, or above it in a narrow column (1.144.0). */
    private JComponent row(JLabel label, JComponent field) {
        rowLabels.add(label);
        JPanel row = new JPanel(new LabelledRowLayout(labelWidth));
        row.add(label);
        row.add(field);
        return row;
    }

    /** Shows a saved connection. */
    public void load(Connection connection) {
        if (connection == null) {
            newConnection();
            return;
        }
        originalName = connection.getName();
        shownKind = connection.getKind();
        preset.setSelectedIndex(0);
        kind.setSelectedItem(connection.getKind());
        name.setText(connection.getName());
        baseUrl.setText(connection.getBaseUrl());
        keyVariable.setText(connection.getKeyVariable());
        model.removeAllItems();
        model.setSelectedItem(connection.getModel());
        key.setText("");
        remember.setSelected(store.isKeyRemembered(connection.getName()));
        contextLength.setValue(connection.getContextLength());
        maxOutput.setValue(connection.getMaxOutputTokens());
        plainHttp.setSelected(connection.isPlainHttpOnNetwork());
        plainHttpWarning.setVisible(connection.isPlainHttpOnNetwork());
        result.setText(" ");
        showKind();
    }

    /** An empty form for a new connection: Ollama on this machine. */
    public void newConnection() {
        preset.setSelectedIndex(0);
        clearForm();
    }

    /** Every field but the preset as a new connection has it. */
    private void clearForm() {
        originalName = null;
        Connection fresh = Connection.of("", ConnectionKind.OLLAMA);
        shownKind = fresh.getKind();
        kind.setSelectedItem(fresh.getKind());
        name.setText("");
        baseUrl.setText(fresh.getBaseUrl());
        keyVariable.setText(fresh.getKeyVariable());
        model.removeAllItems();
        model.setSelectedItem("");
        key.setText("");
        remember.setSelected(false);
        contextLength.setValue(fresh.getContextLength());
        maxOutput.setValue(fresh.getMaxOutputTokens());
        plainHttp.setSelected(false);
        plainHttpWarning.setVisible(false);
        result.setText(" ");
        showKind();
    }

    private void kindChanged() {
        ConnectionKind chosen = (ConnectionKind) kind.getSelectedItem();
        if (chosen == null || chosen == shownKind) {
            return;
        }
        String typed = baseUrl.getText().trim();
        if (typed.isEmpty() || typed.equals(shownKind.getDefaultBaseUrl())) {
            baseUrl.setText(chosen.getDefaultBaseUrl());
            // A key typed for the address shown goes with it: kept, it went to the other kind's
            // server in "Test connection", and after Save into every chat (1.147.0, review
            // 1.146.0 keys-02).
            key.setText("");
        }
        String variable = keyVariable.getText().trim();
        if (variable.isEmpty() || variable.equals(shownKind.getDefaultKeyVariable())) {
            keyVariable.setText(chosen.getDefaultKeyVariable());
        }
        shownKind = chosen;
        showKind();
    }

    private void showKind() {
        boolean ollama = shownKind == ConnectionKind.OLLAMA;
        contextLabel.setVisible(ollama);
        contextLength.setVisible(ollama);
        showEnvironment();
        revalidate();
    }

    private void showEnvironment() {
        String variable = keyVariable.getText().trim();
        boolean fromEnvironment = !variable.isEmpty() && environment != null
                && environment.get(variable) != null
                && !environment.get(variable).trim().isEmpty();
        environmentLine.setText(fromEnvironment
                ? "Using $" + variable + " from the environment when no key is typed." : " ");
        revalidate();
    }

    /** The connection the form describes now. */
    Connection read() {
        Connection connection = Connection.of(name.getText().trim(), shownKind)
                .withBaseUrl(baseUrl.getText())
                .withKeyVariable(keyVariable.getText())
                .withModel(model.getSelectedItem() == null ? ""
                        : model.getSelectedItem().toString())
                .withPlainHttpOnNetwork(plainHttp.isSelected())
                .withContextLength((Integer) contextLength.getValue())
                .withMaxOutputTokens((Integer) maxOutput.getValue());
        return connection;
    }

    private String keyFor(Connection connection) {
        String typed = new String(key.getPassword()).trim();
        if (!typed.isEmpty()) {
            return typed;
        }
        // A key typed earlier or remembered was given for the saved connection's host, and never
        // goes to another one the form now shows (1.146.0, review 1.145.0 keys-01).
        boolean savedHost = isSavedHost(connection);
        return ApiKeys.resolve(connection, savedHost ? SessionKeys.get(connection.getName()) : "",
                environment, savedHost ? store.rememberedKey(connection.getName()) : "").getKey();
    }

    /** Saves the connection, makes it the active one, and keeps or erases the key as ticked. */
    public void save() {
        Connection connection = read();
        if (connection.getName().isEmpty()) {
            result.setText("A connection needs a name.");
            return;
        }
        if (connection.hasCredentials()) {
            // Java never sends them, so the address could not work; and saved, they would be
            // kept in plain text and shown in errors (1.144.0, review 1.143.0 M-4).
            result.setText("Not saved: OntoBoard cannot sign in with a name and password in the"
                    + " address. Remove them from the address, and put the key in the Key"
                    + " field.");
            return;
        }
        if (saved(connection.getName()) != null
                && !connection.getName().equals(originalName)) {
            // A new connection under a saved one's name replaced it, and erased its remembered
            // key (1.147.0, review 1.146.0 keys-04). A rename onto one replaced it too, and kept
            // its remembered key when Remember was ticked and no key typed: from the next
            // session on, every chat sent that key to the renamed connection's host (1.148.0,
            // review 1.147.0 keys-01).
            result.setText("Not saved: a connection called " + connection.getName() + " exists."
                    + " Choose another name, or pick it under Active connection to change it.");
            return;
        }
        // A key replaced, forgotten or renamed away below stays known (review 1.144.0
        // privacy-01).
        de.fizkarlsruhe.ise.ontoboard.assistant.history.KeyRedactor.noteKnownKeys(
                store.connections(), environment, store::rememberedKey);
        // The connection this form edits: under its old name when it is being renamed.
        boolean renamed = originalName != null && !originalName.equals(connection.getName());
        Connection before = saved(renamed ? originalName : connection.getName());
        // With no saved connection of this name, a key still kept under it was a deleted
        // connection's - Settings' Delete leaves the typed one - for a host no longer known.
        boolean otherHost = before == null
                || !before.getHost().equalsIgnoreCase(connection.getHost());
        String keptTyped = renamed ? SessionKeys.get(originalName) : "";
        String keptRemembered = renamed ? store.rememberedKey(originalName) : null;
        List<Connection> all = new ArrayList<Connection>(store.connections());
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            String existing = all.get(i).getName();
            if (existing.equals(originalName) || existing.equals(connection.getName())) {
                if (!replaced) {
                    all.set(i, connection);
                    replaced = true;
                } else {
                    all.remove(i--);
                }
            }
        }
        if (!replaced) {
            all.add(connection);
        }
        store.saveConnections(all);
        store.setActiveConnection(connection.getName());
        String typed = new String(key.getPassword()).trim();
        if (!typed.isEmpty()) {
            SessionKeys.put(connection.getName(), typed);
            if (remember.isSelected()) {
                store.rememberKey(connection.getName(), typed);
            }
        } else if (renamed && !otherHost) {
            // Renamed on the same host: its keys go with the name. They were dropped, Remember
            // still ticked, so the next chat had none (1.147.0, review 1.145.0 keys-09).
            SessionKeys.put(connection.getName(), keptTyped);
            if (remember.isSelected() && keptRemembered != null && !keptRemembered.isEmpty()) {
                store.rememberKey(connection.getName(), keptRemembered);
            }
        } else if (otherHost) {
            // The keys kept under this name were given for another host: every chat would send
            // them to this one (1.146.0, review 1.145.0 keys-01). Noted above, so they stay out
            // of what is written. A new connection's tick is the person's choice for the key
            // they type next, and is left.
            SessionKeys.clear(connection.getName());
            store.forgetKey(connection.getName());
            if (before != null) {
                remember.setSelected(false);
            }
        }
        if (!remember.isSelected()) {
            store.forgetKey(connection.getName());
        }
        if (originalName != null && !originalName.equals(connection.getName())) {
            store.forgetKey(originalName);
            SessionKeys.clear(originalName);
        }
        originalName = connection.getName();
        key.setText("");
        result.setText("Saved. " + connection.getName() + " is the active connection.");
        onSaved.run();
    }

    private void fetchModels() {
        final Connection connection = read();
        final String apiKey = keyFor(connection);
        result.setText("Asking " + connection.getHost() + " for its models…");
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws ModelCallException {
                return ModelClients.create(connection, apiKey).listModels();
            }

            @Override
            protected void done() {
                try {
                    Object chosen = model.getSelectedItem();
                    model.removeAllItems();
                    for (String found : get()) {
                        model.addItem(found);
                    }
                    if (chosen != null && !chosen.toString().isEmpty()) {
                        model.setSelectedItem(chosen);
                    }
                    result.setText(model.getItemCount() + " models on " + connection.getHost()
                            + ".");
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                    result.setText(String.valueOf(cause.getMessage()));
                }
            }
        }.execute();
    }

    private void test() {
        final Connection connection = read();
        final String apiKey = keyFor(connection);
        result.setText("Testing " + connection.getHost() + "…");
        new SwingWorker<ConnectionCheck.Result, Void>() {
            @Override
            protected ConnectionCheck.Result doInBackground() {
                return ConnectionCheck.run(connection, apiKey);
            }

            @Override
            protected void done() {
                try {
                    ConnectionCheck.Result checked = get();
                    String mark = checked.getState() == ConnectionCheck.State.READY
                            ? "Works: " : "Not working: ";
                    result.setText(mark + checked.getDetail()
                            + (checked.getRemedy().isEmpty() ? "" : "\n" + checked.getRemedy()));
                } catch (Exception failed) {
                    result.setText("The test did not finish: " + failed.getMessage());
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------ for tests

    /** What the last "Fetch the list" or "Test connection" said. */
    javax.swing.text.JTextComponent resultLine() {
        return result;
    }

    javax.swing.ListCellRenderer<? super String> modelRenderer() {
        return model.getRenderer();
    }

    JCheckBox rememberBox() {
        return remember;
    }

    String storageText() {
        return storage.getText();
    }

    String environmentText() {
        return environmentLine.getText();
    }

    boolean isContextLengthShown() {
        return contextLength.isVisible();
    }

    void setKind(ConnectionKind chosen) {
        kind.setSelectedItem(chosen);
    }

    void typeKey(String typed) {
        key.setText(typed);
    }

    String typedKey() {
        return new String(key.getPassword());
    }

    void choosePreset(String label) {
        preset.setSelectedItem(ConnectionPreset.byLabel(label));
    }

    void setKeyVariable(String variable) {
        keyVariable.setText(variable);
    }

    void setAddress(String address) {
        baseUrl.setText(address);
    }

    void rename(String newName) {
        name.setText(newName);
    }

    /** The key "Test connection" and "Fetch the list" would send for the form as it is now. */
    String keyToSend() {
        return keyFor(read());
    }

    /** A new connection with these values. */
    void fill(String newName, ConnectionKind chosen, String address, String modelName) {
        newConnection();
        kind.setSelectedItem(chosen);
        name.setText(newName);
        baseUrl.setText(address);
        model.setSelectedItem(modelName);
    }
}
