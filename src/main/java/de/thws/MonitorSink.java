package de.thws;

import java.util.Collection;

public interface MonitorSink {
    void onSimulationStart(int nodeCount, double loss, boolean randomFail, double failRate, double permProb, long durationMs);

    void onNodeStart(int id, double initTime, double drift);
    void onNodeStop(int id, boolean permanent);
    void onFailStopStart(int id, long downtimeMs);
    void onFailStopEnd(int id);

    void onMasterElected(int id);
    void onDemotedTo(int newMasterId);
    void onElectionStart(int id);
    void onCoordinatorAnnounce(int id);

    void onSyncStart(int masterId);
    void onSyncEnd(int masterId, double E, double gamma, Collection<Integer> inliers);

    void updateNodeTime(int id, double time);
    void updateNodeDrift(int id, double drift);

    void onMessageAttempt(Message msg);
    void onMessageLost(Message msg);
    void onMessageDelivered(Message msg);

    void shutdown();
}