package de.thws;

import java.net.SocketException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Node {

    private final NodeInfo info;
    private final List<NodeInfo> peers;
    private final Clock clock;
    private final UDPTransport transport;
    private final NodeConfig cfg;
    private final SimulationMonitor monitor;
    private final UDPTransport monitorTransport;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile boolean alive = true;
    public volatile boolean isMaster = false;

    private Thread clockThread;
    private Thread listenerThread;
    private Thread berkeleyThread;
    private Thread heartbeatThread;
    private Thread monitorThread;

    // Liveness / Wahl
    private volatile long lastHeartbeatMs = System.currentTimeMillis();
    private volatile boolean electionInProgress = false;
    private volatile boolean gotOkFromHigher = false;
    private volatile int currentMasterId = -1;
    private volatile long electionCooldownUntilMs = 0L;
    private volatile int missedHbCount = 0;

    private final Random rnd = new Random();

    // Berkeley
    private final Object lock = new Object();
    private final AtomicInteger seqGen = new AtomicInteger(1);
    private boolean firstSync = true;

    private final Map<Integer, Double> pendingT1 = new HashMap<>();
    private final Map<Integer, List<OffsetSample>> samplesByPeer = new HashMap<>();

    private static class OffsetSample {
        final double offset; final double rtt;
        OffsetSample(double offset, double rtt) { this.offset = offset; this.rtt = rtt; }
    }

    // De-dup Guards für Monitor
    private volatile boolean inFailStop = false;
    private volatile int lastNotifiedMasterId = -1;

    // TEMPO-Join-Handling: erster großer Offset hart setzen, danach nur slewen
    private volatile boolean firstAdjustOnThisNode = true;
    private static final double JOIN_HARD_SET_THRESHOLD_SECONDS = 1.0; // >1s → einmalig hart setzen
    private static final double P_BOUND_DEFAULT = 1e-4; // 100 ppm Bound (Paper-Niveau)

    // Konstruktoren (mit/ohne Monitor/Config)
    public Node(NodeInfo info, List<NodeInfo> peers, double drift, double initialTime, UDPTransport transport) throws SocketException {
        this(info, peers, drift, initialTime, transport, NodeConfig.defaults(), null);
    }

    public Node(NodeInfo info, List<NodeInfo> peers, double drift, double initialTime, UDPTransport transport, NodeConfig cfg) throws SocketException {
        this(info, peers, drift, initialTime, transport, cfg, null);
    }

    public Node(NodeInfo info, List<NodeInfo> peers, double drift, double initialTime, UDPTransport transport, NodeConfig cfg, SimulationMonitor monitor) throws SocketException {
        this.info = info;
        this.peers = peers;
        this.clock = new Clock(initialTime, drift);
        this.transport = transport;
        this.cfg = cfg;
        this.monitor = monitor;
        this.monitorTransport = new UDPTransport(
                0,        // ephemeral port (sadece send)
                1.0,
                null
        );
    }

    // Start: KEIN onNodeStart hier (Demo meldet Start einheitlich)
    public void start() {
        clockThread = new Thread(this::runClock, "ClockThread-" + info.id()); clockThread.setDaemon(true); clockThread.start();
        listenerThread = new Thread(this::runListener, "ListenerThread-" + info.id()); listenerThread.setDaemon(true); listenerThread.start();
        berkeleyThread = new Thread(this::runBerkeley, "BerkeleyThread-" + info.id()); berkeleyThread.setDaemon(true); berkeleyThread.start();
        heartbeatThread = new Thread(this::runHeartbeat, "HeartbeatThread-" + info.id()); heartbeatThread.setDaemon(true); heartbeatThread.start();
        monitorThread = new Thread(this::runMonitor, "MonitorThread-" + info.id()); monitorThread.setDaemon(true); monitorThread.start();
        sendMonitorEvent("NODE_START|time=" + clock.getTime() +
                "|drift=" + clock.getDrift() +
                "|peers=" + peers.size() +
                "|host=" + info.host() +
                "|port=" + info.port());
        log("Node gestartet.");
    }

    // Stop-Overloads (permanent/geregelt)
    public void stop() { stop(false); }

    public void stop(boolean permanent) {
        running.set(false);
        transport.close();

        interruptSilently(clockThread);
        interruptSilently(listenerThread);
        interruptSilently(berkeleyThread);
        interruptSilently(heartbeatThread);
        interruptSilently(monitorThread);

        joinSilently(clockThread, 1000);
        joinSilently(listenerThread, 1000);
        joinSilently(berkeleyThread, 1000);
        joinSilently(heartbeatThread, 1000);
        joinSilently(monitorThread, 1000);

        if (monitor != null) monitor.onNodeStop(info.id(), permanent);
        sendMonitorEvent("NODE_STOP|permanent=" + permanent);
        log("Node gestoppt.");
    }

    public boolean isAlive() { return alive; }

    // Temporärer Fail-Stop
    public void failTemporarily(long downtimeMs) {
        if (!running.get() || !alive) return;
        if (!inFailStop) {
            inFailStop = true;
            if (monitor != null) monitor.onFailStopStart(info.id(), downtimeMs);
        }
        sendMonitorEvent("FAIL_START|mode=temp|ms=" + downtimeMs);
        log("Fail-Stop (temporär) für " + downtimeMs + "ms");

        isMaster = false;
        alive = false;
        clock.pause();

        new Thread(() -> {
            try { Thread.sleep(downtimeMs); } catch (InterruptedException ignored) {}
            clock.resume();
            alive = true;
            if (inFailStop) {
                inFailStop = false;
                if (monitor != null) monitor.onFailStopEnd(info.id());
            }
            electionCooldownUntilMs = System.currentTimeMillis() + cfg.graceAfterCoordMs;
            log("Node wieder aktiv (Return von Fail-Stop).");
            if (!isMaster) startElection();
        }, "FailReturn-" + info.id()).start();
    }

    // Permanenter Fail-Stop
    public void failPermanently() {
        log("PERMANENTER Fail-Stop – Node stoppt.");
        sendMonitorEvent("FAIL_START|mode=perm");
        stop(true);
    }

// Threads

    private void runClock() {
        try {
            while (running.get()) {
                if (alive) {
                    clock.tick();
                    if (monitor != null) monitor.updateNodeTime(info.id(), clock.getTime());
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException ignored) {}
    }

    private void runListener() {
        while (running.get()) {
            try {
                Message msg = transport.receive();
                if (!running.get()) break;
                if (!alive) continue;
                if (msg != null) handleMessage(msg);
            } catch (Exception e) { break; }
        }
    }

    private void runBerkeley() {
        while (running.get()) {
            try {
                if (isMaster && alive) runBerkeleySyncRound();
                if (!running.get()) break;
                Thread.sleep(cfg.syncIntervalMs);
            } catch (InterruptedException ignored) { break; }
        }
    }

    private void runHeartbeat() {
        while (running.get()) {
            try {
                if (isMaster && alive) {
                    double now = clock.getTime();
                    for (NodeInfo p : peers) send(Message.heartbeat(info.id(), p.id(), now), p);
                }
                Thread.sleep(cfg.heartbeatIntervalMs);
            } catch (InterruptedException ignored) { break; }
        }
    }

    private void runMonitor() {
        while (running.get()) {
            try {
                if (alive && !isMaster) {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastHeartbeatMs > cfg.heartbeatTimeoutMs) missedHbCount++;
                    else missedHbCount = 0;

                    if (!electionInProgress && nowMs >= electionCooldownUntilMs && missedHbCount >= cfg.missedHbThreshold) {
                        sendMonitorEvent("ELECTION_START|reason=missed_hb|missed=" + missedHbCount);
                        startElection();
                        missedHbCount = 0;
                    }
                }
                Thread.sleep(100);
            } catch (InterruptedException ignored) { break; }
        }
    }

    // Berkeley-Runde (TEMPO-orientiert)
    private void runBerkeleySyncRound() {
        if (!running.get() || !alive) return;
        if (monitor != null) monitor.onSyncStart(info.id());
        log("Starte Berkeley-Sync-Runde...");
        sendMonitorEvent("SYNC_START|master=" + info.id());


        // 1) Probing
        synchronized (lock) { pendingT1.clear(); samplesByPeer.clear(); }
        for (NodeInfo peer : peers) {
            for (int i = 0; i < cfg.probes; i++) {
                if (!running.get() || !alive) return;
                int reqId = seqGen.getAndIncrement();
                double t1 = clock.getTime();
                synchronized (lock) { pendingT1.put(reqId, t1); }
                send(Message.timeRequest(info.id(), peer.id(), reqId, t1), peer);
                try { Thread.sleep(2); } catch (InterruptedException ignored) { return; }
            }
        }
        try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
        if (!running.get() || !alive) return;

        // 2) Beste Offsets je Peer (min-RTT innerhalb TM), min RTT sammeln
        Map<Integer, Double> bestOffsetByPeer = new HashMap<>();
        Map<Integer, Double> minRttByPeer     = new HashMap<>();
        synchronized (lock) {
            for (NodeInfo peer : peers) {
                List<OffsetSample> list = samplesByPeer.getOrDefault(peer.id(), Collections.emptyList());
                OffsetSample best = null;
                double minRtt = Double.POSITIVE_INFINITY;
                for (OffsetSample s : list) {
                    if (s.rtt < minRtt) minRtt = s.rtt;
                    if (s.rtt <= cfg.tmBoundSeconds && (best == null || s.rtt < best.rtt)) {
                        best = s;
                    }
                }
                if (best == null) {
                    for (OffsetSample s : list) if (best == null || s.rtt < best.rtt) best = s;
                }
                if (best != null) {
                    bestOffsetByPeer.put(peer.id(), best.offset);
                    minRttByPeer.put(peer.id(), minRtt);
                } else {
                    log("Warnung: keine gültigen Samples von Peer " + peer.id());
                    sendMonitorEvent("SYNC_PEER_MISSING|peer=" + peer.id());
                }
            }
        }

        // 3) Offsets inkl. Master (0.0)
        Map<Integer, Double> offsetsInclMaster = new HashMap<>(bestOffsetByPeer);
        offsetsInclMaster.put(info.id(), 0.0);

        // 4) Paper-basierte Bounds: ε und γ
        double minRttGlobal = minRttByPeer.values().stream().mapToDouble(v -> v).min().orElse(cfg.tmBoundSeconds);
        double epsilon = Math.max(0.0, (cfg.tmBoundSeconds - minRttGlobal) / 2.0);
        double Tsec = cfg.syncIntervalMs / 1000.0;
        double pEst = Math.max(P_BOUND_DEFAULT, Math.abs(clock.getDrift() - 1.0)); // einfacher Bound
        double gammaUse = Math.max(cfg.gammaBaseSeconds, 4.0 * epsilon + 2.0 * pEst * Tsec);

        // 5) Inlier-Cluster innerhalb γ und fault-toleranter Mittelwert
        Set<Integer> inliers = largestClusterWithinGamma(offsetsInclMaster, gammaUse);
        double E = average(inliers, offsetsInclMaster);
        firstSync = false;

        // 6) Master-Korrektur: Join-Fast-Path (hart) oder Slewing
        if (firstAdjustOnThisNode && Math.abs(E) > JOIN_HARD_SET_THRESHOLD_SECONDS) {
            clock.setTime(clock.getTime() + E); // einmalig hart setzen beim Join
            log(String.format("Clock set %+.6f s (Master, Join-Fast-Path)", E));
        } else {
            clock.adjust(E); // danach nur noch slewen
            log(String.format("Clock adjust %+.6f s (Master, slewing)", E));
        }
        firstAdjustOnThisNode = false;

        // 7) Korrekturen an Slaves (auch „faulty“)
        for (NodeInfo peer : peers) {
            Double eak = bestOffsetByPeer.get(peer.id());
            if (eak == null) continue;
            double corr = E - eak;
            send(Message.timeAdjust(info.id(), peer.id(), corr), peer);
        }

        log(String.format("Berkeley-Sync fertig. E=%+.6f s, gamma=%.6f, eps=%.6f, inliers=%s, offsets=%s",
                E, gammaUse, epsilon, inliers, bestOffsetByPeer));
        if (monitor != null) monitor.onSyncEnd(info.id(), E, gammaUse, inliers);
    }

    private Set<Integer> largestClusterWithinGamma(Map<Integer, Double> offsets, double gamma) {
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(offsets.entrySet());
        list.sort(Comparator.comparingDouble(Map.Entry::getValue));
        int i = 0, j = 0, bi = 0, bj = -1;
        while (i < list.size()) {
            while (j + 1 < list.size() && list.get(j + 1).getValue() - list.get(i).getValue() <= gamma) j++;
            if (j - i > bj - bi) { bi = i; bj = j; }
            i++; if (i > j) j = i;
        }
        Set<Integer> ids = new HashSet<>();
        for (int k = bi; k <= bj; k++) ids.add(list.get(k).getKey());
        return ids;
    }

    private double average(Set<Integer> ids, Map<Integer, Double> map) {
        double s = 0.0; int n = 0;
        for (int id : ids) { Double v = map.get(id); if (v != null) { s += v; n++; } }
        return n == 0 ? 0.0 : s / n;
    }

// Nachrichtenverarbeitung

    private void handleMessage(Message msg) {
        switch (msg.type()) {
            case TIME_REQUEST -> onTimeRequest(msg);
            case TIME_RESPONSE -> onTimeResponse(msg);
            case TIME_ADJUST -> onTimeAdjust(msg);
            case HEARTBEAT -> onHeartbeat(msg);
            case ELECTION -> onElection(msg);
            case ELECTION_OK -> onElectionOk(msg);
            case COORDINATOR_ELECTED -> onCoordinator(msg);
            default -> log("Unbekannte Nachricht: " + msg);
        }
    }

    private void onTimeRequest(Message msg) {
        if (!alive || isMaster) return;
        double t2 = clock.getTime();
        NodeInfo master = peerById(msg.senderId());
        if (master != null) send(Message.timeResponse(info.id(), master.id(), msg.reqId(), t2, msg.time()), master);
    }

    private void onTimeResponse(Message msg) {
        if (!alive || !isMaster) return;
        double t3 = clock.getTime();
        double t2 = msg.time();
        double t1;
        synchronized (lock) {
            Double t1Obj = pendingT1.remove(msg.reqId());
            t1 = (t1Obj != null) ? t1Obj : msg.offset();
            double rtt = t3 - t1;
            double offset = t2 - (t1 + t3) / 2.0;
            samplesByPeer.computeIfAbsent(msg.senderId(), k -> new ArrayList<>()).add(new OffsetSample(offset, rtt));
        }
    }

    private void onTimeAdjust(Message msg) {
        if (!alive || isMaster) return;

        double off = msg.offset();
        if (firstAdjustOnThisNode && Math.abs(off) > JOIN_HARD_SET_THRESHOLD_SECONDS) {
            clock.setTime(clock.getTime() + off); // einmalig hart setzen beim Join
            log(String.format("Clock set %+.6f s (vom Master, Join-Fast-Path)", off));
        } else {
            clock.adjust(off); // danach nur noch slewen
            log(String.format("Clock adjust %+.6f s (vom Master, slewing)", off));
        }
        firstAdjustOnThisNode = false;
    }

    private void onHeartbeat(Message msg) {
        if (!alive) return;
        long now = System.currentTimeMillis();

        if (currentMasterId == -1) {
            currentMasterId = msg.senderId();
            lastHeartbeatMs = now;
            electionCooldownUntilMs = now + cfg.graceAfterCoordMs;
            notifyMasterIfChanged(currentMasterId, false);
            log("Erster Heartbeat: vermuteter Master ist " + currentMasterId);
            sendMonitorEvent("COORDINATOR_ADOPT|master=" + currentMasterId + "|via=first_hb");
            return;
        }

        if (msg.senderId() == currentMasterId) {
            lastHeartbeatMs = now;
            electionCooldownUntilMs = now + cfg.graceAfterCoordMs;
        } else if (msg.senderId() > currentMasterId) {
            currentMasterId = msg.senderId();
            lastHeartbeatMs = now;
            electionCooldownUntilMs = now + cfg.graceAfterCoordMs;
            isMaster = false;
            notifyMasterIfChanged(currentMasterId, true); // Demotion
            log("Heartbeat höherer ID akzeptiert, neuer vermuteter Master: " + currentMasterId);
            sendMonitorEvent("DEMOTED|newMaster=" + currentMasterId + "|via=higher_hb");

        }
    }

    private void onElection(Message msg) {
        if (!alive) return;

        if (isMaster && msg.senderId() < info.id()) {
            NodeInfo src = peerById(msg.senderId());
            if (src != null) {
                send(Message.electionOk(info.id(), src.id()), src);
                announceCoordinator();
            }
            return;
        }

        if (msg.senderId() < info.id()) {
            NodeInfo src = peerById(msg.senderId());
            if (src != null) send(Message.electionOk(info.id(), src.id()), src);
            long nowMs = System.currentTimeMillis();
            if (!electionInProgress && nowMs >= electionCooldownUntilMs) startElection();
            return;
        }
    }

    private void onElectionOk(Message msg) {
        gotOkFromHigher = true;
        sendMonitorEvent("ELECTION_OK_RX|from=" + msg.senderId());
    }

    private void onCoordinator(Message msg) {
        if (!alive) return;

        if (msg.senderId() < info.id()) {
            if (!isMaster && !electionInProgress) {
                startElection();
            }
            return;
        }

        boolean iAmCoordinator = (msg.senderId() == info.id());
        isMaster = iAmCoordinator;
        currentMasterId = msg.senderId();
        electionInProgress = false;
        gotOkFromHigher = false;
        lastHeartbeatMs = System.currentTimeMillis();
        electionCooldownUntilMs = lastHeartbeatMs + cfg.graceAfterCoordMs;

        notifyMasterIfChanged(currentMasterId, false);
        log("Neuer Master ist " + currentMasterId);
        sendMonitorEvent("COORDINATOR_ADOPT|master=" + currentMasterId);
    }

    private void startElection() {
        if (electionInProgress || !running.get() || !alive) return;
        electionInProgress = true;
        gotOkFromHigher = false;
        if (monitor != null) monitor.onElectionStart(info.id());
        log("Starte Wahl (Bully) ...");

        int higherSent = 0;
        for (NodeInfo p : peers) {
            if (p.id() > info.id()) {
                send(Message.election(info.id(), p.id()), p);
                higherSent++;
            }
        }

        long start = System.currentTimeMillis();
        while (running.get() && alive && (System.currentTimeMillis() - start) < cfg.electionTimeoutMs) {
            if (gotOkFromHigher) break;
            try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
        }

        if (!gotOkFromHigher && higherSent == 0) {
            becomeMasterAndAnnounce();
            electionInProgress = false;
            return;
        }
        if (!gotOkFromHigher) {
            becomeMasterAndAnnounce();
            electionInProgress = false;
            return;
        }

        long waitStart = System.currentTimeMillis();
        boolean coordinatorArrived = false;
        while (running.get() && alive && (System.currentTimeMillis() - waitStart) < cfg.coordinatorWaitMs) {
            if (currentMasterId != -1 && currentMasterId > info.id()) {
                coordinatorArrived = true;
                break;
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
        }

        if (!coordinatorArrived) {
            electionInProgress = false;
            long backoff = cfg.electionBackoffBaseMs + rnd.nextInt((int) cfg.electionBackoffJitterMs + 1);
            electionCooldownUntilMs = System.currentTimeMillis() + backoff;
            log("Kein (gültiger) COORDINATOR empfangen – Backoff " + backoff + "ms.");
        } else {
            electionInProgress = false;
        }
    }

    private void becomeMasterAndAnnounce() {
        isMaster = true;
        currentMasterId = info.id();
        lastHeartbeatMs = System.currentTimeMillis();
        electionCooldownUntilMs = lastHeartbeatMs + cfg.graceAfterCoordMs;

        for (int i = 0; i < cfg.coordinatorAnnounceRepeats; i++) {
            announceCoordinator();
            try { Thread.sleep(cfg.coordinatorAnnounceIntervalMs); } catch (InterruptedException ignored) {}
        }
        notifyMasterIfChanged(currentMasterId, false);
        sendMonitorEvent("MASTER_ELECTED|id=" + info.id() + "|via=self_promo");
        log("Ich bin der neue Master.");
    }

    private void announceCoordinator() {
        if (monitor != null) {
            monitor.onCoordinatorAnnounce(info.id());
            sendMonitorEvent("COORDINATOR_ANNOUNCE");
        }
        for (NodeInfo p : peers) send(Message.coordinatorElected(info.id(), p.id()), p);
    }

    // Notify helper (dedupe)
    private void notifyMasterIfChanged(int newMasterId, boolean demotion) {
        if (newMasterId == lastNotifiedMasterId) return;
        lastNotifiedMasterId = newMasterId;
        if (monitor != null) {
            if (demotion) monitor.onDemotedTo(newMasterId);
            else monitor.onMasterElected(newMasterId);
        }
    }

    private NodeInfo peerById(int id) {
        for (NodeInfo n : peers) if (n.id() == id) return n;
        return null;
    }

    private void send(Message msg, NodeInfo target) {
        if (target != null) transport.send(msg, target);
    }

    private void log(String s) {
        System.out.printf("[Node %d @ %.3f] %s%n", info.id(), clock.getTime(), s);
    }

    private static void interruptSilently(Thread t) { if (t != null) try { t.interrupt(); } catch (Exception ignored) {} }
    private static void joinSilently(Thread t, long ms) { if (t != null) try { t.join(ms); } catch (InterruptedException ignored) {} }

    public NodeInfo getInfo() { return info; }
    public double getLocalTime() { return clock.getTime(); }
    public Clock getClock() { return clock; }
    public List<NodeInfo> getPeers() { return peers; }
    public boolean getIsMaster() { return isMaster; }

    private void sendMonitorEvent(String payload) {
        if (monitorTransport == null) return;

        monitorTransport.send(
                Message.monitorEvent(info.id(), payload),
                NodeMain.MONITOR
        );
    }
}