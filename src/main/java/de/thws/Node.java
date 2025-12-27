package de.thws;

import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Node {

    private final NodeInfo info;

    // Safety clamps (wichtig)
    private static final double MAX_MASTER_STEP_SECONDS = 5.0;
    private static final double MAX_SLAVE_STEP_SECONDS  = 30.0;

    // Debug/Diagnose: erkenne epoch mismatch (Nodes in völlig anderen Zeit-Domänen)
    // Berkeley setzt voraus: Uhren sind schon "grob" nah beieinander. Wenn nicht, wird alles Outlier.
    private static final double EPOCH_MISMATCH_THRESHOLD_SECONDS = 60.0;   // > 1 Minute -> sehr wahrscheinlich andere Epoch/Startzeit
    private static final int    EPOCH_MISMATCH_REPORT_EVERY_N    = 3;      // alle N Runden reporten (Spam vermeiden)

    private final UDPTransport monitorTransport;
    private final NodeInfo monitorInfo;
    private final List<NodeInfo> peers;
    private final Clock clock;
    private final UDPTransport transport;
    private final NodeConfig cfg;
    private final SimulationMonitor monitor;

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
        final double offset;
        final double rtt;
        OffsetSample(double offset, double rtt) { this.offset = offset; this.rtt = rtt; }
    }

    // De-dup Guards für Monitor
    private volatile boolean inFailStop = false;
    private volatile int lastNotifiedMasterId = -1;

    // TEMPO-Join-Handling: erster großer Offset hart setzen, danach nur slewen
    private volatile boolean firstAdjustOnThisNode = true;
    private static final double JOIN_HARD_SET_THRESHOLD_SECONDS = 1.0; // >1s → einmalig hart setzen
    private static final double P_BOUND_DEFAULT = 1e-4; // 100 ppm Bound (Paper-Niveau)

    // Diagnose: epoch mismatch counting
    private int epochMismatchRounds = 0;

    // Konstruktoren (mit/ohne Monitor/Config)
    public Node(NodeInfo info, List<NodeInfo> peers, double drift, double initialTime, UDPTransport transport, NodeInfo monitorInfo) throws SocketException {
        this(info, peers, drift, initialTime, transport, NodeConfig.defaults(), null, monitorInfo);
    }

    public Node(NodeInfo info, List<NodeInfo> peers, double drift, double initialTime, UDPTransport transport, NodeConfig cfg, NodeInfo monitorInfo) throws SocketException {
        this(info, peers, drift, initialTime, transport, cfg, null, monitorInfo);
    }

    public Node(
            NodeInfo info,
            List<NodeInfo> peers,
            double drift,
            double initialTime,
            UDPTransport transport,
            NodeConfig cfg,
            SimulationMonitor monitor,
            NodeInfo monitorInfo
    ) throws SocketException {

        this.info = info;
        this.peers = peers;
        this.clock = new Clock(initialTime, drift);
        this.transport = transport;
        this.cfg = cfg;
        this.monitor = monitor;
        this.monitorInfo = monitorInfo;

        // separater UDP-Transport nur für Monitoring (nur send)
        this.monitorTransport = (monitorInfo != null)
                ? new UDPTransport(0, 1.0, null)
                : null;
    }

    // Start
    public void start() {
        clockThread = new Thread(this::runClock, "ClockThread-" + info.id());
        clockThread.setDaemon(true);
        clockThread.start();

        listenerThread = new Thread(this::runListener, "ListenerThread-" + info.id());
        listenerThread.setDaemon(true);
        listenerThread.start();

        berkeleyThread = new Thread(this::runBerkeley, "BerkeleyThread-" + info.id());
        berkeleyThread.setDaemon(true);
        berkeleyThread.start();

        heartbeatThread = new Thread(this::runHeartbeat, "HeartbeatThread-" + info.id());
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        monitorThread = new Thread(this::runMonitor, "MonitorThread-" + info.id());
        monitorThread.setDaemon(true);
        monitorThread.start();

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
        try { transport.close(); } catch (Exception ignored) {}

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

        // === FAIL-STOP: Node ist komplett „weg“ ===
        isMaster = false;
        alive = false;
        clock.pause();

        new Thread(() -> {
            try {
                Thread.sleep(downtimeMs);
            } catch (InterruptedException ignored) {}

            // === WIEDERANLAUF ===
            clock.resume();
            alive = true;

            // 🔴 KRITISCH: Election- & Master-State RESET
            // Der Node darf KEINE alten Annahmen behalten
            isMaster = false;
            currentMasterId = -1;
            electionInProgress = false;
            gotOkFromHigher = false;
            lastNotifiedMasterId = -1;
            missedHbCount = 0;

            // Neue Epoche → erste Korrektur darf wieder HARD SET sein
            firstAdjustOnThisNode = true;

            if (inFailStop) {
                inFailStop = false;
                if (monitor != null) monitor.onFailStopEnd(info.id());
            }

            // Grace-Periode, damit nicht sofort Chaos entsteht
            electionCooldownUntilMs = System.currentTimeMillis() + cfg.graceAfterCoordMs;

            log("Node wieder aktiv (Return von Fail-Stop). Starte neue Wahl.");
            startElection();

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

                    sendMonitorEvent("TIME|t=" + clock.getTime());
                    sendMonitorEvent("DRIFT|d=" + clock.getDrift());
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException ignored) {}
    }


    /**
     * WICHTIG: Listener darf nicht bei jeder Exception sterben.
     * UDP kann transient Exceptions werfen (Timeout, Socket closed, Parse-Glitches).
     */
    private void runListener() {
        while (running.get()) {
            try {
                Message msg = transport.receive();
                if (!running.get()) return;
                if (!alive) continue;
                if (msg != null) handleMessage(msg);
            } catch (Exception e) {
                if (!running.get()) return;
                log("Listener: Exception (ignoriere, laufe weiter): " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
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
                        log("Monitor: Heartbeat fehlt -> starte Wahl. missedHbCount=" + missedHbCount);
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
        synchronized (lock) {
            pendingT1.clear();
            samplesByPeer.clear();
        }

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

        // 2) Beste Offsets je Peer (min RTT innerhalb TM)
        Map<Integer, Double> bestOffsetByPeer = new HashMap<>();
        Map<Integer, Double> minRttByPeer = new HashMap<>();
        Map<Integer, Double> bestRttByPeer = new HashMap<>();

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

                // Fallback: wenn nichts innerhalb TM, nimm das schnellste Sample (nur für Diagnose/robuste Weiterarbeit)
                if (best == null) {
                    for (OffsetSample s : list) {
                        if (best == null || s.rtt < best.rtt) best = s;
                    }
                }

                if (best != null) {
                    bestOffsetByPeer.put(peer.id(), best.offset);
                    minRttByPeer.put(peer.id(), minRtt);
                    bestRttByPeer.put(peer.id(), best.rtt);
                } else {
                    log("Warnung: keine gültigen Samples von Peer " + peer.id() + " (keine TIME_RESPONSE erhalten?)");
                    sendMonitorEvent("SYNC_PEER_MISSING|peer=" + peer.id());
                }
            }
        }

        // Diagnose: epoch mismatch? (Offsets extrem groß)
        int huge = 0;
        for (Map.Entry<Integer, Double> e : bestOffsetByPeer.entrySet()) {
            if (Math.abs(e.getValue()) > EPOCH_MISMATCH_THRESHOLD_SECONDS) huge++;
        }
        if (huge > 0) {
            epochMismatchRounds++;
            if (epochMismatchRounds % EPOCH_MISMATCH_REPORT_EVERY_N == 0) {
                log("DIAGNOSE: Sehr große Offsets entdeckt (epoch mismatch wahrscheinlich). "
                        + "huge=" + huge + "/" + peers.size()
                        + " threshold=" + EPOCH_MISMATCH_THRESHOLD_SECONDS + "s "
                        + "offsets=" + bestOffsetByPeer);
                sendMonitorEvent("EPOCH_MISMATCH_SUSPECT|huge=" + huge + "|thr=" + EPOCH_MISMATCH_THRESHOLD_SECONDS + "|offsets=" + bestOffsetByPeer);
            } else {
                log("DIAGNOSE: Große Offsets (epoch mismatch wahrscheinlich). huge=" + huge + " offsets=" + bestOffsetByPeer);
            }
        } else {
            epochMismatchRounds = 0;
        }

        // 3) Offsets inkl. Master (0)
        Map<Integer, Double> offsetsInclMaster = new HashMap<>(bestOffsetByPeer);
        offsetsInclMaster.put(info.id(), 0.0);

        // 4) Paper-basierte Bounds (epsilon, gamma)
        double minRttGlobal = minRttByPeer.values().stream().mapToDouble(v -> v).min().orElse(cfg.tmBoundSeconds);
        double epsilon = Math.max(0.0, (cfg.tmBoundSeconds - minRttGlobal) / 2.0);

        double Tsec = cfg.syncIntervalMs / 1000.0;
        double pEst = Math.max(P_BOUND_DEFAULT, Math.abs(clock.getDrift() - 1.0));
        double gammaUse = Math.max(cfg.gammaBaseSeconds, 4.0 * epsilon + 2.0 * pEst * Tsec);

        // 5) Inlier cluster wählen (Tie-breaker: Master-haltiger Cluster bevorzugt)
        Set<Integer> inliers = largestClusterWithinGamma(offsetsInclMaster, gammaUse, info.id());

        // Sicherheitslog (sehr hilfreich bei Debugging)
        log(String.format(Locale.US,
                "DEBUG: gamma=%.6f eps=%.6f minRttGlobal=%.6f drift=%.6f probes=%d tmBound=%.6f offsets=%s inliers=%s bestRtt=%s",
                gammaUse, epsilon, minRttGlobal, clock.getDrift(), cfg.probes, cfg.tmBoundSeconds, bestOffsetByPeer, inliers, bestRttByPeer));

        //  Kritik: Master muss inlier sein, sonst nichts tun
        if (!inliers.contains(info.id())) {
            log("INFO: Master ist außerhalb des Inlier-Clusters -> füge Master kontrolliert hinzu.");
            inliers.add(info.id());
        }

        //  Zu wenig Inlier (nur Master)
        if (inliers.size() < 2) {
            log("Zu wenige Inlier (" + inliers.size() + ") -> überspringe Anpassung. inliers=" + inliers);
            sendMonitorEvent("SYNC_SKIP|reason=too_few_inliers|inliers=" + inliers + "|offsets=" + bestOffsetByPeer + "|gamma=" + gammaUse);
            if (monitor != null) monitor.onSyncEnd(info.id(), 0.0, gammaUse, inliers);
            return;
        }

        double E = average(inliers, offsetsInclMaster);

        // ✅ Master step clamp
        if (Math.abs(E) > MAX_MASTER_STEP_SECONDS) {
            log(String.format(Locale.US, "Master-Offset zu groß (|E|=%.6f s), ignoriere diese Runde.", E));
            sendMonitorEvent("MASTER_OFFSET_IGNORED|E=" + E + "|max=" + MAX_MASTER_STEP_SECONDS + "|inliers=" + inliers);
            if (monitor != null) monitor.onSyncEnd(info.id(), 0.0, gammaUse, inliers);
            return;
        }

        firstSync = false;

        // 6) Master korrigieren
        if (firstAdjustOnThisNode && Math.abs(E) > JOIN_HARD_SET_THRESHOLD_SECONDS) {
            clock.setTime(clock.getTime() + E);
            log(String.format(Locale.US, "Uhr HARD SET %+.6f s (Master initial sync)", E));
            sendMonitorEvent("MASTER_HARD_SET|E=" + E);
        } else {
            clock.adjust(E);
            log(String.format(Locale.US, "Uhr adjust %+.6f s (Master slewing)", E));
            sendMonitorEvent("MASTER_SLEW|E=" + E);
        }
        firstAdjustOnThisNode = false;

        // 7) Slaves: Correction senden
        for (NodeInfo peer : peers) {
            Double eak = bestOffsetByPeer.get(peer.id());
            if (eak == null) continue;

            double corr = E - eak;

            // Clamp bevor wir Müll verschicken
            if (Math.abs(corr) > MAX_SLAVE_STEP_SECONDS) {
                log(String.format(Locale.US, "WARN: Slave-Korrektur zu groß -> clamp. peer=%d corr=%+.6f max=%.6f",
                        peer.id(), corr, MAX_SLAVE_STEP_SECONDS));
                sendMonitorEvent("SLAVE_CORR_CLAMP|peer=" + peer.id() + "|corr=" + corr + "|max=" + MAX_SLAVE_STEP_SECONDS);
                corr = Math.copySign(MAX_SLAVE_STEP_SECONDS, corr);
            }

            send(Message.timeAdjust(info.id(), peer.id(), corr), peer);
        }

        log(String.format(Locale.US,
                "Berkeley-Sync fertig. E=%+.6f s, gamma=%.6f, eps=%.6f, inliers=%s, offsets=%s",
                E, gammaUse, epsilon, inliers, bestOffsetByPeer));

        if (monitor != null) monitor.onSyncEnd(info.id(), E, gammaUse, inliers);
    }

    /**
     * Größtes Cluster innerhalb gamma.
     * Tie-breaker: bei gleicher Größe wird ein Cluster bevorzugt, das den Master enthält.
     * Bei weiterer Gleichheit: kleinerer Spann (tight cluster) gewinnt.
     */
    private Set<Integer> largestClusterWithinGamma(Map<Integer, Double> offsets, double gamma, int masterId) {
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(offsets.entrySet());
        list.sort(Comparator.comparingDouble(Map.Entry::getValue));

        int i = 0, j = 0;

        int bestI = 0, bestJ = -1;
        int bestSize = 0;
        boolean bestContainsMaster = false;
        double bestSpan = Double.POSITIVE_INFINITY;

        while (i < list.size()) {
            while (j + 1 < list.size() && list.get(j + 1).getValue() - list.get(i).getValue() <= gamma) j++;

            int size = j - i + 1;
            boolean containsMaster = false;
            for (int k = i; k <= j; k++) {
                if (list.get(k).getKey() == masterId) { containsMaster = true; break; }
            }
            double span = list.get(j).getValue() - list.get(i).getValue();

            if (size > bestSize
                    || (size == bestSize && containsMaster && !bestContainsMaster)
                    || (size == bestSize && containsMaster == bestContainsMaster && span < bestSpan)) {
                bestSize = size;
                bestContainsMaster = containsMaster;
                bestSpan = span;
                bestI = i;
                bestJ = j;
            }

            i++;
            if (i > j) j = i;
        }

        Set<Integer> ids = new HashSet<>();
        for (int k = bestI; k <= bestJ; k++) ids.add(list.get(k).getKey());
        return ids;
    }

    private double average(Set<Integer> ids, Map<Integer, Double> map) {
        double s = 0.0; int n = 0;
        for (int id : ids) {
            Double v = map.get(id);
            if (v != null) { s += v; n++; }
        }
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
        if (master != null) {
            // Wichtig: msg.time() ist das t1 des Masters (Echo)
            send(Message.timeResponse(info.id(), master.id(), msg.reqId(), t2, msg.time()), master);
        }
    }

    private void onTimeResponse(Message msg) {
        if (!alive || !isMaster) return;

        double t3 = clock.getTime();
        double t2 = msg.time();        // bei TIME_RESPONSE: "time" = t2 (Slave Zeit)
        double t1;

        synchronized (lock) {
            Double t1Obj = pendingT1.remove(msg.reqId());

            // KRITISCH: Kein sinnvolles Fallback! Wenn t1 weg ist, Sample verwerfen.
            if (t1Obj == null) {
                log("WARN: TIME_RESPONSE ohne passendes t1 (reqId=" + msg.reqId() + ") -> Sample wird verworfen.");
                sendMonitorEvent("TIME_RESPONSE_DROPPED|reason=missing_t1|reqId=" + msg.reqId() + "|from=" + msg.senderId());
                return;
            }

            t1 = t1Obj;
            double rtt = t3 - t1;
            double offset = t2 - (t1 + t3) / 2.0;

            samplesByPeer
                    .computeIfAbsent(msg.senderId(), k -> new ArrayList<>())
                    .add(new OffsetSample(offset, rtt));
        }
    }

    private void onTimeAdjust(Message msg) {
        if (!alive || isMaster) return;

        double off = msg.offset();

        if (Math.abs(off) > 10_000) {
            log("WARN: Offset absurd groß, ignoriere: " + off);
            sendMonitorEvent("SLAVE_OFFSET_ABSURD_IGNORED|off=" + off);
            return;
        }

        if (Math.abs(off) > MAX_SLAVE_STEP_SECONDS) {
            log(String.format(Locale.US, "WARN: Slave-Offset clamp. off=%+.6f max=%.6f", off, MAX_SLAVE_STEP_SECONDS));
            sendMonitorEvent("SLAVE_OFFSET_CLAMP|off=" + off + "|max=" + MAX_SLAVE_STEP_SECONDS);
            off = Math.copySign(MAX_SLAVE_STEP_SECONDS, off);
        }

        if (firstAdjustOnThisNode && Math.abs(off) > JOIN_HARD_SET_THRESHOLD_SECONDS) {
            clock.setTime(clock.getTime() + off);
            log(String.format(Locale.US, "Uhr HARD SET %+.6f s (initial sync)", off));
            sendMonitorEvent("SLAVE_HARD_SET|off=" + off);
        } else {
            clock.adjust(off);
            log(String.format(Locale.US, "Uhr adjust %+.6f s (slewing)", off));
            sendMonitorEvent("SLAVE_SLEW|off=" + off);
        }

        firstAdjustOnThisNode = false;
    }

    private void onHeartbeat(Message msg) {
        if (!alive) return;

        long now = System.currentTimeMillis();
        int sender = msg.senderId();

        // === Election sırasında daha yüksek ID geldiyse: DERHAL DEMOTE ===
        if (electionInProgress && sender > info.id()) {
            electionInProgress = false;
            gotOkFromHigher = false;
            isMaster = false;

            currentMasterId = sender;
            lastHeartbeatMs = now;
            electionCooldownUntilMs = now + cfg.graceAfterCoordMs;

            firstAdjustOnThisNode = true;

            notifyMasterIfChanged(currentMasterId, true);
            log("Election abgebrochen: höherer Master per Heartbeat: " + sender);
            sendMonitorEvent("COORDINATOR_ADOPT|master=" + sender + "|via=election_hb");
            return;
        }

        // === Noch kein Master bekannt ===
        if (currentMasterId == -1) {
            currentMasterId = sender;
            lastHeartbeatMs = now;
            electionCooldownUntilMs = now + cfg.graceAfterCoordMs;

            firstAdjustOnThisNode = true;

            notifyMasterIfChanged(currentMasterId, false);
            log("Erster Heartbeat: vermuteter Master ist " + currentMasterId);
            sendMonitorEvent("COORDINATOR_ADOPT|master=" + currentMasterId + "|via=first_hb");
            return;
        }

        // === Heartbeat vom aktuellen Master ===
        if (sender == currentMasterId) {
            lastHeartbeatMs = now;
            electionCooldownUntilMs = now + cfg.graceAfterCoordMs;
            return;
        }

        // === Höhere ID übernimmt Master-Rolle ===
        if (sender > currentMasterId) {
            currentMasterId = sender;
            lastHeartbeatMs = now;
            electionCooldownUntilMs = now + cfg.graceAfterCoordMs;

            isMaster = false;
            electionInProgress = false;
            gotOkFromHigher = false;

            firstAdjustOnThisNode = true;

            notifyMasterIfChanged(currentMasterId, true);
            log("Heartbeat höherer ID akzeptiert, neuer Master: " + currentMasterId);
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
        log("Wahl: OK von höherer ID empfangen: " + msg.senderId());
    }

    private void onCoordinator(Message msg) {
        if (!alive) return;

        if (msg.senderId() < info.id()) {
            if (!isMaster && !electionInProgress) {
                log("COORDINATOR von niedrigerer ID ignoriert -> starte eigene Wahl.");
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

        firstAdjustOnThisNode = true; // neue Epoche

        notifyMasterIfChanged(currentMasterId, false);
        log("Neuer Master ist " + currentMasterId + (iAmCoordinator ? " (ich)" : ""));
        sendMonitorEvent("COORDINATOR_ADOPT|master=" + currentMasterId);
    }

    private void startElection() {
        if (electionInProgress || !running.get() || !alive) return;

        electionInProgress = true;
        gotOkFromHigher = false;
        if (monitor != null) monitor.onElectionStart(info.id());

        log("Starte Wahl (Bully) ...");
        sendMonitorEvent("ELECTION_START|reason=manual_or_timeout");

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

        if (!gotOkFromHigher) {
            // Niemand Höheres reagiert (oder niemand existiert) -> ich werde Master
            becomeMasterAndAnnounce();
            electionInProgress = false;
            return;
        }

        // Höhere ID existiert -> warte auf COORDINATOR
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
            sendMonitorEvent("ELECTION_BACKOFF|ms=" + backoff);
        } else {
            electionInProgress = false;
        }
    }

    private void becomeMasterAndAnnounce() {
        isMaster = true;
        currentMasterId = info.id();
        lastHeartbeatMs = System.currentTimeMillis();
        electionCooldownUntilMs = lastHeartbeatMs + cfg.graceAfterCoordMs;

        firstAdjustOnThisNode = true; // Master-Epoch frisch

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
        System.out.printf(Locale.US, "[Node %d @ %.3f] %s%n", info.id(), clock.getTime(), s);
    }

    private static void interruptSilently(Thread t) {
        if (t != null) {
            try { t.interrupt(); } catch (Exception ignored) {}
        }
    }

    private static void joinSilently(Thread t, long ms) {
        if (t != null) {
            try { t.join(ms); } catch (InterruptedException ignored) {}
        }
    }

    public NodeInfo getInfo() { return info; }
    public double getLocalTime() { return clock.getTime(); }
    public Clock getClock() { return clock; }
    public List<NodeInfo> getPeers() { return peers; }
    public boolean getIsMaster() { return isMaster; }


    private void sendMonitorEvent(String payload) {
        if (monitorTransport == null || monitorInfo == null) return;

        try {
            monitorTransport.send(
                    Message.monitorEvent(info.id(), payload),
                    monitorInfo
            );
        } catch (Exception e) {
            System.err.println("[Node " + info.id() + "] Monitor send error: " + e.getMessage());
        }
    }
}
