package org.fog.placement;

import org.fog.utils.Logger;

/**
 * Created by Samodha Pallewatta.
 */
public class PlacementLogicFactory {

    public static final int EDGEWART_MICROSERCVICES_PLACEMENT = 1;
    public static final int CLUSTERED_MICROSERVICES_PLACEMENT = 2;
    public static final int DISTRIBUTED_MICROSERVICES_PLACEMENT = 3;
    /** Delegates placement decisions to an external Python agent via a TCP socket bridge. */
    public static final int PYTHON_BRIDGE_PLACEMENT = 4;
    /**
     * Delegates placement AND migration decisions to an external Python DRL/PPO agent,
     * called repeatedly throughout the simulation (one state/action/reward exchange per
     * MicroservicePlacementConfig.PLACEMENT_INTERVAL) instead of once at time 0.
     */
    public static final int PPO_BRIDGE_PLACEMENT = 5;
    /** Shared service-level PPO bridge with local rewards and masked actions. */
    public static final int SHARED_PPO_BRIDGE_PLACEMENT = 6;

    public MicroservicePlacementLogic getPlacementLogic(int logic, int fonId) {
        switch (logic) {
//            case EDGEWART_MICROSERCVICES_PLACEMENT:
//                return new EdgewardMicroservicePlacementLogic(fonId);
            case CLUSTERED_MICROSERVICES_PLACEMENT:
                return new ClusteredMicroservicePlacementLogic(fonId);
            case DISTRIBUTED_MICROSERVICES_PLACEMENT:
                return new DistributedMicroservicePlacementLogic(fonId);
            case PYTHON_BRIDGE_PLACEMENT:
                return new PythonBridgePlacementLogic(fonId);
            case PPO_BRIDGE_PLACEMENT:
                return new PPOBridgePlacementLogic(fonId);
            case SHARED_PPO_BRIDGE_PLACEMENT:
                return new SharedPolicyPPOBridgePlacementLogic(fonId);
        }

        Logger.error("Placement Logic Error", "Error initializing placement logic");
        return null;
    }

}
