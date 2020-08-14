package edu.stanford.futuredata.uniserve.coordinator;

import com.google.protobuf.ByteString;
import edu.stanford.futuredata.uniserve.*;
import edu.stanford.futuredata.uniserve.broker.Broker;
import edu.stanford.futuredata.uniserve.utilities.DataStoreDescription;
import edu.stanford.futuredata.uniserve.utilities.TableInfo;
import edu.stanford.futuredata.uniserve.utilities.Utilities;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ServiceDataStoreCoordinator extends DataStoreCoordinatorGrpc.DataStoreCoordinatorImplBase {

    private static final Logger logger = LoggerFactory.getLogger(ServiceDataStoreCoordinator.class);

    private final Coordinator coordinator;

    ServiceDataStoreCoordinator(Coordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public void registerDataStore(RegisterDataStoreMessage request,
                                  StreamObserver<RegisterDataStoreResponse> responseObserver) {
        responseObserver.onNext(registerDataStoreHandler(request));
        responseObserver.onCompleted();
    }

    private RegisterDataStoreResponse registerDataStoreHandler(RegisterDataStoreMessage m) {
        String host = m.getHost();
        int port = m.getPort();
        int cloudID = m.getCloudID();
        coordinator.consistentHashLock.lock();
        int dsID = coordinator.dataStoreNumber.getAndIncrement();
        if (cloudID != -1) {
            assert(cloudID >= 0);
            coordinator.dsIDToCloudID.put(dsID, cloudID);
        }
        DataStoreDescription dsDescription = new DataStoreDescription(dsID, DataStoreDescription.ALIVE, host, port);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        CoordinatorDataStoreGrpc.CoordinatorDataStoreBlockingStub stub = CoordinatorDataStoreGrpc.newBlockingStub(channel);
        coordinator.consistentHash.addBucket(dsID);
        ByteString newConsistentHash = Utilities.objectToByteString(coordinator.consistentHash);
        ExecuteReshuffleMessage reshuffleMessage = ExecuteReshuffleMessage.newBuilder()
                .setNewConsistentHash(newConsistentHash).build();
        for (DataStoreDescription otherDescription: coordinator.dataStoresMap.values()) {
            if (otherDescription.status.get() == DataStoreDescription.ALIVE) {
                CoordinatorDataStoreGrpc.CoordinatorDataStoreBlockingStub otherStub =
                        coordinator.dataStoreStubsMap.get(otherDescription.dsID);
                otherStub.executeReshuffle(reshuffleMessage);
            }
        }
        stub.executeReshuffle(reshuffleMessage);
        coordinator.dataStoreChannelsMap.put(dsID, channel);
        coordinator.dataStoreStubsMap.put(dsID, stub);
        coordinator.dataStoresMap.put(dsID, dsDescription);
        coordinator.zkCurator.setDSDescription(dsDescription);
        coordinator.zkCurator.setConsistentHashFunction(coordinator.consistentHash);
        coordinator.consistentHashLock.unlock();
        logger.info("Registered DataStore ID: {} Host: {} Port: {} CloudID: {}", dsID, host, port, cloudID);
        if (cloudID != -1) {
            coordinator.loadBalancerSemaphore.release();
        }
        return RegisterDataStoreResponse.newBuilder().setReturnCode(0).setDataStoreID(dsID).build();
    }

    @Override
    public void potentialDSFailure(PotentialDSFailureMessage request, StreamObserver<PotentialDSFailureResponse> responseObserver) {
        responseObserver.onNext(potentialDSFailureHandler(request));
        responseObserver.onCompleted();
    }

    private PotentialDSFailureResponse potentialDSFailureHandler(PotentialDSFailureMessage request) {
        int dsID = request.getDsID();
        CoordinatorPingMessage m = CoordinatorPingMessage.newBuilder().build();
        try {
            coordinator.dataStoreStubsMap.get(dsID).coordinatorPing(m);
        } catch (StatusRuntimeException e) {
            coordinator.consistentHashLock.lock();
            DataStoreDescription dsDescription = coordinator.dataStoresMap.get(dsID);
            if (dsDescription.status.compareAndSet(DataStoreDescription.ALIVE, DataStoreDescription.DEAD)) {
                logger.warn("DS{} Failure Detected", dsID);
                coordinator.zkCurator.setDSDescription(dsDescription);
                coordinator.consistentHash.removeBucket(dsID);
                ByteString newConsistentHash = Utilities.objectToByteString(coordinator.consistentHash);
                ExecuteReshuffleMessage reshuffleMessage = ExecuteReshuffleMessage.newBuilder()
                        .setNewConsistentHash(newConsistentHash).build();
                // TODO:  Parallelize
                for (DataStoreDescription otherDescription: coordinator.dataStoresMap.values()) {
                    if (otherDescription.status.get() == DataStoreDescription.ALIVE) {
                        CoordinatorDataStoreGrpc.CoordinatorDataStoreBlockingStub otherStub =
                                coordinator.dataStoreStubsMap.get(otherDescription.dsID);
                        otherStub.executeReshuffle(reshuffleMessage);
                    }
                }
                coordinator.zkCurator.setConsistentHashFunction(coordinator.consistentHash);
            }
            coordinator.consistentHashLock.unlock();
        }
        return PotentialDSFailureResponse.newBuilder().build();
    }

    @Override
    public void tableInfo(DTableInfoMessage request, StreamObserver<DTableInfoResponse> responseObserver) {
        responseObserver.onNext(tableIDHandler(request));
        responseObserver.onCompleted();
    }

    private DTableInfoResponse tableIDHandler(DTableInfoMessage m) {
        String tableName = m.getTableName();
        if (coordinator.tableInfoMap.containsKey(tableName)) {
            TableInfo t = coordinator.tableInfoMap.get(tableName);
            return DTableInfoResponse.newBuilder().setReturnCode(Broker.QUERY_SUCCESS)
                    .setId(t.id)
                    .setNumShards(t.numShards).build();
        } else {
            return DTableInfoResponse.newBuilder().setReturnCode(Broker.QUERY_FAILURE).build();
        }
    }
}
