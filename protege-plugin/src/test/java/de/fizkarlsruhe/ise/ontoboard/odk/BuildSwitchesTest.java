package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * ODK's switches as Build offers them: each box ticked is ODK's default, and each one that is
 * not goes on make's command line as ODK's variable, in ODK's words.
 */
class BuildSwitchesTest {

    @Test
    void aSwitchThatIsOffIsOdksVariable() {
        assertEquals(Collections.singletonMap("IMP", "false"),
                BuildSwitches.variables(EnumSet.of(BuildSwitches.Switch.IMP)));
        assertEquals("make test IMP=false MIR=false", BuildSwitches.command("test",
                EnumSet.of(BuildSwitches.Switch.MIR, BuildSwitches.Switch.IMP)));
    }

    /** Every box ticked: make is run as ODK's defaults have it, naming nothing. */
    @Test
    void allTickedIsOdksOwnDefault() {
        assertEquals(Collections.emptyMap(),
                BuildSwitches.variables(EnumSet.noneOf(BuildSwitches.Switch.class)));
        assertEquals("make test",
                BuildSwitches.command("test", EnumSet.noneOf(BuildSwitches.Switch.class)));
    }

    @Test
    void theMakeCommandIsTheTargetThenEachSwitchThatIsOffInOdksOrder() {
        assertEquals(Arrays.asList("make", "prepare_release", "IMP=false", "PAT=false",
                "COMP=false"), BuildSwitches.makeCommand("prepare_release",
                        EnumSet.of(BuildSwitches.Switch.COMP, BuildSwitches.Switch.PAT,
                                BuildSwitches.Switch.IMP)));
    }

    @Test
    void eachSwitchSaysWhatItDoes() {
        assertEquals("Build the import modules again", BuildSwitches.Switch.IMP.words());
        assertEquals("Download the imports' sources again", BuildSwitches.Switch.MIR.words());
        assertEquals("Make the DOSDP patterns' files again", BuildSwitches.Switch.PAT.words());
        assertEquals("Build the components again", BuildSwitches.Switch.COMP.words());
    }
}
