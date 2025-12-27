// RemoteMonitorClient.java
package de.thws;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.stream.Collectors;

public class RemoteMonitorClient implements MonitorSink {

    private final String host;
    private final int port;

    public RemoteMonitorClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Wire format (UTF-8 text over UDP):
     *   "<senderId>|<payload>"
     *
     * payload examples:
     *   "NODE_START|time=123.0|drift=1.02"
     *   "ELECTION_START|reason=missed_hb|missed=2"
     *   "MASTER_ELECTED|id=5|via=self_promo"
     *   "SYNC_SKIP|reason=too_few_inliers|inliers=[5]|offsets={...}|gamma=0.43"
     */
    private void send(int senderId, String payload) {
        try (DatagramSocket s = new DatagramSocket()) {
            String line = senderId + "|" + payload;
            byte[] data = line.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(
                    data, data.length, InetAddress.getByName(host), port
            );
            s.send(p);
        } catch (Exception ignored) {}
    }

    private static String joinIds(Collection<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @Override public void onNodeStart(int id, double initTime, double drift) {
        send(id, "NODE_START|time=" + initTime + "|drift=" + drift);
    }

    @Override public void onNodeStop(int id, boolean permanent) {
        send(id, "NODE_STOP|perm=" + permanent);
    }

    @Override public void onFailStopStart(int id, long downtimeMs) {
        send(id, "FAIL_STOP_START|ms=" + downtimeMs);
    }

    @Override public void onFailStopEnd(int id) {
        send(id, "FAIL_STOP_END");
    }

    @Override public void onMasterElected(int id) {
        send(id, "MASTER_ELECTED|id=" + id + "|via=self_promo");
    }

    @Override public void onDemotedTo(int newMasterId) {
        // senderId unknown here, keep senderId=-1
        send(-1, "DEMOTED|newMaster=" + newMasterId);
    }

    @Override public void onElectionStart(int id) {
        send(id, "ELECTION_START|reason=manual_or_timeout");
    }

    @Override public void onCoordinatorAnnounce(int id) {
        send(id, "COORDINATOR_ANNOUNCE|id=" + id);
    }

    @Override public void onSyncStart(int masterId) {
        send(masterId, "SYNC_START|master=" + masterId);
    }

    @Override public void onSyncEnd(int masterId, double E, double gamma, Collection<Integer> inliers) {
        send(masterId, "SYNC_END|master=" + masterId + "|E=" + E + "|gamma=" + gamma + "|inliers=" + joinIds(inliers));
    }

    @Override public void onSyncSkip(int masterId, String reason, String inliers, String offsets, double gamma) {
        send(masterId, "SYNC_SKIP|reason=" + reason + "|inliers=" + inliers + "|offsets=" + offsets + "|gamma=" + gamma);
    }

    @Override public void onEpochMismatchSuspect(int masterId, int huge, double thr, String offsets) {
        send(masterId, "EPOCH_MISMATCH_SUSPECT|huge=" + huge + "|thr=" + thr + "|offsets=" + offsets);
    }

    @Override public void onMasterSlew(int masterId, double E) {
        send(masterId, "MASTER_SLEW|E=" + E);
    }

    @Override public void onSlaveSlew(int nodeId, double off) {
        send(nodeId, "SLAVE_SLEW|off=" + off);
    }

    @Override public void onSlaveHardSet(int nodeId, double off) {
        send(nodeId, "SLAVE_HARD_SET|off=" + off);
    }

    @Override public void updateNodeTime(int id, double time) {
        send(id, "TIME|t=" + time);
    }

    @Override public void updateNodeDrift(int id, double drift) {
        send(id, "DRIFT|d=" + drift);
    }
}
