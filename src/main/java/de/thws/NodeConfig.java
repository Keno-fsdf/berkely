package de.thws;

public class NodeConfig {

    // Berkeley/TEMPO
    public final int    probes;               // Messungen pro Peer
    public final double tmBoundSeconds;       // maximal akzeptierte RTT (s) -->Wichtig das bezieht sich auf das Sampling von der Uhrzeit einer Uhr
    //Also ob quasi die RTT niedrig genug ist, dass man noch eine akzeptable symmetrie annehmen kann und es nicht durch netzwerkprobleme zu sehr verzehrt wird
    //und somit die Uhrzeit_messung zu ungenau /abweicht von der realen uhrzeit der uhr
    public final double gammaBaseSeconds;     // Basisfenster γ -->Wichtig das bezieht darauf ob ein wert als faulty clock zählt, also quasi
    // ob eine Slave-Uhr signifkant mehr abweicht von der master-uhrzeit als die anderen uhren. -->also das wirklich einfach nur das mit faulty clock
    //bei Fig. 2 vom paper mit Slave 3 wo mit 20 abweichung es zu groß ist und somit eine faulty clock ist.
    //Präziser: γ ist das Fenster, in dem Offsets zueinander passen; wir wählen den größten Block, dessen Spannweite ≤ γ (inkl. Master=0). Alles außerhalb --> “faulty” für diese Runde

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

    /*
    Der Backoff (base + zufälliger Jitter) sorgt dafür, dass nicht alle Kandidaten gleichzeitig wieder eine Wahl starten.

-->Desynchronisieren der Re‑Elections, um Kollisionen/Wahlstürme zu vermeiden.
Umsetzung im Code:
backoff = electionBackoffBaseMs + rnd.nextInt(jitter + 1);
electionCooldownUntilMs = now + backoff;
Effekt: Jeder Knoten hat eine leicht andere Wartezeit --> gestaffelte Neustarts statt “alle auf einmal”.
     */


    public final long graceAfterCoordMs;      // Grace nach Coordinator/HB (keine Wahl starten)








/*
Leader‑election Cooldowns (Unterschiede)

graceAfterCoordMs:
Fester Debounce nach Koordination/Heartbeat (Adoption, Demotion, Self‑Promotion, Fail‑Stop‑Return).
In dieser Zeit KEINE neue Wahl starten (stabilisiert nach Leaderwechsel).


WICHTIG ; DAS HAB ICH NICHT IN DER PRÄSI DRIN; ABER DAS WÜRDE ICH AUCH NUR BEI RÜCKFRAGEN NENNEN /ERKLÄREN
electionBackoffBaseMs:
Grundwartezeit, falls eine gestartete Wahl keinen COORDINATOR liefert
(Re‑Election wird verzögert).

electionBackoffJitterMs:
Zufallsanteil zum Backoff: cooldown = base + rand(0..jitter).
Entkoppelt Kandidaten --> verhindert gleichzeitige Re‑Elections.
*/


    public final int  coordinatorAnnounceRepeats;     // wie oft Coordinator-Elected senden
    public final long coordinatorAnnounceIntervalMs;  // Abstand zwischen Announce-Wiederholungen

    /*
coordinatorAnnounceRepeats / coordinatorAnnounceIntervalMs:

Neuer Master broadcastet COORDINATOR_ELECTED mehrfach (mit Abstand),
um Verlust zu überbrücken und parallele Wahlen schnell zu beenden.
*/


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