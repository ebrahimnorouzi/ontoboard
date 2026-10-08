package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Regenerates patterns/index.tsv. Not a test; run when a pattern is added. */
public final class Reindex {
    public static void main(String[] args) throws Exception {
        File patterns = new File(args.length > 0 ? args[0] : "../patterns");
        String built = PatternIndex.buildFrom(patterns);
        File out = new File(patterns, "index.tsv");
        Files.write(out.toPath(), built.getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out.getAbsolutePath() + " (" + built.length() + " bytes)");
    }
}
