package protocols.agreement;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import protocols.agreement.messages.AcceptMessage;
import protocols.agreement.messages.AcceptOkMessage;
import protocols.agreement.messages.PrepareMessage;
import protocols.agreement.messages.PrepareOkMessage;
import protocols.agreement.notifications.DecidedNotification;
import protocols.agreement.notifications.MPJoinedNotification;
import protocols.agreement.notifications.NewLeaderNotification;
import protocols.agreement.requests.AddReplicaRequest;
import protocols.agreement.requests.ProposeRequest;
import protocols.agreement.requests.RemoveReplicaRequest;
import protocols.agreement.requests.SameReplicasRequest;
import protocols.agreement.timers.MultiPaxosLeaderAcceptTimer;
import protocols.agreement.timers.MultiPaxosStartTimer;
import protocols.agreement.utils.HostAndSn;
import protocols.agreement.utils.MultiPaxosState;
import protocols.app.utils.Operation;
import protocols.statemachine.notifications.ChannelReadyNotification;
import protocols.statemachine.utils.OperationAndId;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.network.data.Host;

import java.io.IOException;
import java.util.*;

public class MultiPaxosAgreement extends GenericProtocol {

    private static final Logger logger = LogManager.getLogger(MultiPaxosAgreement.class);

    //Protocol information, to register in babel
    public final static short PROTOCOL_ID = 500;
    public final static String PROTOCOL_NAME = "MultiPaxosAgreement";

    private Host myself;
    private Host currentLeader; // Current leader replica
    private int currentInstance; // Instance that is currently running
    private int joinedInstance; // Instance in which we joined the system
    private int currentSn; // SeqNumber of the current leader
    private Map<Integer, MultiPaxosState> paxosByInstance; // PaxosState for each instance

    public MultiPaxosAgreement(Properties props) throws IOException, HandlerRegistrationException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        currentInstance = -1; // -1 means we have not yet joined the system
        joinedInstance = -1; //-1 means we have not yet joined the system
        paxosByInstance = new HashMap<>();
        currentLeader = null;

        /*--------------------- Register Timer Handlers ----------------------------- */
        registerTimerHandler(MultiPaxosLeaderAcceptTimer.TIMER_ID, this::uponMultiPaxosLeaderTimer);
        registerTimerHandler(MultiPaxosStartTimer.TIMER_ID, this::uponMultiPaxosStartTimer);

        /*--------------------- Register Request Handlers ----------------------------- */
        registerRequestHandler(ProposeRequest.REQUEST_ID, this::uponProposeRequest);
        registerRequestHandler(AddReplicaRequest.REQUEST_ID, this::uponAddReplicaRequest);
        registerRequestHandler(RemoveReplicaRequest.REQUEST_ID, this::uponRemoveReplicaRequest);
        registerRequestHandler(SameReplicasRequest.REQUEST_ID, this::uponSameReplicasRequest);

        /*--------------------- Register Notification Handlers ----------------------------- */
        subscribeNotification(ChannelReadyNotification.NOTIFICATION_ID, this::uponChannelCreated);
        subscribeNotification(MPJoinedNotification.NOTIFICATION_ID, this::uponJoinedNotification);
    }

    @Override
    public void init(Properties props) {
        //Nothing to do here, we just wait for events from the application or agreement
    }

    //Upon receiving the channelId from the membership, register our own callbacks and serializers
    private void uponChannelCreated(ChannelReadyNotification notification, short sourceProto) {
        int cId = notification.getChannelId();
        myself = notification.getMyself();
        logger.info("Channel {} created, I am {}", cId, myself);
        // Allows this protocol to receive events from this channel.
        registerSharedChannel(cId);

        /*---------------------- Register Message Serializers ---------------------- */
        registerMessageSerializer(cId, PrepareMessage.MSG_ID, PrepareMessage.serializer);
        registerMessageSerializer(cId, PrepareOkMessage.MSG_ID, PrepareOkMessage.serializer);
        registerMessageSerializer(cId, AcceptMessage.MSG_ID, AcceptMessage.serializer);
        registerMessageSerializer(cId, AcceptOkMessage.MSG_ID, AcceptOkMessage.serializer);

        /*---------------------- Register Message Handlers -------------------------- */
        try {
            registerMessageHandler(cId, PrepareMessage.MSG_ID, this::uponPrepareMessage, this::uponMsgFail);
            registerMessageHandler(cId, PrepareOkMessage.MSG_ID, this::uponPrepareOkMessage, this::uponMsgFail);
            registerMessageHandler(cId, AcceptMessage.MSG_ID, this::uponAcceptMessage, this::uponMsgFail);
            registerMessageHandler(cId, AcceptOkMessage.MSG_ID, this::uponAcceptOkMessage, this::uponMsgFail);

        } catch (HandlerRegistrationException e) {
            throw new AssertionError("Error registering message handler.", e);
        }
    }

    /*--------------------------------- Messages ---------------------------------------- */

    private void uponPrepareMessage(PrepareMessage msg, Host host, short sourceProto, int channelId) {
        try {
            int instance = msg.getInstance();

            // If the message is not from an instance that has already ended
            if (instance >= currentInstance) {
                // If I am not the host and there is no leader (or the sn of the msg is higher)
                if(host.compareTo(myself) != 0 && (currentLeader == null || msg.getSn() > currentSn)) {
                    logger.debug("uponPrepareMessage: new leader is {}", host);
                    currentLeader = host;
                    currentSn = msg.getSn();
                    triggerNotification(new NewLeaderNotification(currentLeader));
                }

                // Send prepareOks to myself or to the current leader
                if(host.compareTo(myself) == 0 || currentLeader.compareTo(host) == 0) {
                    logger.debug("uponPrepareMessage: sending prepare ok to {}", host);
                    sendMessage(new PrepareOkMessage(instance, null, null, -1, currentSn), host);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Only the leader will receive prepareOks
    private void uponPrepareOkMessage(PrepareOkMessage msg, Host host, short sourceProto, int channelId) {
        try {
            int instance = msg.getInstance();
            MultiPaxosState ps = getPaxosInstance(instance);

            // If the message is not from an instance that has already ended
            // and we don't have a majority of prepareOks
            if (instance >= currentInstance && !ps.havePrepareOkMajority()) {
                int msgSn = msg.getSn();
                logger.debug("uponPrepareOkMessage: MsgSn: {}, MsgInstance: {}", msgSn, instance);
                ps.incrementPrepareOkCounter();

                // If majority quorum was achieved
                if (ps.getPrepareOkCounter() >= ps.getQuorumSize()) {
                    logger.debug("I am the leader!");
                    ps.setPrepareOkMajority(true);
                    currentLeader = myself;
                    currentSn = msg.getSn();
                    triggerNotification(new NewLeaderNotification(currentLeader));

                    OperationAndId opnId = ps.getInitialProposal();
                    ps.setToAcceptOpnId(opnId);
                    ps.setToAcceptSn(currentSn);

                    // Send accept messages to all
                    for (Host h : ps.getMembership()) {
                        sendMessage(new AcceptMessage(instance, opnId.getOpId(),
                                opnId.getOperation().toByteArray(), currentSn), h);
                    }
                    logger.debug("uponPrepareOkMessage: Sent AcceptMessages");
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void uponAcceptMessage(AcceptMessage msg, Host host, short sourceProto, int channelId) {
        try {
            int instance = msg.getInstance();

            // If we receive an accept in the instance we joined,
            // we make the sender our leader
            if(currentInstance == joinedInstance) {
                currentLeader = host;
            }

            // If the message is not from an instance that has already ended
            if (instance >= currentInstance && (currentLeader != null && host.compareTo(currentLeader) == 0)) {
                logger.debug("uponAcceptMessage: got accept from leader");
                int msgSn = msg.getSn();
                MultiPaxosState ps = getPaxosInstance(instance);
                logger.debug("uponAcceptMessage: MsgSn: {}, MsgInstance: {}", msgSn, instance);

                OperationAndId opnId = new OperationAndId(Operation.fromByteArray(msg.getOp()), msg.getOpId());

                if(ps.isMembershipOk()){
                    logger.debug("uponAcceptMessage: Membership: {}", ps.getMembership());
                    // Send acceptOk with that seqNumber and value to all learners
                    for (Host h : ps.getMembership()) {
                        sendMessage(new AcceptOkMessage(instance, opnId.getOpId(),
                                opnId.getOperation().toByteArray(), msgSn), h);
                    }
                    logger.debug("uponAcceptMessage: Sent AcceptOkMessages");
                }

                ps.setToAcceptOpnId(opnId);
                ps.setToAcceptSn(msgSn);
                ps.checkAcceptsOks();

                // If majority quorum was achieved
                if (ps.hasAcceptOkQuorum() && ps.getToDecide() == null) {
                    logger.debug("uponAcceptMessage: Can decide after getting new membership in instance {}", instance);
                    logger.debug("uponAcceptMessage: Got AcceptOk majority");

                    ps.setToDecide(opnId);

                    // If the quorum is for the current instance then decide
                    if (currentInstance == instance) {
                        logger.debug("uponAcceptMessage: Decided {} in instance {}", opnId.getOpId(), instance);
                        triggerNotification(new DecidedNotification(instance, opnId.getOpId(),
                                opnId.getOperation().toByteArray()));

                        currentInstance++;
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void uponAcceptOkMessage(AcceptOkMessage msg, Host host, short sourceProto, int channelId) {
        try {
            int instance = msg.getInstance();
            MultiPaxosState ps = getPaxosInstance(instance);

            logger.debug("uponAcceptOkMessage: Received MsgSn: {}, MsgInstance: {}", msg.getHighestAccept(), instance);

            // If the message is not from an instance that has already ended
            // and the accept is for the sequence number we are accepting
            if (instance >= currentInstance && msg.getHighestAccept() == ps.getToAcceptSn()) {

                logger.debug("uponAcceptOkMessage: Adding host to accepted hosts");
                ps.addHostToHaveAccepted(host);

                // If majority quorum was achieved
                if (ps.hasAcceptOkQuorum() && ps.getToDecide() == null) {
                    logger.debug("uponAcceptOkMessage: Got AcceptOk majority");

                    OperationAndId opnId = new OperationAndId(Operation.fromByteArray(msg.getOp()), msg.getOpId());
                    ps.setToDecide(opnId);

                    // If the quorum is for the current instance then decide
                    if (currentInstance == instance) {
                        logger.debug("uponAcceptOkMessage: Decided {} in instance {}", opnId.getOpId(), instance);
                        triggerNotification(new DecidedNotification(instance, opnId.getOpId(),
                                opnId.getOperation().toByteArray()));

                        currentInstance++;
                    }

                    // Cancel timer that waits for responses
                    if(currentLeader.compareTo(myself) == 0) {
                        cancelTimer(ps.getPaxosLeaderTimer());
                    }
                }
            // If we have not received the accept message, store the acceptOk info for later
            } else if(instance >= currentInstance && ps.getToAcceptSn() == -1) {
                ps.addToAcceptOksList(new HostAndSn(host, msg.getHighestAccept()));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void uponMsgFail(ProtoMessage msg, Host host, short destProto, Throwable throwable, int channelId) {
        // If a message fails to be sent, for whatever reason, log the message and the reason
        logger.error("Message {} to {} failed, reason: {}", msg, host, throwable);
    }


    /*--------------------------------- Notifications ------------------------------------ */

    private void uponJoinedNotification(MPJoinedNotification notification, short sourceProto) {
        // We joined the system and can now start doing things
        currentInstance = notification.getJoinInstance();
        joinedInstance = currentInstance;
        currentLeader = notification.getCurrentLeader();
        MultiPaxosState ps = getPaxosInstance(currentInstance);

        // Initialize membership and open connections
        for (Host h : notification.getMembership()) {
            ps.addReplicaToMembership(h);
            openConnection(h);
        }

        ps.setMembershipOk();
        logger.debug("uponJoined: Joined successful");
        logger.debug("uponJoined: currentLeader: {}", currentLeader);
        logger.debug("uponJoined: current instance {}", currentInstance);
        logger.debug("uponJoined: current membership {}", ps.getMembership());

        logger.info("Agreement starting at instance {},  membership: {}",
                currentInstance, ps.getMembership());

        decideOrSendLateAcceptOks(currentInstance);
    }

    /*--------------------------------- Requests ---------------------------------------- */

    private void uponProposeRequest(ProposeRequest request, short sourceProto) {
        logger.debug("uponProposeRequest: New Propose");

        // Random timer to avoid replicas sending prepares at the same time
        if (currentLeader == null) {
            Random r = new Random();
            setupTimer(new MultiPaxosStartTimer(request), r.nextInt(5000));
        }
        // Proceed if I am the leader
        else
            proposeRequest(request);
    }

    private void uponAddReplicaRequest(AddReplicaRequest request, short sourceProto) {
        logger.debug("Received " + request);
        Host replica = request.getReplica();
        int instance = request.getInstance();
        MultiPaxosState ps = getPaxosInstance(instance);
        // Adding replica to membership of instance and opening connection
        usePreviousMembership(instance);
        ps.addReplicaToMembership(replica);
        logger.debug("After adding new replica: {}", ps.getMembership());

        openConnection(replica);
        // Membership up to date
        ps.setMembershipOk();

        decideOrSendLateAcceptOks(instance);
    }

    private void uponRemoveReplicaRequest(RemoveReplicaRequest request, short sourceProto) {
        logger.debug("Received " + request);
        Host replica = request.getReplica();
        int instance = request.getInstance();
        MultiPaxosState ps = getPaxosInstance(instance);
        // Removing replica from membership of instance and closing connection
        usePreviousMembership(instance);
        ps.removeReplicaFromMembership(replica);
        logger.debug("After removing replica: {}", ps.getMembership());

        closeConnection(replica);
        // Membership up to date
        ps.setMembershipOk();

        decideOrSendLateAcceptOks(instance);
    }

    private void uponSameReplicasRequest(SameReplicasRequest request, short sourceProto) {
        logger.debug("Received " + request);
        int instance = request.getInstance();
        MultiPaxosState ps = getPaxosInstance(instance);
        // Membership up to date
        usePreviousMembership(instance);
        ps.setMembershipOk();

        decideOrSendLateAcceptOks(instance);
    }

    /*--------------------------------- Timers ---------------------------------------- */

    private void uponMultiPaxosLeaderTimer(MultiPaxosLeaderAcceptTimer paxosTimer, long timerId) {
        MultiPaxosState ps = getPaxosInstance(currentInstance);
        logger.debug("MultiPaxosLeaderTimer Timeout in instance {}", currentInstance);

        List<Host> membership = ps.getMembership();
        ProposeRequest request = paxosTimer.getRequest();
        // Send accepts again
        membership.forEach(h -> sendMessage(new AcceptMessage(currentInstance, request.getOpId(),
                request.getOperation(), currentSn), h));

        // Setup new timer to wait for quorum of responses
        long newTimerId = setupTimer(new MultiPaxosLeaderAcceptTimer(request), 2000);
        ps.setPaxosLeaderTimer(newTimerId);
        logger.debug("New MultiPaxosLeaderAcceptTimer created with id {}", newTimerId);
    }

    private void uponMultiPaxosStartTimer(MultiPaxosStartTimer paxosTimer, long timerId) {
        // Send first prepares for leader election, if there is no leader
        if (currentLeader == null){
            logger.debug("MultiPaxosStartTimer timeout, will propose");
            proposeRequest(paxosTimer.getRequest());
        }
    }

    /*--------------------------------- Procedures ---------------------------------------- */

    private MultiPaxosState getPaxosInstance(int instance) {
        // If we haven't initialized the instance
        if (!paxosByInstance.containsKey(instance))
            paxosByInstance.put(instance, new MultiPaxosState());

        return paxosByInstance.get(instance);
    }

    private void proposeRequest(ProposeRequest request){
        int instance = request.getInstance();
        MultiPaxosState ps = getPaxosInstance(instance);

        try{
            if (ps.isMembershipOk()) {
                logger.debug("Membership is ok to propose in {}", currentInstance);
                List<Host> membership = ps.getMembership();

                OperationAndId opnId = new OperationAndId(Operation.fromByteArray(request.getOperation()),
                        request.getOpId());

                // If I dont know who the leader is, send prepares for leader election
                if (currentLeader == null) {
                    ps.generateSn(myself);
                    currentSn = ps.getSn();
                    membership.forEach(h -> sendMessage(new PrepareMessage(currentSn, instance), h));
                    logger.debug("uponProposeRequest: Sent Prepares");
                    ps.setInitialProposal(opnId);

                // If I am the leader, send accepts, always with the same seqNumber
                } else if (currentLeader.compareTo(myself) == 0) {
                    logger.debug("uponProposeRequest: Sent Accepts");
                    membership.forEach(h -> sendMessage(new AcceptMessage(instance, request.getOpId(),
                            request.getOperation(), currentSn), h));

                    // Setup new timer to wait for quorum of responses
                    long timerId = setupTimer(new MultiPaxosLeaderAcceptTimer(request), 2000);
                    ps.setPaxosLeaderTimer(timerId);
                    logger.debug("New MultiPaxosLeaderAcceptTimer created with id {}", timerId);

                    ps.setInitialProposal(opnId);
                    ps.setToAcceptOpnId(opnId);
                    ps.setToAcceptSn(currentSn);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void decideOrSendLateAcceptOks(int instance){
        MultiPaxosState ps = getPaxosInstance(instance);

        try {
            // If did not get a majority ok accept oks but already received an accept from leader
            // send accept oks to everyone
            if (ps.getToAcceptOpnId() != null) {
                OperationAndId opnId = ps.getToAcceptOpnId();

                for (Host h : ps.getMembership()) {
                    sendMessage(new AcceptOkMessage(instance, opnId.getOpId(),
                            opnId.getOperation().toByteArray(), ps.getToAcceptSn()), h);
                }
            }

            canDecide(instance);

        } catch(Exception e){
            e.printStackTrace();
        }
    }

    private void canDecide(int instance) throws IOException {
        MultiPaxosState ps = getPaxosInstance(instance);

        // If we have an acceptOk quorum and we have received
        // the corresponding accept, we can decide
        if(ps.hasAcceptOkQuorum() && ps.getToAcceptOpnId() != null) {
            ps.setToDecide(ps.getToAcceptOpnId());
            OperationAndId opnId = ps.getToAcceptOpnId();

            logger.debug("canDecide: Decided {} in instance {}", opnId.getOpId(), instance);
            triggerNotification(new DecidedNotification(instance, opnId.getOpId(),
                    opnId.getOperation().toByteArray()));

            currentInstance++;
        }
    }

    private void usePreviousMembership(int instance) {
        MultiPaxosState ps = getPaxosInstance(instance);
        logger.debug("Getting previous membership for instance {}", instance);

        if (joinedInstance != currentInstance) {
            List<Host> prevMembership = getPaxosInstance(instance - 1).getMembership();
            logger.debug("Previous Membership {}", prevMembership);
            for (Host h : prevMembership)
                ps.addReplicaToMembership(h);
        }

        logger.debug("New membership: {}", ps.getMembership());
    }
}