package de.thws;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SimulationMonitor implements MonitorSink {

    public enum Verbosity { SUMMARY, DETAIL }

    public static class NodeState {
        public final int id;
        public volatile boolean alive = true;
        public volatile boolean master = false;
        public volatile double time = 0.0;
        public volatile double drift = 1.0;

        public NodeState(int id) { this.id = id; }
    }

    private final Verbosity verbosity;
    private final int recentCapacity;

    private final Map<Integer, NodeState> nodes = new ConcurrentHashMap<>();
    private final EnumMap<Message.Type, AtomicLong> sentTotal = new EnumMap<>(Message.Type.class);
    private final EnumMap<Message.Type, AtomicLong> lostTotal = new EnumMap<>(Message.Type.class);
    private final EnumMap<Message.Type, AtomicLong> sentDelta = new EnumMap<>(Message.Type.class);
    private final EnumMap<Message.Type, AtomicLong> lostDelta = new EnumMap<>(Message.Type.class);

    private final Deque<String> recentEvents = new ArrayDeque<>();

    // Master state
    private volatile int currentMaster = -1;
    private volatile String lastSync = "—";
    private volatile int syncRound = 0;

    public SimulationMonitor(Collection<Integer> nodeIds, Verbosity verbosity, int recentCapacity) {
        for (int id : nodeIds) nodes.put(id, new NodeState(id));
        for (Message.Type t : Message.Type.values()) {
            sentTotal.put(t, new AtomicLong());
            lostTotal.put(t, new AtomicLong());
            sentDelta.put(t, new AtomicLong());
            lostDelta.put(t, new AtomicLong());
        }
        this.verbosity = verbosity;
        this.recentCapacity = recentCapacity;
    }

    /* =====================================================
       === RICHTIGER REMOTE ENTRYPOINT (Message-basiert)
       ===================================================== */
    public void onExternalMessage(Message msg) {
        int senderId = msg.senderId();
        String payload = msg.payload();

        addEvent("RX MONITOR_EVENT von N" + senderId +
                " | type=" + msg.type() +
                " | payload='" + payload + "'");

        if (payload == null || payload.isBlank()) {
            addEvent("WARN: Leeres Payload von N" + senderId);
            renderDashboard();
            return;
        }

        handlePayload(senderId, payload);
        renderDashboard();
    }

    /* =====================================================
       === LEGACY: falls irgendwo noch String benutzt wird
       ===================================================== */
    public void onExternalEvent(int senderId, String payload) {
        addEvent("WARN: onExternalEvent(String) benutzt! payload='" + payload + "'");
        handlePayload(senderId, payload);
        renderDashboard();
    }

    public int getCurrentMasterId() {
        return currentMaster;
    }

    /* =====================================================
       === ZENTRALE PAYLOAD-VERARBEITUNG
       ===================================================== */
    private void handlePayload(int senderId, String payload) {
        try {
            String[] parts = payload.split("\\|");
            String type = parts[0].trim();

            Map<String, String> kv = new HashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String[] p = parts[i].split("=", 2);
                if (p.length == 2) kv.put(p[0].trim(), p[1].trim());
            }

            switch (type) {

                case "MASTER_ELECTED" -> {
                    int id = Integer.parseInt(kv.getOrDefault("id", String.valueOf(senderId)));
                    setMaster(id, "MASTER_ELECTED");
                }

                case "SYNC_START" -> {
                    int m = Integer.parseInt(kv.getOrDefault("master", String.valueOf(senderId)));
                    setMaster(m, "SYNC_START");
                    onSyncStart(m);
                }

                case "SYNC_END" -> {
                    int m = Integer.parseInt(kv.getOrDefault("master", String.valueOf(senderId)));
                    double E = parseDouble(kv.get("E"), 0.0);
                    double g = parseDouble(kv.getOrDefault("gamma", "0.0"), 0.0);
                    Set<Integer> in = parseIdSet(kv.get("inliers"));
                    setMaster(m, "SYNC_END");
                    onSyncEnd(m, E, g, in);
                }

                case "SYNC_SKIP" -> {
                    String reason = kv.getOrDefault("reason", "unknown");
                    String inliers = kv.getOrDefault("inliers", "");
                    String offsets = kv.getOrDefault("offsets", "");
                    double g = parseDouble(kv.getOrDefault("gamma", "0.0"), 0.0);
                    onSyncSkip(senderId, reason, inliers, offsets, g);
                }

                case "MASTER_SLEW" ->
                        addEvent("MASTER_SLEW von N" + senderId + " E=" + fmt(parseDouble(kv.get("E"), 0.0)));

                case "SLAVE_SLEW" ->
                        onSlaveSlew(senderId, parseDouble(kv.get("off"), 0.0));

                case "TIME" ->
                        updateNodeTime(senderId, parseDouble(kv.get("t"), 0.0));

                case "DRIFT" ->
                        updateNodeDrift(senderId, parseDouble(kv.get("d"), 1.0));


                case "DEMOTED" -> {
                    addEvent("Node " + senderId + " wurde degradiert");
                    String nm = kv.get("newMaster");
                    if (nm != null) {
                        try { setMaster(Integer.parseInt(nm.trim()), "DEMOTED"); } catch (Exception ignored) {}
                    }
                }

                case "COORDINATOR_ADOPT" -> {
                    // Bu eventi Node tarafı zaten atıyordu. Masterı direkt buradan düzeltmek iyi olur.
                    String m = kv.get("master");
                    if (m != null) {
                        try { setMaster(Integer.parseInt(m.trim()), "COORDINATOR_ADOPT"); }
                        catch (Exception e) { addEvent("PARSE ERROR COORDINATOR_ADOPT: " + payload); }
                    } else {
                        addEvent("COORDINATOR_ADOPT ohne master=? payload=" + payload);
                    }
                }

                case "ELECTION_OK_RX" ->
                        addEvent("Election OK von Node " + senderId);

                case "EPOCH_MISMATCH_SUSPECT" ->
                        addEvent("Epoch-Mismatch vermutet bei Node " + senderId);

                case "MASTER_OFFSET_IGNORED" ->
                        addEvent("Master-Offset ignoriert (Node " + senderId + ")");

                default ->
                        addEvent("UNBEKANNTES EVENT von N" + senderId + ": " + payload);
            }

        } catch (Exception e) {
            addEvent("PARSE ERROR von N" + senderId + ": " + payload);
            addEvent("ERROR: " + e.getMessage());
        }
    }

    /* ======================
       === MonitorSink impl
       ====================== */

    @Override
    public void onMasterElected(int id) {
        setMaster(id, "onMasterElected()");
        addEvent("MASTER_ELECTED N" + id);
    }

    @Override
    public void onSyncStart(int masterId) {
        addEvent("SYNC_START M=" + masterId);
    }

    @Override
    public void onSyncEnd(int masterId, double E, double gamma, Collection<Integer> inliers) {
        syncRound++;
        lastSync = "Sync#" + syncRound + " E=" + fmt(E) + " gamma=" + fmt(gamma);
        addEvent("SYNC_END M=" + masterId + " E=" + fmt(E) + " gamma=" + fmt(gamma) + " inliers=" + inliers);
    }

    @Override
    public void onSyncSkip(int masterId, String reason, String inliers, String offsets, double gamma) {
        addEvent("SYNC_SKIP M=" + masterId + " reason=" + reason + " gamma=" + fmt(gamma));
        if (verbosity == Verbosity.DETAIL && offsets != null && !offsets.isBlank()) {
            addEvent("  offsets=" + offsets);
        }
    }

    @Override
    public void onMasterSlew(int masterId, double E) {
        addEvent("MASTER_SLEW M=" + masterId + " E=" + fmt(E));
    }

    @Override
    public void onSlaveSlew(int nodeId, double off) {
        addEvent("SLAVE_SLEW N" + nodeId + " off=" + fmt(off));
    }

    @Override
    public void updateNodeTime(int id, double time) {
        nodes.computeIfAbsent(id, NodeState::new).time = time;
    }

    @Override
    public void updateNodeDrift(int id, double drift) {
        nodes.computeIfAbsent(id, NodeState::new).drift = drift;
    }

    @Override
    public void onMessageAttempt(Message msg) {
        sentTotal.get(msg.type()).incrementAndGet();
        sentDelta.get(msg.type()).incrementAndGet();
    }

    @Override
    public void onMessageLost(Message msg) {
        lostTotal.get(msg.type()).incrementAndGet();
        lostDelta.get(msg.type()).incrementAndGet();
    }

    @Override public void onMessageDelivered(Message msg) {}

    /**
     * Tek doğru master-set noktası.
     * currentMaster + node.master flag'lerini burada güncelliyoruz.
     */
    private void setMaster(int id, String via) {
        if (id <= 0) return; // 0/-1 gibi saçmalıkları yok say
        if (currentMaster == id) return;

        currentMaster = id;

        // node state'i garantiye al
        nodes.computeIfAbsent(id, NodeState::new);

        // master flag'lerini güncelle
        nodes.values().forEach(n -> n.master = (n.id == id));

        addEvent("MASTER SET -> " + id + " via " + via);
    }

    /* ======================
       === Dashboard
       ====================== */

    private void addEvent(String e) {
        if (recentEvents.size() >= recentCapacity) recentEvents.removeFirst();
        recentEvents.addLast(e);
    }

    private void renderDashboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===================== DASHBOARD @ ").append(LocalTime.now()).append(" =====================\n");
        sb.append("Master: ").append(currentMaster <= 0 ? "—" : currentMaster).append("\n");
        sb.append("Last Sync: ").append(lastSync).append("\n\n");

        sb.append("Nodes:\n");
        sb.append(" ID | State | M |        Time |   Drift\n");
        sb.append("----+-------+---+-------------+---------\n");

        nodes.values().stream()
                .sorted(Comparator.comparingInt(n -> n.id))
                .forEach(n -> sb.append(String.format(
                        " %2d | %-5s | %s | %11.3f | %7.5f\n",
                        n.id, n.alive ? "UP" : "DOWN", n.master ? "*" : " ", n.time, n.drift
                )));

        sb.append("\nRecent events:\n");
        for (String e : recentEvents) sb.append(" - ").append(e).append("\n");
        sb.append("=====================================================================\n");
        System.out.print(sb);
    }

    private static double parseDouble(String s, double def) {
        try { return s == null ? def : Double.parseDouble(s); }
        catch (Exception e) { return def; }
    }

    private static Set<Integer> parseIdSet(String s) {
        if (s == null || s.isBlank()) return Set.of();
        String cleaned = s.replace("[", "").replace("]", "");
        Set<Integer> set = new HashSet<>();
        for (String p : cleaned.split(",")) {
            try { set.add(Integer.parseInt(p.trim())); } catch (Exception ignored) {}
        }
        return set;
    }

    private static String fmt(double v) { return String.format("%.6f", v); }
}
