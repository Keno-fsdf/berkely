package de.thws;

import java.util.List;

public class NodeMain {

    /**
     * Usage:
     *   java de.thws.NodeMain <nodeId>
     *
     * Beispiel:
     *   java de.thws.NodeMain 3
     */

    // Adresse des externen Monitors (separater Prozess)
    // MonitorMain läuft z. B. mit:
    //   java de.thws.MonitorMain
    static final String MONITOR_HOST = "192.168.178.22";
    static final int MONITOR_PORT = 6000;
    static final NodeInfo MONITOR =
            new NodeInfo(-1, "192.168.178.22", 6000);


    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            System.err.println("Usage: java NodeMain <nodeId>");
            System.exit(1);
        }

        int nodeId = Integer.parseInt(args[0]);

        // -------------------------------
        // Definition aller Nodes im System
        // -------------------------------
        List<NodeInfo> allNodes = List.of(
                new NodeInfo(1, "192.168.178.22", 5001),
                new NodeInfo(2, "192.168.178.22", 5002),
                new NodeInfo(3, "192.168.178.22", 5003),
                new NodeInfo(4, "192.168.178.22", 5004),
                new NodeInfo(5, "192.168.178.22", 5005)
        );

        // Eigener Node
        NodeInfo self = allNodes.stream()
                .filter(n -> n.id() == nodeId)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("Unknown nodeId " + nodeId));

        // Alle anderen Nodes = Peers
        List<NodeInfo> peers = allNodes.stream()
                .filter(n -> n.id() != nodeId)
                .toList();

        // -------------------------------
        // Uhrenparameter (lokale Uhr)
        // -------------------------------
        // Drift ∈ [0.95, 1.10]
        double drift = 0.95 + Math.random() * 0.15;

        // Basiszeit (Sekunden seit Epoch)
        double baseTime = System.currentTimeMillis() / 1000.0;

        // Zufälliger Offset ±250 ms
        double jitter = (Math.random() - 0.5) * 0.5;

        double initialTime = baseTime + jitter;

        // -------------------------------
        // Transport: Node ↔ Node (UDP)
        // -------------------------------
        // Kein SimulationMonitor hier!
        // Monitoring läuft extern.
        UDPTransport transport = new UDPTransport(
                self.port(),
                1.0,     // successProbability = 1.0 → kein Packet Loss
                null     // kein lokaler Monitor im Node-Prozess
        );

        // -------------------------------
        // Remote Monitor (UDP → MonitorMain)
        // -------------------------------
        // Dieser Sink schickt strukturierte Events
        // an den separaten Monitor-Prozess.
        MonitorSink remoteMonitor =
                new RemoteMonitorClient(MONITOR_HOST, MONITOR_PORT);

        // -------------------------------
        // Node erzeugen
        // -------------------------------
        Node node = new Node(
                self,
                peers,
                drift,
                initialTime,
                transport,
                NodeConfig.defaults(),
                null,
                MONITOR
        );


        // -------------------------------
        // Node starten
        // -------------------------------
        node.start();

        System.out.printf(
                "Node %d STARTED @ %s:%d | drift=%.4f | t0=%.2f%n",
                nodeId,
                self.host(),
                self.port(),
                drift,
                initialTime
        );

        // JVM am Leben halten
        // CTRL+C entspricht Fail-Stop
        Thread.currentThread().join();
    }
}
