/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.cluster.coordination;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.admin.cluster.bootstrap.BootstrapClusterAction;
import org.elasticsearch.action.admin.cluster.bootstrap.BootstrapClusterRequest;
import org.elasticsearch.action.admin.cluster.bootstrap.BootstrapClusterResponse;
import org.elasticsearch.action.admin.cluster.bootstrap.BootstrapConfiguration;
import org.elasticsearch.action.admin.cluster.bootstrap.GetDiscoveredNodesAction;
import org.elasticsearch.action.admin.cluster.bootstrap.GetDiscoveredNodesRequest;
import org.elasticsearch.action.admin.cluster.bootstrap.GetDiscoveredNodesResponse;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.threadpool.ThreadPool.Names;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class ClusterBootstrapService {

    private static final Logger logger = LogManager.getLogger(ClusterBootstrapService.class);

    // The number of master-eligible nodes which, if discovered, can be used to bootstrap the cluster. This setting is unsafe in the event
    // that more master nodes are started than expected.
    public static final Setting<Integer> INITIAL_MASTER_NODE_COUNT_SETTING =
        Setting.intSetting("cluster.unsafe_initial_master_node_count", 0, 0, Property.NodeScope);

    public static final Setting<List<String>> INITIAL_MASTER_NODES_SETTING =
        Setting.listSetting("cluster.initial_master_nodes", Collections.emptyList(), Function.identity(), Property.NodeScope);

    private final int initialMasterNodeCount;
    private final List<String> initialMasterNodes;
    private final TransportService transportService;
    private volatile boolean running;

    public ClusterBootstrapService(Settings settings, TransportService transportService) {
        initialMasterNodeCount = INITIAL_MASTER_NODE_COUNT_SETTING.get(settings);
        initialMasterNodes = INITIAL_MASTER_NODES_SETTING.get(settings);
        this.transportService = transportService;
    }

    public void start() {
        assert running == false;
        running = true;

        if ((initialMasterNodeCount > 0 || initialMasterNodes.isEmpty() == false) && transportService.getLocalNode().isMasterNode()) {
            logger.debug("unsafely waiting for discovery of [{}] master-eligible nodes", initialMasterNodeCount);

            final ThreadContext threadContext = transportService.getThreadPool().getThreadContext();
            try (ThreadContext.StoredContext ignore = threadContext.stashContext()) {
                threadContext.markAsSystemContext();

                final GetDiscoveredNodesRequest request = new GetDiscoveredNodesRequest();
                if (initialMasterNodeCount > 0) {
                    request.setWaitForNodes(initialMasterNodeCount);
                }
                request.setRequiredNodes(initialMasterNodes);
                request.setTimeout(null);
                logger.trace("sending {}", request);
                transportService.sendRequest(transportService.getLocalNode(), GetDiscoveredNodesAction.NAME, request,
                    new TransportResponseHandler<GetDiscoveredNodesResponse>() {
                        @Override
                        public void handleResponse(GetDiscoveredNodesResponse response) {
                            assert response.getNodes().size() >= initialMasterNodeCount;
                            assert response.getNodes().stream().allMatch(DiscoveryNode::isMasterNode);
                            logger.debug("discovered {}, starting to bootstrap", response.getNodes());
                            awaitBootstrap(response.getBootstrapConfiguration());
                        }

                        @Override
                        public void handleException(TransportException exp) {
                            logger.warn("discovery attempt failed", exp);
                        }

                        @Override
                        public String executor() {
                            return Names.SAME;
                        }

                        @Override
                        public GetDiscoveredNodesResponse read(StreamInput in) throws IOException {
                            return new GetDiscoveredNodesResponse(in);
                        }
                    });
            }
        }
    }

    public void stop() {
        assert running == true;
        running = false;
    }

    private void awaitBootstrap(final BootstrapConfiguration bootstrapConfiguration) {
        if (running == false) {
            logger.debug("awaitBootstrap: not running");
            return;
        }

        BootstrapClusterRequest request = new BootstrapClusterRequest(bootstrapConfiguration);
        logger.trace("sending {}", request);
        transportService.sendRequest(transportService.getLocalNode(), BootstrapClusterAction.NAME, request,
            new TransportResponseHandler<BootstrapClusterResponse>() {
                @Override
                public void handleResponse(BootstrapClusterResponse response) {
                    logger.debug("automatic cluster bootstrapping successful: received {}", response);
                }

                @Override
                public void handleException(TransportException exp) {
                    // log a warning since a failure here indicates a bad problem, such as:
                    // - bootstrap configuration resolution failed (e.g. discovered nodes no longer match those in the bootstrap config)
                    // - discovered nodes no longer form a quorum in the bootstrap config
                    logger.warn(new ParameterizedMessage("automatic cluster bootstrapping failed, retrying [{}]",
                        bootstrapConfiguration.getNodeDescriptions()), exp);

                    // There's not really much else we can do apart from retry and hope that the problem goes away. The retry is delayed
                    // since a tight loop here is unlikely to help.
                    transportService.getThreadPool().scheduleUnlessShuttingDown(TimeValue.timeValueSeconds(10), Names.SAME, new Runnable() {
                        @Override
                        public void run() {
                            awaitBootstrap(bootstrapConfiguration);
                        }

                        @Override
                        public String toString() {
                            return "retry bootstrapping with " + bootstrapConfiguration.getNodeDescriptions();
                        }
                    });
                }

                @Override
                public String executor() {
                    return Names.SAME;
                }

                @Override
                public BootstrapClusterResponse read(StreamInput in) throws IOException {
                    return new BootstrapClusterResponse(in);
                }
            });
    }
}
