package de.thws;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SimulationMonitor {

    public enum Verbosity {
        SUMMARY,  // Dashboard: kompakt (keine Einzel-MSG-LOST Events)
        DETAIL,   // Dashboard: zeigt alle Events
        DEBUG     // wie DETAIL; Platzhalter
    }

    public static class NodeState {
        public final int id;
        public volatile boolean alive = true;
        public volatile boolean dead = false;
        public volatile boolean master = false;
        public volatile double time = 0.0;
        public volatile double drift = 1.0;
        public NodeState(int id) { this.id = id; }
    }

    // Konfiguration
    private final Verbosity verbosity;
    private final boolean showLossInDashboard;
    private final boolean renderMasterChangeDashboard;
    private final int recentCapacity;
    private final int rawMax;

    // Zustände
    private final Map<Integer, NodeState> nodes = new ConcurrentHashMap<>();
    private final EnumMap<Message.Type, AtomicLong> sentTotal = new EnumMap<>(Message.Type.class);
    private final EnumMap<Message.Type, AtomicLong> lostTotal = new EnumMap<>(Message.Type.class);
    private final EnumMap<Message.Type, AtomicLong> sentDelta = new EnumMap<>(Message.Type.class);
    private final EnumMap<Message.Type, AtomicLong> lostDelta = new EnumMap<>(Message.Type.class);

    private final Deque<String> recentEvents = new ArrayDeque<>();
    private final List<String> rawEvents = Collections.synchronizedList(new ArrayList<>());

    private volatile int currentMasterId = -1;
    private volatile String lastSyncSummary = "—";
    private volatile int syncRound = 0;

    // Für Event-Komprimierung (nur Dashboard)
    private String lastEvent = null;
    private int lastEventCount = 0;

    // Konstruktoren
    public SimulationMonitor(Collection<Integer> nodeIds) {
        this(nodeIds, Verbosity.SUMMARY, false, false, 15, 10_000);
    }

    public SimulationMonitor(Collection<Integer> nodeIds,
                             Verbosity verbosity,
                             boolean showLossInDashboard,
                             boolean renderMasterChangeDashboard,
                             int recentCapacity) {
        this(nodeIds, verbosity, showLossInDashboard, renderMasterChangeDashboard, recentCapacity, 10_000);
    }

    public SimulationMonitor(Collection<Integer> nodeIds,
                             Verbosity verbosity,
                             boolean showLossInDashboard,
                             boolean renderMasterChangeDashboard,
                             int recentCapacity,
                             int rawMax) {
        for (int id : nodeIds) nodes.put(id, new NodeState(id));
        for (Message.Type t : Message.Type.values()) {
            sentTotal.put(t, new AtomicLong());
            lostTotal.put(t, new AtomicLong());
            sentDelta.put(t, new AtomicLong());
            lostDelta.put(t, new AtomicLong());
        }
        this.verbosity = verbosity;
        this.showLossInDashboard = showLossInDashboard;
        this.renderMasterChangeDashboard = renderMasterChangeDashboard;
        this.recentCapacity = recentCapacity;
        this.rawMax = rawMax;
    }

    public void shutdown() {}

    // Simulation-Metadaten
    public void onSimulationStart(int nodeCount, double loss, boolean randomFail, double failRate, double permProb, long durationMs) {
        logRaw("SimulationStart", "nodes=%d loss=%.2f randomFail=%b failRate=%.3f/s permProb=%.2f duration=%ds"
                .formatted(nodeCount, loss, randomFail, failRate, permProb, durationMs/1000));
        addEvent("Simulation start: nodes=%d, loss=%.2f, randomFail=%b, failRate=%.3f/s, permProb=%.2f, duration=%ds"
                .formatted(nodeCount, loss, randomFail, failRate, permProb, durationMs/1000), false);
    }

    // Node-API
    public void onNodeStart(int id, double initTime, double drift) {
        NodeState ns = nodes.get(id);
        if (ns == null) { ns = new NodeState(id); nodes.put(id, ns); }
        ns.time = initTime; ns.drift = drift; ns.alive = true; ns.dead = false; ns.master = false;
        logRaw("NodeStart", "id=%d t=%.3f drift=%.5f".formatted(id, initTime, drift));
        addEvent("Node %d started (t=%.3f, drift=%.5f)".formatted(id, initTime, drift), true);
    }

    public void onNodeStop(int id, boolean permanent) {
        NodeState ns = nodes.get(id);
        if (ns != null) {
            ns.alive = false;
            ns.dead = permanent;
            ns.master = false;
        }
        logRaw("NodeStop", "id=%d permanent=%b".formatted(id, permanent));
        addEvent("Node %d stopped%s".formatted(id, permanent ? " (PERM)" : ""), true);
    }

    public void onFailStopStart(int id, long downtimeMs) {
        NodeState ns = nodes.get(id);
        if (ns != null) ns.alive = false;
        logRaw("FailStopStart", "id=%d ms=%d".formatted(id, downtimeMs));
        addEvent("Node %d FAIL-STOP (%.0f ms)".formatted(id, (double) downtimeMs), true);
    }

    public void onFailStopEnd(int id) {
        NodeState ns = nodes.get(id);
        if (ns != null) ns.alive = true;
        logRaw("FailStopEnd", "id=%d".formatted(id));
        addEvent("Node %d RETURN from fail-stop".formatted(id), true);
    }

    public void onMasterElected(int id) {
        if (id == currentMasterId) return; // dedupe
        currentMasterId = id;
        nodes.values().forEach(n -> n.master = (n.id == id));
        logRaw("MasterElected", "id=%d".formatted(id));
        addEvent("Master elected: Node %d".formatted(id), true);
        if (renderMasterChangeDashboard) {
            renderSnapshot("Master change");
        }
    }

    public void onDemotedTo(int newMasterId) {
        if (newMasterId == currentMasterId) return; // dedupe
        currentMasterId = newMasterId;
        nodes.values().forEach(n -> n.master = (n.id == newMasterId));
        logRaw("Demotion", "newMaster=%d".formatted(newMasterId));
        addEvent("Demotion by HB: new master %d".formatted(newMasterId), true);
    }

    public void onElectionStart(int id) {
        logRaw("ElectionStart", "node=%d".formatted(id));
        addEvent("Node %d starts election".formatted(id), true);
    }

    public void onCoordinatorAnnounce(int id) {
        logRaw("CoordinatorAnnounce", "by=%d".formatted(id));
        addEvent("Node %d announces COORDINATOR".formatted(id), true);
    }

    public void onSyncStart(int masterId) {
        logRaw("SyncStart", "master=%d".formatted(masterId));
        addEvent("Master %d starts Berkeley round".formatted(masterId), true);
    }

    public void onSyncEnd(int masterId, double E, double gamma, Collection<Integer> inliers) {
        syncRound++;
        String s = "Sync: E=%+.6f, γ=%.6f, inliers=%s (M=%d)".formatted(E, gamma, inliers, masterId);
        lastSyncSummary = s;
        logRaw("SyncEnd", "round=%d master=%d E=%.6f gamma=%.6f inliers=%s"
                .formatted(syncRound, masterId, E, gamma, inliers.toString()));
        addEvent(s, true);
        renderSnapshot("After Sync #" + syncRound);
    }

    public void updateNodeTime(int id, double time) {
        NodeState ns = nodes.get(id);
        if (ns != null) ns.time = time;
    }

    public void updateNodeDrift(int id, double drift) {
        NodeState ns = nodes.get(id);
        if (ns != null) ns.drift = drift;
    }

    // Message-API
    public void onMessageAttempt(Message msg) {
        // MONITOR_EVENT gibi şeyleri burada saymak istemiyorsanız, isterseniz filter ekleriz.
        sentTotal.get(msg.type()).incrementAndGet();
        sentDelta.get(msg.type()).incrementAndGet();
        logRaw("MsgSend", "%s %d->%d".formatted(msg.type(), msg.senderId(), msg.targetId()));
    }

    public void onMessageLost(Message msg) {
        lostTotal.get(msg.type()).incrementAndGet();
        lostDelta.get(msg.type()).incrementAndGet();
        logRaw("MsgLost", "%s %d->%d".formatted(msg.type(), msg.senderId(), msg.targetId()));
        if (verbosity != Verbosity.SUMMARY || showLossInDashboard) {
            addEvent("MSG LOST: %s %d->%d".formatted(msg.type(), msg.senderId(), msg.targetId()), true);
        }
    }

    public void onMessageDelivered(Message msg) {
        logRaw("MsgDelivered", "%s %d->%d".formatted(msg.type(), msg.senderId(), msg.targetId()));
    }

    public List<String> getRawEvents(int lastN) {
        synchronized (rawEvents) {
            int from = Math.max(0, rawEvents.size() - lastN);
            return new ArrayList<>(rawEvents.subList(from, rawEvents.size()));
        }
    }

    // Rendering
    private void renderSnapshot(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===================== DASHBOARD @ ").append(LocalTime.now()).append(" =====================\n");
        sb.append("Master: ").append(currentMasterId == -1 ? "—" : currentMasterId)
                .append(" | Last Sync: ").append(lastSyncSummary)
                .append(" | ").append(title).append("\n");

        double masterTime = (currentMasterId != -1 && nodes.containsKey(currentMasterId)) ? nodes.get(currentMasterId).time : 0.0;
        List<Double> diffs = new ArrayList<>();
        for (NodeState n : nodes.values()) if (!n.dead && n.alive) diffs.add(Math.abs(n.time - masterTime));
        double maxSkew = diffs.stream().mapToDouble(d -> d).max().orElse(0.0);
        double mean = diffs.stream().mapToDouble(d -> d).average().orElse(0.0);
        double var = diffs.stream().mapToDouble(d -> (d - mean)*(d - mean)).average().orElse(0.0);
        double stddev = Math.sqrt(var);
        sb.append("Skew: max=").append(String.format("%.3f", maxSkew))
                .append("s, σ=").append(String.format("%.3f", stddev)).append("s\n");

        sb.append("Nodes:\n");
        sb.append(" ID | State  | M |     Time(s) |  Drift  \n");
        sb.append("----+--------+---+-------------+---------\n");
        nodes.values().stream().sorted(Comparator.comparingInt(n -> n.id)).forEach(n -> {
            String state = n.dead ? "DEAD" : (n.alive ? "UP" : "DOWN");
            sb.append(String.format(" %2d | %-6s | %s | %11.3f | %7.5f\n",
                    n.id, state, (n.master ? "*" : " "), n.time, n.drift));
        });

        sb.append("\nMessages (Δ/total):\n");
        for (Message.Type t : Message.Type.values()) {
            long sD = sentDelta.get(t).getAndSet(0);
            long lD = lostDelta.get(t).getAndSet(0);
            long sT = sentTotal.get(t).get();
            long lT = lostTotal.get(t).get();
            sb.append(String.format(" %-18s sent %5d/%-5d  lost %4d/%-4d\n", t, sD, sT, lD, lT));
        }

        sb.append("\nRecent events:\n");
        synchronized (recentEvents) {
            for (String e : recentEvents) sb.append(" - ").append(e).append("\n");
        }
        sb.append("=====================================================================\n");
        System.out.print(sb);
    }

    private void addEvent(String e, boolean eligibleForDashboard) {
        logRaw("Event", e);

        if (!eligibleForDashboard) return;

        if (verbosity == Verbosity.SUMMARY && e.startsWith("MSG LOST")) {
            return;
        }

        synchronized (recentEvents) {
            if (recentEvents.isEmpty() || lastEvent == null || !lastEvent.equals(e)) {
                if (lastEventCount > 1) {
                    recentEvents.removeLast();
                    recentEvents.addLast(lastEvent + " × " + lastEventCount);
                }
                recentEvents.addLast(e);
                lastEvent = e;
                lastEventCount = 1;
                while (recentEvents.size() > recentCapacity) recentEvents.removeFirst();
            } else {
                lastEventCount++;
                recentEvents.removeLast();
                recentEvents.addLast(lastEvent + " × " + lastEventCount);
            }
        }
    }

    private void logRaw(String type, String payload) {
        String line = LocalTime.now() + " [" + type + "] " + payload;
        synchronized (rawEvents) {
            rawEvents.add(line);
            if (rawEvents.size() > rawMax) {
                int cut = Math.max(1, rawMax / 10);
                for (int i = 0; i < cut; i++) rawEvents.remove(0);
            }
        }
    }

    public int getCurrentMasterId() { return currentMasterId; }

    /* =========================================================
       === NEW: Distributed monitor entrypoint (used by MonitorMain)
       === payload format: "TYPE|k=v|k=v|..."
       ========================================================= */
    public void onExternalEvent(int senderId, String payload) {
        // örnek: "NODE_START|time=123.0|drift=0.97"
        //        "MASTER_ELECTED|id=5"
        //        "SYNC_END|master=5|E=-1.2|gamma=0.02|inliers=1,2,5"
        try {
            String[] parts = payload.split("\\|");
            String type = parts[0].trim();

            Map<String, String> kv = new HashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String[] p = parts[i].split("=", 2);
                if (p.length == 2) kv.put(p[0].trim(), p[1].trim());
            }

            switch (type) {
                case "NODE_START" -> {
                    double t = parseDouble(kv.get("time"), 0.0);
                    double d = parseDouble(kv.get("drift"), 1.0);
                    onNodeStart(senderId, t, d);
                }
                case "NODE_STOP" -> onNodeStop(senderId, false);
                case "MASTER_ELECTED" -> onMasterElected((int) parseDouble(kv.get("id"), senderId));
                case "ELECTION_START" -> onElectionStart(senderId);
                case "COORDINATOR_ANNOUNCE" -> onCoordinatorAnnounce(senderId);
                case "SYNC_START" -> onSyncStart(senderId);
                case "SYNC_END" -> {
                    int master = (int) parseDouble(kv.get("master"), senderId);
                    double E = parseDouble(kv.get("E"), 0.0);
                    double gamma = parseDouble(kv.get("gamma"), 0.0);
                    Set<Integer> inliers = parseInliers(kv.get("inliers"));
                    onSyncEnd(master, E, gamma, inliers);
                }
                case "TIME" -> {
                    double t = parseDouble(kv.get("t"), 0.0);
                    updateNodeTime(senderId, t);
                }
                default -> {
                    // bilinmeyen event: en azından dashboard’a düşsün
                    addEvent("External: N" + senderId + " -> " + payload, true);
                }
            }
        } catch (Exception e) {
            addEvent("External parse error from N" + senderId + ": " + payload, true);
        }
    }

    private static double parseDouble(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private static Set<Integer> parseInliers(String s) {
        Set<Integer> set = new HashSet<>();
        if (s == null || s.isBlank()) return set;
        // "1,2,5" formatı
        String[] parts = s.split(",");
        for (String p : parts) {
            try { set.add(Integer.parseInt(p.trim())); } catch (Exception ignored) {}
        }
        return set;
    }
}
