package org.fog.placement;

import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.ControllerComponent;
import org.fog.entities.FogDevice;
import org.fog.entities.PlacementRequest;
import org.fog.utils.Config;
import org.fog.utils.FogEvents;
import org.fog.utils.MicroservicePlacementConfig;
import org.fog.utils.TrainingLog;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.*;

/**
 * Revised bridge for a service-level shared PPO policy.
 *
 * Step zero deliberately uses the legacy placement schema so Python can call
 * GeneticAgent unchanged.  Later steps expose a bounded set of independent
 * service actors.  Each actor sees the same fixed feature vocabulary and a
 * variable candidate list with an explicit feasibility flag.  Rewards contain
 * only energy attributed to the actor's current host and the latency of its own
 * request, avoiding the global-state/global-reward credit-assignment problem.
 */
public class SharedPolicyPPOBridgePlacementLogic extends PPOBridgePlacementLogic {
    private int sharedStep = 0;
    private final long episodeSeed = Long.getLong("ifogsim.episode.seed", 1L);
    private final Map<Integer, Double> previousEnergy = new HashMap<>();
    private int actorCursor = 0;
    private final Map<String, String> previousOutcome = new HashMap<>();

    public static int ACCEPTED_MIGRATIONS = 0;
    public static int REJECTED_MIGRATIONS = 0;
    public static double ACTOR_REWARD_SUM = 0.0;
    public static long ACTOR_REWARD_COUNT = 0;

    public SharedPolicyPPOBridgePlacementLogic(int fonId) {
        super(fonId,
                System.getProperty("ifogsim.bridge.host", DEFAULT_HOST),
                Integer.getInteger("ifogsim.bridge.port", DEFAULT_PORT));
    }

    public static void clearSharedLogs() {
        clearLogs();
        ACCEPTED_MIGRATIONS = 0;
        REJECTED_MIGRATIONS = 0;
        ACTOR_REWARD_SUM = 0.0;
        ACTOR_REWARD_COUNT = 0;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void mapModules() {
        stepCounter = sharedStep; // keeps inherited placement/migration logs correctly indexed
        initializePlacementMaps();
        forceSpecialPlacements();
        boolean done = CloudSim.clock() + MicroservicePlacementConfig.PLACEMENT_INTERVAL
                >= Config.MAX_SIMULATION_TIME;

        try {
            JSONObject state;
            if (sharedStep == 0) {
                state = buildInitialState();
            } else {
                Map<Integer, Double> energyDeltas = readEnergyDeltas();
                List<ServiceActor> actors = selectActors(done);
                state = buildSharedState(actors, energyDeltas, done);
                logMeanReward(state, done);
            }
            String response = queryPythonAgent(state.toJSONString());
            if (response != null && !response.trim().isEmpty()) {
                applySharedResponse(response, sharedStep == 0);
            }
        } catch (Exception e) {
            System.err.println("[SharedPPOBridge] Communication error: " + e.getMessage());
            e.printStackTrace();
        }

        snapshotEnergy();
        sharedStep++;
        if (!done) {
            // The periodic fog-device loop clears its request queue on every tick.
            // Reinsert the same requests just before the next processing event.
            for (PlacementRequest request : placementRequests) {
                CloudSim.send(fonID, fonID, MicroservicePlacementConfig.PLACEMENT_INTERVAL,
                        FogEvents.RECEIVE_PR, request);
            }
        }
    }

    private void initializePlacementMaps() {
        for (PlacementRequest request : placementRequests) {
            mappedMicroservices.put(request.getPlacementRequestId(),
                    new HashMap<>(request.getPlacedMicroservices()));
        }
    }

    private void forceSpecialPlacements() {
        for (PlacementRequest request : placementRequests) {
            Application app = applicationInfo.get(request.getApplicationId());
            for (String module : app.getSpecialPlacementInfo().keySet()) {
                if (request.getPlacedMicroservices().containsKey(module)) continue;
                for (String deviceName : app.getSpecialPlacementInfo().get(module)) {
                    FogDevice device = findDeviceByName(deviceName);
                    if (device != null && freeCpu(device.getId()) >= getModuleMips(module, app)) {
                        recordNewPlacement(request.getPlacementRequestId(), module, device.getId(), app);
                        break;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildInitialState() {
        JSONObject state = new JSONObject();
        state.put("type", "shared_initial");
        state.put("episodeSeed", episodeSeed);
        state.put("step", 0);
        state.put("devices", buildLegacyDevices());
        JSONArray requests = new JSONArray();
        Set<String> moduleNames = new LinkedHashSet<>();
        for (PlacementRequest request : placementRequests) {
            JSONObject item = new JSONObject();
            item.put("requestId", request.getPlacementRequestId());
            item.put("appId", request.getApplicationId());
            item.put("gatewayDeviceId", request.getGatewayDeviceId());
            item.put("alreadyPlaced", new JSONObject(mappedMicroservices.get(request.getPlacementRequestId())));
            JSONArray pending = new JSONArray();
            Application app = applicationInfo.get(request.getApplicationId());
            for (AppModule module : app.getModules()) {
                moduleNames.add(module.getName());
                if (mappedMicroservices.get(request.getPlacementRequestId()).containsKey(module.getName())) continue;
                JSONObject spec = new JSONObject();
                spec.put("name", module.getName());
                spec.put("requiredMips", (double) module.getMips());
                spec.put("requiredRam", (double) module.getRam());
                pending.add(spec);
            }
            item.put("pendingModules", pending);
            requests.add(item);
        }
        state.put("requests", requests);
        state.put("allModules", new JSONArray() {{ addAll(moduleNames); }});
        return state;
    }

    @SuppressWarnings("unchecked")
    private JSONArray buildLegacyDevices() {
        JSONArray devices = new JSONArray();
        for (FogDevice device : fogDevices) {
            JSONObject item = new JSONObject();
            item.put("id", device.getId());
            item.put("name", device.getName());
            item.put("level", device.getLevel());
            item.put("parentId", device.getParentId());
            item.put("availableMips", freeCpu(device.getId()));
            item.put("availableRam", freeRam(device.getId()));
            item.put("currentLoad", 0.0);
            devices.add(item);
        }
        return devices;
    }

    private List<ServiceActor> selectActors(boolean done) {
        List<ServiceActor> all = new ArrayList<>();
        for (PlacementRequest request : placementRequests) {
            Application app = applicationInfo.get(request.getApplicationId());
            for (AppModule module : app.getModules()) {
                String moduleName = module.getName();
                Integer deviceId = request.getPlacedMicroservices().get(moduleName);
                if (deviceId == null || module == null) continue;
                ServiceActor actor = new ServiceActor(request, app, module, deviceId);
                all.add(actor);
            }
        }
        all.sort(Comparator.comparing(ServiceActor::id));
        if (all.isEmpty()) return all;
        // Keep each bridge exchange bounded even when each client hosts a
        // preprocessor. Round-robin selection gives every service equal turns.
        int maxActors = Math.max(1, Integer.getInteger("ifogsim.max.actors.per.step", 32));
        List<ServiceActor> selected = new ArrayList<>();
        int count = Math.min(maxActors, all.size());
        for (int offset = 0; offset < count; offset++) {
            selected.add(all.get((actorCursor + offset) % all.size()));
        }
        actorCursor = (actorCursor + count) % all.size();
        return selected;
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildSharedState(List<ServiceActor> actors,
                                        Map<Integer, Double> energyDeltas,
                                        boolean done) {
        JSONObject state = new JSONObject();
        state.put("type", "shared_step");
        state.put("episodeSeed", episodeSeed);
        state.put("step", sharedStep);
        state.put("simTime", CloudSim.clock());
        state.put("done", done);
        JSONArray devices = new JSONArray();
        for (FogDevice device : candidateDevices()) {
            JSONObject item = new JSONObject();
            item.put("id", device.getId());
            item.put("freeMips", freeCpu(device.getId()));
            devices.add(item);
        }
        state.put("devices", devices);

        JSONArray actorArray = new JSONArray();
        for (ServiceActor actor : actors) actorArray.add(buildActor(actor, energyDeltas));
        state.put("actors", actorArray);
        return state;
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildActor(ServiceActor actor, Map<Integer, Double> energyDeltas) {
        JSONObject item = new JSONObject();
        int homeGateway = findDeviceById(actor.request.getGatewayDeviceId()).getParentId();
        FogDevice current = findDeviceById(actor.currentDeviceId);
        double currentLatency = estimateRequestLatency(actor.request, actor.module.getName(), actor.currentDeviceId);
        double used = Math.max(usedCpu(actor.currentDeviceId), actor.module.getMips());
        double attributedEnergy = energyDeltas.getOrDefault(actor.currentDeviceId, 0.0)
                * actor.module.getMips() / used;
        double energyCost = attributedEnergy /
                Math.max(200.0 * MicroservicePlacementConfig.PLACEMENT_INTERVAL, 1.0);
        double delayCost = currentLatency / 500.0;
        String outcome = previousOutcome.remove(actor.id());
        double actionPenalty = "rejected".equals(outcome) ? 0.5 : ("migrated".equals(outcome) ? 0.02 : 0.0);

        JSONObject reward = new JSONObject();
        reward.put("total", -(0.5 * energyCost + 0.5 * delayCost + actionPenalty));
        reward.put("energy", -energyCost);
        reward.put("delay", -delayCost);
        reward.put("actionPenalty", -actionPenalty);
        reward.put("previousAction", outcome == null ? "none" : outcome);

        item.put("actorId", actor.id());
        item.put("requestId", actor.request.getPlacementRequestId());
        item.put("module", actor.module.getName());
        item.put("requiredMips", (double) actor.module.getMips());
        item.put("requiredRam", (double) actor.module.getRam());
        item.put("currentDeviceId", actor.currentDeviceId);
        item.put("currentLevel", current.getLevel());
        item.put("currentLatency", currentLatency);
        item.put("homeGatewayId", homeGateway);
        item.put("mobileClient", findDeviceById(actor.request.getGatewayDeviceId()).getName().startsWith("MobileRobot-"));
        item.put("reward", reward);

        Set<Integer> peerDevices = new HashSet<>();
        for (Map.Entry<String, Integer> placement : actor.request.getPlacedMicroservices().entrySet()) {
            if (!placement.getKey().equals(actor.module.getName())) peerDevices.add(placement.getValue());
        }
        JSONArray candidates = new JSONArray();
        for (FogDevice candidate : actorCandidates(actor.currentDeviceId)) {
            double freeCpu = freeCpu(candidate.getId());
            double freeRam = freeRam(candidate.getId());
            double capacity = candidate.getHost().getTotalMips();
            double ramCapacity = candidate.getHost().getRam();
            JSONObject option = new JSONObject();
            option.put("deviceId", candidate.getId());
            option.put("level", candidate.getLevel());
            option.put("isCurrent", candidate.getId() == actor.currentDeviceId);
            option.put("isHomeGateway", candidate.getId() == homeGateway);
            option.put("sameAsPeer", peerDevices.contains(candidate.getId()));
            option.put("freeMips", freeCpu);
            option.put("cpuCapacity", capacity);
            option.put("freeRam", freeRam);
            option.put("ramCapacity", ramCapacity);
            option.put("utilization", Math.max(0.0, Math.min(1.0, 1.0 - freeCpu / Math.max(capacity, 1.0))));
            option.put("currentUtilization", Math.max(0.0, Math.min(1.0,
                    1.0 - freeCpu(actor.currentDeviceId) /
                            Math.max(current.getHost().getTotalMips(), 1.0))));
            option.put("energyDelta", energyDeltas.getOrDefault(candidate.getId(), 0.0));
            option.put("currentEnergyDelta", energyDeltas.getOrDefault(actor.currentDeviceId, 0.0));
            option.put("estimatedLatency", estimateRequestLatency(actor.request,
                    actor.module.getName(), candidate.getId()));
            option.put("pathToClient", pathLatency(actor.request.getGatewayDeviceId(), candidate.getId()));
            option.put("feasible", candidate.getId() == actor.currentDeviceId ||
                    (freeCpu >= actor.module.getMips() && freeRam >= actor.module.getRam()));
            candidates.add(option);
        }
        item.put("candidates", candidates);
        return item;
    }

    private List<FogDevice> candidateDevices() {
        List<FogDevice> candidates = new ArrayList<>();
        for (FogDevice device : fogDevices) if (device.getLevel() < 2) candidates.add(device);
        candidates.sort(Comparator.comparingInt(FogDevice::getId));
        return candidates;
    }

    /** A service on a client may stay there, but may only migrate to fog/cloud. */
    private List<FogDevice> actorCandidates(int currentDeviceId) {
        List<FogDevice> candidates = candidateDevices();
        if (candidates.stream().noneMatch(device -> device.getId() == currentDeviceId)) {
            FogDevice current = findDeviceById(currentDeviceId);
            if (current != null) candidates.add(current);
        }
        candidates.sort(Comparator.comparingInt(FogDevice::getId));
        return candidates;
    }

    private double estimateRequestLatency(PlacementRequest request, String movingModule, int candidateId) {
        Integer preprocessor = request.getPlacedMicroservices().get("data_preprocessor");
        Integer analyzer = request.getPlacedMicroservices().get("smart_analyzer");
        Integer controller = request.getPlacedMicroservices().get("actuator_controller");
        if (movingModule.equals("data_preprocessor")) preprocessor = candidateId;
        if (movingModule.equals("smart_analyzer")) analyzer = candidateId;
        if (movingModule.equals("actuator_controller")) controller = candidateId;
        if (preprocessor == null || analyzer == null || controller == null) return 500.0;
        int client = request.getGatewayDeviceId();
        return 2.0 + pathLatency(client, preprocessor) + pathLatency(preprocessor, analyzer)
                + pathLatency(analyzer, controller) + pathLatency(controller, preprocessor)
                + pathLatency(preprocessor, client);
    }

    /** Tree-path latency using parent links and each child's uplink latency. */
    private double pathLatency(int fromId, int toId) {
        if (fromId == toId) return 0.0;
        Map<Integer, Double> fromAncestors = new HashMap<>();
        double distance = 0.0;
        FogDevice cursor = findDeviceById(fromId);
        while (cursor != null) {
            fromAncestors.put(cursor.getId(), distance);
            if (cursor.getParentId() < 0) break;
            distance += Math.max(0.0, cursor.getUplinkLatency());
            cursor = findDeviceById(cursor.getParentId());
        }
        distance = 0.0;
        cursor = findDeviceById(toId);
        while (cursor != null) {
            Double left = fromAncestors.get(cursor.getId());
            if (left != null) return left + distance;
            if (cursor.getParentId() < 0) break;
            distance += Math.max(0.0, cursor.getUplinkLatency());
            cursor = findDeviceById(cursor.getParentId());
        }
        return 500.0;
    }

    private double freeCpu(int deviceId) {
        return resourceAvailability.getOrDefault(deviceId, Collections.emptyMap())
                .getOrDefault(ControllerComponent.CPU, 0.0)
                - getCurrentCpuLoad().getOrDefault(deviceId, 0.0);
    }

    private double usedCpu(int deviceId) {
        FogDevice device = findDeviceById(deviceId);
        return device == null ? 0.0 : Math.max(0.0, device.getHost().getTotalMips() - freeCpu(deviceId));
    }

    private double freeRam(int deviceId) {
        FogDevice device = findDeviceById(deviceId);
        if (device == null) return 0.0;
        double used = 0.0;
        for (PlacementRequest request : placementRequests) {
            Application app = applicationInfo.get(request.getApplicationId());
            for (Map.Entry<String, Integer> placement : request.getPlacedMicroservices().entrySet()) {
                if (placement.getValue() != deviceId) continue;
                AppModule module = app.getModuleByName(placement.getKey());
                if (module != null) used += module.getRam();
            }
        }
        return Math.max(0.0, device.getHost().getRam() - used);
    }

    private Map<Integer, Double> readEnergyDeltas() {
        Map<Integer, Double> deltas = new HashMap<>();
        for (FogDevice device : fogDevices) {
            double current = device.getEnergyConsumption();
            deltas.put(device.getId(), Math.max(0.0,
                    current - previousEnergy.getOrDefault(device.getId(), current)));
        }
        return deltas;
    }

    private void snapshotEnergy() {
        for (FogDevice device : fogDevices) previousEnergy.put(device.getId(), device.getEnergyConsumption());
    }

    @SuppressWarnings("unchecked")
    private void logMeanReward(JSONObject state, boolean done) {
        JSONArray actors = (JSONArray) state.get("actors");
        double total = 0.0;
        for (Object value : actors) {
            JSONObject reward = (JSONObject) ((JSONObject) value).get("reward");
            total += ((Number) reward.get("total")).doubleValue();
        }
        ACTOR_REWARD_SUM += total;
        ACTOR_REWARD_COUNT += actors.size();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", sharedStep);
        entry.put("simTime", CloudSim.clock());
        entry.put("reward", actors.isEmpty() ? 0.0 : total / actors.size());
        entry.put("done", done);
        STEP_LOG.add(entry);
    }

    private void applySharedResponse(String response, boolean initial) throws Exception {
        JSONObject root = (JSONObject) new JSONParser().parse(response);
        if (root.containsKey("error")) throw new IllegalStateException(root.get("error").toString());
        if (initial) {
            JSONArray placements = (JSONArray) root.getOrDefault("placements", new JSONArray());
            for (Object value : placements) applyInitialPlacement((JSONObject) value);
            return;
        }
        JSONArray actions = (JSONArray) root.getOrDefault("actions", new JSONArray());
        for (Object value : actions) applyActorAction((JSONObject) value);
    }

    private void applyInitialPlacement(JSONObject action) {
        int requestId = ((Number) action.get("requestId")).intValue();
        String moduleName = action.get("module").toString();
        int deviceId = ((Number) action.get("deviceId")).intValue();
        PlacementRequest request = findPrById(requestId);
        FogDevice device = findDeviceById(deviceId);
        if (request == null || device == null) return;
        Application app = applicationInfo.get(request.getApplicationId());
        AppModule module = app.getModuleByName(moduleName);
        if (module == null) return;
        // Match PythonBridgePlacementLogic exactly for the GA phase: the GA owns
        // feasibility and Java records its chromosome without a second policy.
        recordNewPlacement(requestId, moduleName, deviceId, app);
        TrainingLog.decision("GA placed request=" + requestId + " " + moduleName
                + " -> " + device.getName());
    }

    private void applyActorAction(JSONObject action) {
        String actorId = action.get("actorId").toString();
        int separator = actorId.indexOf(':');
        if (separator < 1) return;
        int requestId = Integer.parseInt(actorId.substring(0, separator));
        String moduleName = actorId.substring(separator + 1);
        int destination = ((Number) action.get("toDeviceId")).intValue();
        PlacementRequest request = findPrById(requestId);
        if (request == null) return;
        Integer source = request.getPlacedMicroservices().get(moduleName);
        if (source == null || source == destination) {
            previousOutcome.put(actorId, "no_op");
            return;
        }
        applyMigration(requestId, moduleName, destination);
        if (Objects.equals(request.getPlacedMicroservices().get(moduleName), destination)) {
            ACCEPTED_MIGRATIONS++;
            previousOutcome.put(actorId, "migrated");
            TrainingLog.decision("PPO migrated request=" + requestId + " " + moduleName
                    + " -> " + findDeviceById(destination).getName());
        } else {
            REJECTED_MIGRATIONS++;
            previousOutcome.put(actorId, "rejected");
        }
    }

    private static class ServiceActor {
        final PlacementRequest request;
        final Application app;
        final AppModule module;
        final int currentDeviceId;

        ServiceActor(PlacementRequest request, Application app, AppModule module, int currentDeviceId) {
            this.request = request;
            this.app = app;
            this.module = module;
            this.currentDeviceId = currentDeviceId;
        }

        String id() { return request.getPlacementRequestId() + ":" + module.getName(); }
    }
}
