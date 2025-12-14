package de.thws;



import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.stream.Collectors;

public class RemoteMonitorClient implements MonitorSink {
    private final String host;
    private final int port;

    public RemoteMonitorClient(String host, int port) {
        this.host = host; this.port = port;
    }

    private void send(String type, String kv) {
        try (DatagramSocket s = new DatagramSocket()) {
            String line = type + "|" + kv;
            byte[] data = line.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(data, data.length, InetAddress.getByName(host), port);
            s.send(p);
        } catch (Exception ignored) {}
    }

    private static String joinIds(Collection<Integer> ids){
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @Override public void onSimulationStart(int n,double l,boolean rf,double fr,double pp,long d){
        send("SimulationStart", "nodes="+n+";loss="+l+";rf="+rf+";fr="+fr+";pp="+pp+";dur="+d);
    }
    @Override public void onNodeStart(int id,double t,double d){ send("NodeStart","id="+id+";t="+t+";drift="+d); }
    @Override public void onNodeStop(int id, boolean perm){ send("NodeStop","id="+id+";perm="+perm); }
    @Override public void onFailStopStart(int id,long ms){ send("FailStopStart","id="+id+";ms="+ms); }
    @Override public void onFailStopEnd(int id){ send("FailStopEnd","id="+id); }
    @Override public void onMasterElected(int id){ send("MasterElected","id="+id); }
    @Override public void onDemotedTo(int newId){ send("Demotion","newMaster="+newId); }
    @Override public void onElectionStart(int id){ send("ElectionStart","id="+id); }
    @Override public void onCoordinatorAnnounce(int id){ send("CoordAnnounce","id="+id); }
    @Override public void onSyncStart(int m){ send("SyncStart","m="+m); }
    @Override public void onSyncEnd(int m,double E,double g, Collection<Integer> in){
        send("SyncEnd","m="+m+";E="+E+";g="+g+";in="+joinIds(in));
    }
    @Override public void updateNodeTime(int id,double t){ send("Time","id="+id+";t="+t); }
    @Override public void updateNodeDrift(int id,double d){ send("Drift","id="+id+";d="+d); }
    @Override public void onMessageAttempt(Message msg){ send("MsgSend","t="+msg.type()+";s="+msg.senderId()+";d="+msg.targetId()); }
    @Override public void onMessageLost(Message msg){ send("MsgLost","t="+msg.type()+";s="+msg.senderId()+";d="+msg.targetId()); }
    @Override public void onMessageDelivered(Message msg){ send("MsgDelivered","t="+msg.type()+";s="+msg.senderId()+";d="+msg.targetId()); }
    @Override public void shutdown(){ send("Shutdown",""); }
}