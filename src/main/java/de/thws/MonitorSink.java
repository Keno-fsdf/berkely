// MonitorSink.java
package de.thws;

import java.util.Collection;

public interface MonitorSink {
    // Simulation meta (opsiyonel)
    default void onSimulationStart(int nodeCount, double loss, boolean randomFail,
                                   double failRate, double permProb, long durationMs) {}

    // Node lifecycle
    default void onNodeStart(int id, double initTime, double drift) {}
    default void onNodeStop(int id, boolean permanent) {}
    default void onFailStopStart(int id, long downtimeMs) {}
    default void onFailStopEnd(int id) {}

    // Election
    default void onMasterElected(int id) {}
    default void onDemotedTo(int newMasterId) {}
    default void onElectionStart(int id) {}
    default void onCoordinatorAnnounce(int id) {}

    // Berkeley
    default void onSyncStart(int masterId) {}
    default void onSyncEnd(int masterId, double E, double gamma, Collection<Integer> inliers) {}
    default void onSyncSkip(int masterId, String reason, String inliers, String offsets, double gamma) {}
    default void onEpochMismatchSuspect(int masterId, int huge, double thr, String offsets) {}
    default void onMasterSlew(int masterId, double E) {}
    default void onSlaveSlew(int nodeId, double off) {}
    default void onSlaveHardSet(int nodeId, double off) {}

    // Telemetry
    default void updateNodeTime(int id, double time) {}
    default void updateNodeDrift(int id, double drift) {}

    // Transport statistics (Node↔Node)
    default void onMessageAttempt(Message msg) {}
    default void onMessageLost(Message msg) {}
    default void onMessageDelivered(Message msg) {}

    default void shutdown() {}
}
