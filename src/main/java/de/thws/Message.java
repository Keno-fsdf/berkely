

        package de.thws;

import java.io.Serializable;

public record Message(
        int senderId,
        int targetId,
        Type type,
        int reqId, // t1/t2/t3-Zuordnung für Berkeley
        double time, // TIME_REQUEST: t1, TIME_RESPONSE: t2, HEARTBEAT: Senderzeit (optional)
        double offset // TIME_RESPONSE: echo t1 (Fallback), TIME_ADJUST: Korrektur
) implements Serializable {

    public enum Type {
        TIME_REQUEST,
        TIME_RESPONSE,
        TIME_ADJUST,
        HEARTBEAT,
        ELECTION,
        ELECTION_OK,
        COORDINATOR_ELECTED
    }


    /*
Election messages
•	ELECTION: suspect master (silence AND grace over) → send to all higher IDs.
•	on ELECTION: if receiver has higher ID (or is master) → reply ELECTION_OK; do NOT start local election while in grace.
•	ELECTION_OK: candidate sets gotOkFromHigher=true; waits coordinatorWaitMs for COORDINATOR_ELECTED.
•	COORDINATOR_ELECTED: sender self promotes (no higher OK) → broadcast; receivers adopt as master, reset lastHeartbeatMs, start grace.
•	Demotion: on HEARTBEAT from higher ID → adopt higher (isMaster=false), reset lastHeartbeatMs, start grace.
•	Gate to startElection: (silence ≥ missedHbThreshold×heartbeatTimeoutMs) AND (now ≥ electionCooldownUntilMs).
*/



    // Berkeley
    public static Message timeRequest(int senderId, int targetId, int reqId, double t1) {
        return new Message(senderId, targetId, Type.TIME_REQUEST, reqId, t1, 0.0);
    }

    public static Message timeResponse(int senderId, int targetId, int reqId, double t2, double echoT1) {
        return new Message(senderId, targetId, Type.TIME_RESPONSE, reqId, t2, echoT1);
    }

    public static Message timeAdjust(int senderId, int targetId, double corr) {
        return new Message(senderId, targetId, Type.TIME_ADJUST, 0, 0.0, corr);
    }

    // Liveness / Election
    public static Message heartbeat(int senderId, int targetId, double now) {
        return new Message(senderId, targetId, Type.HEARTBEAT, 0, now, 0.0);
    }

    public static Message election(int senderId, int targetId) {
        return new Message(senderId, targetId, Type.ELECTION, 0, 0.0, 0.0);
    }

    public static Message electionOk(int senderId, int targetId) {
        return new Message(senderId, targetId, Type.ELECTION_OK, 0, 0.0, 0.0);
    }

    public static Message coordinatorElected(int senderId, int targetId) {
        return new Message(senderId, targetId, Type.COORDINATOR_ELECTED, 0, 0.0, 0.0);
    }

    @Override
    public String toString() {
        return String.format("Message[%s] from %d to %d (reqId=%d, time=%.6f, offset=%.6f)",
                type, senderId, targetId, reqId, time, offset);
    }
}