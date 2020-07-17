package edu.stanford.futuredata.uniserve.integration;

import edu.stanford.futuredata.uniserve.awscloud.AWSDataStoreCloud;
import edu.stanford.futuredata.uniserve.broker.Broker;
import edu.stanford.futuredata.uniserve.coordinator.Coordinator;
import edu.stanford.futuredata.uniserve.datastore.DataStore;
import edu.stanford.futuredata.uniserve.interfaces.ReadQueryPlan;
import edu.stanford.futuredata.uniserve.interfaces.WriteQueryPlan;
import edu.stanford.futuredata.uniserve.kvmockinterface.*;
import edu.stanford.futuredata.uniserve.kvmockinterface.queryplans.*;
import org.apache.commons.io.FileUtils;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;


public class KVStoreTests {

    private static final Logger logger = LoggerFactory.getLogger(KVStoreTests.class);

    private static String zkHost = "127.0.0.1";
    private static Integer zkPort = 2181;

    public static void cleanUp(String zkHost, int zkPort) {
        // Clean up ZooKeeper
        String connectString = String.format("%s:%d", zkHost, zkPort);
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        CuratorFramework cf = CuratorFrameworkFactory.newClient(connectString, retryPolicy);
        cf.start();
        try {
            for (String child : cf.getChildren().forPath("/")) {
                if (!child.equals("zookeeper")) {
                    cf.delete().deletingChildrenIfNeeded().forPath("/" + child);
                }
            }
        } catch (Exception e) {
            logger.info("Zookeeper cleanup failed: {}", e.getMessage());
        }
        // Clean up directories.
        try {
            FileUtils.deleteDirectory(new File("/var/tmp/KVUniserve0"));
            FileUtils.deleteDirectory(new File("/var/tmp/KVUniserve1"));
            FileUtils.deleteDirectory(new File("/var/tmp/KVUniserve2"));
            FileUtils.deleteDirectory(new File("/var/tmp/KVUniserve3"));
        } catch (IOException e) {
            logger.info("FS cleanup failed: {}", e.getMessage());
        }
    }

    @BeforeAll
    static void startUpCleanUp() {
        cleanUp(zkHost, zkPort);
    }

    @AfterEach
    private void unitTestCleanUp() {
        cleanUp(zkHost, zkPort);
    }

    @Test
    public void testSingleKey() {
        logger.info("testSingleKey");
        int numShards = 1;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7777);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        DataStore<KVRow, KVShard>  dataStore = new DataStore<>(null, new KVShardFactory(),
                Path.of("/var/tmp/KVUniserve"), zkHost, zkPort, "127.0.0.1", 8000, -1);
        int d_r = dataStore.startServing();
        assertEquals(0, d_r);
        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        assertTrue(broker.createTable("table1", numShards));
        assertTrue(broker.createTable("table2", numShards));
        assertFalse(broker.createTable("table1", numShards + 1));

        WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert("table1");
        assertTrue(broker.writeQuery(writeQueryPlan, Collections.singletonList(new KVRow(1, 2))));

        ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanGet("table1", 1);
        assertEquals(Integer.valueOf(2), broker.readQuery(readQueryPlan));

        WriteQueryPlan<KVRow, KVShard> writeQueryPlan2 = new KVWriteQueryPlanInsert("table2");
        assertTrue(broker.writeQuery(writeQueryPlan2, Collections.singletonList(new KVRow(1, 3))));

        ReadQueryPlan<KVShard, Integer> readQueryPlan2 = new KVReadQueryPlanGet("table2", 1);
        assertEquals(Integer.valueOf(3), broker.readQuery(readQueryPlan2));

        dataStore.shutDown();
        coordinator.stopServing();
        broker.shutdown();
    }

    @Test
    public void testMultiKey() {
        logger.info("testMultiKey");
        int numShards = 2;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7778);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        List<DataStore<KVRow, KVShard> > dataStores = new ArrayList<>();
        int num_datastores = 4;
        for (int i = 0; i < num_datastores; i++) {
            DataStore<KVRow, KVShard>  dataStore = new DataStore<>(null, new KVShardFactory(),
                    Path.of("/var/tmp/KVUniserve"), zkHost, zkPort,"127.0.0.1",  8100 + i, -1);
            dataStore.runPingDaemon = false;
            int d_r = dataStore.startServing();
            assertEquals(0, d_r);
            dataStores.add(dataStore);
        }
        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);
        List<KVRow> rows = new ArrayList<>();
        for (int i = 1; i < 11; i++) {
            rows.add(new KVRow(i, i));
        }
        WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
        boolean writeSuccess = broker.writeQuery(writeQueryPlan, rows);
        assertTrue(writeSuccess);

        ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanSumGet(Collections.singletonList(1));
        Integer queryResponse = broker.readQuery(readQueryPlan);
        assertEquals(Integer.valueOf(1), queryResponse);

        readQueryPlan = new KVReadQueryPlanSumGet(Arrays.asList(1, 5));
        queryResponse = broker.readQuery(readQueryPlan);
        assertEquals(Integer.valueOf(6), queryResponse);

        readQueryPlan = new KVReadQueryPlanSumGet(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        queryResponse = broker.readQuery(readQueryPlan);
        assertEquals(Integer.valueOf(55), queryResponse);

        for (int i = 0; i < num_datastores; i++) {
           dataStores.get(i).shutDown();
        }
        coordinator.stopServing();
        broker.shutdown();
    }

    @Test
    public void testAddingServers() throws InterruptedException {
        logger.info("testAddingServers");
        int numShards = 5;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7778);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);

        DataStore<KVRow, KVShard>  dataStoreOne = new DataStore<>(new AWSDataStoreCloud("kraftp-uniserve"),
                new KVShardFactory(), Path.of(String.format("/var/tmp/KVUniserve%d", 1)),
                zkHost, zkPort, "127.0.0.1", 8200, -1);
        dataStoreOne.runPingDaemon = false;
        dataStoreOne.startServing();

        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);
        List<KVRow> rows = new ArrayList<>();
        for (int i = 1; i < 11; i++) {
            rows.add(new KVRow(i, i));
        }
        WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
        assertTrue(broker.writeQuery(writeQueryPlan, rows));

        DataStore<KVRow, KVShard>  dataStoreTwo = new DataStore<>(new AWSDataStoreCloud("kraftp-uniserve"),
                new KVShardFactory(), Path.of(String.format("/var/tmp/KVUniserve%d", 2)),
                zkHost, zkPort, "127.0.0.1", 8201, -1);
        dataStoreTwo.runPingDaemon = false;
        dataStoreTwo.startServing();

        Thread.sleep(Broker.shardMapDaemonSleepDurationMillis * 2);

        assertTrue(broker.writeQuery(writeQueryPlan, Collections.singletonList(new KVRow(1, 2))));

        ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanSumGet(Collections.singletonList(1));
        Integer queryResponse = broker.readQuery(readQueryPlan);
        assertEquals(Integer.valueOf(2), queryResponse);

        readQueryPlan = new KVReadQueryPlanSumGet(Arrays.asList(1, 5));
        queryResponse = broker.readQuery(readQueryPlan);
        assertEquals(Integer.valueOf(7), queryResponse);

        readQueryPlan = new KVReadQueryPlanSumGet(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        queryResponse = broker.readQuery(readQueryPlan);
        assertEquals(Integer.valueOf(56), queryResponse);

        dataStoreOne.shutDown();
        dataStoreTwo.shutDown();
        coordinator.stopServing();
        broker.shutdown();
    }


//    @Test
    public void testBroadcastJoin() {
        logger.info("testSingleKey");
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7777);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        List<DataStore<KVRow, KVShard> > dataStores = new ArrayList<>();
        int num_datastores = 4;
        for (int i = 0; i < num_datastores; i++) {
            DataStore<KVRow, KVShard>  dataStore = new DataStore<>(new AWSDataStoreCloud("kraftp-uniserve"),
                    new KVShardFactory(), Path.of(String.format("/var/tmp/KVUniserve%d", i)),
                    zkHost, zkPort, "127.0.0.1", 8200 + i, -1);
            dataStore.runPingDaemon = false;
            int d_r = dataStore.startServing();
            assertEquals(0, d_r);
            dataStores.add(dataStore);
        }
        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        assertTrue(broker.createTable("table1", 2));
        assertTrue(broker.createTable("table2", 1));

        WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert("table1");
        assertTrue(broker.writeQuery(writeQueryPlan, Collections.singletonList(new KVRow(1, 1))));

        WriteQueryPlan<KVRow, KVShard> writeQueryPlan2 = new KVWriteQueryPlanInsert("table2");
        assertTrue(broker.writeQuery(writeQueryPlan2, Collections.singletonList(new KVRow(1, 4))));

        ReadQueryPlan<KVShard, Integer> readQueryPlan2 = new KVPseudoBroadcastJoin("table1", "table2");
        assertEquals(Integer.valueOf(5), broker.readQuery(readQueryPlan2));

        for (int i = 0; i < num_datastores; i++) {
            dataStores.get(i).shutDown();
        }
        coordinator.stopServing();
        broker.shutdown();
    }

    @Test
    public void testBasicNestedQuery() {
        logger.info("testBasicNestedQuery");
        int numShards = 1;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7777);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        DataStore<KVRow, KVShard> dataStore =
                new DataStore<>(null,
                        new KVShardFactory(), Path.of("/var/tmp/KVUniserve"),
                        zkHost, zkPort, "127.0.0.1", 8000, -1);
        int d_r = dataStore.startServing();
        assertEquals(0, d_r);
        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);

        WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
        boolean writeSuccess = broker.writeQuery(writeQueryPlan, Collections.singletonList(new KVRow(0, 1)));
        assertTrue(writeSuccess);
        writeSuccess = broker.writeQuery(writeQueryPlan, Collections.singletonList(new KVRow(1, 2)));
        assertTrue(writeSuccess);

        ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanNested(0);
        Integer queryResponse = broker.readQuery(readQueryPlan);
        assertEquals(Integer.valueOf(2), queryResponse);

        dataStore.shutDown();
        coordinator.stopServing();
        broker.shutdown();
    }

    static abstract class BrokerThread extends Thread {
        public abstract Integer getQueryResponse();
    }

    @Test
    public void testSimultaneousReadQuery() throws InterruptedException {
        logger.info("testSimultaneousReadQuery");
        int numShards = 5;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7779);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        List<DataStore<KVRow, KVShard> > dataStores = new ArrayList<>();
        int numDataStores = 4;
        for (int i = 0; i < numDataStores; i++) {
            DataStore<KVRow, KVShard>  dataStore = new DataStore<>(new AWSDataStoreCloud("kraftp-uniserve"),
                    new KVShardFactory(), Path.of(String.format("/var/tmp/KVUniserve%d", i)),
                    zkHost, zkPort, "127.0.0.1", 8200 + i, -1);
            dataStore.runPingDaemon = false;
            int d_r = dataStore.startServing();
            assertEquals(0, d_r);
            dataStores.add(dataStore);
        }
        final Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);

        List<KVRow> rows = new ArrayList<>();
        for (int i = 1; i < 100; i++) {
            rows.add(new KVRow(i, i));
        }
        WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
        boolean writeSuccess = broker.writeQuery(writeQueryPlan, rows);
        assertTrue(writeSuccess);

        List<BrokerThread> brokerThreads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int finalI = i;
            BrokerThread brokerThread = new BrokerThread() {
                private Integer queryResponse = null;
                public void run() {
                    ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanSumGet(Collections.singletonList(finalI));
                    this.queryResponse = broker.readQuery(readQueryPlan);
                }
                public Integer getQueryResponse() {
                    return this.queryResponse;
                }
            };
            brokerThread.start();
            brokerThreads.add(brokerThread);
        }
        for (int i = 0; i < 100; i++) {
            BrokerThread brokerThread = brokerThreads.get(i);
            brokerThread.join();
            Integer queryResponse = brokerThread.getQueryResponse();
            assertEquals(Integer.valueOf(i), queryResponse);
        }
        for (int i = 0; i < numDataStores; i++) {
            dataStores.get(i).shutDown();
        }
        coordinator.stopServing();
        broker.shutdown();
    }

    @Test
    public void testReplication() {
        logger.info("testReplication");
        int numShards = 5;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7779);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        List<DataStore<KVRow, KVShard> > dataStores = new ArrayList<>();
        int num_datastores = 4;
        for (int i = 0; i < num_datastores; i++) {
            DataStore<KVRow, KVShard>  dataStore = new DataStore<>(new AWSDataStoreCloud("kraftp-uniserve"),
                    new KVShardFactory(), Path.of(String.format("/var/tmp/KVUniserve%d", i)),
                    zkHost, zkPort, "127.0.0.1", 8200 + i, -1);
            dataStore.runPingDaemon = false;
            int d_r = dataStore.startServing();
            assertEquals(0, d_r);
            dataStores.add(dataStore);
        }
        final Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);
        for (int i = 1; i < 6; i++) {
            WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
            boolean writeSuccess = broker.writeQuery(writeQueryPlan, Collections.singletonList(new KVRow(i, i)));
            assertTrue(writeSuccess);
            ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanGet(i);
            Integer queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(i), queryResponse);
        }

        coordinator.addReplica(0, 1);
        coordinator.addReplica(1, 2);
        coordinator.addReplica(2, 3);
        coordinator.addReplica(3, 2);
        coordinator.addReplica(3, 0);
        coordinator.addReplica(3, 1);
        for (int i = 1; i < 6; i++) {
            WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
            boolean writeSuccess = broker.writeQuery(writeQueryPlan, Arrays.asList(new KVRow(i, 2 * i),
                    new KVRow(2 * i, 4 * i), new KVRow(4 * i, 8 * i)));
            assertTrue(writeSuccess);
            ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanGet(i);
            Integer queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(2 * i), queryResponse);
            readQueryPlan = new KVReadQueryPlanGet(2 * i);
            queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(4 * i), queryResponse);
            readQueryPlan = new KVReadQueryPlanGet(4 *  i);
            queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(8 * i), queryResponse);
        }


        for (int i = 0; i < num_datastores; i++) {
            dataStores.get(i).shutDown();
        }
        coordinator.stopServing();
        broker.shutdown();
    }

     @Test
    public void testAddRemoveReplicas() throws InterruptedException {
        logger.info("testAddRemoveReplicas");
        int numShards = 5;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7779);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        List<DataStore<KVRow, KVShard> > dataStores = new ArrayList<>();
        int num_datastores = 4;
        for (int i = 0; i < num_datastores; i++) {
            DataStore<KVRow, KVShard>  dataStore = new DataStore<>(new AWSDataStoreCloud("kraftp-uniserve"),
                    new KVShardFactory(), Path.of(String.format("/var/tmp/KVUniserve%d", i)),
                    zkHost, zkPort, "127.0.0.1", 8200 + i, -1);
            dataStore.runPingDaemon = false;
            int d_r = dataStore.startServing();
            assertEquals(0, d_r);
            dataStores.add(dataStore);
        }
        final Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);
        for (int i = 1; i < 6; i++) {
            WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
            boolean writeSuccess = broker.writeQuery(writeQueryPlan, Collections.singletonList(new KVRow(i, i)));
            assertTrue(writeSuccess);
            ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanGet(i);
            Integer queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(i), queryResponse);
        }

        coordinator.addReplica(0, 1);
        coordinator.addReplica(1, 2);
        coordinator.addReplica(2, 3);
        coordinator.addReplica(3, 2);
        coordinator.addReplica(3, 0);
        coordinator.addReplica(3, 1);
        coordinator.addReplica(3, 1);

        for (int i = 1; i < 6; i++) {
            WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
            boolean writeSuccess = broker.writeQuery(writeQueryPlan, Arrays.asList(new KVRow(i, 2 * i),
                    new KVRow(2 * i, 4 * i), new KVRow(4 * i, 8 * i)));
            assertTrue(writeSuccess);
            ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanGet(i);
            Integer queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(2 * i), queryResponse);
            readQueryPlan = new KVReadQueryPlanGet(2 * i);
            queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(4 * i), queryResponse);
            readQueryPlan = new KVReadQueryPlanGet(4 *  i);
            queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(8 * i), queryResponse);
        }

        coordinator.removeShard(0, 1);
        coordinator.addReplica(0, 2);
        coordinator.removeShard(1, 1);
        coordinator.removeShard(3, 0);
        coordinator.removeShard(3, 3);
        coordinator.removeShard(3, 1);
        coordinator.addReplica(3, 3);
        coordinator.addReplica(0, 2);

        for (int i = 1; i < 6; i++) {
            WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
            boolean writeSuccess = broker.writeQuery(writeQueryPlan, Arrays.asList(new KVRow(i, 2 * i + 1),
                    new KVRow(2 * i, 4 * i + 1), new KVRow(4 * i, 8 * i + 1)));
            assertTrue(writeSuccess);
            ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanGet(i);
            Integer queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(2 * i + 1), queryResponse);
            readQueryPlan = new KVReadQueryPlanGet(2 * i);
            queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(4 * i + 1), queryResponse);
            readQueryPlan = new KVReadQueryPlanGet(4 *  i);
            queryResponse = broker.readQuery(readQueryPlan);
            assertEquals(Integer.valueOf(8 * i + 1), queryResponse);
        }
        coordinator.removeShard(2, 3);
        ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanGet(2);
        Integer queryResponse = broker.readQuery(readQueryPlan);
        assertEquals(Integer.valueOf(2 * 2 + 1), queryResponse);


        for (int i = 0; i < num_datastores; i++) {
            dataStores.get(i).shutDown();
        }
        coordinator.stopServing();
        broker.shutdown();
    }

    @Test
    public void testAbortedWrite() {
        logger.info("testAbortedWrite");
        int numShards = 4;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7777);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        DataStore<KVRow, KVShard>  dataStore = new DataStore<>(null, new KVShardFactory(),
                Path.of("/var/tmp/KVUniserve"), zkHost, zkPort, "127.0.0.1", 8000, -1);
        int d_r = dataStore.startServing();
        assertEquals(0, d_r);
        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);

        WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
        boolean writeSuccess = broker.writeQuery(writeQueryPlan, List.of(new KVRow(1, 1), new KVRow(2, 2), new KVRow(3, 2)));
        assertTrue(writeSuccess);

        writeSuccess = broker.writeQuery(writeQueryPlan, List.of(new KVRow(1, 3), new KVRow(2, 3), new KVRow(3, 3),
                new KVRow(123123123, 1)));
        assertFalse(writeSuccess);

        ReadQueryPlan<KVShard, Integer> readQueryPlan = new KVReadQueryPlanSumGet(Arrays.asList(1, 2, 3));
        Integer queryResponse = broker.readQuery(readQueryPlan);
        assertEquals(Integer.valueOf(5), queryResponse);

        dataStore.shutDown();
        coordinator.stopServing();
        broker.shutdown();
    }

    @Test
    public void testShardUpload() {
        logger.info("testShardUpload");
        int numShards = 1;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7777);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        DataStore<KVRow, KVShard> dataStore = new DataStore<>(new AWSDataStoreCloud("kraftp-uniserve"),
                new KVShardFactory(), Path.of("/var/tmp/KVUniserve"),
                zkHost, zkPort, "127.0.0.1", 8000, -1);
        int d_r = dataStore.startServing();
        assertEquals(0, d_r);
        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);

        WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
        boolean writeSuccess = broker.writeQuery(writeQueryPlan, Collections.singletonList(new KVRow(1, 2)));
        assertTrue(writeSuccess);

        Optional<KVShard> shard = dataStore.downloadShardFromCloud(0, "0_1", 1, true);
        assertTrue(shard.isPresent());

        dataStore.shutDown();
        coordinator.stopServing();
        broker.shutdown();
    }

    @Test
    public void testSimultaneousWrites() throws InterruptedException {
        logger.info("testSimultaneousWrites");
        int numShards = 4;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7779);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        List<DataStore<KVRow, KVShard> > dataStores = new ArrayList<>();
        int numDataStores = 4;
        for (int i = 0; i < numDataStores; i++) {
            DataStore<KVRow, KVShard>  dataStore = new DataStore<>(null,
                    new KVShardFactory(), Path.of(String.format("/var/tmp/KVUniserve%d", i)),
                    zkHost, zkPort, "127.0.0.1", 8200 + i, -1);
            dataStore.runPingDaemon = false;
            int d_r = dataStore.startServing();
            assertEquals(0, d_r);
            dataStores.add(dataStore);
        }
        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);

        List<Thread> threads = new ArrayList<>();
        int numThreads = 10;

        long startTime = System.currentTimeMillis();
        for (int threadNum = 0; threadNum < numThreads; threadNum++) {
            int finalThreadNum = threadNum;
            Thread t = new Thread(() -> {
                while (System.currentTimeMillis() < startTime + 1000) {
                    List<KVRow> insertList = new ArrayList<>();
                    for (int i = 0; i < numShards; i++) {
                        insertList.add(new KVRow(i, finalThreadNum));
                    }
                    WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
                    assertTrue(broker.writeQuery(writeQueryPlan, insertList));
                }
            });
            t.start();
            threads.add(t);
        }

        Thread.sleep(2000);
        ThreadMXBean mbean = ManagementFactory.getThreadMXBean();
        long[] ds = mbean.findDeadlockedThreads();
        if (ds != null) {
            ThreadInfo[] ts = mbean.getThreadInfo(ds);
            for (ThreadInfo t : ts) {
                logger.info("{} {} {}", t, t.getThreadState(), t.getStackTrace());
                for (StackTraceElement e : t.getStackTrace()) {
                    logger.info("{}", e);
                }
            }
        }

        for(Thread t: threads) {
            t.join();
        }

        for (int i = 0; i < numDataStores; i++) {
            dataStores.get(i).shutDown();
        }
        coordinator.stopServing();
        broker.shutdown();
    }

    // @Test  // TODO:  Fix rare deadlock.
    public void testSimultaneousWritesReplicas() throws InterruptedException {
        logger.info("testSimultaneousWritesReplicas");
        int numShards = 4;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7779);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        List<DataStore<KVRow, KVShard> > dataStores = new ArrayList<>();
        int numDataStores = numShards;
        for (int i = 0; i < numDataStores; i++) {
            DataStore<KVRow, KVShard>  dataStore = new DataStore<>(new AWSDataStoreCloud("kraftp-uniserve"),
                    new KVShardFactory(), Path.of(String.format("/var/tmp/KVUniserve%d", i)),
                    zkHost, zkPort, "127.0.0.1", 8200 + i, -1);
            dataStore.runPingDaemon = false;
            int d_r = dataStore.startServing();
            assertEquals(0, d_r);
            dataStores.add(dataStore);
        }
        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);

        List<Thread> threads = new ArrayList<>();
        int numThreads = 10;

        List<KVRow> firstList = new ArrayList<>();
        for (int i = 0; i < numShards; i++) {
            firstList.add(new KVRow(i, 0));
        }
        WriteQueryPlan<KVRow, KVShard> firstPlan = new KVWriteQueryPlanInsert();
        assertTrue(broker.writeQuery(firstPlan, firstList));

        for (int i = 0; i < numShards; i++) {
            coordinator.addReplica(i, (i + 1) % numShards);
        }

        long startTime = System.currentTimeMillis();
        for (int threadNum = 0; threadNum < numThreads; threadNum++) {
            int finalThreadNum = threadNum;
            Thread t = new Thread(() -> {
                while (System.currentTimeMillis() < startTime + 1000) {
                    List<KVRow> insertList = new ArrayList<>();
                    for (int i = 0; i < numShards; i++) {
                        insertList.add(new KVRow(i, finalThreadNum));
                    }
                    WriteQueryPlan<KVRow, KVShard> writeQueryPlan = new KVWriteQueryPlanInsert();
                    assertTrue(broker.writeQuery(writeQueryPlan, insertList));
                }
            });
            t.start();
            threads.add(t);
        }

        Thread.sleep(2000);
        ThreadMXBean mbean = ManagementFactory.getThreadMXBean();
        long[] ds = mbean.findDeadlockedThreads();
        if (ds != null) {
            ThreadInfo[] ts = mbean.getThreadInfo(ds);
            for (ThreadInfo t : ts) {
                logger.info("{} {} {}", t, t.getThreadState(), t.getStackTrace());
                for (StackTraceElement e : t.getStackTrace()) {
                    logger.info("{}", e);
                }
            }
        }

        for(Thread t: threads) {
            t.join();
        }

        for (int i = 0; i < numDataStores; i++) {
            dataStores.get(i).shutDown();
        }
        coordinator.stopServing();
        broker.shutdown();
    }

    @Test
    public void testMaterializedViews() {
        logger.info("testMaterializedViews");
        int numShards = 4;
        Coordinator coordinator = new Coordinator(null, zkHost, zkPort, "127.0.0.1", 7779);
        coordinator.runLoadBalancerDaemon = false;
        int c_r = coordinator.startServing();
        assertEquals(0, c_r);
        List<DataStore<KVRow, KVShard>> dataStores = new ArrayList<>();
        int numDataStores = numShards;
        for (int i = 0; i < numDataStores; i++) {
            DataStore<KVRow, KVShard> dataStore = new DataStore<>(new AWSDataStoreCloud("kraftp-uniserve"),
                    new KVShardFactory(), Path.of(String.format("/var/tmp/KVUniserve%d", i)),
                    zkHost, zkPort, "127.0.0.1", 8200 + i, -1);
            dataStore.runPingDaemon = false;
            int d_r = dataStore.startServing();
            assertEquals(0, d_r);
            dataStores.add(dataStore);
        }
        Broker broker = new Broker(zkHost, zkPort, new KVQueryEngine());
        broker.createTable("table", numShards);

        WriteQueryPlan<KVRow, KVShard> w = new KVWriteQueryPlanInsert();
        broker.writeQuery(w, Collections.singletonList(new KVRow(0, 0, 0)));

        ReadQueryPlan<KVShard, Integer> r = new KVMaterializedViewSum();
        broker.registerMaterializedView(r, "rmv");

        coordinator.addReplica(0, 1);

        broker.writeQuery(w, Collections.singletonList(new KVRow(numShards, numShards, numShards)));

        broker.registerMaterializedView(r, "rmv2");

        Integer v;
        int sum = numShards;
        for (int i = numShards + 1; i < 10; i++) {
            broker.writeQuery(w, Collections.singletonList(new KVRow(i, i, i)));
            sum += i;
            v = broker.queryMaterializedView(r, "rmv");
            assertEquals(sum, v);
            v = broker.queryMaterializedView(r, "rmv2");
            assertEquals(sum, v);
        }

        broker.writeQuery(w, Collections.singletonList(new KVRow(500, 500, 105)));
        sum += 500;
        v = broker.queryMaterializedView(r, "rmv");
        assertEquals(sum, v);
        broker.writeQuery(w, Collections.singletonList(new KVRow(400, 400, 103)));
        sum += 400;
        v = broker.queryMaterializedView(r, "rmv");
        assertEquals(sum, v);

        for (int i = 0; i < numDataStores; i++) {
            dataStores.get(i).shutDown();
        }
        coordinator.stopServing();
        broker.shutdown();
    }
}
