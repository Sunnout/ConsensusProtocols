package protocols.statemachine.requests;

import pt.unl.fct.di.novasys.babel.generic.ProtoRequest;
import pt.unl.fct.di.novasys.network.data.Host;

public class AddReplicaRequest extends ProtoRequest {

    public static final short REQUEST_ID = 202;

    private final int instance;
    private final Host replica;

    public AddReplicaRequest(int instance, Host replica) {
        super(REQUEST_ID);
        this.instance = instance;
        this.replica = replica;
    }

    public int getInstance() {
        return instance;
    }

    public Host getReplica() {
        return replica;
    }
}