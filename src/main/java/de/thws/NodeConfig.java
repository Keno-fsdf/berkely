package de.thws;

public class NodeConfig {

    // Berkeley/TEMPO
    public final int    probes;               // Messungen pro Peer
    public final double tmBoundSeconds;       // maximal akzeptierte RTT (s)
    public final double gammaBaseSeconds;     // Basisfenster γ
    public final long   syncIntervalMs;       // Intervall zwischen Sync-Runden

    // Heartbeat / Failure Detector
    public final long heartbeatIntervalMs;    // Sendeintervall HB
    public final long heartbeatTimeoutMs;     // Timeout bis “Master evtl. weg?”
    public final int  missedHbThreshold;      // wie viele Timeouts in Folge vor Wahlstart (Debounce)

    // Bully-Election
    public final long electionTimeoutMs;      // Wartezeit auf OK von höherer ID
    public final long coordinatorWaitMs;      // Wartezeit auf Coordinator-Elected
    public final long electionBackoffBaseMs;  // Grund-Backoff
    public final long electionBackoffJitterMs;// zusätzlicher Jitter
    public final long graceAfterCoordMs;      // Grace nach Coordinator/HB (keine Wahl starten)
    public final int  coordinatorAnnounceRepeats;     // wie oft Coordinator-Elected senden
    public final long coordinatorAnnounceIntervalMs;  // Abstand zwischen Announce-Wiederholungen

    public NodeConfig(
            int probes,
            double tmBoundSeconds,
            double gammaBaseSeconds,
            long syncIntervalMs,
            long heartbeatIntervalMs,
            long heartbeatTimeoutMs,
            int  missedHbThreshold,
            long electionTimeoutMs,
            long coordinatorWaitMs,
            long electionBackoffBaseMs,
            long electionBackoffJitterMs,
            long graceAfterCoordMs,
            int  coordinatorAnnounceRepeats,
            long coordinatorAnnounceIntervalMs
    ) {
        this.probes = probes;
        this.tmBoundSeconds = tmBoundSeconds;
        this.gammaBaseSeconds = gammaBaseSeconds;
        this.syncIntervalMs = syncIntervalMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.missedHbThreshold = missedHbThreshold;
        this.electionTimeoutMs = electionTimeoutMs;
        this.coordinatorWaitMs = coordinatorWaitMs;
        this.electionBackoffBaseMs = electionBackoffBaseMs;
        this.electionBackoffJitterMs = electionBackoffJitterMs;
        this.graceAfterCoordMs = graceAfterCoordMs;
        this.coordinatorAnnounceRepeats = coordinatorAnnounceRepeats;
        this.coordinatorAnnounceIntervalMs = coordinatorAnnounceIntervalMs;
    }

    public static NodeConfig defaults() {
        return new NodeConfig(
                7,           // probes
                0.020,       // tmBoundSeconds (20 ms, Paper-nah)
                0.020,       // gammaBaseSeconds (klein; wird dynamisch von 4ε+2pT überdeckt)
                5000,        // syncIntervalMs

                500,         // heartbeatIntervalMs
                2800,        // heartbeatTimeoutMs
                2,           // missedHbThreshold

                1200,        // electionTimeoutMs
                2200,        // coordinatorWaitMs
                300,         // electionBackoffBaseMs
                600,         // electionBackoffJitterMs
                1500,        // graceAfterCoordMs

                3,           // coordinatorAnnounceRepeats
                100          // coordinatorAnnounceIntervalMs
        );
    }
}