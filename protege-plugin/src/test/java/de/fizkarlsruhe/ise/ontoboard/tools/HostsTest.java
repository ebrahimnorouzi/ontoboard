package de.fizkarlsruhe.ise.ontoboard.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Which hosts a model connection may reach over plain http.
 *
 * <p>Ollama listens on plain http on this machine by default, so that has to work without a
 * question; a server elsewhere on the network only when the person says so; anything else never.
 */
class HostsTest {

    @Test
    void thisMachine() {
        assertTrue(Hosts.isThisMachine("127.0.0.1"));
        assertTrue(Hosts.isThisMachine("LOCALHOST"));
        assertTrue(Hosts.isThisMachine("::1"));
        assertTrue(Hosts.isThisMachine("[::1]"));
        assertFalse(Hosts.isThisMachine("127.0.0.2.example.org"),
                "a name that merely starts like a loopback address is somewhere else");
        assertFalse(Hosts.isThisMachine("example.org"));
        assertFalse(Hosts.isThisMachine(null));
    }

    @Test
    void privateNetwork() {
        assertTrue(Hosts.isPrivateNetwork("10.1.2.3"));
        assertTrue(Hosts.isPrivateNetwork("172.16.0.1"));
        assertTrue(Hosts.isPrivateNetwork("172.31.255.255"));
        assertTrue(Hosts.isPrivateNetwork("192.168.1.10"));
        assertTrue(Hosts.isPrivateNetwork("lab.local"));
        assertFalse(Hosts.isPrivateNetwork("172.32.0.1"));
        assertFalse(Hosts.isPrivateNetwork("8.8.8.8"));
        assertFalse(Hosts.isPrivateNetwork("example.org"));
        assertFalse(Hosts.isPrivateNetwork("10.1.2"), "not a whole address");
        assertFalse(Hosts.isPrivateNetwork(null));
    }
}
