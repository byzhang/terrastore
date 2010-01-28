/**
 * Copyright 2009 - 2010 Sergio Bossa (sergio.bossa@gmail.com)
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package terrastore.cluster.impl;

import com.tc.cluster.DsoCluster;
import com.tc.cluster.DsoClusterEvent;
import com.tc.cluster.DsoClusterListener;
import com.tc.cluster.DsoClusterTopology;
import com.tc.cluster.simulation.SimulatedDsoCluster;
import com.tc.injection.annotations.InjectedDsoInstance;
import com.tcclient.cluster.DsoNode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.modules.annotations.HonorTransient;
import org.terracotta.modules.annotations.InstrumentedClass;
import org.terracotta.modules.annotations.Root;
import terrastore.communication.Node;
import terrastore.cluster.Cluster;
import terrastore.store.FlushCondition;
import terrastore.store.FlushStrategy;
import terrastore.communication.local.LocalNode;
import terrastore.communication.local.LocalProcessor;
import terrastore.communication.remote.RemoteProcessor;
import terrastore.communication.remote.RemoteNode;
import terrastore.router.Router;
import terrastore.store.Store;
import terrastore.store.impl.TCStore;

/**
 * @author Sergio Bossa
 */
@InstrumentedClass
@HonorTransient
public class TCCluster implements Cluster, DsoClusterListener {

    private static final Logger LOG = LoggerFactory.getLogger(TCCluster.class);
    //
    @Root
    private static final TCCluster INSTANCE = new TCCluster();
    //
    @InjectedDsoInstance
    private DsoCluster dsoCluster;
    //
    private Store store = new TCStore();
    private ReentrantLock stateLock = new ReentrantLock();
    private Map<String, Address> addressTable = new HashMap<String, Address>();
    private Condition setupAddressCondition = stateLock.newCondition();
    //
    private volatile transient String thisNodeName;
    private volatile transient String thisNodeHost;
    private volatile transient int thisNodePort;
    private volatile transient ConcurrentMap<String, Node> nodes;
    private volatile transient LocalProcessor localProcessor;
    private volatile transient RemoteProcessor remoteProcessor;
    //
    private volatile transient long nodeTimeout;
    private volatile transient int workerThreads;
    //
    private volatile transient ExecutorService globalExecutor;
    //
    private volatile transient Router router;
    private volatile transient FlushStrategy flushStrategy;
    private volatile transient FlushCondition flushCondition;

    private TCCluster() {
    }

    public static TCCluster getInstance() {
        return INSTANCE;
    }

    @Override
    public void setNodeTimeout(long nodeTimeout) {
        this.nodeTimeout = nodeTimeout;
    }

    @Override
    public void setWokerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public ExecutorService getGlobalExecutor() {
        return globalExecutor;
    }

    public Router getRouter() {
        return router;
    }

    public FlushStrategy getFlushStrategy() {
        return flushStrategy;
    }

    public FlushCondition getFlushCondition() {
        return flushCondition;
    }

    public void setRouter(Router router) {
        this.router = router;
    }

    public void setFlushStrategy(FlushStrategy flushStrategy) {
        this.flushStrategy = flushStrategy;
    }

    public void setFlushCondition(FlushCondition flushCondition) {
        this.flushCondition = flushCondition;
    }

    public void start(String host, int port) {
        stateLock.lock();
        try {
            thisNodeName = getServerId(dsoCluster.getCurrentNode());
            thisNodeHost = host;
            thisNodePort = port;
            nodes = new ConcurrentHashMap<String, Node>();
            globalExecutor = Executors.newFixedThreadPool(workerThreads);
            getDsoCluster().addClusterListener(this);
        } catch (Exception ex) {
            LOG.error(ex.getMessage(), ex);
        } finally {
            stateLock.unlock();
        }
    }

    public void operationsEnabled(DsoClusterEvent event) {
    }

    public void nodeJoined(DsoClusterEvent event) {
        String joinedNodeName = getServerId(event.getNode());
        if (isThisNode(joinedNodeName)) {
            stateLock.lock();
            try {
                LOG.info("Joining this node {}", thisNodeName);
                setupThisNode();
                setupThisRemoteProcessor();
                setupAddressTable();
                setupRemoteNodes();
            } catch (Exception ex) {
                LOG.error(ex.getMessage(), ex);
            } finally {
                stateLock.unlock();
            }
        } else {
            stateLock.lock();
            try {
                LOG.info("Joining remote node {}", joinedNodeName);
                connectRemoteNode(joinedNodeName);
            } catch (Exception ex) {
                LOG.error(ex.getMessage(), ex);
            } finally {
                stateLock.unlock();
                pauseProcessing();
                flushThisNodeKeys();
                resumeProcessing();
            }
        }
    }

    public void nodeLeft(DsoClusterEvent event) {
        String leftNodeName = getServerId(event.getNode());
        if (!isThisNode(leftNodeName)) {
            stateLock.lock();
            try {
                disconnectRemoteNode(leftNodeName);
            } catch (Exception ex) {
                LOG.error(ex.getMessage(), ex);
            } finally {
                stateLock.unlock();
                pauseProcessing();
                flushThisNodeKeys();
                resumeProcessing();
            }
        }
    }

    public void operationsDisabled(DsoClusterEvent event) {
        try {
            LOG.info("Disabling cluster node {}", thisNodeName);
            disconnectEverything();
            cleanupEverything();
        } catch (Exception ex) {
            LOG.error(ex.getMessage(), ex);
        } finally {
            doExit();
        }
    }

    private DsoCluster getDsoCluster() {
        if (dsoCluster != null) {
            return dsoCluster;
        } else {
            return new SimulatedDsoCluster();
        }
    }

    private String getServerId(DsoNode dsoNode) {
        return dsoNode.getId().replace("Client", "Server");
    }

    private boolean isThisNode(String candidateNodeName) {
        return thisNodeName.equals(candidateNodeName);
    }

    private void setupThisRemoteProcessor() {
        remoteProcessor = new RemoteProcessor(thisNodeHost, thisNodePort, store, workerThreads);
        remoteProcessor.start();
        LOG.info("Set up processor for {}", thisNodeName);
    }

    private void setupThisNode() {
        localProcessor = new LocalProcessor(store, workerThreads);
        LocalNode thisNode = new LocalNode(thisNodeName, localProcessor);
        localProcessor.start();
        thisNode.connect();
        nodes.put(thisNodeName, thisNode);
        router.setLocalNode(thisNode);
        router.addRouteTo(thisNode);
        LOG.info("Set up this node {}", thisNodeName);
    }

    private void setupAddressTable() {
        addressTable.put(thisNodeName, new Address(thisNodeHost, thisNodePort));
        setupAddressCondition.signalAll();
    }

    private void setupRemoteNodes() throws InterruptedException {
        DsoClusterTopology dsoTopology = getDsoCluster().getClusterTopology();
        for (DsoNode dsoNode : dsoTopology.getNodes()) {
            String serverId = getServerId(dsoNode);
            if (!isThisNode(serverId)) {
                String remoteNodeName = serverId;
                connectRemoteNode(remoteNodeName);
            }
        }
    }

    private void connectRemoteNode(String remoteNodeName) throws InterruptedException {
        while (!addressTable.containsKey(remoteNodeName)) {
            setupAddressCondition.await(1000, TimeUnit.MILLISECONDS);
        }
        Address remoteNodeAddress = addressTable.get(remoteNodeName);
        if (remoteNodeAddress != null) {
            Node remoteNode = new RemoteNode(remoteNodeAddress.getHost(), remoteNodeAddress.getPort(), remoteNodeName, nodeTimeout, router.getLocalNode());
            remoteNode.connect();
            nodes.put(remoteNodeName, remoteNode);
            router.addRouteTo(remoteNode);
            LOG.info("Set up remote node {}", remoteNodeName);
        } else {
            LOG.warn("Cannot set up remote node {}", remoteNodeName);
        }
    }

    private void pauseProcessing() {
        localProcessor.pause();
        remoteProcessor.pause();
    }

    private void resumeProcessing() {
        localProcessor.resume();
        remoteProcessor.resume();
    }

    private void flushThisNodeKeys() {
        LOG.info("About to flush keys on node {}", thisNodeName);
        store.flush(flushStrategy, flushCondition);
    }

    private void disconnectRemoteNode(String nodeName) {
        Node remoteNode = nodes.remove(nodeName);
        remoteNode.disconnect();
        router.removeRouteTo(remoteNode);
        LOG.info("Discarded node {}", nodeName);
    }

    private void disconnectEverything() {
        for (Node node : nodes.values()) {
            node.disconnect();
        }
        localProcessor.stop();
        remoteProcessor.stop();
    }

    private void cleanupEverything() {
        nodes.clear();
        router.cleanup();
        globalExecutor.shutdownNow();
    }

    private void doExit() {
        new Thread() {

            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (Exception ex) {
                } finally {
                    LOG.info("Exiting cluster node {}", thisNodeName);
                    System.exit(0);
                }
            }
        }.start();
    }

    @InstrumentedClass
    private static class Address {

        private String host;
        private int port;

        public Address(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }
    }
}
