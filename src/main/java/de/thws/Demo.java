
        package de.thws;

import java.net.SocketException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Demo {

    static class Args {
        int nodes = 5;

        long durationMs = 30_000;
        double driftMin = 0.95;
        double driftMax = 1.10;
        double loss = 0.9;
        boolean randomFail = true;
        double failRatePerNodePerSec = 0.02;
        double permanentDeathProb = 0.10;
        int maxConcurrentFails = 2;

        long downMinMs = 2000;
        long downMaxMs = 5000;
        String masterMode = "highest";
        long seed = System.nanoTime();
        int basePort = 5001;

        // gezielter Master-Kill (optional)
        boolean killMaster = false;
        long killMasterAtMs = 2000;
        boolean killMasterPermanent = false;
        long killMasterDownMs = 3000;

        // Verteiltes Setup
        String cluster = null;            // "id@host:port;id@host:port;..."
        String spawn = null;              // "1,2,3"
        String monitorMode = "server";    // "server" (A) | "client" (B) | "local"
        String monitorAddr = "127.0.0.1";
        int    monitorPort = 9999;
        boolean monitorEnabled = true;
    }

    private static final boolean PRINT_LOSS = true;

    public static void main(String[] argv) throws SocketException, InterruptedException {
        Args a = parseArgs(argv);
        UDPTransport.setPrintLoss(PRINT_LOSS);
        Random rnd = new Random(a.seed);

        // 1) Cluster + Spawn
        List<NodeInfo> infos = (a.cluster != null)
                ? parseCluster(a.cluster)
                : buildLocalDefault(a.nodes, a.basePort);
        Set<Integer> spawnSet = (a.spawn != null)
                ? parseSpawn(a.spawn)
                : new HashSet<>(infos.stream().map(NodeInfo::id).toList());

        // 2) Monitor einrichten (zentral auf A, remote auf B, sonst lokal)
        MonitorSink monitor;
        SimulationMonitor simMon = null;
        MonitorServer monitorServer = null;

        if ("server".equalsIgnoreCase(a.monitorMode)) {
            simMon = new SimulationMonitor(infos.stream().map(NodeInfo::id).toList(),
                    SimulationMonitor.Verbosity.SUMMARY, false, false, 15);
            monitor = new LocalMonitor(simMon);
            if (a.monitorEnabled) {
                simMon.onSimulationStart(infos.size(), a.loss, a.randomFail,
                        a.failRatePerNodePerSec, a.permanentDeathProb, a.durationMs);
            }
            monitorServer = new MonitorServer(a.monitorPort, simMon);
            Thread ms = new Thread(monitorServer, "MonitorServer");
            ms.setDaemon(true);
            ms.start();
        } else if ("client".equalsIgnoreCase(a.monitorMode)) {
            monitor = new RemoteMonitorClient(a.monitorAddr, a.monitorPort);
            monitor.onSimulationStart(infos.size(), a.loss, a.randomFail,
                    a.failRatePerNodePerSec, a.permanentDeathProb, a.durationMs);
        } else { // local
            simMon = new SimulationMonitor(infos.stream().map(NodeInfo::id).toList(),
                    SimulationMonitor.Verbosity.SUMMARY, false, false, 15);
            monitor = new LocalMonitor(simMon);
            simMon.onSimulationStart(infos.size(), a.loss, a.randomFail,
                    a.failRatePerNodePerSec, a.permanentDeathProb, a.durationMs);
        }

        // 3) Transports nur für spawn-IDs
        Map<Integer, UDPTransport> transById = new HashMap<>();
        for (NodeInfo ni : infos) {
            if (!spawnSet.contains(ni.id())) continue;
            UDPTransport t = new UDPTransport(ni.port(), a.loss, monitor);
            transById.put(ni.id(), t);
        }

        // 4) Nodes nur für spawn-IDs
        List<Node> nodes = new ArrayList<>();
        for (NodeInfo self : infos) {
            if (!spawnSet.contains(self.id())) continue;

            List<NodeInfo> peers = new ArrayList<>(infos);
            peers.remove(self);

            double initTime = rnd.nextDouble() * 86400.0;
            double drift = a.driftMin + rnd.nextDouble() * (a.driftMax - a.driftMin);

            UDPTransport t = transById.get(self.id());
            // WICHTIG: Monitor-Interface übergeben
            Node n = new Node(self, peers, drift, initTime, t, NodeConfig.defaults(), monitor);
            nodes.add(n);

            monitor.onNodeStart(self.id(), initTime, drift);
            monitor.updateNodeTime(self.id(), initTime);
        }

        // 5) Starten
        for (Node n : nodes) n.start();
        Thread.sleep(300);

        // 6) Initialen Master setzen (nur wenn dieser Prozess ihn spawnt)
        int highestId = infos.stream().mapToInt(NodeInfo::id).max().orElse(-1);
        int masterId = "random".equalsIgnoreCase(a.masterMode)
                ? spawnSet.stream().skip(new Random(a.seed).nextInt(Math.max(1, spawnSet.size()))).findFirst().orElse(highestId)
                : highestId;

        if (spawnSet.contains(masterId)) {
            for (Node n : nodes) {
                if (n.getInfo().id() == masterId) {
                    n.isMaster = true;
                    monitor.onMasterElected(masterId);
                    break;
                }
            }
        }

        System.out.println("Demo läuft... (" + (a.durationMs / 1000) + " Sekunden) seed=" + a.seed);

        // 7) Ausfälle orchestrieren
        Set<Integer> currentlyDown = ConcurrentHashMap.newKeySet();
        Set<Integer> permanentlyDead = ConcurrentHashMap.newKeySet();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // Gezielten Master‑Kill (effectively final-Fix: simMonRef + midFinal)
        final SimulationMonitor simMonRef = simMon;
        if (a.killMaster) {
            scheduler.schedule(() -> {
                int mId = -1;
                if (simMonRef != null) {
                    mId = simMonRef.getCurrentMasterId();
                } else {
                    for (Node n : nodes) {
                        if (n.getIsMaster()) { mId = n.getInfo().id(); break; }
                    }
                }
                if (mId == -1) return;
                final int midFinal = mId; // effectively final für die Stream-Lambda
                Node target = nodes.stream()
                        .filter(n -> n.getInfo().id() == midFinal)
                        .findFirst().orElse(null);
                if (target != null && target.isAlive()) {
                    if (a.killMasterPermanent) target.failPermanently();
                    else target.failTemporarily(a.killMasterDownMs);
                }
            }, a.killMasterAtMs, TimeUnit.MILLISECONDS);
        }

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < a.durationMs) {
            Thread.sleep(1000);

            if (a.randomFail) {
                for (Node n : nodes) {
                    int nodeId = n.getInfo().id();

                    if (permanentlyDead.contains(nodeId)) continue;
                    if (currentlyDown.contains(nodeId)) continue;
                    if (!n.isAlive()) continue;
                    if (currentlyDown.size() >= a.maxConcurrentFails) break;

                    if (rnd.nextDouble() < a.failRatePerNodePerSec) {
                        boolean perm = rnd.nextDouble() < a.permanentDeathProb;
                        if (perm) {
                            n.failPermanently();
                            permanentlyDead.add(nodeId);
                            currentlyDown.remove(nodeId);
                        } else {
                            long dt = a.downMinMs + rnd.nextInt((int) Math.max(1, a.downMaxMs - a.downMinMs));
                            currentlyDown.add(nodeId);
                            n.failTemporarily(dt);
                            scheduler.schedule(() -> currentlyDown.remove(nodeId), dt + 100, TimeUnit.MILLISECONDS);
                        }
                    }
                }
            }

            for (Node n : nodes) monitor.updateNodeTime(n.getInfo().id(), n.getLocalTime());
        }

        // 8) Aufräumen
        scheduler.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);

        for (Node n : nodes) {
            int id = n.getInfo().id();
            if (!permanentlyDead.contains(id)) n.stop();
        }
        monitor.shutdown();
        if (monitorServer != null) monitorServer.stop();

        // Finale Zeiten
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

                case "killMaster" -> a.killMaster = Boolean.parseBoolean(v);
                case "killAt" -> a.killMasterAtMs = Long.parseLong(v);
                case "killMasterPermanent" -> a.killMasterPermanent = Boolean.parseBoolean(v);
                case "killMasterDownMs" -> a.killMasterDownMs = Long.parseLong(v);

                case "cluster" -> a.cluster = v;
                case "spawn" -> a.spawn = v;
                case "monitorMode" -> a.monitorMode = v;
                case "monitorAddr" -> a.monitorAddr = v;
                case "monitorPort" -> a.monitorPort = Integer.parseInt(v);
                case "monitorEnabled" -> a.monitorEnabled = Boolean.parseBoolean(v);
            }
        }
        return a;
    }

    private static List<NodeInfo> parseCluster(String s) {
        List<NodeInfo> list = new ArrayList<>();
        for (String part : s.split(";")) {
            if (part.isBlank()) continue;
            String[] idAt = part.split("@", 2);
            String[] hp = idAt[1].split(":", 2);
            list.add(new NodeInfo(Integer.parseInt(idAt[0]), hp[0], Integer.parseInt(hp[1])));
        }
        list.sort(Comparator.comparingInt(NodeInfo::id));
        return list;
    }

    private static Set<Integer> parseSpawn(String s) {
        Set<Integer> set = new HashSet<>();
        for (String p : s.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) set.add(Integer.parseInt(t));
        }
        return set;
    }

    private static List<NodeInfo> buildLocalDefault(int n, int basePort) {
        List<NodeInfo> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new NodeInfo(i + 1, "localhost", basePort + i));
        return list;
    }
}