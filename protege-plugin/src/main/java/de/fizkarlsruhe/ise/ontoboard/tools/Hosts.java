package de.fizkarlsruhe.ise.ontoboard.tools;

import java.util.Locale;

/**
 * Where a host is, as far as sending something to it without encryption goes.
 *
 * <p>Decided from the name as written, never by resolving it: the question is asked before
 * anything is sent, and a DNS lookup is already traffic. A name that resolves to a private
 * address therefore does not count as private unless it says so ({@code .local}); the person can
 * still tick "on my network" for it.
 */
public final class Hosts {

    private Hosts() {
    }

    /** This machine: the loopback names Ollama, LM Studio and vLLM listen on by default. */
    public static boolean isThisMachine(String host) {
        if (host == null) {
            return false;
        }
        String name = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(name) || "127.0.0.1".equals(name) || "::1".equals(name)
                || "[::1]".equals(name);
    }

    /**
     * An address on a private network: the three RFC 1918 ranges written as IPv4 literals, and
     * multicast-DNS names ending in {@code .local}.
     */
    public static boolean isPrivateNetwork(String host) {
        if (host == null) {
            return false;
        }
        String name = host.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".local") && name.length() > ".local".length()) {
            return true;
        }
        int[] octets = ipv4(name);
        if (octets == null) {
            return false;
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    /** The four octets of a dotted IPv4 literal, or null when it is not one. */
    private static int[] ipv4(String name) {
        String[] parts = name.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return null;
            }
            for (char c : parts[i].toCharArray()) {
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) {
                return null;
            }
        }
        return octets;
    }
}
