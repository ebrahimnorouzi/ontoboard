package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project's {@code robot_java_args} against the memory Protégé runs with. In ODK's container
 * ROBOT gets the project's own {@code -Xmx}; run by OntoBoard itself, ROBOT runs inside
 * Protégé and has Protégé's memory - so a run that may need more says so before it starts,
 * with the setting of that Protégé that gives it ({@code docs/limitations.md}).
 */
public final class RobotMemory {

    private static final long KIB = 1024L;

    /** {@code -Xmx}, its number and its unit; Java takes the last of them. */
    private static final Pattern XMX = Pattern.compile("(?:^|\\s)-Xmx(\\d+)([kKmMgGtT]?)(?=\\s|$)");

    /**
     * Below this share of what is asked for, the heap is too small: some collectors report a
     * little less than {@code -Xmx} as the most there is.
     */
    private static final double ENOUGH = 0.9;

    private RobotMemory() {
    }

    /** The heap {@code robot_java_args} asks for, in bytes - its last {@code -Xmx} - or 0. */
    public static long asked(String robotJavaArgs) {
        if (robotJavaArgs == null) {
            return 0;
        }
        long asked = 0;
        Matcher xmx = XMX.matcher(robotJavaArgs);
        while (xmx.find()) {
            long number = Long.parseLong(xmx.group(1));
            String unit = xmx.group(2).toLowerCase(Locale.ROOT);
            asked = unit.isEmpty() ? number : unit.equals("k") ? number * KIB
                    : unit.equals("m") ? number * KIB * KIB : unit.equals("g")
                            ? number * KIB * KIB * KIB : number * KIB * KIB * KIB * KIB;
        }
        return asked;
    }

    /**
     * What to say before OntoBoard itself runs ROBOT for this project, or null when Protégé has
     * memory enough: how much it has, how much the project asks for, and the setting of this
     * Protégé that gives it.
     *
     * @param maxHeap        the most memory Protégé's Java has, in bytes
     * @param protegeVersion Protégé's version, {@code 5.6.9}
     * @param windows        whether it runs on Windows
     */
    public static String note(String robotJavaArgs, long maxHeap, String protegeVersion,
            boolean windows) {
        long asked = asked(robotJavaArgs);
        if (asked == 0 || maxHeap >= asked * ENOUGH) {
            return null;
        }
        String size = javaSize(asked);
        String setting = isProtege56OrLater(protegeVersion)
                ? "max_heap_size=" + size + " in conf/jvm.conf in Protégé's folder"
                : windows ? "-Xmx" + size + " on the -Xmx line of Protege.l4j.ini beside "
                        + "Protege.exe, or in run.bat when Protégé is started with it"
                        : "-Xmx" + size + " in run.sh in Protégé's folder";
        return "Run by OntoBoard itself, ROBOT has Protégé's memory: " + readable(maxHeap)
                + " here, where this project's robot_java_args asks for " + readable(asked)
                + " (" + robotJavaArgs.trim() + "), which ODK's container would give it. A large "
                + "import may not fit. Protégé's own setting gives it more: " + setting
                + "; then start Protégé again.";
    }

    /**
     * What to say before OntoBoard itself runs ROBOT for the project whose Makefile is in this
     * folder, here - this Protégé, the memory of its Java, this platform - or null.
     */
    public static String noteHere(java.io.File ontologyDirectory) {
        return note(OdkBuildSettings.scalarAt(ontologyDirectory, "robot_java_args"),
                Runtime.getRuntime().maxMemory(), protegeVersion(),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    /** The Protégé this runs in: its core bundle's version, else told by its Java. */
    public static String protegeVersion() {
        try {
            org.osgi.framework.Bundle core = org.osgi.framework.FrameworkUtil.getBundle(
                    org.protege.editor.core.ProtegeApplication.class);
            if (core != null) {
                return core.getVersion().toString();
            }
        } catch (RuntimeException | LinkageError notInOsgi) {
            // Outside Protégé, in a test: its Java tells which one it would be.
        }
        return System.getProperty("java.specification.version", "").startsWith("1.") ? "5.5.0"
                : "5.6";
    }

    private static boolean isProtege56OrLater(String version) {
        Matcher numbers = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(
                version == null ? "" : version.trim());
        if (!numbers.find()) {
            return true;
        }
        int major = Integer.parseInt(numbers.group(1));
        int minor = Integer.parseInt(numbers.group(2));
        return major > 5 || major == 5 && minor >= 6;
    }

    /** As Java's options write a size: in whole gigabytes where it is, else megabytes. */
    private static String javaSize(long bytes) {
        long gib = KIB * KIB * KIB;
        return bytes % gib == 0 ? bytes / gib + "G"
                : (bytes + KIB * KIB - 1) / (KIB * KIB) + "M";
    }

    private static String readable(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (KIB * KIB * KIB));
    }
}
