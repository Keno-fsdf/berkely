package de.thws;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class MonitorServer implements Runnable {

    private final int port;
    private final SimulationMonitor mon;
    private volatile boolean running = true;
    private int soTimeoutMs = 500;

    public MonitorServer(int port, SimulationMonitor mon) {
        this.port = port;
        this.mon = mon;
    }

    public void setSoTimeoutMs(int soTimeoutMs) { this.soTimeoutMs = soTimeoutMs; }
    public void stop() { running = false; }

    private static Map<String, String> parseKV(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isBlank()) return m;
        for (String p : s.split(";")) {
            if (p.isBlank()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0 || eq == p.length() - 1) continue;
            String k = p.substring(0, eq).trim();
            String v = p.substring(eq + 1).trim();
            if (!k.isEmpty()) m.put(k, v);
        }
        return m;
    }

    private static List<Integer> parseIdList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(Integer::parseInt).collect(Collectors.toList());
    }

    @Override public void run() {
        try (DatagramSocket sock = new DatagramSocket(port)) {
            sock.setSoTimeout(soTimeoutMs);
            byte[] buf = new byte[4096];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException ste) {
                    continue;
                } catch (Exception e) {
                    if (!running) break;
                    continue;
                }
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                int bar = msg.indexOf('|'); if (bar < 0) continue;
                String type = msg.substring(0, bar).trim();
                Map<String, String> kv = parseKV(msg.substring(bar + 1));
                try {
                    switch (type) {
                        case "SimulationStart" -> mon.onSimulationStart(
                                Integer.parseInt(kv.get("nodes")),
                                Double.parseDouble(kv.get("loss")),
                                Boolean.parseBoolean(kv.get("rf")),
                                Double.parseDouble(kv.get("fr")),
                                Double.parseDouble(kv.get("pp")),
                                Long.parseLong(kv.get("dur")));
                        case "NodeStart" -> mon.onNodeStart(
                                Integer.parseInt(kv.get("id")),
                                Double.parseDouble(kv.get("t")),
                                Double.parseDouble(kv.get("drift")));
                        case "NodeStop" -> mon.onNodeStop(
                                Integer.parseInt(kv.get("id")),
                                Boolean.parseBoolean(kv.get("perm")));
                        case "FailStopStart" -> mon.onFailStopStart(
                                Integer.parseInt(kv.get("id")),
                                Long.parseLong(kv.get("ms")));
                        case "FailStopEnd" -> mon.onFailStopEnd(Integer.parseInt(kv.get("id")));
                        case "MasterElected" -> mon.onMasterElected(Integer.parseInt(kv.get("id")));
                        case "Demotion" -> mon.onDemotedTo(Integer.parseInt(kv.get("newMaster")));
                        case "ElectionStart" -> mon.onElectionStart(Integer.parseInt(kv.get("id")));
                        case "CoordAnnounce" -> mon.onCoordinatorAnnounce(Integer.parseInt(kv.get("id")));
                        case "SyncStart" -> mon.onSyncStart(Integer.parseInt(kv.get("m")));
                        case "SyncEnd" -> {
                            int mId = Integer.parseInt(kv.get("m"));
                            double E = Double.parseDouble(kv.get("E"));
                            double g = Double.parseDouble(kv.get("g"));
                            List<Integer> inliers = parseIdList(kv.getOrDefault("in", ""));
                            mon.onSyncEnd(mId, E, g, inliers);
                        }
                        case "Time" -> mon.updateNodeTime(
                                Integer.parseInt(kv.get("id")), Double.parseDouble(kv.get("t")));
                        case "Drift" -> mon.updateNodeDrift(
                                Integer.parseInt(kv.get("id")), Double.parseDouble(kv.get("d")));
                        case "MsgSend" -> {
                            Message.Type tt = Message.Type.valueOf(kv.get("t"));
                            int s = Integer.parseInt(kv.get("s"));
                            int d = Integer.parseInt(kv.get("d"));
                            mon.onMessageAttempt(new Message(s,d,tt,0,0.0,0.0));
                        }
                        case "MsgLost" -> {
                            Message.Type tt = Message.Type.valueOf(kv.get("t"));
                            int s = Integer.parseInt(kv.get("s"));
                            int d = Integer.parseInt(kv.get("d"));
                            mon.onMessageLost(new Message(s,d,tt,0,0.0,0.0));
                        }
                        case "MsgDelivered" -> {
                            Message.Type tt = Message.Type.valueOf(kv.get("t"));
                            int s = Integer.parseInt(kv.get("s"));
                            int d = Integer.parseInt(kv.get("d"));
                            mon.onMessageDelivered(new Message(s,d,tt,0,0.0,0.0));
                        }
                        default -> {}
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }
}