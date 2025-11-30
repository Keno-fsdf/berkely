package de.thws;

public class Clock {
    /*
     time = 1000;
       → Die simulierte Uhr steht auf 1000 Sekunden.

     time += drift;   // z.B. drift = 0.95
       → Die Uhr rückt um 0.95 simulierte Sekunden vor.

     adjust(3.5);
       → Die Uhr wird um 3.5 Sekunden korrigiert.
    */



    //angabe vielleicht so machen: nach einem tag hat man 5 sekundeen drift oder so beim programm start.




    private double time;    // simulierte Zeit in Sekunden
    private double drift;   // z.B. 0.95 = 5% zu langsam
    private boolean paused; // true = Node ist fail-stop, Uhr pausiert
    //simon fragen, ob er der meinung ist einen ausfall einfach mit einem boolean zu simulieren, weil ich weiß nicht ob das passt.

    public Clock(double initialTime, double drift) {
        this.time = initialTime;
        this.drift = drift;
        this.paused = false;
    }

    /**
     * Jede Tick-Operation simuliert das Fortschreiten der Zeit
     */
    public synchronized void tick() {
        if (!paused) {
            time += drift;  //nur drift zu addieren passt, weil wir quasi von eine tick=1 sekunde ausgehen und drift dann bspw. 0,95 sekunden sind.
        }
    }

    /**
     * Offset-Korrektur (vom Berkeley Algorithmus gesendet)
     */
    public synchronized void adjust(double offset) {
        if (!paused) {
            time += offset;
        }
    }

    /**
     * Liefert die aktuelle simulierte Zeit
     */
    public synchronized double getTime() {
        return time;
    }

    /**
     * Setzt die Zeit explizit (optional)
     */
    public synchronized void setTime(double time) {
        this.time = time;
    }

    public synchronized double getDrift() {
        return drift;
    }

    public synchronized void setDrift(double drift) {
        this.drift = drift;
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

    @Override
    public synchronized String toString() {
        return String.format("Clock(time=%.3f, drift=%.4f, paused=%b)", time, drift, paused);
    }

    public boolean isPaused() {
        return paused;
    }
}
