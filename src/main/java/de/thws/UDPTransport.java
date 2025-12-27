// UDPTransport.java  (Senin Node↔Node transport. Sadece monitor tipi düzeltildi.)
package de.thws;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

public class UDPTransport {

    private static volatile boolean PRINT_LOSS = false;
    public static void setPrintLoss(boolean enabled) { PRINT_LOSS = enabled; }

    private final DatagramSocket socket;
    private final double successProbability;
    private final Random random = new Random();
    private volatile boolean closed = false;

    private final MonitorSink monitor;

    public UDPTransport(int port, double successProbability, MonitorSink monitor) throws SocketException {
        this.successProbability = successProbability;
        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(200);
        this.monitor = monitor;
    }

    public void close() {
        closed = true;
        if (!socket.isClosed()) socket.close();
    }

    public void send(Message msg, NodeInfo target) {
        if (closed) return;

        if (monitor != null) monitor.onMessageAttempt(msg);

        if (random.nextDouble() > successProbability) {
            if (monitor != null) monitor.onMessageLost(msg);
            if (PRINT_LOSS) {
                System.out.printf("Message LOST: %s %d->%d%n", msg.type(), msg.senderId(), target.id());
            }
            return;
        }

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {

            oos.writeObject(msg);
            oos.flush();
            byte[] data = bos.toByteArray();

            InetAddress address = InetAddress.getByName(target.host());
            DatagramPacket packet = new DatagramPacket(data, data.length, address, target.port());
            socket.send(packet);

            if (monitor != null) monitor.onMessageDelivered(msg);

        } catch (IOException e) {
            if (!closed) e.printStackTrace();
        }
    }

    public void broadcast(Message msg, List<NodeInfo> peers) {
        for (NodeInfo peer : peers) send(msg, peer);
    }

    public Message receive() {
        if (closed) return null;

        try {
            byte[] buf = new byte[4096];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);

            try (ByteArrayInputStream bis = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                 ObjectInputStream ois = new ObjectInputStream(bis)) {

                return (Message) ois.readObject();
            }
        } catch (SocketTimeoutException e) {
            return null;
        } catch (SocketException e) {
            return null;
        } catch (IOException | ClassNotFoundException e) {
            if (!closed) e.printStackTrace();
            return null;
        }
    }
    public void sendRawMonitorEvent(int senderId, String payload, NodeInfo target) {
        if (closed) return;

        try {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(target.host()),
                    target.port()
            );
            socket.send(packet);
        } catch (Exception e) {
            if (!closed) e.printStackTrace();
        }
    }
    public DatagramSocket getSocket() {
        return socket;
    }
}
