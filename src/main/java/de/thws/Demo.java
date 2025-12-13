package de.thws;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Demo {

    // Konfigurierbare Argumente
    static class Args {
        int nodes = 5;  //davor war es 5 stück gewesen
        //Also kurzer hinweis, ab einer gewissen grenze an ports kann es sein dass es versucht ports zu nutzen die von anderen services/programmen genutzt wird
        // und dann kommt halt ein fehler darauf bezogen.
        //Man könnte den port bereich mit dem man arbeitet ändern dafür. Aber ihc halte das für unnötig.
        //Bei mir geht bei nodes=40 alles aber bei nodes=50 geht es nicht mehr. +
        // Aber 40 ist meiner Meinung total ausreichend für das hier.



        long durationMs = 20_000; //davor war es 30 sekunden  -->wie lange das Programm läuft bzw. die Demo
        double driftMin = 0.95;
        double driftMax = 1.10;
        double loss = 0.9;                   // Zustellwahrscheinlichkeit (1.0 = kein Loss)
        boolean randomFail = true;  //ja gut wenn man das auf false macht, dann kommen halt keine Ausfälle mehr von den NODES. Also message ausfälle
        //wird über loss oben gesteuert.
        double failRatePerNodePerSec = 0.02;  //was ist die ausfallchance pro sekunde pro node
        double permanentDeathProb = 0.10;  //chance das ein node gar nicht mehr zurück kommt
        int maxConcurrentFails = 2;   //maximale gleichzeitige ausfälle. Ein sicherheitsparamenter

        /*
        Wir simulieren Ausfälle zentral in der Demo (Fail-Stop, Dauer, Anzahl). Das ist bewusst so gemacht und betrifft nur die Orchestrierung, nicht das Protokoll.
Das Zeit‑Sync‑ und Wahlprotokoll bleibt vollständig dezentral: Heartbeats, Failure‑Detection, Demotion und Bully laufen ohne zentrale Instanz.
Grund: Reproduzierbarkeit und Steuerbarkeit. Ohne zentrale Injektion ließen sich Parameter wie Häufigkeit, gleichzeitige Ausfälle oder Down‑Zeiten nicht verlässlich testen.

dezentral wäre das natürlich auch machbar (z. B. jeder Node wirft selbst per Zufall aus), aber dann sind Szenarien/Seeds schwer vergleichbar; zentrale Orchestrierung macht A/B‑Vergleiche und Parameter‑Änderungen-Vergleiche leichter.

         */



        long downMinMs = 2000;
        long downMaxMs = 5000;
        String masterMode = "highest";       // "highest" oder "random"
        long seed = System.nanoTime();
        int basePort = 5001;










    }

    // Fester Schalter: Console-Prints für verlorene Nachrichten AN
    private static final boolean PRINT_LOSS = true;

    public static void main(String[] argv) throws SocketException, InterruptedException {
        Args a = parseArgs(argv);

        // Console-Prints im UDPTransport aktivieren
        UDPTransport.setPrintLoss(PRINT_LOSS);

        Random rnd = new Random(a.seed);

        // 1) NodeInfos + Monitor
        List<NodeInfo> infos = new ArrayList<>();
        for (int i = 0; i < a.nodes; i++) infos.add(new NodeInfo(i + 1, "localhost", a.basePort + i));

        SimulationMonitor monitor = new SimulationMonitor(
                infos.stream().map(NodeInfo::id).toList(),
                SimulationMonitor.Verbosity.SUMMARY, // kompaktes Dashboard
                false,                               // showLossInDashboard = nein (Loss nur in Δ/total)
                false,                               // renderMasterChangeDashboard = nein
                15                                   // recentCapacity
        );
        monitor.onSimulationStart(a.nodes, a.loss, a.randomFail, a.failRatePerNodePerSec, a.permanentDeathProb, a.durationMs);

        // 2) Transports (mit Monitor für Send/Loss-Zähler)
        List<UDPTransport> transports = new ArrayList<>();
        for (NodeInfo ni : infos) transports.add(new UDPTransport(ni.port(), a.loss, monitor));

        // 3) Nodes (mit Monitor) erzeugen
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < a.nodes; i++) {
            NodeInfo self = infos.get(i);
            List<NodeInfo> peers = new ArrayList<>(infos);
            peers.remove(self);

            double initTime = rnd.nextDouble() * 86400.0;
            double drift = a.driftMin + rnd.nextDouble() * (a.driftMax - a.driftMin);

            UDPTransport t = transports.get(i);
            Node n = new Node(self, peers, drift, initTime, t, NodeConfig.defaults(), monitor);
            nodes.add(n);

            monitor.onNodeStart(self.id(), initTime, drift);
            monitor.updateNodeTime(self.id(), initTime);
        }

        // 4) Starten
        for (Node n : nodes) n.start();
        Thread.sleep(300);

        // 5) Master festlegen und Monitor informieren (kein initialer Bully-Lauf)
        Node master = "random".equalsIgnoreCase(a.masterMode)
                ? nodes.get(rnd.nextInt(nodes.size()))
                : nodes.get(nodes.size() - 1);
        master.isMaster = true;
        monitor.onMasterElected(master.getInfo().id());

        System.out.println("Demo läuft... (" + (a.durationMs / 1000) + " Sekunden) seed=" + a.seed);

        // 6) Ausfälle orchestrieren (nur Node meldet Events)
        Set<Integer> currentlyDown = ConcurrentHashMap.newKeySet();
        Set<Integer> permanentlyDead = ConcurrentHashMap.newKeySet();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < a.durationMs) {
            Thread.sleep(1000);

            if (a.randomFail) {
                for (int i = 0; i < nodes.size(); i++) {
                    Node n = nodes.get(i);
                    int nodeId = n.getInfo().id();

                    if (permanentlyDead.contains(nodeId)) continue;
                    if (currentlyDown.contains(nodeId)) continue;
                    if (!n.isAlive()) continue;
                    if (currentlyDown.size() >= a.maxConcurrentFails) break;

                    if (rnd.nextDouble() < a.failRatePerNodePerSec) {
                        boolean perm = rnd.nextDouble() < a.permanentDeathProb;
                        if (perm) {
                            // nur Node meldet permanenten Stop
                            n.failPermanently();
                            permanentlyDead.add(nodeId);
                            currentlyDown.remove(nodeId);
                        } else {
                            long dt = a.downMinMs + rnd.nextInt((int) Math.max(1, a.downMaxMs - a.downMinMs));
                            currentlyDown.add(nodeId);
                            // nur Node meldet Fail-Stop Start/Ende
                            n.failTemporarily(dt);
                            scheduler.schedule(() -> currentlyDown.remove(nodeId), dt + 100, TimeUnit.MILLISECONDS);
                        }
                    }
                }
            }

            // Zeiten an Monitor (kein Rendering hier)
            for (Node n : nodes) monitor.updateNodeTime(n.getInfo().id(), n.getLocalTime());
        }

        // 7) Aufräumen
        scheduler.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);

        for (Node n : nodes) {
            int id = n.getInfo().id();
            if (!permanentlyDead.contains(id)) {
                n.stop();
            }
        }

        monitor.shutdown();

        // Finale Zeiten (optional)
        System.out.print("Finale Zeiten: ");
        for (Node n : nodes) System.out.printf("N%d=%.3f | ", n.getInfo().id(), n.getLocalTime());
        System.out.println();
    }

    // CLI: --key=value
    private static Args parseArgs(String[] argv) {
        Args a = new Args();
        for (String s : argv) {
            String[] kv = s.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].replaceFirst("^--", "").trim();
            String v = s.substring(s.indexOf('=') + 1).trim();
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