package de.thws;

import java.util.List;

public class NodeMain {

    /**
     * Usage:
     *   java NodeMain <nodeId>
     *
     * Example:
     *   java NodeMain 3
     */
    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            System.err.println("Usage: java NodeMain <nodeId>");
            System.exit(1);
        }

        int nodeId = Integer.parseInt(args[0]);
        List<NodeInfo> allNodes = List.of(
                new NodeInfo(1, "192.168.1.10", 5001),
                new NodeInfo(2, "192.168.1.10", 5002),
                new NodeInfo(3, "192.168.1.20", 5001),
                new NodeInfo(4, "192.168.1.20", 5002),
                new NodeInfo(5, "192.168.1.20", 5003)
        );

        NodeInfo self = allNodes.stream()
                .filter(n -> n.id() == nodeId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown nodeId " + nodeId));

        List<NodeInfo> peers = allNodes.stream()
                .filter(n -> n.id() != nodeId)
                .toList();

        // === CLOCK PARAMS ===
        double drift = 0.95 + Math.random() * 0.15;   // drift ∈ [0.95, 1.10]
        double initialTime = Math.random() * 86400.0;

        // === TRANSPORT ===
        UDPTransport transport = new UDPTransport(
                self.port(),
                1.0,        // loss = 0.0 (demo sırasında kaos istemiyoruz)
                null        // SimulationMonitor yok (process başına sade)
        );

        Node node = new Node(
                self,
                peers,
                drift,
                initialTime,
                transport,
                NodeConfig.defaults(),
                null
        );

        node.start();

        System.out.printf(
                "Node %d STARTED @ %s:%d | drift=%.4f | t0=%.2f%n",
                nodeId, self.host(), self.port(), drift, initialTime
        );

        // JVM açık kalsın → CTRL+C = fail-stop
        Thread.currentThread().join();
    }
}

