package protocols.agreement.utils;

import protocols.statemachine.utils.OperationAndId;
import pt.unl.fct.di.novasys.network.data.Host;

import java.util.LinkedList;
import java.util.List;

public class PaxosState {

    private List<Host> membership;

    private int highestPrepare; // Highest prepared seqNumber
    private OperationAndId highestPreparedValue; // Highest prepared value
    private int prepareOkCounter; /// Number of prepareOks for the same seqNumber

    private int highestAccept; // Highest accepted seqNumber
    private OperationAndId highestAcceptedValue; // Highest accepted value
    private int acceptOkCounter; // Number of acceptOks for the same seqNumber

    private Host replicaToRemove; // Host to remove

    private OperationAndId toDecide; // Value to decide

    private long prepareOkTimer; // Id of prepareOkTimer
    private long acceptOkTimer; // Id of acceptOkTimer

    public PaxosState(List<Host> membership) {
        this.membership = membership;
        this.highestPrepare = -1;
        this.highestPreparedValue = null;
        this.prepareOkCounter = 0;
        this.highestAccept = -1;
        this.highestAcceptedValue = null;
        this.acceptOkCounter = 0;
        this.replicaToRemove = null;
        this.toDecide = null;
        this.prepareOkTimer = -1;
        this.acceptOkTimer = -1;
    }

    public PaxosState() {
        this.membership = new LinkedList<>();
        this.highestPrepare = -1;
        this.highestPreparedValue = null;
        this.prepareOkCounter = 0;
        this.highestAccept = -1;
        this.highestAcceptedValue = null;
        this.acceptOkCounter = 0;
        this.replicaToRemove = null;
        this.toDecide = null;
        this.prepareOkTimer = -1;
        this.acceptOkTimer = -1;
    }

    public List<Host> getMembership() {
        return this.membership;
    }

    public int getMembershipSize() {
        return this.membership.size();
    }

    public int getQuorumSize() {
        return this.membership.size() / 2 + 1;
    }

    public void addReplicaToMembership(Host replica) {
        if(!this.replicaToRemove.equals(replica))
            this.membership.add(replica);
    }

    public void removeReplicaFromMembership(Host replica) {
        this.membership.remove(replica);
        this.replicaToRemove = replica;
    }

    public int getHighestPrepare() {
        return highestPrepare;
    }

    public void setHighestPrepare(int highestPrepare) {
        this.highestPrepare = highestPrepare;
    }

    public OperationAndId getHighestPreparedValue() {
        return highestPreparedValue;
    }

    public void setHighestPreparedValue(OperationAndId highestPreparedValue) {
        this.highestPreparedValue = highestPreparedValue;
    }

    public int getPrepareOkCounter() {
        return prepareOkCounter;
    }

    public void resetPrepareOkCounter() {
        this.prepareOkCounter = 0;
    }

    public void incrementPrepareOkCounter() {
        this.prepareOkCounter += 1;
    }

    public int getHighestAccept() {
        return highestAccept;
    }

    public void setHighestAccept(int highestAccept) {
        this.highestAccept = highestAccept;
    }

    public OperationAndId getHighestAcceptedValue() {
        return highestAcceptedValue;
    }

    public void setHighestAcceptedValue(OperationAndId highestAcceptedValue) {
        this.highestAcceptedValue = highestAcceptedValue;
    }

    public int getAcceptOkCounter() {
        return acceptOkCounter;
    }

    public void resetAcceptOkCounter() {
        this.acceptOkCounter = 0;
    }

    public void incrementAcceptOkCounter() {
        this.acceptOkCounter += 1;
    }

    public OperationAndId getToDecide() {
        return toDecide;
    }

    public void setToDecide(OperationAndId toDecide) {
        this.toDecide = toDecide;
    }

    public long getPrepareOkTimer() {
        return prepareOkTimer;
    }

    public void setPrepareOkTimer(long prepareOkTimer) {
        this.prepareOkTimer = prepareOkTimer;
    }

    public long getAcceptOkTimer() {
        return acceptOkTimer;
    }

    public void setAcceptOkTimer(long acceptOkTimer) {
        this.acceptOkTimer = acceptOkTimer;
    }
}