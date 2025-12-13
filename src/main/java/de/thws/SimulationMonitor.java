package de.thws;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
public class SimulationMonitor {

    public enum Verbosity {
        SUMMARY,  // Dashboard: kompakt (keine Einzel-MSG-LOST Events)
        DETAIL,   // Dashboard: zeigt alle Events
        DEBUG     // wie DETAIL; Platzhalter für zusätzliche Debug-Ausgaben -->Noch nicht implementiert.
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
    private final Verbosity verbosity; // SUMMARY = kompakt; DETAIL/DEBUG = alle Events (DEBUG reserviert für extra zeugs beim debuggen, jetzt gerade macht das
    // kein Unterschied zu Detail ).
    private final boolean showLossInDashboard; // Ohne diesen Schalter erscheinen Verluste in SUMMARY nur in den Zählern (Δ/total), nicht als Einzellinien.
    //Zeigt einzelne “MSG LOST …”-Zeilen im Dashboard an (selbst in SUMMARY) -->wenn true.
    private final boolean renderMasterChangeDashboard; // Rendert bei jedem Master-Wechsel sofort einen vollständigen Dashboard-Snapshot (Zwischenstand).
    private final int recentCapacity; // Anzahl der Einträge im “Recent events”-Bereich (Ringpuffer). Ältere Einträge werden entfernt; gleiche Events werden zusammengefasst (“× n”).

    private final int rawMax; // Obergrenze für gespeicherte Rohlog-Zeilen (rawEvents). Bei Überschreitung werden die ältesten ~10% verworfen, um Speicher zu sparen.

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
        // KEIN Start-Dashboard (nur Info)
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
        sentTotal.get(msg.type()).incrementAndGet();
        sentDelta.get(msg.type()).incrementAndGet();
        logRaw("MsgSend", "%s %d->%d".formatted(msg.type(), msg.senderId(), msg.targetId()));
    }

    public void onMessageLost(Message msg) {
        lostTotal.get(msg.type()).incrementAndGet();
        lostDelta.get(msg.type()).incrementAndGet();
        logRaw("MsgLost", "%s %d->%d".formatted(msg.type(), msg.senderId(), msg.targetId()));
        // ins Dashboard nur, wenn gewünscht (beeinflusst nicht das Rohlog)
        if (verbosity != Verbosity.SUMMARY || showLossInDashboard) {
            addEvent("MSG LOST: %s %d->%d".formatted(msg.type(), msg.senderId(), msg.targetId()), true);
        }
    }

    public void onMessageDelivered(Message msg) {
        logRaw("MsgDelivered", "%s %d->%d".formatted(msg.type(), msg.senderId(), msg.targetId()));
    }

    // Rohlog API (optional auslesbar)
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

        // zusätzliche Metriken: Skew (max, σ)
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

    // Dashboard-Event-Puffer + Komprimierung; Rohlog bleibt unverändert
    private void addEvent(String e, boolean eligibleForDashboard) {
        // Rohlog: immer
        logRaw("Event", e);

        if (!eligibleForDashboard) return;

        // Dashboard: je nach Verbosity ggf. filtern (keine MSG LOST in SUMMARY)
        if (verbosity == Verbosity.SUMMARY && e.startsWith("MSG LOST")) {
            return;
        }

        synchronized (recentEvents) {
            // Komprimiere nur gleiche Linie nacheinander
            if (recentEvents.isEmpty() || lastEvent == null || !lastEvent.equals(e)) {
                // commit vorherigen Counter
                if (lastEventCount > 1) {
                    recentEvents.removeLast();
                    recentEvents.addLast(lastEvent + " × " + lastEventCount);
                }
                // neues Event
                recentEvents.addLast(e);
                lastEvent = e;
                lastEventCount = 1;
                // begrenzen
                while (recentEvents.size() > recentCapacity) recentEvents.removeFirst();
            } else {
                // gleiches Event wie zuvor – live zusammenfassen
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
                // einfache Begrenzung (entferne die ältesten 10%)
                int cut = Math.max(1, rawMax / 10);
                for (int i = 0; i < cut; i++) rawEvents.remove(0);
            }
        }
    }

    public int getCurrentMasterId() { return currentMasterId; }
}