package de.thws;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.List;

public class MonitorMain {

    public static void main(String[] args) throws Exception {

        int port = 6000;
        DatagramSocket socket = new DatagramSocket(port);
        System.out.println("Monitor listening on UDP port " + port);

        byte[] buf = new byte[8192];

        SimulationMonitor monitor = new SimulationMonitor(
                List.of(1, 2, 3, 4, 5),
                SimulationMonitor.Verbosity.SUMMARY,
                15
        );

        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);

            try {
                ByteArrayInputStream bais =
                        new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                ObjectInputStream ois = new ObjectInputStream(bais);

                Object obj = ois.readObject();

                if (!(obj instanceof Message msg)) {
                    System.err.println("WARN: Unbekanntes Objekt empfangen: " + obj);
                    continue;
                }

                monitor.onExternalMessage(msg);

            } catch (Exception e) {
                System.err.println("MONITOR ERROR: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
