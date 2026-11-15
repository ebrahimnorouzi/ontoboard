package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ODK's config model, ported: what a config means once ODK has read it.
 *
 * <p>The templates do not read the YAML; they read an {@code OntologyProject} that ODK builds
 * from it - every key the config leaves out filled with its default, import products given their
 * group's module type, components their base IRIs. A seeded file is only ODK's if that object is
 * ODK's, field for field, so the port is held to ODK's own dump of it
 * ({@code odk.py export-project}) for OntoBoard's eight configs and for all 79 of ODK's samples.
 */
class OdkProjectModelTest {

    static List<String> configsWithModels() {
        List<String> names = new ArrayList<String>(Goldens.configNames());
        for (String corpus : Goldens.corpusNames()) {
            names.add("corpus:" + corpus);
        }
        return names;
    }

    @ParameterizedTest
    @MethodSource("configsWithModels")
    void theFilledInProjectIsWhatExportProjectDumps(String name) {
        boolean corpus = name.startsWith("corpus:");
        String config = corpus ? Goldens.corpusConfig(name.substring(7)) : Goldens.config(name);
        Map<String, Object> odks = corpus ? Goldens.corpusModel(name.substring(7))
                : Goldens.model(name);

        Object ours = OdkProjectModel.toPlain(OdkProjectModel.load(config));

        assertEquals("", difference("project", normalised(odks), ours),
                "the first place the port and ODK disagree");
    }

    /** The fingerprint the Makefile records, of the text as Python reads it. */
    @Test
    void theHashIsOfTheTextWithLfEndings() {
        String lf = Goldens.config("evidence-eggboil");

        assertEquals("8a5a8b7d2e2f0c8698940dd146c9113a5d914b7cd9da41d128e55ef029ec30b6",
                OdkProjectModel.configHash(lf));
        assertEquals(OdkProjectModel.configHash(lf),
                OdkProjectModel.configHash(lf.replace("\n", "\r\n")),
                "a config saved on Windows has the same fingerprint");
        assertEquals(OdkProjectModel.configHash(lf),
                OdkProjectModel.load(lf).get("config_hash"));
    }

    /** With a robot_report block, ODK keeps that block and none of its own defaults. */
    @Test
    void aRobotReportBlockReplacesEveryDefault() {
        PyObject project = OdkProjectModel.load(Goldens.config("evidence-eggboil"));
        Map<?, ?> report = (Map<?, ?>) project.get("robot_report");

        assertEquals(new TreeSet<String>(Arrays.asList("use_labels", "fail_on", "report_on")),
                new TreeSet<Object>(report.keySet()));
        assertTrue(Py.attr(report, "custom_sparql_checks") instanceof PyUndefined,
                "which is why that Makefile runs four checks, not five");
    }

    @Test
    void withoutABlockTheDataclassDefaultsApply() {
        PyObject project = OdkProjectModel.load(Goldens.config("nodefaults"));
        Map<?, ?> report = (Map<?, ?>) project.get("robot_report");

        assertEquals(Arrays.asList("owldef-self-reference", "iri-range", "label-with-iri",
                "multiple-replaced_by", "dc-properties"), report.get("custom_sparql_checks"));
        assertEquals(Boolean.TRUE, report.get("use_base_iris"));
        assertSame(Py.NONE, report.get("fail_on"));
        assertEquals("None", project.get("description"), "the string, not Python's None");
        assertEquals("noname", project.get("repo"));
        assertSame(Py.NONE, project.get("import_group"));
        assertEquals("--clean-obo \"strict drop-untranslatable-axioms\"",
                project.get("obo_format_options"));
        assertTrue(String.valueOf(project.get("custom_makefile_header")).startsWith("\n# ---"));
    }

    @Test
    void yamlOneOneBooleansAreBooleans() {
        PyObject project = OdkProjectModel.load("id: x\nuse_dosdps: yes\nrelease_date: on\n"
                + "gzip_main: TRUE\nuse_context: No\n");

        assertEquals(Boolean.TRUE, project.get("use_dosdps"));
        assertEquals(Boolean.TRUE, project.get("release_date"));
        assertEquals(Boolean.TRUE, project.get("gzip_main"));
        assertEquals(Boolean.FALSE, project.get("use_context"));
    }

    /** 1.9.8 is not a number, and must not become one on the way. */
    @Test
    void aVersionLikeScalarStaysAString() {
        PyObject project = OdkProjectModel.load("id: x\nrobot_version: 1.9.8\n");

        assertEquals("1.9.8", project.get("robot_version"));
    }

    @Test
    void integersStayIntegers() {
        PyObject project = OdkProjectModel.load(
                "id: x\nimport_group:\n  mirror_retry_download: 7\n  products:\n    - id: ro\n");
        PyObject group = (PyObject) project.get("import_group");

        assertEquals(7L, group.get("mirror_retry_download"));
        assertEquals(200L, group.get("mirror_max_time_download"));
    }

    /** A product that differs from its group gets a rule of its own in the Makefile. */
    @Test
    void productsThatDifferFromTheGroupAreSpecial() {
        PyObject project = OdkProjectModel.load(Goldens.config("oboimports"));
        PyObject group = (PyObject) project.get("import_group");
        List<String> special = new ArrayList<String>();
        for (Object product : (List<?>) group.get("special_products")) {
            special.add(String.valueOf(((PyObject) product).get("id")));
        }

        assertEquals(Arrays.asList("iao", "bfo", "pato"), special);
        PyObject ro = (PyObject) ((List<?>) group.get("products")).get(0);
        assertEquals("slme", ro.get("module_type"), "inherited from the group");
        assertEquals("BOT", ro.get("module_type_slme"));
        assertEquals("include", ro.get("slme_individuals"));
        assertEquals(Arrays.asList("http://purl.obolibrary.org/obo/RO"), ro.get("base_iris"));
        PyObject bfo = (PyObject) ((List<?>) group.get("products")).get(2);
        assertSame(Py.NONE, bfo.get("module_type_slme"), "only an slme module has an slme method");
    }

    @Test
    void aComponentGetsItsBaseIriAndItsTemplate() {
        PyObject project = OdkProjectModel.load(Goldens.config("components"));
        List<?> products = (List<?>) ((PyObject) project.get("components")).get("products");
        PyObject manual = (PyObject) products.get(0);
        PyObject fromTemplate = (PyObject) products.get(2);

        assertEquals(Arrays.asList("http://purl.obolibrary.org/obo/COMP"), manual.get("base_iris"));
        assertSame(Py.NONE, manual.get("templates"));
        assertEquals(Arrays.asList("first.tsv", "second.tsv"), fromTemplate.get("templates"));

        PyObject derived = OdkProjectModel.load("id: x\ncomponents:\n  products:\n"
                + "    - filename: parts.owl\n      use_template: TRUE\n");
        PyObject parts = (PyObject) ((List<?>) ((PyObject) derived.get("components"))
                .get("products")).get(0);
        assertEquals(Arrays.asList("parts.tsv"), parts.get("templates"));
    }

    /** A key ODK does not know is ignored, as ODK ignores it; 51 of its own samples have one. */
    @Test
    void aKeyOdkDoesNotKnowIsIgnored() {
        PyObject project = OdkProjectModel.load("id: x\nreport_fail_on: ERROR\nmade_up: 3\n");

        assertFalse(project.has("report_fail_on"));
        assertFalse(project.has("made_up"));
    }

    /** ODK reads a file that starts with a byte-order mark, and its fingerprint includes it. */
    @Test
    void aByteOrderMarkIsReadAsOdkReadsIt() {
        PyObject project = OdkProjectModel.load("﻿id: x\ntitle: y\n");

        assertEquals("x", project.get("id"));
        assertFalse(OdkProjectModel.configHash("﻿id: x\ntitle: y\n")
                .equals(OdkProjectModel.configHash("id: x\ntitle: y\n")));
    }

    @Test
    void aYamlErrorNamesItsLine() {
        OdkConfigException refused = assertThrows(OdkConfigException.class,
                () -> OdkProjectModel.load("id: x\ntitle: \"unterminated\nrepo: y\n"));

        assertTrue(refused.getLine() >= 2, "line " + refused.getLine());
        assertTrue(refused.getMessage().contains("line " + refused.getLine()), refused.getMessage());
    }

    @Test
    void aWrongTypeNamesTheKey() {
        OdkConfigException list = assertThrows(OdkConfigException.class,
                () -> OdkProjectModel.load("id: x\nrelease_artefacts: full\n"));
        assertTrue(list.getMessage().contains("release_artefacts"), list.getMessage());

        OdkConfigException bool = assertThrows(OdkConfigException.class,
                () -> OdkProjectModel.load("id: x\nuse_dosdps: \"yes\"\n"));
        assertTrue(bool.getMessage().contains("use_dosdps"), bool.getMessage());

        OdkConfigException nested = assertThrows(OdkConfigException.class, () -> OdkProjectModel
                .load("id: x\nimport_group:\n  products:\n    - id: ro\n      is_large: 3\n"));
        assertTrue(nested.getMessage().contains("import_group.products[0].is_large"),
                nested.getMessage());
    }

    @Test
    void aProductWithoutAnIdIsRefused() {
        OdkConfigException refused = assertThrows(OdkConfigException.class, () -> OdkProjectModel
                .load("id: x\nimport_group:\n  products:\n    - mirror_from: http://x/y.owl\n"));

        assertTrue(refused.getMessage().contains("import_group.products[0]"), refused.getMessage());
        assertTrue(refused.getMessage().contains("id"), refused.getMessage());
    }

    @Test
    void aConfigThatIsNotAMappingIsRefused() {
        assertThrows(OdkConfigException.class, () -> OdkProjectModel.load("- id: x\n"));
        assertThrows(OdkConfigException.class, () -> OdkProjectModel.load(""));
    }

    @Test
    void idsBecomeProductsLastAndTheGroupsOwnAreKept() {
        PyObject project = OdkProjectModel.load("id: x\nimport_group:\n  ids: [ro, pato]\n"
                + "  products:\n    - id: pato\n      module_type: mirror\n");
        List<String> ids = new ArrayList<String>();
        for (Object product : (List<?>) ((PyObject) project.get("import_group")).get("products")) {
            ids.add(String.valueOf(((PyObject) product).get("id")));
        }

        assertEquals(Arrays.asList("pato", "ro"), ids);
    }

    @Test
    void anUnknownSourceMappingSetIsRefusedAsOdkRefusesIt() {
        OdkConfigException refused = assertThrows(OdkConfigException.class, () -> OdkProjectModel
                .load("id: x\nsssom_mappingset_group:\n  products:\n    - id: all\n"
                        + "      maintenance: merged\n      source_mappings: [nowhere]\n"));

        assertTrue(refused.getMessage().contains("nowhere"), refused.getMessage());
    }

    @Test
    void releasedMappingSetsExistOnlyWhenThereAreSome() {
        PyObject none = (PyObject) OdkProjectModel.load("id: x\nsssom_mappingset_group:\n"
                + "  products:\n    - id: a\n").get("sssom_mappingset_group");
        PyObject some = (PyObject) OdkProjectModel.load("id: x\nsssom_mappingset_group:\n"
                + "  products:\n    - id: a\n      release_mappings: TRUE\n    - id: b\n"
                + "      maintenance: extract\n").get("sssom_mappingset_group");

        assertFalse(none.has("released_products"), "ODK sets it only when it is not empty");
        assertEquals(1, ((List<?>) some.get("released_products")).size());
        assertEquals("$(EDIT_PREPROCESSED)",
                ((PyObject) ((List<?>) some.get("products")).get(1)).get("source_file"));
    }

    @Test
    void toPlainLeavesOutWhatOdkDoesNotDump() {
        Object plain = OdkProjectModel.toPlain(OdkProjectModel.load(Goldens.config("oboimports")));
        Map<?, ?> project = (Map<?, ?>) plain;

        assertFalse(project.containsKey("config_hash"));
        assertFalse(((Map<?, ?>) project.get("import_group")).containsKey("special_products"));
        assertNull(project.get("components"), "None is null in the dump");
    }

    // ------------------------------------------------------------------ comparing two dumps

    /** A YAML dump as SnakeYAML reads it, with every integer a Long, as the model has them. */
    private static Object normalised(Object value) {
        if (value instanceof Integer) {
            return Long.valueOf((Integer) value);
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(entry.getKey()), normalised(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                out.add(normalised(item));
            }
            return out;
        }
        return value;
    }

    /** Where two dumps part, as a path and both readings; empty when they agree. */
    private static String difference(String path, Object odks, Object ours) {
        if (odks instanceof Map && ours instanceof Map) {
            Map<?, ?> theirs = (Map<?, ?>) odks;
            Map<?, ?> mine = (Map<?, ?>) ours;
            for (Object key : new TreeSet<Object>(theirs.keySet())) {
                if (!mine.containsKey(key)) {
                    return path + "." + key + ": ODK has " + theirs.get(key) + ", the port has no "
                            + "such field";
                }
                String inside = difference(path + "." + key, theirs.get(key), mine.get(key));
                if (!inside.isEmpty()) {
                    return inside;
                }
            }
            for (Object key : mine.keySet()) {
                if (!theirs.containsKey(key)) {
                    return path + "." + key + ": the port has " + mine.get(key) + ", ODK has no "
                            + "such field";
                }
            }
            return "";
        }
        if (odks instanceof List && ours instanceof List) {
            List<?> theirs = (List<?>) odks;
            List<?> mine = (List<?>) ours;
            if (theirs.size() != mine.size()) {
                return path + ": ODK has " + theirs.size() + " items " + theirs + ", the port "
                        + mine.size() + " " + mine;
            }
            for (int i = 0; i < theirs.size(); i++) {
                String inside = difference(path + "[" + i + "]", theirs.get(i), mine.get(i));
                if (!inside.isEmpty()) {
                    return inside;
                }
            }
            return "";
        }
        if (odks == null ? ours == null : odks.equals(ours)) {
            return "";
        }
        return path + ": ODK has " + described(odks) + ", the port " + described(ours);
    }

    private static String described(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + " [" + value + "]";
    }
}
