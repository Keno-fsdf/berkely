package de.thws;

import java.util.List;

public class MonitorMain {

    private static final int MONITOR_PORT = 6000;

    public static void main(String[] args) throws Exception {

        SimulationMonitor monitor = new SimulationMonitor(
                List.of(1, 2, 3, 4, 5),
                SimulationMonitor.Verbosity.SUMMARY,
                false,
                false,
                15
        );

        UDPTransport transport = new UDPTransport(MONITOR_PORT, 1.0, null);
        System.out.println("MonitorMain listening on UDP port " + MONITOR_PORT);

        while (true) {
            Message msg = transport.receive();
            if (msg == null) continue;

            System.out.println("MONITOR RX: " + msg);

            if (msg.type() == Message.Type.MONITOR_EVENT) {
                monitor.onExternalEvent(msg.senderId(), msg.payload());
            }
        }

    }
}
