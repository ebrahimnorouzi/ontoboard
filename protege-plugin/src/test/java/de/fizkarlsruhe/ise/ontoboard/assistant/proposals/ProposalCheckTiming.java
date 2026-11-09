package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

/** For tests in other packages: the reasoner check's timeout and grace, which are package-private. */
public final class ProposalCheckTiming {

    private ProposalCheckTiming() {
    }

    public static long timeoutMillis() {
        return ProposalCheck.timeoutMillis();
    }

    public static long graceMillis() {
        return ProposalCheck.graceMillis();
    }

    public static void set(long timeoutMillis, long graceMillis) {
        ProposalCheck.setTimeoutMillis(timeoutMillis);
        ProposalCheck.setGraceMillis(graceMillis);
    }
}
