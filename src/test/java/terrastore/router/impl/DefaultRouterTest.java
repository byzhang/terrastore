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
package terrastore.router.impl;

import com.google.common.collect.Sets;
import java.util.Set;
import org.junit.Test;
import terrastore.communication.Cluster;
import terrastore.communication.Node;
import terrastore.partition.ClusterPartitioner;
import terrastore.partition.EnsemblePartitioner;
import static org.junit.Assert.*;
import static org.easymock.classextension.EasyMock.*;
import terrastore.router.MissingRouteException;

/**
 * @author Sergio Bossa
 */
public class DefaultRouterTest {

    @Test
    public void testRouteToBucket() throws MissingRouteException {
        String bucket = "bucket";

        EnsemblePartitioner ensemblePartitioner = createMock(EnsemblePartitioner.class);
        ClusterPartitioner clusterPartitioner = createMock(ClusterPartitioner.class);
        Cluster cluster1 = createMock(Cluster.class);
        Cluster cluster2 = createMock(Cluster.class);
        Node node = createMock(Node.class);

        ensemblePartitioner.setupClusters(Sets.newHashSet(cluster1, cluster2));
        expectLastCall().once();
        ensemblePartitioner.getClusterFor(bucket);
        expectLastCall().andReturn(cluster1).once();
        clusterPartitioner.addNode(cluster1, node);
        expectLastCall().once();
        clusterPartitioner.getNodeFor(cluster1, bucket);
        expectLastCall().andReturn(node).once();
        cluster1.getName();
        expectLastCall().andReturn("cluster1").anyTimes();
        cluster2.getName();
        expectLastCall().andReturn("cluster2").anyTimes();
        node.getName();
        expectLastCall().andReturn("node").anyTimes();

        replay(ensemblePartitioner, clusterPartitioner, cluster1, cluster2, node);

        DefaultRouter router = new DefaultRouter(clusterPartitioner, ensemblePartitioner);
        router.setupClusters(Sets.newHashSet(cluster1, cluster2));
        router.addRouteTo(cluster1, node);
        assertSame(node, router.routeToNodeFor(bucket));

        verify(ensemblePartitioner, clusterPartitioner, cluster1, cluster2, node);
    }

    @Test
    public void testRouteToBucketAndKey() throws MissingRouteException {
        String bucket = "bucket";
        String key = "key";

        EnsemblePartitioner ensemblePartitioner = createMock(EnsemblePartitioner.class);
        ClusterPartitioner clusterPartitioner = createMock(ClusterPartitioner.class);
        Cluster cluster1 = createMock(Cluster.class);
        Cluster cluster2 = createMock(Cluster.class);
        Node node = createMock(Node.class);

        ensemblePartitioner.setupClusters(Sets.newHashSet(cluster1, cluster2));
        expectLastCall().once();
        ensemblePartitioner.getClusterFor(bucket, key);
        expectLastCall().andReturn(cluster1).once();
        clusterPartitioner.addNode(cluster1, node);
        expectLastCall().once();
        clusterPartitioner.getNodeFor(cluster1, bucket, key);
        expectLastCall().andReturn(node).once();
        cluster1.getName();
        expectLastCall().andReturn("cluster1").anyTimes();
        cluster2.getName();
        expectLastCall().andReturn("cluster2").anyTimes();
        node.getName();
        expectLastCall().andReturn("node").anyTimes();

        replay(ensemblePartitioner, clusterPartitioner, cluster1, cluster2, node);

        DefaultRouter router = new DefaultRouter(clusterPartitioner, ensemblePartitioner);
        router.setupClusters(Sets.newHashSet(cluster1, cluster2));
        router.addRouteTo(cluster1, node);
        assertSame(node, router.routeToNodeFor(bucket, key));

        verify(ensemblePartitioner, clusterPartitioner, cluster1, cluster2, node);
    }

    @Test
    public void testClusterRoute() throws MissingRouteException {
        EnsemblePartitioner ensemblePartitioner = createMock(EnsemblePartitioner.class);
        ClusterPartitioner clusterPartitioner = createMock(ClusterPartitioner.class);
        Cluster cluster1 = createMock(Cluster.class);
        Cluster cluster2 = createMock(Cluster.class);
        Node node1 = createMock(Node.class);
        Node node2 = createMock(Node.class);

        ensemblePartitioner.setupClusters(Sets.newHashSet(cluster1, cluster2));
        expectLastCall().once();
        clusterPartitioner.addNode(cluster1, node1);
        expectLastCall().once();
        clusterPartitioner.addNode(cluster1, node2);
        expectLastCall().once();
        clusterPartitioner.getNodesFor(cluster1);
        expectLastCall().andReturn(Sets.newHashSet(node1, node2)).once();
        cluster1.getName();
        expectLastCall().andReturn("cluster1").anyTimes();
        cluster2.getName();
        expectLastCall().andReturn("cluster2").anyTimes();
        node1.getName();
        expectLastCall().andReturn("node1").anyTimes();
        node2.getName();
        expectLastCall().andReturn("node2").anyTimes();

        replay(ensemblePartitioner, clusterPartitioner, cluster1, cluster2, node1, node2);

        DefaultRouter router = new DefaultRouter(clusterPartitioner, ensemblePartitioner);
        router.setupClusters(Sets.newHashSet(cluster1, cluster2));
        router.addRouteTo(cluster1, node1);
        router.addRouteTo(cluster1, node2);
        Set<Node> nodes = router.clusterRoute(cluster1);
        assertEquals(2, nodes.size());
        assertTrue(nodes.contains(node1));
        assertTrue(nodes.contains(node2));

        verify(ensemblePartitioner, clusterPartitioner, cluster1, cluster2, node1, node2);
    }

    @Test
    public void testBroadcastRoute() throws MissingRouteException {
        EnsemblePartitioner ensemblePartitioner = createMock(EnsemblePartitioner.class);
        ClusterPartitioner clusterPartitioner = createMock(ClusterPartitioner.class);
        Cluster cluster1 = createMock(Cluster.class);
        Cluster cluster2 = createMock(Cluster.class);
        Node node1 = createMock(Node.class);
        Node node2 = createMock(Node.class);

        ensemblePartitioner.setupClusters(Sets.newHashSet(cluster1, cluster2));
        expectLastCall().once();
        clusterPartitioner.addNode(cluster2, node2);
        expectLastCall().once();
        clusterPartitioner.getNodesFor(cluster2);
        expectLastCall().andReturn(Sets.newHashSet(node2)).once();
        cluster1.isLocal();
        expectLastCall().andReturn(true).anyTimes();
        cluster2.isLocal();
        expectLastCall().andReturn(false).anyTimes();
        cluster1.getName();
        expectLastCall().andReturn("cluster1").anyTimes();
        cluster2.getName();
        expectLastCall().andReturn("cluster2").anyTimes();
        node1.getName();
        expectLastCall().andReturn("node1").anyTimes();
        node2.getName();
        expectLastCall().andReturn("node2").anyTimes();

        replay(ensemblePartitioner, clusterPartitioner, cluster1, cluster2, node1, node2);

        DefaultRouter router = new DefaultRouter(clusterPartitioner, ensemblePartitioner);
        router.setupClusters(Sets.newHashSet(cluster1, cluster2));
        router.addRouteToLocalNode(node1);
        router.addRouteTo(cluster2, node2);
        Set<Node> nodes = router.broadcastRoute();
        assertEquals(2, nodes.size());
        assertTrue(nodes.contains(node1));
        assertTrue(nodes.contains(node2));

        verify(ensemblePartitioner, clusterPartitioner, cluster1, cluster2, node1, node2);
    }
}