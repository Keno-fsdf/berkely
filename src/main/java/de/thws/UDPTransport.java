package de.thws;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Random;

public class UDPTransport {

    private DatagramSocket socket;
    private final double successProbability;
    private final Random random = new Random();
    private volatile boolean closed = false;

    public UDPTransport(int port, double successProbability) throws SocketException {
        this.successProbability = successProbability;
        this.socket = new DatagramSocket(port);
        this.socket.setSoTimeout(200); // 200ms Timeout: receive() kehrt regelmäßig zurück
    }

    // Beendet den Socket (entblockt receive())
    public void close() {
        closed = true;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public void send(Message msg, NodeInfo target) {
        if (closed) return;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {

            oos.writeObject(msg);
            oos.flush();
            byte[] data = bos.toByteArray();

            if (random.nextDouble() <= successProbability) {
                InetAddress address = InetAddress.getByName(target.host());
                DatagramPacket packet = new DatagramPacket(data, data.length, address, target.port());
                socket.send(packet);
            } else {
                System.out.println("Message lost: " + msg.type() + " to Node " + target.id());
            }

        } catch (IOException e) {
            if (!closed) e.printStackTrace();
        }
    }

    public void broadcast(Message msg, List<NodeInfo> peers) {
        for (NodeInfo peer : peers) {
            send(msg, peer);
        }
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
            return null; // nichts empfangen, weiterlaufen
        } catch (SocketException e) {
            // passiert bei close()
            return null;
        } catch (IOException | ClassNotFoundException e) {
            if (!closed) e.printStackTrace();
            return null;
        }
    }
}