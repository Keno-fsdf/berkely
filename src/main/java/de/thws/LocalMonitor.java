package de.thws;

import java.util.Collection;

public class LocalMonitor implements MonitorSink {
    private final SimulationMonitor delegate;
    public LocalMonitor(SimulationMonitor delegate) { this.delegate = delegate; }

    @Override public void onSimulationStart(int nc,double l,boolean rf,double fr,double pp,long d){ delegate.onSimulationStart(nc,l,rf,fr,pp,d); }
    @Override public void onNodeStart(int id,double t,double dr){ delegate.onNodeStart(id,t,dr); }
    @Override public void onNodeStop(int id, boolean perm){ delegate.onNodeStop(id,perm); }
    @Override public void onFailStopStart(int id,long ms){ delegate.onFailStopStart(id,ms); }
    @Override public void onFailStopEnd(int id){ delegate.onFailStopEnd(id); }
    @Override public void onMasterElected(int id){ delegate.onMasterElected(id); }
    @Override public void onDemotedTo(int newId){ delegate.onDemotedTo(newId); }
    @Override public void onElectionStart(int id){ delegate.onElectionStart(id); }
    @Override public void onCoordinatorAnnounce(int id){ delegate.onCoordinatorAnnounce(id); }
    @Override public void onSyncStart(int m){ delegate.onSyncStart(m); }
    @Override public void onSyncEnd(int m,double E,double g, Collection<Integer> in){ delegate.onSyncEnd(m,E,g,in); }
    @Override public void updateNodeTime(int id,double t){ delegate.updateNodeTime(id,t); }
    @Override public void updateNodeDrift(int id,double d){ delegate.updateNodeDrift(id,d); }
    @Override public void onMessageAttempt(Message msg){ delegate.onMessageAttempt(msg); }
    @Override public void onMessageLost(Message msg){ delegate.onMessageLost(msg); }
    @Override public void onMessageDelivered(Message msg){ delegate.onMessageDelivered(msg); }
    @Override public void shutdown(){ delegate.shutdown(); }

    public SimulationMonitor unwrap() { return delegate; }
}