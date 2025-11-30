package de.thws;

/**
 * @param id   Eindeutige Node-ID
 * @param host IP-Adresse / Hostname
 * @param port Port für Nachrichten
 */
public record NodeInfo(int id, String host, int port) {

    @Override
    public String toString() {
        return "Node " + id + " (" + host + ":" + port + ")";
    }
}
