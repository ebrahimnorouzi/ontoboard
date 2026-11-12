package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.fail;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * The projects ODK's own update was recorded on ({@code update/<case>/} among the goldens), put
 * together again: before the update, and as ODK left them.
 *
 * <p>A case is the egg fixture - the eggboil seed with {@code equivalence/egg/overlay} over it -
 * or another case ({@code base.txt}), with the case's {@code changes/} in place of files,
 * {@code append/} added to the end of files, and the paths of {@code deleted.txt} taken away.
 * That is how {@code tools/odk-goldens/update_goldens.py} made the project the image saw; the
 * recorded hashes of that project are checked here, so that a test never compares with the
 * update of another project than the one it built.
 */
public final class UpdateCases {

    private UpdateCases() {
    }

    private static File caseFolder(String name) {
        return new File(Goldens.root(), "update/" + name);
    }

    /** The egg fixture: path from the project's root, with {@code /}, to bytes. */
    public static Map<String, byte[]> eggProject() {
        Map<String, byte[]> project = new TreeMap<String, byte[]>(Goldens.seedTree("eggboil"));
        project.putAll(Goldens.tree(new File(Goldens.root(), "equivalence/egg/overlay")));
        return project;
    }

    /** The project of a case as it was before ODK's update. */
    public static Map<String, byte[]> before(String name) {
        Map<String, byte[]> project = composed(name);
        // Is it the project the image saw?
        String recorded = text(new File(caseFolder(name), "before.sha256"));
        StringBuilder ours = new StringBuilder();
        for (Map.Entry<String, byte[]> file : project.entrySet()) {
            ours.append(sha256(file.getValue())).append("  ").append(file.getKey()).append('\n');
        }
        if (!recorded.equals(ours.toString())) {
            fail("update/" + name + " put together here is not the project ODK's update was "
                    + "recorded on:\nrecorded\n" + recorded + "\nhere\n" + ours);
        }
        return project;
    }

    private static Map<String, byte[]> composed(String name) {
        File folder = caseFolder(name);
        File base = new File(folder, "base.txt");
        Map<String, byte[]> project = base.isFile() ? composed(text(base).trim()) : eggProject();
        project.putAll(treeOrNone(new File(folder, "changes")));
        for (Map.Entry<String, byte[]> more : treeOrNone(new File(folder, "append"))
                .entrySet()) {
            byte[] has = project.get(more.getKey());
            if (has == null) {
                fail("update/" + name + "/append/" + more.getKey() + " adds to a file the "
                        + "project does not have");
            }
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            joined.write(has, 0, has.length);
            joined.write(more.getValue(), 0, more.getValue().length);
            project.put(more.getKey(), joined.toByteArray());
        }
        File deleted = new File(folder, "deleted.txt");
        if (deleted.isFile()) {
            for (String line : text(deleted).split("\n")) {
                if (!line.trim().isEmpty() && project.remove(line.trim()) == null) {
                    fail("update/" + name + "/deleted.txt names " + line.trim() + ", which the "
                            + "project does not have");
                }
            }
        }
        return project;
    }

    /** A case that lays nothing over its base has no folder for it. */
    private static Map<String, byte[]> treeOrNone(File folder) {
        return folder.isDirectory() ? Goldens.tree(folder) : new TreeMap<String, byte[]>();
    }

    /** The same project as ODK's update left it. */
    public static Map<String, byte[]> after(String name) {
        Map<String, byte[]> project = before(name);
        project.putAll(Goldens.tree(new File(caseFolder(name), "after/files")));
        for (String line : text(new File(caseFolder(name), "after/gone.txt")).split("\n")) {
            if (!line.trim().isEmpty()) {
                project.remove(line.trim());
            }
        }
        return project;
    }

    /**
     * The plan of an update with it agreed that the imports the configuration does not declare
     * are taken out - which is what ODK's own update did, without a word, when the cases were
     * recorded. OntoBoard's does not until somebody says so ({@link ImportGuard}).
     */
    public static OdkUpdate.Plan planAsOdkDoes(File root) {
        OdkUpdate.Plan plan = OdkUpdate.plan(root);
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (ImportGuard.Undeclared item : plan.undeclared()) {
            out.add(item.iri());
        }
        return plan.removing(out);
    }

    /** What ODK's update printed. */
    public static String transcript(String name) {
        return text(new File(caseFolder(name), "transcript.txt"));
    }

    /** A file of a tree as text; fails when the tree has no such file. */
    public static String text(Map<String, byte[]> tree, String path) {
        byte[] bytes = tree.get(path);
        if (bytes == null) {
            fail("no " + path + " among " + tree.keySet());
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Writes a tree into a folder. */
    public static void write(Map<String, byte[]> tree, File root) {
        try {
            for (Map.Entry<String, byte[]> file : tree.entrySet()) {
                File target = new File(root, file.getKey());
                Files.createDirectories(target.getParentFile().toPath());
                Files.write(target.toPath(), file.getValue());
            }
        } catch (IOException cannotWrite) {
            throw new UncheckedIOException(cannotWrite);
        }
    }

    private static String text(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
