package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which ODK image runs what.
 *
 * <p>Two rules, and one thing never done.
 *
 * <ul>
 *   <li>Projects OntoBoard creates, and <i>Update project files</i>, use the pinned ODK
 *       ({@link OdkPin#VERSION}): the version whose templates are bundled, so that the container
 *       and the bundle write the same project.
 *   <li>An existing project's other targets run in the ODK that wrote its Makefile
 *       ({@code ODK_VERSION_MAKEFILE}), when that image is on the machine; else in the pinned
 *       one, with a sentence for the transcript. An image the project names itself goes first:
 *       in {@code run.sh.conf}, as for ODK's own {@code run.sh}, or as a tag written out in a
 *       {@code run.sh} of its own (go-ontology's says {@code obolibrary/odkfull:v1.5.4}, and
 *       that the version must match its pipeline). Such a pin is kept or the run is refused;
 *       another ODK is not swapped in for it.
 *   <li>OntoBoard never downloads an image on its own - five gigabytes is a decision. An image
 *       that is not on the machine is therefore never the answer; when none will do, the answer
 *       is no image and a sentence saying what to do.
 * </ul>
 *
 * <p>An image is recognised by the {@code ODK_VERSION} in its environment as well as by its tag:
 * whoever ran {@code docker pull obolibrary/odkfull} has the current ODK under {@code latest} and
 * under no other name.
 */
public final class OdkImage {

    /** What can be learned about the images on this machine. */
    public interface Probe {
        /** Whether this image, named with its tag, is on the machine. */
        boolean isPresent(String reference);

        /** The {@code ODK_VERSION} in the image's environment, or null. */
        String odkVersionOf(String reference);

        /** The tags of a repository that are on the machine: {@code v1.6.1}, {@code latest}. */
        List<String> localTags(String repository);
    }

    /** The image for a project, and what to tell its user about the choice. */
    public static final class Choice {
        private final String reference;
        private final String note;

        Choice(String reference, String note) {
            this.reference = reference;
            this.note = note;
        }

        /** The image, with its tag, or null when no image on this machine will do. */
        public String reference() {
            return reference;
        }

        /**
         * Something the user should read: why it is not the project's own ODK, or why there is
         * none. Null when the project's own ODK runs it.
         */
        public String note() {
            return note;
        }
    }

    private static final String ADVICE = "OntoBoard does not download an image on its own (it is "
            + "about 5 GB). OntoBoard > Check requirements... shows the command that does.";

    private OdkImage() {
    }

    /** The pinned ODK on this machine, under whatever tag, or null. */
    public static String pinned(Probe probe) {
        return withVersion(OdkPin.VERSION, probe);
    }

    /**
     * {@code obolibrary/odkfull} of one ODK version: the image tagged with it, else an image
     * under another tag whose own environment says it is that version, else null.
     */
    static String withVersion(String version, Probe probe) {
        String tagged = OdkPin.IMAGE_REPOSITORY + ":" + version;
        if (probe.isPresent(tagged)) {
            return tagged;
        }
        for (String tag : probe.localTags(OdkPin.IMAGE_REPOSITORY)) {
            String other = OdkPin.IMAGE_REPOSITORY + ":" + tag;
            if (version.equals(probe.odkVersionOf(other))) {
                return other;
            }
        }
        return null;
    }

    /**
     * The image that runs this project's targets.
     *
     * @param ontologyDirectory the project's {@code src/ontology}
     */
    public static Choice forProject(File ontologyDirectory, Probe probe) {
        String confNote = null;
        String conf = read(new File(ontologyDirectory, "run.sh.conf"));
        if (conf != null) {
            String image = assigned(conf, "ODK_IMAGE");
            String tag = assigned(conf, "ODK_TAG");
            if (COMPUTED.equals(image) || COMPUTED.equals(tag)) {
                confNote = "run.sh.conf works out the ODK image in the shell, which OntoBoard "
                        + "does not run; the project's Makefile decides instead. ";
            } else if (image != null || tag != null) {
                // As run.sh reads them: a tag inside ODK_IMAGE beats ODK_TAG; the default image
                // is odkfull and the default tag is latest.
                String name = image == null || image.isEmpty() ? "odkfull" : image;
                int colon = name.indexOf(':');
                if (colon >= 0) {
                    tag = name.substring(colon + 1);
                    name = name.substring(0, colon);
                }
                String reference = "obolibrary/" + name + ":"
                        + (tag == null || tag.isEmpty() ? "latest" : tag);
                if (probe.isPresent(reference)) {
                    return new Choice(reference, null);
                }
                return new Choice(null, "This project's run.sh.conf asks for " + reference
                        + ", and that image is not on this machine. " + ADVICE
                        + " Or change run.sh.conf.");
            }
        }

        File runner = MakeRun.odkRunner(ontologyDirectory);
        String pin = runner == null ? null : pinnedBy(read(runner));
        if (pin != null) {
            if (probe.isPresent(pin)) {
                return new Choice(pin, confNote == null ? null : confNote.trim());
            }
            return new Choice(null, "This project's " + runner.getName() + " names " + pin
                    + ", and that image is not on this machine. " + ADVICE);
        }

        String makefile = read(new File(ontologyDirectory, "Makefile"));
        String version = makefile == null ? null : versionOf(makefile);
        if (version != null) {
            String own = withVersion(version, probe);
            if (own != null) {
                return new Choice(own, confNote == null ? null : confNote.trim());
            }
        }
        String pinned = pinned(probe);
        if (pinned == null) {
            return new Choice(null, "No ODK image that can run this project is on this machine. "
                    + ADVICE);
        }
        String why = version == null
                ? "This project's Makefile does not say which ODK wrote it"
                : "This project's Makefile was written by ODK " + version
                        + ", and that image is not on this machine";
        return new Choice(pinned, (confNote == null ? "" : confNote) + why + "; ODK "
                + OdkPin.VERSION + " (" + pinned + ") runs it.");
    }

    /**
     * The image a project's own wrapper pins: an ODK image written out with its tag, or null.
     *
     * <p>ODK's generated {@code run.sh} composes the name from {@code $ODK_IMAGE:$ODK_TAG} and
     * its {@code run.bat} names the image without a tag; neither is a pin. A wrapper somebody
     * wrote by hand, with the tag in it, is one.
     */
    static String pinnedBy(String runnerScript) {
        if (runnerScript == null) {
            return null;
        }
        Matcher mention = Pattern
                .compile("obolibrary/(?:odkfull|odklite):[A-Za-z0-9_][A-Za-z0-9._-]*")
                .matcher(runnerScript);
        return mention.find() ? mention.group() : null;
    }

    /** The ODK that wrote a Makefile, as its {@code ODK_VERSION_MAKEFILE} line says, or null. */
    static String versionOf(String makefile) {
        Matcher line = Pattern
                .compile("(?m)^ODK_VERSION_MAKEFILE[ \\t]*[:?]?=[ \\t]*(\\S+)[ \\t]*$")
                .matcher(makefile.replace("\r\n", "\n"));
        if (!line.find()) {
            return null;
        }
        String version = line.group(1);
        // Seeded outside the image, ODK writes the word "Unknown", quotes included.
        return version.matches("v?[0-9][A-Za-z0-9._-]*") ? version : null;
    }

    /** What stands for a value the shell would have to work out. */
    private static final String COMPUTED = "\u0000computed";

    /**
     * The value a shell fragment gives a variable, in its last plain assignment: null when it
     * has none, {@link #COMPUTED} when the value has a {@code $} or a backquote in it.
     */
    private static String assigned(String shell, String variable) {
        String value = null;
        Pattern assignment = Pattern.compile("^\\s*(?:export\\s+)?" + variable + "=(.*)$");
        for (String line : shell.replace("\r\n", "\n").split("\n")) {
            Matcher match = assignment.matcher(line);
            if (!match.find()) {
                continue;
            }
            String rest = match.group(1).trim();
            if (rest.startsWith("\"") || rest.startsWith("'")) {
                int close = rest.indexOf(rest.charAt(0), 1);
                boolean literal = rest.charAt(0) == '\'';
                rest = close < 0 ? rest.substring(1) : rest.substring(1, close);
                value = !literal && (rest.indexOf('$') >= 0 || rest.indexOf('`') >= 0)
                        ? COMPUTED : rest;
            } else {
                // Unquoted, a value ends at the first blank; a comment may follow.
                rest = rest.split("\\s", 2)[0];
                value = rest.indexOf('$') >= 0 || rest.indexOf('`') >= 0 ? COMPUTED : rest;
            }
        }
        return value;
    }

    private static String read(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * Asks a container runtime about its images.
     *
     * @param runtime {@code docker} or {@code podman}; their answers to these questions are the
     *                same
     */
    public static Probe probe(final String runtime, final ProcessRunner.Runner runner) {
        return new Probe() {
            @Override
            public boolean isPresent(String reference) {
                return ask("image", "inspect", "--format", "{{.Id}}", reference) != null;
            }

            @Override
            public String odkVersionOf(String reference) {
                List<String> environment = ask("image", "inspect", "--format",
                        "{{json .Config.Env}}", reference);
                if (environment == null) {
                    return null;
                }
                Matcher version = Pattern.compile("\"ODK_VERSION=([^\"]*)\"")
                        .matcher(String.join("\n", environment));
                return version.find() ? version.group(1) : null;
            }

            @Override
            public List<String> localTags(String repository) {
                List<String> tags = new ArrayList<String>();
                List<String> lines = ask("image", "ls", "--format", "{{.Tag}}", repository);
                if (lines != null) {
                    for (String line : lines) {
                        // A tag has no blanks; a warning the runtime prints has, and "<none>"
                        // is an image without one.
                        if (line.trim().matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")
                                && !tags.contains(line.trim())) {
                            tags.add(line.trim());
                        }
                    }
                }
                return tags;
            }

            /** The runtime's answer, or null when it failed or could not be started. */
            private List<String> ask(String... arguments) {
                List<String> command = new ArrayList<String>();
                command.add(runtime);
                command.addAll(Arrays.asList(arguments));
                try {
                    ProcessRunner.Outcome outcome = runner.run(null, command, 1, null);
                    return outcome.isSuccess() ? outcome.getOutput() : null;
                } catch (IOException notThere) {
                    return null;
                } catch (RuntimeException notThere) {
                    return null;
                }
            }
        };
    }
}
