package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The description a parameter form is generated from.
 *
 * <p>Most of these assertions are about the help text, which is the whole reason parameters are
 * data rather than hand-written dialogs. ROBOT has twenty-odd operations and a hundred options; a
 * hand-written form gets the explanation right for the first two and then degenerates into
 * controls nobody can interpret. Making help mandatory <em>and</em> rejecting a restatement of the
 * label is what stops that, and both rules are asserted here because both are easy to weaken later
 * under deadline.
 */
class ParameterTest {

    private static Parameter.Builder reasoner() {
        return Parameter.of("reasoner", "Reasoner", Parameter.Kind.CHOICE)
                .choices("ELK", "HermiT", "JFact");
    }

    // ---------- help is not optional ----------

    @Test
    void aParameterWithNoHelpIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> reasoner().build());

        assertTrue(thrown.getMessage().contains("no help text"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("nobody can interpret"), thrown.getMessage());
    }

    /** "The reasoner to use" for a parameter called Reasoner is the non-explanation to prevent. */
    @Test
    void helpThatMerelyRestatesTheLabelIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> reasoner().help("The reasoner to use for this."));
        assertThrows(IllegalArgumentException.class,
                () -> reasoner().help("Reasoner selection setting."));
    }

    @Test
    void helpTooShortToExplainAnythingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> reasoner().help("Pick one."));
        assertThrows(IllegalArgumentException.class, () -> reasoner().help(""));
        assertThrows(IllegalArgumentException.class, () -> reasoner().help(null));
    }

    @Test
    void helpThatActuallyExplainsIsAccepted() {
        Parameter parameter = reasoner()
                .help("ELK is fast and handles most OBO ontologies, but supports only a subset "
                        + "of OWL 2. HermiT is slower and complete.")
                .defaultValue("ELK").build();

        assertTrue(parameter.getHelp().contains("ELK is fast"));
        assertEquals("ELK", parameter.getDefaultValue());
    }

    // ---------- a form built from this cannot be nonsense ----------

    @Test
    void aChoiceWithNothingToChooseFromIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Parameter.of("x", "Something", Parameter.Kind.CHOICE)
                        .help("This explains what the parameter actually does at some length.")
                        .build());
    }

    /** A default outside the choices renders as a dropdown with nothing selected. */
    @Test
    void aDefaultThatIsNotOneOfTheChoicesIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> reasoner()
                        .help("ELK is fast and handles most OBO ontologies; HermiT is complete.")
                        .defaultValue("Pellet").build());

        assertTrue(thrown.getMessage().contains("Pellet"), thrown.getMessage());
    }

    @Test
    void aParameterNeedsAKeyAndALabel() {
        assertThrows(IllegalArgumentException.class,
                () -> Parameter.of("", "Label", Parameter.Kind.TEXT));
        assertThrows(IllegalArgumentException.class,
                () -> Parameter.of("key", "", Parameter.Kind.TEXT));
        assertThrows(IllegalArgumentException.class,
                () -> Parameter.of("key", null, Parameter.Kind.TEXT));
    }

    // ---------- validation names the field ----------

    private static Parameter valid(String key, String label, Parameter.Kind kind) {
        return Parameter.of(key, label, kind)
                .help("A sufficiently long explanation of what this parameter changes.")
                .build();
    }

    @Test
    void aRejectionNamesTheParameterSoAUserKnowsWhichFieldIsWrong() {
        Parameter number = valid("n", "Maximum depth", Parameter.Kind.NUMBER);

        String problem = number.reject("lots");
        assertTrue(problem.contains("Maximum depth"), problem);
        assertTrue(problem.contains("lots"), "quote what they typed: " + problem);
    }

    @Test
    void aNumberMustBeANumber() {
        Parameter number = valid("n", "Depth", Parameter.Kind.NUMBER);

        assertNull(number.reject("42"));
        assertNull(number.reject(" -7 "));
        assertTrue(number.reject("3.5") != null, "3.5 is not a whole number");
    }

    @Test
    void aChoiceMustBeOneOfTheChoices() {
        Parameter choice = reasoner()
                .help("ELK is fast and handles most OBO ontologies; HermiT is complete.").build();

        assertNull(choice.reject("HermiT"));
        assertTrue(choice.reject("Pellet").contains("must be one of"));
    }

    @Test
    void aFlagMustBeTrueOrFalse() {
        Parameter flag = valid("f", "Include imports", Parameter.Kind.FLAG);

        assertNull(flag.reject("true"));
        assertNull(flag.reject("FALSE"));
        assertTrue(flag.reject("yes") != null);
    }

    @Test
    void anEmptyOptionalValueIsFineAndAnEmptyRequiredOneIsNot() {
        Parameter optional = valid("o", "Output", Parameter.Kind.TEXT);
        Parameter needed = Parameter.of("r", "Source", Parameter.Kind.TEXT)
                .help("Where the terms are read from; a file path or a resolvable IRI.")
                .required().build();

        assertNull(optional.reject(""));
        assertNull(optional.reject(null));
        assertTrue(needed.reject("").contains("Source is required"));
    }

    // ---------- the form reports everything at once ----------

    /**
     * All the problems, not the first. A form that reports one per attempt makes a user discover
     * six mistakes in six rounds.
     */
    @Test
    void everyProblemIsReportedTogether() {
        List<Parameter> parameters = Arrays.asList(
                valid("n", "Depth", Parameter.Kind.NUMBER),
                Parameter.of("r", "Source", Parameter.Kind.TEXT)
                        .help("Where the terms are read from; a file path or a resolvable IRI.")
                        .required().build(),
                valid("f", "Include imports", Parameter.Kind.FLAG));
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("n", "deep");
        values.put("r", "");
        values.put("f", "maybe");

        List<String> problems = Parameter.rejections(parameters, values);

        assertEquals(3, problems.size(), problems.toString());
    }

    @Test
    void goodValuesProduceNoProblems() {
        List<Parameter> parameters = Arrays.asList(valid("n", "Depth", Parameter.Kind.NUMBER));
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("n", "3");

        assertTrue(Parameter.rejections(parameters, values).isEmpty());
    }

    @Test
    void theDefaultsAreTheFormsStartingPoint() {
        List<Parameter> parameters = Arrays.asList(
                reasoner().help("ELK is fast and handles most OBO ontologies; HermiT is complete.")
                        .defaultValue("ELK").build(),
                valid("n", "Depth", Parameter.Kind.NUMBER));

        Map<String, String> defaults = Parameter.defaultsOf(parameters);

        assertEquals("ELK", defaults.get("reasoner"));
        assertEquals("", defaults.get("n"));
        assertEquals(2, defaults.size());
    }

    @Test
    void missingValuesAreTreatedAsEmptyRatherThanCrashing() {
        List<Parameter> parameters = Arrays.asList(
                Parameter.of("r", "Source", Parameter.Kind.TEXT)
                        .help("Where the terms are read from; a file path or a resolvable IRI.")
                        .required().build());

        assertEquals(1, Parameter.rejections(parameters, null).size());
        assertEquals(1, Parameter.rejections(parameters,
                new LinkedHashMap<String, String>()).size());
    }
}
