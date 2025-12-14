## Start / Demo

Die IP-Adressen der Nodes werden über Argumente bzw. die `CLUSTER`-Variable übergeben, z. B.:

java -cp target\classes de.thws.Demo --cluster="%CLUSTER%" --spawn=1,6,7 --monitorMode=server --monitorPort=9999 --monitorEnabled=true --master=highest --loss=1.0 --duration=30000 --seed=123456



**Wichtig:**  
PC und Laptop müssen die **gleiche Java-Hauptversion (Major Version)** - also bei mir ist das eben version 21 gewesen - verwenden.  
Unterversionen (Minor/Patch) müssen nicht identisch sein.

Getestet wurde das Setup bisher mit **7 Nodes**.

---

### Start unter Windows (CMD, im Berkeley-Ordner)

#### PC

1. Cluster setzen:

    ```
    set CLUSTER=1@10.133.64.60:5001;2@10.133.64.183:5001;3@10.133.64.183:5002;4@10.133.64.183:5003;5@10.133.64.183:5004;6@10.133.64.60:5002;7@10.133.64.60:5003
    ```

2. Starten:

    ```
    java -cp target\classes de.thws.Demo --cluster="%CLUSTER%" --spawn=1,6,7 --monitorMode=server --monitorPort=9999 --monitorEnabled=true --master=highest --loss=1.0 --duration=30000 --seed=123456
    ```

---

#### Laptop

1. Cluster setzen:

    ```
    set CLUSTER=1@10.133.64.60:5001;2@10.133.64.183:5001;3@10.133.64.183:5002;4@10.133.64.183:5003;5@10.133.64.183:5004;6@10.133.64.60:5002;7@10.133.64.60:5003
    ```

2. Starten:

    ```
    java -cp target\classes de.thws.Demo --cluster="%CLUSTER%" --spawn=2,3,4,5 --monitorMode=client --monitorAddr=10.133.64.60 --monitorPort=9999 --monitorEnabled=false --master=highest --loss=1.0 --duration=30000 --seed=123456
    ```

---
