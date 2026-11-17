package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ODK's switches as Build offers them - IMP, MIR, PAT and COMP, which ODK's Makefile takes as
 * true unless make's command line sets them false. A box ticked is ODK's default and goes on
 * no command line; each one that is not goes on it as {@code NAME=false}, on every route.
 */
public final class BuildSwitches {

    /** The switches, in ODK's order. */
    public enum Switch {
        IMP("Build the import modules again"),
        MIR("Download the imports' sources again"),
        PAT("Make the DOSDP patterns' files again"),
        COMP("Build the components again");

        private final String words;

        Switch(String words) {
            this.words = words;
        }

        /** What it does, as the form's box says it. */
        public String words() {
            return words;
        }
    }

    private BuildSwitches() {
    }

    /** Each switch that is off as ODK's variable, {@code NAME=false}, in ODK's order. */
    public static Map<String, String> variables(Set<Switch> off) {
        Map<String, String> variables = new LinkedHashMap<String, String>();
        for (Switch one : Switch.values()) {
            if (off != null && off.contains(one)) {
                variables.put(one.name(), "false");
            }
        }
        return Collections.unmodifiableMap(variables);
    }

    /** make's command for a target with these switches off. */
    public static List<String> makeCommand(String target, Set<Switch> off) {
        List<String> command = new ArrayList<String>(Arrays.asList("make", target));
        for (Map.Entry<String, String> one : variables(off).entrySet()) {
            command.add(one.getKey() + "=" + one.getValue());
        }
        return command;
    }

    /** The same as one line, as the form shows it: {@code make test IMP=false MIR=false}. */
    public static String command(String target, Set<Switch> off) {
        return String.join(" ", makeCommand(target, off));
    }
}
