package org.fog.placement;

import org.apache.commons.math3.util.Pair;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.application.AppEdge;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.ControllerComponent;
import org.fog.entities.FogDevice;
import org.fog.entities.PlacementRequest;
import org.fog.entities.Tuple;
import org.fog.utils.Config;
import org.fog.utils.FogEvents;
import org.fog.utils.MicroservicePlacementConfig;
import org.fog.utils.TimeKeeper;
import org.json.simple.JSONObject;

import java.io.*;
import java.net.Socket;
import java.util.*;

/** Periodic Gym-style placement/migration bridge to an external Python PPO agent. */
public class PPOBridgePlacementLogic extends ClusteredMicroservicePlacementLogic {

    public static final String DEFAULT_HOST = "localhost";
    public static final int    DEFAULT_PORT = 5555;
    private static final int   SOCKET_TIMEOUT_MS = 10_000;

    /** Reward weights: penalise energy growth and latency since the previous step. */
    private static final double W_ENERGY  = 1.0e-6; // energy deltas are in Joules (large numbers)
    private static final double W_LATENCY = 1.0;     // latency is already in milliseconds

    private final String host;
    private final int    port;

    /** Monotonically increasing counter included in each step message. */
    protected int stepCounter = 0;

    /** Total energy consumption recorded at the end of the previous step (for reward delta). */
    private static double lastTotalEnergy = 0.0;

    /** Per-step reward log: step, simTime, reward, done. */
    public static final List<Map<String, Object>> STEP_LOG = new ArrayList<>();

    /** Applied migrations: step, requestId, module, fromDevice, toDevice. */
    public static final List<Map<String, Object>> MIGRATION_LOG = new ArrayList<>();

    /** First-time placements: step, requestId, module, device, deviceId. */
    public static final List<Map<String, Object>> PLACEMENT_LOG = new ArrayList<>();

    /** Clears all static logs between simulation runs (call before startSimulation()). */
    public static void clearLogs() {
        STEP_LOG.clear();
        MIGRATION_LOG.clear();
        PLACEMENT_LOG.clear();
        lastTotalEnergy = 0.0;
    }

    public PPOBridgePlacementLogic(int fonId) {
        this(fonId, DEFAULT_HOST, DEFAULT_PORT);
    }

    public PPOBridgePlacementLogic(int fonId, String host, int port) {
        super(fonId);
        this.host = host;
        this.port = port;
    }

    @Override
    public void mapModules() {
        for (PlacementRequest pr : placementRequests) {
            mappedMicroservices.put(pr.getPlacementRequestId(),
                    new HashMap<>(pr.getPlacedMicroservices()));

            Application app = applicationInfo.get(pr.getApplicationId());
            Map<String, Integer> placed = pr.getPlacedMicroservices();
            for (String microservice : app.getSpecialPlacementInfo().keySet()) {
                if (placed.containsKey(microservice)) continue;
                for (String deviceName : app.getSpecialPlacementInfo().get(microservice)) {
                    FogDevice device = findDeviceByName(deviceName);
                    if (device == null) continue;
                    int    devId    = device.getId();
                    double required = getModuleMips(microservice, app);
                    double available = resourceAvailability
                            .getOrDefault(devId, Collections.emptyMap())
                            .getOrDefault(ControllerComponent.CPU, 0.0);
                    if (required + getCurrentCpuLoad().getOrDefault(devId, 0.0) <= available) {
                        recordNewPlacement(pr.getPlacementRequestId(), microservice, devId, app);
                        break;
                    }
                }
            }
        }

        double totalEnergy = sumEnergyConsumption();
        double energyDelta = (stepCounter == 0) ? 0.0 : Math.max(0.0, totalEnergy - lastTotalEnergy);
        Double loopDelay = readCurrentLoopDelayMs();
        double reward = -(W_ENERGY * energyDelta + W_LATENCY * (loopDelay != null ? loopDelay : 0.0));
        lastTotalEnergy = totalEnergy;

        boolean done = CloudSim.clock() + MicroservicePlacementConfig.PLACEMENT_INTERVAL
                >= Config.MAX_SIMULATION_TIME;

        Map<String, Object> stepEntry = new LinkedHashMap<>();
        stepEntry.put("step", stepCounter);
        stepEntry.put("simTime", CloudSim.clock());
        stepEntry.put("reward", reward);
        stepEntry.put("done", done);
        STEP_LOG.add(stepEntry);

        try {
            String stateJson  = buildStepStateJson(reward, done);
            String actionJson = queryPythonAgent(stateJson);
            if (actionJson != null && !actionJson.trim().isEmpty()) {
                applyAction(actionJson);
            } else {
                System.err.println("[PPOBridge] Empty response from Python agent.");
            }
        } catch (Exception e) {
            System.err.println("[PPOBridge] Communication error: " + e.getMessage());
            e.printStackTrace();
        }

        stepCounter++;

        if (!done) {
            for (PlacementRequest pr : placementRequests) {
                CloudSim.send(fonID, fonID, MicroservicePlacementConfig.PLACEMENT_INTERVAL,
                        FogEvents.RECEIVE_PR, pr);
            }
        }
    }

    private double sumEnergyConsumption() {
        double total = 0.0;
        for (FogDevice d : fogDevices) total += d.getEnergyConsumption();
        return total;
    }

    /** Reads the live average E2E loop delay for the first registered AppLoop, if any sample exists yet. */
    private Double readCurrentLoopDelayMs() {
        for (Application app : applicationInfo.values()) {
            if (app.getLoops() == null) continue;
            for (org.fog.application.AppLoop loop : app.getLoops()) {
                Double avg = TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(loop.getLoopId());
                if (avg != null) return avg;
            }
        }
        return null;
    }

    protected String queryPythonAgent(String stateJson) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            PrintWriter    out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            BufferedReader in  = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out.println(stateJson);
            return in.readLine();
        }
    }

    private String buildStepStateJson(double reward, boolean done) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"type\":\"step\",");
        sb.append("\"step\":").append(stepCounter).append(",");
        sb.append("\"simTime\":").append(CloudSim.clock()).append(",");
        sb.append("\"done\":").append(done).append(",");
        sb.append("\"reward\":").append(reward).append(",");

        sb.append("\"devices\":[");
        boolean firstDev = true;
        for (FogDevice device : fogDevices) {
            if (!firstDev) sb.append(",");
            firstDev = false;
            Map<String, Double> res = resourceAvailability.getOrDefault(
                    device.getId(), Collections.emptyMap());
            sb.append("{")
                    .append("\"id\":").append(device.getId()).append(",")
                    .append("\"name\":\"").append(escape(device.getName())).append("\",")
                    .append("\"level\":").append(device.getLevel()).append(",")
                    .append("\"parentId\":").append(device.getParentId()).append(",")
                    .append("\"availableMips\":").append(res.getOrDefault(ControllerComponent.CPU, 0.0)).append(",")
                    .append("\"availableRam\":").append(res.getOrDefault(ControllerComponent.RAM, 0.0)).append(",")
                    .append("\"currentLoad\":").append(getCurrentCpuLoad().getOrDefault(device.getId(), 0.0))
                    .append("}");
        }
        sb.append("],");

        sb.append("\"modules\":[");
        boolean firstMod = true;
        for (PlacementRequest pr : placementRequests) {
            Application app = applicationInfo.get(pr.getApplicationId());
            Map<String, Integer> placed = pr.getPlacedMicroservices();
            for (AppModule module : app.getModules()) {
                if (!firstMod) sb.append(",");
                firstMod = false;
                Integer devId = placed.get(module.getName());
                String status = (devId != null) ? "placed" : "pending";
                sb.append("{")
                        .append("\"requestId\":").append(pr.getPlacementRequestId()).append(",")
                        .append("\"name\":\"").append(escape(module.getName())).append("\",")
                        .append("\"status\":\"").append(status).append("\",")
                        .append("\"deviceId\":").append(devId != null ? devId : "null").append(",")
                        .append("\"requiredMips\":").append((double) module.getMips()).append(",")
                        .append("\"requiredRam\":").append((double) module.getRam())
                        .append("}");
            }
        }
        sb.append("]}");

        return sb.toString();
    }

    private void applyAction(String actionJson) {
        Map<String, String> top = parseFlatOrArraySections(actionJson);

        String placementsArr = top.get("placements");
        if (placementsArr != null) {
            for (String obj : splitJsonArrayObjects(placementsArr)) {
                Map<String, String> f = parseFlatJsonObject(obj);
                try {
                    int prId     = Integer.parseInt(f.get("requestId"));
                    String mod   = unquote(f.get("module"));
                    int deviceId = Integer.parseInt(f.get("deviceId"));
                    applyPlacement(prId, mod, deviceId);
                } catch (Exception e) {
                    System.err.println("[PPOBridge] Malformed placement entry: " + obj);
                }
            }
        }

        String migrationsArr = top.get("migrations");
        if (migrationsArr != null) {
            for (String obj : splitJsonArrayObjects(migrationsArr)) {
                Map<String, String> f = parseFlatJsonObject(obj);
                try {
                    int prId       = Integer.parseInt(f.get("requestId"));
                    String mod     = unquote(f.get("module"));
                    int toDeviceId = Integer.parseInt(f.get("toDeviceId"));
                    applyMigration(prId, mod, toDeviceId);
                } catch (Exception e) {
                    System.err.println("[PPOBridge] Malformed migration entry: " + obj);
                }
            }
        }
    }

    /** Place a not-yet-deployed module if the target has capacity. */
    private void applyPlacement(int prId, String moduleName, int deviceId) {
        PlacementRequest pr = findPrById(prId);
        if (pr == null) {
            System.err.println("[PPOBridge] Unknown requestId " + prId + " in placement.");
            return;
        }
        if (pr.getPlacedMicroservices().containsKey(moduleName)) return; // already placed, ignore
        FogDevice device = findDeviceById(deviceId);
        if (device == null) {
            System.err.println("[PPOBridge] Unknown deviceId " + deviceId + " for module " + moduleName);
            return;
        }
        Application app = applicationInfo.get(pr.getApplicationId());
        AppModule moduleSpec = app.getModuleByName(moduleName);
        if (moduleSpec == null) return;

        double mips = moduleSpec.getMips();
        double ram  = moduleSpec.getRam();
        double availMips = resourceAvailability.getOrDefault(deviceId, Collections.emptyMap())
                .getOrDefault(ControllerComponent.CPU, 0.0) - getCurrentCpuLoad().getOrDefault(deviceId, 0.0);
        double availRam  = resourceAvailability.getOrDefault(deviceId, Collections.emptyMap())
                .getOrDefault(ControllerComponent.RAM, 0.0);

        if (mips > availMips || ram > availRam) {
            System.err.println("[PPOBridge] Placement of '" + moduleName + "' on "
                    + device.getName() + " rejected: insufficient capacity.");
            return;
        }

        recordNewPlacement(prId, moduleName, deviceId, app);
    }

    /** Move an already-placed module: release old instance, start new, update discovery. */
    protected void applyMigration(int prId, String moduleName, int toDeviceId) {
        PlacementRequest pr = findPrById(prId);
        if (pr == null) {
            System.err.println("[PPOBridge] Unknown requestId " + prId + " in migration.");
            return;
        }
        Integer fromDeviceId = pr.getPlacedMicroservices().get(moduleName);
        if (fromDeviceId == null) {
            System.err.println("[PPOBridge] Cannot migrate '" + moduleName
                    + "': not currently placed for request " + prId);
            return;
        }
        if (fromDeviceId == toDeviceId) return; // no-op, already there

        FogDevice fromDevice = findDeviceById(fromDeviceId);
        FogDevice toDevice   = findDeviceById(toDeviceId);
        if (fromDevice == null || toDevice == null) {
            System.err.println("[PPOBridge] Unknown device in migration for " + moduleName);
            return;
        }

        Application app = applicationInfo.get(pr.getApplicationId());
        AppModule moduleSpec = app.getModuleByName(moduleName);
        if (moduleSpec == null) return;

        double mips = moduleSpec.getMips();
        double ram  = moduleSpec.getRam();

        double targetAvailMips = resourceAvailability.getOrDefault(toDeviceId, Collections.emptyMap())
                .getOrDefault(ControllerComponent.CPU, 0.0);
        double targetAvailRam  = resourceAvailability.getOrDefault(toDeviceId, Collections.emptyMap())
                .getOrDefault(ControllerComponent.RAM, 0.0);
        if (mips > targetAvailMips || ram > targetAvailRam) {
            System.err.println("[PPOBridge] Migration of '" + moduleName + "' to "
                    + toDevice.getName() + " rejected: insufficient capacity.");
            return;
        }

        creditResource(fromDeviceId, ControllerComponent.CPU, mips);
        creditResource(fromDeviceId, ControllerComponent.RAM, ram);
        debitResource(toDeviceId, ControllerComponent.CPU, mips);
        debitResource(toDeviceId, ControllerComponent.RAM, ram);

        pr.getPlacedMicroservices().put(moduleName, toDeviceId);
        moduleToApp.put(moduleName, app.getAppId());

        double upDelay   = Math.max(0.0, fromDevice.getUplinkLatency());
        double downDelay = Math.max(0.0, toDevice.getUplinkLatency());

        JSONObject jsonSend = new JSONObject();
        jsonSend.put("module", moduleSpec);
        jsonSend.put("delay", upDelay);
        CloudSim.send(fonID, fromDeviceId, upDelay, FogEvents.MODULE_SEND, jsonSend);

        JSONObject jsonReceive = new JSONObject();
        jsonReceive.put("module", new AppModule(moduleSpec));
        jsonReceive.put("delay", downDelay);
        jsonReceive.put("application", app);
        CloudSim.send(fonID, toDeviceId, downDelay, FogEvents.MODULE_RECEIVE, jsonReceive);

        for (String clientModule : getClientMicroservices(app, moduleName)) {
            Integer clientDeviceId = pr.getPlacedMicroservices().get(clientModule);
            if (clientDeviceId == null) continue;
            JSONObject remove = new JSONObject();
            remove.put("service data", new Pair<>(moduleName, fromDeviceId));
            remove.put("action", "REMOVE");
            CloudSim.send(fonID, clientDeviceId, downDelay, FogEvents.UPDATE_SERVICE_DISCOVERY, remove);

            JSONObject add = new JSONObject();
            add.put("service data", new Pair<>(moduleName, toDeviceId));
            add.put("action", "ADD");
            CloudSim.send(fonID, clientDeviceId, downDelay, FogEvents.UPDATE_SERVICE_DISCOVERY, add);
        }
        for (String serviceModule : getServiceMicroservices(app, moduleName)) {
            Integer serviceDeviceId = pr.getPlacedMicroservices().get(serviceModule);
            if (serviceDeviceId == null) continue;
            JSONObject add = new JSONObject();
            add.put("service data", new Pair<>(serviceModule, serviceDeviceId));
            add.put("action", "ADD");
            CloudSim.send(fonID, toDeviceId, downDelay, FogEvents.UPDATE_SERVICE_DISCOVERY, add);
        }

        System.out.println("[PPOBridge] Migrated '" + moduleName + "' from "
                + fromDevice.getName() + " to " + toDevice.getName());

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step",       stepCounter);
        entry.put("requestId",  prId);
        entry.put("module",     moduleName);
        entry.put("fromDevice", fromDevice.getName());
        entry.put("toDevice",   toDevice.getName());
        MIGRATION_LOG.add(entry);
    }

    private void creditResource(int deviceId, String key, double amount) {
        Map<String, Double> res = resourceAvailability.get(deviceId);
        if (res == null) return;
        res.put(key, res.getOrDefault(key, 0.0) + amount);
    }

    private void debitResource(int deviceId, String key, double amount) {
        Map<String, Double> res = resourceAvailability.get(deviceId);
        if (res == null) return;
        res.put(key, res.getOrDefault(key, 0.0) - amount);
    }

    /** Records a brand-new module→device assignment (mirrors PythonBridgePlacementLogic.recordPlacement). */
    protected void recordNewPlacement(int prId, String moduleName, int deviceId, Application app) {
        mappedMicroservices.get(prId).put(moduleName, deviceId);

        if (!currentModuleMap.get(deviceId).contains(moduleName))
            currentModuleMap.get(deviceId).add(moduleName);

        double mips = getModuleMips(moduleName, app);
        getCurrentCpuLoad().put(deviceId, getCurrentCpuLoad().getOrDefault(deviceId, 0.0) + mips);

        currentModuleLoadMap.get(deviceId).merge(moduleName, mips, Double::sum);
        currentModuleInstanceNum.get(deviceId).merge(moduleName, 1, Integer::sum);
        moduleToApp.put(moduleName, app.getAppId());

        String devName = findDeviceById(deviceId).getName();
        System.out.println("[PPOBridge] Placed '" + moduleName + "' on '" + devName + "'");

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step",      stepCounter);
        entry.put("requestId", prId);
        entry.put("module",    moduleName);
        entry.put("device",    devName);
        entry.put("deviceId",  deviceId);
        PLACEMENT_LOG.add(entry);
    }

    /** Modules that call INTO moduleName (i.e. need to know where it lives). */
    private List<String> getClientMicroservices(Application app, String moduleName) {
        List<String> result = new ArrayList<>();
        for (AppEdge edge : app.getEdges()) {
            if (edge.getDestination().equals(moduleName) && edge.getDirection() == Tuple.UP
                    && app.getModuleNames().contains(edge.getSource())) {
                result.add(edge.getSource());
            }
        }
        return result;
    }

    /** Modules that moduleName itself calls (i.e. moduleName needs to know where they live). */
    private List<String> getServiceMicroservices(Application app, String moduleName) {
        List<String> result = new ArrayList<>();
        for (AppEdge edge : app.getEdges()) {
            if (edge.getSource().equals(moduleName) && edge.getDirection() == Tuple.UP
                    && app.getModuleNames().contains(edge.getDestination())) {
                result.add(edge.getDestination());
            }
        }
        return result;
    }

    protected double getModuleMips(String moduleName, Application app) {
        for (AppModule m : app.getModules()) {
            if (m.getName().equals(moduleName)) return m.getMips();
        }
        return 0.0;
    }

    protected FogDevice findDeviceByName(String name) {
        for (FogDevice d : fogDevices) if (d.getName().equals(name)) return d;
        return null;
    }

    protected FogDevice findDeviceById(int id) {
        for (FogDevice d : fogDevices) if (d.getId() == id) return d;
        return null;
    }

    protected PlacementRequest findPrById(int prId) {
        for (PlacementRequest pr : placementRequests) {
            if (pr.getPlacementRequestId() == prId) return pr;
        }
        return null;
    }

    /** Parses a flat top-level object like {"placements":[...],"migrations":[...]} into raw section strings. */
    private static Map<String, String> parseFlatOrArraySections(String json) {
        Map<String, String> sections = new LinkedHashMap<>();
        String body = json.trim();
        if (!body.startsWith("{") || !body.endsWith("}")) return sections;
        body = body.substring(1, body.length() - 1);
        int pos = 0;
        while (pos < body.length()) {
            int keyStart = body.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd = body.indexOf('"', keyStart + 1);
            String key = body.substring(keyStart + 1, keyEnd);
            int colon = body.indexOf(':', keyEnd + 1);
            int valStart = colon + 1;
            while (valStart < body.length() && Character.isWhitespace(body.charAt(valStart))) valStart++;
            if (valStart >= body.length()) break;
            char c = body.charAt(valStart);
            String value;
            int nextPos;
            if (c == '[') {
                value = extractBalanced(body, valStart, '[', ']');
                nextPos = valStart + value.length();
            } else if (c == '{') {
                value = extractBalanced(body, valStart, '{', '}');
                nextPos = valStart + value.length();
            } else {
                int end = body.indexOf(',', valStart);
                if (end < 0) end = body.length();
                value = body.substring(valStart, end).trim();
                nextPos = end;
            }
            sections.put(key, value);
            pos = nextPos + 1;
        }
        return sections;
    }

    /** Splits a JSON array of flat objects "[{...},{...}]" into its element strings. */
    private static List<String> splitJsonArrayObjects(String arrayStr) {
        List<String> result = new ArrayList<>();
        String body = arrayStr.trim();
        if (!body.startsWith("[") || !body.endsWith("]")) return result;
        body = body.substring(1, body.length() - 1).trim();
        int pos = 0;
        while (pos < body.length()) {
            int objStart = body.indexOf('{', pos);
            if (objStart < 0) break;
            String obj = extractBalanced(body, objStart, '{', '}');
            result.add(obj);
            pos = objStart + obj.length();
        }
        return result;
    }

    /** Parses a flat JSON object "{"k":v,"k2":"v2"}" (no nesting) into a string map. */
    private static Map<String, String> parseFlatJsonObject(String objStr) {
        Map<String, String> result = new LinkedHashMap<>();
        String body = objStr.trim();
        if (!body.startsWith("{") || !body.endsWith("}")) return result;
        body = body.substring(1, body.length() - 1);
        int pos = 0;
        while (pos < body.length()) {
            int keyStart = body.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd = body.indexOf('"', keyStart + 1);
            String key = body.substring(keyStart + 1, keyEnd);
            int colon = body.indexOf(':', keyEnd + 1);
            int valStart = colon + 1;
            while (valStart < body.length() && Character.isWhitespace(body.charAt(valStart))) valStart++;
            String value;
            int nextPos;
            if (valStart < body.length() && body.charAt(valStart) == '"') {
                int valEnd = body.indexOf('"', valStart + 1);
                value = body.substring(valStart, valEnd + 1);
                nextPos = valEnd + 1;
            } else {
                int end = body.indexOf(',', valStart);
                if (end < 0) end = body.length();
                value = body.substring(valStart, end).trim();
                nextPos = end;
            }
            result.put(key, value);
            pos = nextPos + 1;
        }
        return result;
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) return s.substring(1, s.length() - 1);
        return s;
    }

    private static String extractBalanced(String s, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return s.substring(start);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @Override
    public void postProcessing() {
        // intentionally empty – socket already closed inside queryPythonAgent()
    }
}
