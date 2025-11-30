package de.thws;

import java.net.SocketException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Demo {

    // Konfigurierbare Argumente
    static class Args {
        int nodes = 5;                       // Anzahl Knoten
        long durationMs = 30_000;            // Laufzeit der Demo
        double driftMin = 0.95;              // min Drift (Sekunden/Sekunde)
        double driftMax = 1.10;              // max Drift
        double loss = 0.9;                   // Zustellwahrscheinlichkeit (1.0 = kein Loss)
        boolean randomFail = true;           // zufällige Ausfälle aktiv
        double failRatePerNodePerSec = 0.02; // Wahrscheinlichkeit pro Node und Sekunde für (irgendeinen) Fail
        double permanentDeathProb = 0.10;    // Wahrscheinlichkeit, dass ein Fail permanent ist
        int maxConcurrentFails = 2;          // max. temporär gleichzeitig ausgefallene Nodes
        long downMinMs = 2000;               // min Downtime temporärer Fail
        long downMaxMs = 5000;               // max Downtime temporärer Fail
        String masterMode = "highest";       // "highest" oder "random"
        long seed = System.nanoTime();       // Zufallsseed
        int basePort = 5001;                 // Startport (pro Node +i)
    }

    public static void main(String[] argv) throws SocketException, InterruptedException {
        Args a = parseArgs(argv);
        Random rnd = new Random(a.seed);

        // NodeInfos und Transports anlegen
        List<NodeInfo> infos = new ArrayList<>();
        List<UDPTransport> transports = new ArrayList<>();
        for (int i = 0; i < a.nodes; i++) {
            NodeInfo ni = new NodeInfo(i + 1, "localhost", a.basePort + i);
            infos.add(ni);
            transports.add(new UDPTransport(ni.port(), a.loss));
        }

        // Nodes erzeugen (zufällige Startzeit [0..86400], Drift in [driftMin..driftMax])
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < a.nodes; i++) {
            NodeInfo self = infos.get(i);
            List<NodeInfo> peers = new ArrayList<>(infos);
            peers.remove(self);

            double initTime = rnd.nextDouble() * 86400.0;
            double drift = a.driftMin + rnd.nextDouble() * (a.driftMax - a.driftMin);

            nodes.add(new Node(self, peers, drift, initTime, transports.get(i)));
        }

        // Starten
        for (Node n : nodes) n.start();
        Thread.sleep(300);

        // Master festlegen
        Node master;
        if ("random".equalsIgnoreCase(a.masterMode)) {
            master = nodes.get(rnd.nextInt(nodes.size()));
        } else {
            master = nodes.get(nodes.size() - 1); // höchste ID
        }
        master.isMaster = true;

        System.out.println("Demo läuft... (" + (a.durationMs / 1000) + " Sekunden) seed=" + a.seed);

        // Verwaltung von temporär/permanent ausgefallenen Nodes
        Set<Integer> currentlyDown = ConcurrentHashMap.newKeySet();  // Node-IDs temporär down
        Set<Integer> permanentlyDead = ConcurrentHashMap.newKeySet(); // Node-IDs permanent tot
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < a.durationMs) {
            Thread.sleep(1000);

            // Zufällige Ausfälle orchestrieren
            if (a.randomFail) {
                for (int i = 0; i < nodes.size(); i++) {
                    Node n = nodes.get(i);
                    int nodeId = n.getInfo().id();

                    // Überspringen, wenn bereits permanent tot oder temporär down
                    if (permanentlyDead.contains(nodeId)) continue;
                    if (currentlyDown.contains(nodeId)) continue;
                    if (!n.isAlive()) continue; // temporär down (aus Node-Sicht)

                    // Limit der gleichzeitigen temporären Ausfälle
                    if (currentlyDown.size() >= a.maxConcurrentFails) break;

                    // probabilistischer Fail-Trigger
                    if (rnd.nextDouble() < a.failRatePerNodePerSec) {
                        boolean perm = rnd.nextDouble() < a.permanentDeathProb;
                        if (perm) {
                            // Permanent: Node stoppen, markieren
                            n.failPermanently();
                            permanentlyDead.add(nodeId);
                            currentlyDown.remove(nodeId);
                        } else {
                            // Temporär: Downtime ziehen, Node failen, im Set markieren und nach Ablauf automatisch entfernen
                            long dt = a.downMinMs + rnd.nextInt((int) Math.max(1, a.downMaxMs - a.downMinMs));
                            n.failTemporarily(dt);
                            currentlyDown.add(nodeId);
                            scheduler.schedule(() -> currentlyDown.remove(nodeId), dt + 100, TimeUnit.MILLISECONDS);
                        }
                    }
                }
            }

            // Status ausgeben
            System.out.print("Zeiten: ");
            for (Node n : nodes) {
                int id = n.getInfo().id();
                String tag = permanentlyDead.contains(id) ? "!" : (!n.isAlive() ? "X" : "");
                System.out.printf("N%d%s=%.3f | ", id, tag, n.getLocalTime());
            }
            System.out.println();
        }

        // Scheduler runterfahren
        scheduler.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);

        // Alle noch lebenden Nodes stoppen (permanent tote sind bereits gestoppt)
        for (Node n : nodes) {
            int id = n.getInfo().id();
            if (!permanentlyDead.contains(id)) {
                n.stop();
            }
        }

        // Finale Zeiten
        System.out.print("Finale Zeiten: ");
        for (Node n : nodes) {
            System.out.printf("N%d=%.3f | ", n.getInfo().id(), n.getLocalTime());
        }
        System.out.println();
    }

    // Einfache CLI-Argumente: --key=value
    private static Args parseArgs(String[] argv) {
        Args a = new Args();
        for (String s : argv) {
            String[] kv = s.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].replaceFirst("^--", "").trim();
            String v = kv[1].trim();
            switch (k) {
                case "nodes" -> a.nodes = Integer.parseInt(v);
                case "duration" -> a.durationMs = Long.parseLong(v);
                case "driftMin" -> a.driftMin = Double.parseDouble(v);
                case "driftMax" -> a.driftMax = Double.parseDouble(v);
                case "loss" -> a.loss = Double.parseDouble(v);
                case "randomFail" -> a.randomFail = Boolean.parseBoolean(v);
                case "failRate" -> a.failRatePerNodePerSec = Double.parseDouble(v);
                case "permProb" -> a.permanentDeathProb = Double.parseDouble(v);
                case "maxConcurrentFails" -> a.maxConcurrentFails = Integer.parseInt(v);
                case "downMinMs" -> a.downMinMs = Long.parseLong(v);
                case "downMaxMs" -> a.downMaxMs = Long.parseLong(v);
                case "master" -> a.masterMode = v;
                case "seed" -> a.seed = Long.parseLong(v);
                case "basePort" -> a.basePort = Integer.parseInt(v);
            }
        }
        return a;
    }
}