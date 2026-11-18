package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DOSDP's pattern check as ODK's test runs it ({@code dosdp validate -i}): OntoBoard's own, held
 * against what ODK's checker decided on each case recorded in its image (dosdp/verdicts.txt).
 */
class DosdpCheckTest {

    private static File check() throws URISyntaxException {
        return new File(DosdpCheckTest.class.getResource("/odk/v1.6.1/dosdp/check").toURI());
    }

    private static List<String> verdicts() throws IOException {
        List<String> lines = new ArrayList<String>();
        try (InputStream in = DosdpCheckTest.class.getResourceAsStream(
                "/odk/v1.6.1/dosdp/verdicts.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                        StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private static Map<String, RecipeShell.Tool> tools() {
        Map<String, RecipeShell.Tool> tools = new LinkedHashMap<String, RecipeShell.Tool>(
                RecipeShell.builtins());
        tools.put("dosdp", new DosdpCheck());
        return tools;
    }

    private static int run(String line, List<String> said) throws URISyntaxException {
        RecipeShell.Script script = RecipeShell.parse(line);
        assertNull(script.whyNot(tools()), line);
        return script.run(tools(), check(), said);
    }

    /** Ruling 13: on every recorded input, the status is the one ODK's checker gave. */
    @Test
    void everyVerdictIsTheOneOdksCheckerGave() throws Exception {
        List<String> differ = new ArrayList<String>();
        int asked = 0;
        for (String line : verdicts()) {
            int space = line.lastIndexOf(' ');
            String input = line.substring(0, space);
            int odk = Integer.parseInt(line.substring(space + 1));
            List<String> said = new ArrayList<String>();
            int here = run("dosdp validate -i " + input, said);
            asked++;
            if (here != odk) {
                differ.add(input + ": ODK's checker " + odk + ", OntoBoard's " + here + " "
                        + said);
            }
        }
        assertEquals(191, asked);
        assertEquals(Collections.emptyList(), differ);
    }

    @Test
    void onlyValidateWithItsInputIsRead() {
        DosdpCheck check = new DosdpCheck();

        assertNull(check.whyNot(Arrays.asList("validate", "-i", "../patterns/dosdp-patterns/")));
        assertNull(check.whyNot(Arrays.asList("validate", "--input", "p.yaml")));
        assertNull(check.whyNot(Arrays.asList("validate", "--input=p.yaml")));
        for (List<String> other : Arrays.asList(Arrays.asList("document", "-i", "p.yaml"),
                Arrays.asList("validate"), Arrays.asList("validate", "-i"),
                Arrays.asList("validate", "-i", "a", "-i", "b"), Arrays.asList("-h"))) {
            String why = check.whyNot(other);
            assertTrue(why != null && why.contains("dosdp validate -i"), other + ": " + why);
        }
    }

    @Test
    void eachFindingIsALineNamingThePatternAndWhatIsWrong() throws Exception {
        List<String> said = new ArrayList<String>();

        assertEquals(1, run("dosdp validate -i vars-undeclared.yaml", said));

        boolean named = false;
        for (String line : said) {
            named |= line.startsWith("dosdp: vars-undeclared.yaml: ") && line.contains("'y'");
        }
        assertTrue(named, said.toString());
    }

    @Test
    void aFolderOfKeptPatternsIsSaidToBeSo() throws Exception {
        List<String> said = new ArrayList<String>();

        assertEquals(0, run("dosdp validate -i folder-kept/", said));

        assertEquals(Collections.singletonList(
                "dosdp: the 3 patterns in folder-kept/ keep DOSDP's rules"), said);
    }
}
