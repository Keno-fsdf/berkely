package de.thws;

public class Clock {
/*
Slewing-Clock:
- adjust(offset) plant nur eine Korrektur ein (remainingOffset).
- tick() trägt pro Tick höchstens maxSlewPerSec von remainingOffset ab.
- Zeit geht nie rückwärts: drift + step >= MIN_ADVANCE.
- drift ist die Basis-Zeit pro Tick (z.B. 0.95 s pro Sekunde).
*/

    private double time;            // simulierte Zeit in Sekunden
    private double drift;           // z.B. 0.95 = 5% zu langsam (Sekunden pro Tick)
    private boolean paused;         // true = Fail-Stop, Uhr pausiert

    // Slewing-Status
    private double remainingOffset = 0.0;   // noch auszubringende Korrektur (Sekunden)
    private double maxSlewPerSec = 0.10;  // maximale Korrektur pro Tick (Sekunden/Tick), z.B. 100 ms/s

    // Min. positiver Fortschritt pro Tick, um strikt monoton zu bleiben
    private static final double MIN_ADVANCE = 1e-9;

    public Clock(double initialTime, double drift) {
        this.time = initialTime;
        this.drift = drift;
        this.paused = false;
    }

    /**
     * Jede Tick-Operation simuliert das Fortschreiten der Zeit.
     * Dabei wird ggf. ein Teil der offenen Korrektur (remainingOffset) sanft "ausgeschlichen".
     */
    public synchronized void tick() {
        if (paused) return;

        double step = 0.0;

        if (remainingOffset != 0.0) {
            // gewünschter Korrekturanteil für diesen Tick (begrenzt durch maxSlewPerSec)
            double want = Math.copySign(
                    Math.min(Math.abs(maxSlewPerSec), Math.abs(remainingOffset)),
                    remainingOffset
            );

            // Sicherstellen, dass wir nicht rückwärts gehen:
            // Gesamtzuwachs = drift + want >= MIN_ADVANCE
            if (drift + want < MIN_ADVANCE) {
                // so weit verlangsamen, dass wir knapp > 0 bleiben
                want = -(drift - MIN_ADVANCE);
            }

            step = want;
            remainingOffset -= want;
        }

        time += drift + step;
    }

    /**
     * Offset-Korrektur (vom Berkeley/TEMPO-Algorithmus gesendet)
     * - Kein harter Sprung mehr; es wird nur die verbleibende Korrekturmenge erhöht.
     */
    public synchronized void adjust(double offset) {
        if (!paused) {
            remainingOffset += offset;
        }
    }

    /**
     * Liefert die aktuelle simulierte Zeit.
     */
    public synchronized double getTime() {
        return time;
    }

    /**
     * Setzt die Zeit explizit.
     * Hinweis: Für Initialisierung/Join gedacht. Bei Laufbetrieb wegen Monotonie vermeiden.
     */
    public synchronized void setTime(double time) {
        this.time = time;
        // optional: remainingOffset = 0; // falls man harte Setzung als "Reset" betrachtet
    }

    public synchronized double getDrift() {
        return drift;
    }

    public synchronized void setDrift(double drift) {
        this.drift = drift;
    }

    /**
     * Konfiguriert die max. Slew-Rate (Sekunden pro Tick).
     * Beispielwerte:
     * - 0.05 ... 0.10 für sichtbare Demos
     * - sehr klein (ppm-Bereich), wenn realitätsnäheres Verhalten gewünscht ist
     */
    public synchronized void setMaxSlewPerSec(double v) {
        this.maxSlewPerSec = Math.max(1e-9, v);
    }

    public synchronized double getMaxSlewPerSec() {
        return maxSlewPerSec;
    }

    public synchronized double getRemainingOffset() {
        return remainingOffset;
    }

    /**
     * Pausiert die Clock (Fail-Stop)
     */
    public synchronized void pause() {
        paused = true;
    }

    /**
     * Setzt die Clock wieder fort (Fail-Stop-Return)
     */
    public synchronized void resume() {
        paused = false;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    @Override
    public synchronized String toString() {
        return String.format(
                "Clock(time=%.3f, drift=%.4f, paused=%b, remainingOffset=%+.6f, maxSlewPerSec=%.6f)",
                time, drift, paused, remainingOffset, maxSlewPerSec
        );
    }
}