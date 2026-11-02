package de.fizkarlsruhe.ise.ontoboard.sheet;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order in which ROBOT's command line - and so an ODK component that lists several
 * templates - has to be given a folder's sheets, and the sheets no order can build.
 *
 * <p>ROBOT constructs and generates one template after the other, so a sheet can name only the
 * rows of the sheets it was given before. Template... in Protege reads every sheet of the folder
 * first and has no such rule (TemplateSheet.Names); this is for the builds that run ROBOT
 * itself. Two sheets that name each other's rows have no order at all: each needs the other to
 * come first.
 *
 * <p>A reference counts when it names a row of another sheet by label - one the sheet does not
 * define itself and the ontology does not answer to either - or by an ID written in quotes, which
 * ROBOT looks up by name. A bare ID needs no order: ROBOT turns it into an IRI by its prefix,
 * measured on robot-core 1.9.8, and the row it names need not have been read. No Swing and no
 * Protege types.
 */
public final class SheetOrder {

    private final List<String> order;
    private final List<List<String>> cycles;
    private final Map<String, Set<String>> needs;

    private SheetOrder(List<String> order, List<List<String>> cycles,
            Map<String, Set<String>> needs) {
        this.order = order;
        this.cycles = cycles;
        this.needs = needs;
    }

    /**
     * @param sheets every sheet of the folder, by the name the index knows it by
     * @param index the names of those sheets, and of the ontology
     */
    public static SheetOrder of(Map<String, List<List<String>>> sheets, LabelIndex index) {
        Map<String, Set<String>> needs = new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, List<List<String>>> sheet : sheets.entrySet()) {
            needs.put(sheet.getKey(), needsOf(sheet.getKey(), sheet.getValue(), sheets, index));
        }

        List<List<String>> cycles = cyclesAmong(needs, Collections.<String>emptySet());
        // Each cycle is placed as one block: its sheets go together, after everything the block
        // needs from outside it, and a sheet that names a sheet of the cycle waits for the block.
        Map<String, List<String>> blockOf = new LinkedHashMap<String, List<String>>();
        for (String sheet : needs.keySet()) {
            blockOf.put(sheet, Collections.singletonList(sheet));
        }
        for (List<String> cycle : cycles) {
            for (String sheet : cycle) {
                blockOf.put(sheet, cycle);
            }
        }

        // Kahn's: a block goes next once every sheet it names from outside it is placed, taking
        // them in the folder's own order whenever there is a choice, so the answer does not
        // reshuffle.
        List<String> order = new ArrayList<String>();
        Set<String> placed = new LinkedHashSet<String>();
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (String sheet : needs.keySet()) {
                List<String> block = blockOf.get(sheet);
                if (placed.contains(sheet) || !block.get(0).equals(sheet)) {
                    continue;
                }
                Set<String> outside = new LinkedHashSet<String>();
                for (String member : block) {
                    outside.addAll(needs.get(member));
                }
                outside.removeAll(block);
                if (placed.containsAll(outside)) {
                    placed.addAll(block);
                    order.addAll(block);
                    progressed = true;
                }
            }
        }
        return new SheetOrder(order, cycles, needs);
    }

    private static Set<String> needsOf(String self, List<List<String>> rows,
            Map<String, List<List<String>>> sheets, LabelIndex index) {
        Set<String> needed = new LinkedHashSet<String>();
        if (rows == null || rows.size() < 2) {
            return needed;
        }
        for (TemplateColumns.Column column : TemplateColumns.of(rows).getColumns()) {
            // A class expression counts when its cell is a single name - a parent named by its
            // label in SC %, quoted or not. A whole Manchester expression is not followed.
            if (column.getKind() != TemplateColumns.Kind.INDIVIDUAL
                    && column.getKind() != TemplateColumns.Kind.TYPE
                    && column.getKind() != TemplateColumns.Kind.ANNOTATION_IRI
                    && column.getKind() != TemplateColumns.Kind.CLASS_EXPRESSION) {
                continue;
            }
            for (int rowAt = 2; rowAt < rows.size(); rowAt++) {
                List<String> row = rows.get(rowAt);
                int at = column.getNumber() - 1;
                String raw = at < row.size() && row.get(at) != null ? row.get(at) : "";
                for (String value : valuesIn(raw, column.getSplit())) {
                    needed.addAll(sheetsDefining(value, self, sheets, index));
                }
                for (String quoted : quotedIdsIn(raw, column.getSplit())) {
                    needed.addAll(sheetsDefiningId(quoted, self, sheets, index));
                }
            }
        }
        return needed;
    }

    /**
     * The other sheets a label is defined in - none when the sheet defines it itself or the
     * ontology does, because ROBOT reads a template's own rows before it builds them.
     */
    private static Set<String> sheetsDefining(String value, String self,
            Map<String, List<List<String>>> sheets, LabelIndex index) {
        Set<String> defining = new LinkedHashSet<String>();
        for (LabelIndex.Entry entry : index.lookup(value)) {
            if (!sheets.containsKey(entry.getSource()) || entry.getSource().equals(self)) {
                return Collections.emptySet();
            }
            defining.add(entry.getSource());
        }
        return defining;
    }

    /**
     * IDs written in quotes - 'ex:i1'. ROBOT looks a quoted value up by name and does not expand
     * it, and a row is known by its ID text as a name only once its sheet has been read; so a
     * quoted ID, unlike a bare one, needs its sheet first.
     */
    private static List<String> quotedIdsIn(String raw, String split) {
        List<String> ids = new ArrayList<String>();
        String separator = split == null ? "" : split;
        if ("\\n".equals(separator.trim())) {
            separator = "\n";
        }
        String[] parts = separator.isEmpty() ? new String[] {raw}
                : raw.split(java.util.regex.Pattern.quote(separator), -1);
        for (String part : parts) {
            String value = part.trim();
            if (value.length() > 2 && value.startsWith("'") && value.endsWith("'")) {
                String inside = value.substring(1, value.length() - 1).trim();
                if (inside.indexOf(' ') < 0 && inside.indexOf(':') > 0) {
                    ids.add(inside);
                }
            }
        }
        return ids;
    }

    /** The other sheets with a row of this ID - none when the sheet has one itself. */
    private static Set<String> sheetsDefiningId(String id, String self,
            Map<String, List<List<String>>> sheets, LabelIndex index) {
        Set<String> defining = new LinkedHashSet<String>();
        List<LabelIndex.Entry> found;
        try {
            found = index.forIri(org.semanticweb.owlapi.model.IRI.create(id));
        } catch (RuntimeException notAnIri) {
            return defining;
        }
        for (LabelIndex.Entry entry : found) {
            if (!sheets.containsKey(entry.getSource()) || entry.getSource().equals(self)) {
                return Collections.<String>emptySet();
            }
            defining.add(entry.getSource());
        }
        return defining;
    }

    /** The names in a cell: split as ROBOT splits it, each without quotes around it. */
    private static List<String> valuesIn(String raw, String split) {
        List<String> values = new ArrayList<String>();
        String separator = split == null ? "" : split;
        if ("\\n".equals(separator.trim())) {
            // ROBOT reads SPLIT=\n as a line break, not as a backslash and an n.
            separator = "\n";
        }
        String[] parts = separator.isEmpty() ? new String[] {raw}
                : raw.split(java.util.regex.Pattern.quote(separator), -1);
        for (String part : parts) {
            String value = part.trim();
            if (value.length() > 1 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    /** The groups of sheets, two or more each, that name one another round in a circle. */
    private static List<List<String>> cyclesAmong(Map<String, Set<String>> needs,
            Set<String> placed) {
        // Tarjan's strongly connected components, over the sheets that could not be placed.
        final Map<String, Integer> number = new LinkedHashMap<String, Integer>();
        final Map<String, Integer> low = new LinkedHashMap<String, Integer>();
        final List<String> stack = new ArrayList<String>();
        final Set<String> onStack = new LinkedHashSet<String>();
        final List<List<String>> found = new ArrayList<List<String>>();
        final int[] counter = {0};
        for (String sheet : needs.keySet()) {
            if (!placed.contains(sheet) && !number.containsKey(sheet)) {
                strongConnect(sheet, needs, placed, number, low, stack, onStack, found, counter);
            }
        }
        return found;
    }

    private static void strongConnect(String sheet, Map<String, Set<String>> needs,
            Set<String> placed, Map<String, Integer> number, Map<String, Integer> low,
            List<String> stack, Set<String> onStack, List<List<String>> found, int[] counter) {
        number.put(sheet, counter[0]);
        low.put(sheet, counter[0]);
        counter[0]++;
        stack.add(sheet);
        onStack.add(sheet);
        for (String next : needs.get(sheet)) {
            if (placed.contains(next)) {
                continue;
            }
            if (!number.containsKey(next)) {
                strongConnect(next, needs, placed, number, low, stack, onStack, found, counter);
                low.put(sheet, Math.min(low.get(sheet), low.get(next)));
            } else if (onStack.contains(next)) {
                low.put(sheet, Math.min(low.get(sheet), number.get(next)));
            }
        }
        if (low.get(sheet).equals(number.get(sheet))) {
            List<String> component = new ArrayList<String>();
            String top;
            do {
                top = stack.remove(stack.size() - 1);
                onStack.remove(top);
                component.add(top);
            } while (!top.equals(sheet));
            if (component.size() > 1) {
                // In the folder's order, which is how somebody will look for them.
                List<String> ordered = new ArrayList<String>();
                for (String each : needs.keySet()) {
                    if (component.contains(each)) {
                        ordered.add(each);
                    }
                }
                found.add(ordered);
            }
        }
    }

    /**
     * Every sheet, each after the sheets it names by label. The sheets of a cycle have no such
     * place among themselves; they stand together, after what they name from outside it.
     */
    public List<String> getOrder() {
        return Collections.unmodifiableList(order);
    }

    /** Groups of two or more sheets that name one another; empty when every sheet has a place. */
    public List<List<String>> getCycles() {
        return Collections.unmodifiableList(cycles);
    }

    /** The sheets this one names rows of. */
    public Set<String> needsOf(String sheet) {
        Set<String> needed = needs.get(sheet);
        return needed == null ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(needed);
    }

    /** Whether any sheet names a row of another at all - when none does, any order will do. */
    public boolean hasReferences() {
        for (Set<String> needed : needs.values()) {
            if (!needed.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
