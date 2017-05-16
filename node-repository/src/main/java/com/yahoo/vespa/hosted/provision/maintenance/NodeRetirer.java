package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.Zone;
import com.yahoo.transaction.Mutex;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.maintenance.retire.RetirementPolicy;
import com.yahoo.vespa.hosted.provision.node.Agent;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * @author freva
 */
public class NodeRetirer extends Maintainer {
    private static final Logger log = Logger.getLogger(NodeRetirer.class.getName());
    private final RetirementPolicy retirementPolicy;

    public NodeRetirer(NodeRepository nodeRepository, Zone zone, Duration interval, JobControl jobControl,
                       RetirementPolicy retirementPolicy, Zone... applies) {
        super(nodeRepository, interval, jobControl);
        if (! Arrays.asList(applies).contains(zone)) {
            String targetZones = Arrays.stream(applies).map(Zone::toString).collect(Collectors.joining(", "));
            log.info("NodeRetirer should only run in " + targetZones + " and not in " + zone + ", stopping.");
            deconstruct();
        }

        this.retirementPolicy = retirementPolicy;
    }

    @Override
    protected void maintain() {
        retireUnallocated();
    }

    boolean retireUnallocated() {
        try (Mutex lock = nodeRepository().lockUnallocated()) {
            List<Node> allNodes = nodeRepository().getNodes();
            Map<Flavor, Long> numSpareNodesByFlavor = getNumberSpareReadyNodesByFlavor(allNodes);

            long numFlavorsWithUnsuccessfullyRetiredNodes = allNodes.stream()
                    .filter(node -> node.state() == Node.State.ready)
                    .filter(retirementPolicy::shouldRetire)
                    .collect(Collectors.groupingBy(
                            Node::flavor,
                            Collectors.toSet()))
                    .entrySet().stream()
                    .filter(entry -> {
                        Set<Node> nodesThatShouldBeRetiredForFlavor = entry.getValue();
                        long numSpareReadyNodesForFlavor = numSpareNodesByFlavor.get(entry.getKey());
                        return !limitedPark(nodesThatShouldBeRetiredForFlavor, numSpareReadyNodesForFlavor);
                    }).count();

            return numFlavorsWithUnsuccessfullyRetiredNodes == 0;
        }
    }

    /**
     * @param nodesToPark Nodes that we want to park
     * @param limit Maximum number of nodes we want to park
     * @return True iff we were able to park all the nodes
     */
    boolean limitedPark(Set<Node> nodesToPark, long limit) {
        nodesToPark.stream()
                .limit(limit)
                .forEach(node -> nodeRepository().park(node.hostname(), Agent.NodeRetirer, "Policy: " + retirementPolicy.getClass().getSimpleName()));

        return limit >= nodesToPark.size();
    }

    Map<Flavor, Long> getNumberSpareReadyNodesByFlavor(List<Node> allNodes) {
        Map<Flavor, Long> numActiveNodesByFlavor = allNodes.stream()
                .filter(node -> node.state() == Node.State.active)
                .collect(Collectors.groupingBy(Node::flavor, Collectors.counting()));

        return allNodes.stream()
                .filter(node -> node.state() == Node.State.ready)
                .collect(Collectors.groupingBy(Node::flavor, Collectors.counting()))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            long numActiveNodesByCurrentFlavor = numActiveNodesByFlavor.getOrDefault(entry.getKey(), 0L);
                            return getNumSpareNodes(numActiveNodesByCurrentFlavor, entry.getValue());
                }));
    }

    /**
     * Returns number of ready nodes to spare (beyond a safety buffer) for a flavor given its number of active
     * and ready nodes.
     */
    long getNumSpareNodes(long numActiveNodes, long numReadyNodes) {
        long numNodesToSpare = (long) Math.ceil(0.1 * numActiveNodes);
        return Math.max(0L, numReadyNodes - numNodesToSpare);
    }
}