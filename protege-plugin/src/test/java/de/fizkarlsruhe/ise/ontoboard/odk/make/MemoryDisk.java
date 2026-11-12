package de.fizkarlsruhe.ise.ontoboard.odk.make;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A {@link MakeFile.Disk} held in memory: a tree of files, the Makefile's folder somewhere inside
 * it, and a few absolute paths beside it.
 *
 * <p>Relative paths are resolved against the Makefile's folder inside the tree, so that
 * {@code ../templates/first.tsv} from {@code src/ontology} finds {@code src/templates/first.tsv};
 * a path that climbs out of the tree does not exist. A folder exists when a file lies under it.
 * Every file has one modification time unless a test gives it another.
 */
final class MemoryDisk implements MakeFile.Disk {

    /** The one time every seeded file has, as in the image's {@code touch -d}. */
    static final long SAME_TIME = 1_762_862_400_000L;

    private final String folder;
    private final String base;
    private final Map<String, String> files = new TreeMap<String, String>();
    private final Map<String, Long> times = new HashMap<String, Long>();

    /**
     * @param folder the absolute folder make runs in, as it prints it
     * @param base   where that folder is inside the tree: {@code ""} for the tree's root
     */
    MemoryDisk(String folder, String base) {
        this.folder = folder;
        this.base = base;
    }

    /**
     * A seed exactly as the make goldens saw it: every file of {@code seed/<name>} with one time,
     * the Makefile's folder {@code src/ontology}, {@code /tools/robot-plugins/} holding the three
     * plugins of the image, and no {@code ../../plugins/}.
     */
    static MemoryDisk seed(String name) {
        MemoryDisk disk = new MemoryDisk("/tmp/odk/" + name + "/src/ontology", "src/ontology");
        for (Map.Entry<String, byte[]> file : Goldens.seedTree(name).entrySet()) {
            disk.files.put(file.getKey(), new String(file.getValue(), StandardCharsets.UTF_8));
        }
        disk.file("/tools/robot-plugins/kgcl.jar", "");
        disk.file("/tools/robot-plugins/odk.jar", "");
        disk.file("/tools/robot-plugins/sssom.jar", "");
        return disk;
    }

    MemoryDisk file(String path, String text) {
        String key = key(path);
        if (key == null) {
            throw new IllegalArgumentException("outside the tree: " + path);
        }
        files.put(key, text);
        return this;
    }

    MemoryDisk file(String path, String text, long time) {
        file(path, text);
        times.put(key(path), time);
        return this;
    }

    @Override
    public String folder() {
        return folder;
    }

    @Override
    public boolean exists(String path) {
        String key = key(path);
        if (key == null) {
            return false;
        }
        if (key.isEmpty() || files.containsKey(key)) {
            return true;
        }
        String prefix = key.endsWith("/") ? key : key + "/";
        String next = ((TreeMap<String, String>) files).ceilingKey(prefix);
        return next != null && next.startsWith(prefix);
    }

    @Override
    public long modified(String path) {
        Long time = times.get(key(path));
        return time != null ? time : SAME_TIME;
    }

    @Override
    public List<String> list(String path) {
        String key = key(path);
        TreeSet<String> names = new TreeSet<String>();
        if (key == null) {
            return new ArrayList<String>();
        }
        String prefix = key.isEmpty() ? "" : (key.endsWith("/") ? key : key + "/");
        for (String file : files.keySet()) {
            if (file.startsWith(prefix) && file.length() > prefix.length()) {
                String rest = file.substring(prefix.length());
                int slash = rest.indexOf('/');
                names.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }
        return new ArrayList<String>(names);
    }

    @Override
    public String read(String path) {
        String key = key(path);
        return key == null ? null : files.get(key);
    }

    /** The tree's own name for a path, or null when it climbs out of the tree. */
    private String key(String path) {
        boolean absolute = path.startsWith("/");
        String joined = absolute ? path : (base.isEmpty() ? path : base + "/" + path);
        List<String> parts = new ArrayList<String>();
        for (String part : joined.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(part);
        }
        StringBuilder key = new StringBuilder(absolute ? "/" : "");
        for (int i = 0; i < parts.size(); i++) {
            key.append(i == 0 ? "" : "/").append(parts.get(i));
        }
        return key.toString();
    }
}
