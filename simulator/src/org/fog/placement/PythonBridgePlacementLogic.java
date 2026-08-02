package org.fog.placement;

import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.ControllerComponent;
import org.fog.entities.FogDevice;
import org.fog.entities.PlacementRequest;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.LinkedHashMap;

/** One-shot TCP JSON placement bridge to an external Python agent. */
public class PythonBridgePlacementLogic extends ClusteredMicroservicePlacementLogic {

    public static final String DEFAULT_HOST      = "localhost";
    public static final int    DEFAULT_PORT      = 5555;
    /** Milliseconds the Java side waits for the Python agent before timing out. */
    private static final int    SOCKET_TIMEOUT_MS = 10_000;

    private final String host;
    private final int    port;

    /** Monotonically increasing counter included in each state message. */
    private int stepCounter = 0;

    /** Placement log: step, requestId, moduleName, deviceName, deviceId. */
    public static final List<Map<String, Object>> PLACEMENT_LOG = new ArrayList<>();

    /** Clears the log between simulation runs (call before startSimulation()). */
    public static void clearLog() { PLACEMENT_LOG.clear(); }

    public PythonBridgePlacementLogic(int fonId) {
        this(fonId, DEFAULT_HOST, DEFAULT_PORT);
    }

    public PythonBridgePlacementLogic(int fonId, String host, int port) {
        super(fonId);
        this.host = host;
        this.port = port;
    }

    /** Special placements, then ask Python for remaining module→device assignments. */
    @Override
    public void mapModules() {
        for (PlacementRequest pr : placementRequests) {
            mappedMicroservices.put(pr.getPlacementRequestId(),
                    new HashMap<>(pr.getPlacedMicroservices()));

            Application app = applicationInfo.get(pr.getApplicationId());
            for (String microservice : app.getSpecialPlacementInfo().keySet()) {
                for (String deviceName : app.getSpecialPlacementInfo().get(microservice)) {
                    FogDevice device = findDeviceByName(deviceName);
                    if (device == null) continue;
                    int    devId     = device.getId();
                    double required  = getModuleMips(microservice, app);
                    double available = resourceAvailability
                            .getOrDefault(devId, Collections.emptyMap())
                            .getOrDefault(ControllerComponent.CPU, 0.0);
                    if (required + getCurrentCpuLoad().getOrDefault(devId, 0.0) <= available) {
                        recordPlacement(pr.getPlacementRequestId(), microservice, devId, app);
                        System.out.println("[Bridge] Special placement: "
                                + microservice + " → " + device.getName());
                        break;
                    }
                }
            }
        }

        try {
            String stateJson  = buildStateJson();
            String actionJson = queryPythonAgent(stateJson);
            if (actionJson != null && !actionJson.trim().isEmpty()) {
                applyPythonDecision(actionJson);
            } else {
                System.err.println("[Bridge] Empty response from Python agent.");
            }
        } catch (Exception e) {
            System.err.println("[Bridge] Communication error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** One TCP exchange per call: send state line, read action line. */
    private String queryPythonAgent(String stateJson) throws IOException {
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

    /** Build state JSON for the Python agent. */
    private String buildStateJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        sb.append("\"step\":").append(stepCounter++).append(",");

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

        sb.append("\"requests\":[");
        Set<String> allModuleNames = new LinkedHashSet<>();
        boolean firstReq = true;

        for (PlacementRequest pr : placementRequests) {
            if (!firstReq) sb.append(",");
            firstReq = false;

            sb.append("{")
                    .append("\"requestId\":").append(pr.getPlacementRequestId()).append(",")
                    .append("\"appId\":\"").append(escape(pr.getApplicationId())).append("\",")
                    .append("\"gatewayDeviceId\":").append(pr.getGatewayDeviceId()).append(",");

            Map<String, Integer> placed = mappedMicroservices.get(pr.getPlacementRequestId());
            sb.append("\"alreadyPlaced\":{");
            if (placed != null) {
                boolean firstP = true;

                java.util.List<String> sortedKeys = new java.util.ArrayList<>(placed.keySet());
                java.util.Collections.sort(sortedKeys);

                for (String key : sortedKeys) {
                    if (!firstP) sb.append(",");
                    firstP = false;
                    sb.append("\"").append(escape(key)).append("\":")
                            .append(placed.get(key));
                }
            }
            sb.append("},");

            Application app = applicationInfo.get(pr.getApplicationId());
            sb.append("\"pendingModules\":[");
            boolean firstMod = true;
            for (AppModule module : app.getModules()) {
                allModuleNames.add(module.getName());
                if (placed != null && placed.containsKey(module.getName())) continue;
                if (!firstMod) sb.append(",");
                firstMod = false;
                sb.append("{")
                        .append("\"name\":\"").append(escape(module.getName())).append("\",")
                        .append("\"requiredMips\":").append((double) module.getMips()).append(",")
                        .append("\"requiredRam\":").append((double) module.getRam())
                        .append("}");
            }
            sb.append("]}");
        }
        sb.append("],");

        sb.append("\"allModules\":[");
        boolean firstM = true;
        for (String name : allModuleNames) {
            if (!firstM) sb.append(",");
            firstM = false;
            sb.append("\"").append(escape(name)).append("\"");
        }
        sb.append("]}");

        return sb.toString();
    }

    /** Parse {"placement":{...}} and record decisions. */
    private void applyPythonDecision(String responseJson) {
        String inner = responseJson.trim();
        if (!inner.startsWith("{") || !inner.endsWith("}")) {
            System.err.println("[Bridge] Malformed response: " + inner);
            return;
        }

        int placementStart = inner.indexOf("\"placement\"");
        if (placementStart < 0) {
            System.err.println("[Bridge] Response missing 'placement' key.");
            return;
        }
        int colonIdx = inner.indexOf(':', placementStart);
        int objStart = inner.indexOf('{', colonIdx + 1);
        if (objStart < 0) return;

        String placementObj = extractBalanced(inner, objStart);

        parsePlacementEntries(placementObj);
    }

    /** Parse placement object entries keyed by request id. */
    private void parsePlacementEntries(String placementObj) {
        String body = placementObj.substring(1, placementObj.length() - 1).trim();
        int pos = 0;
        while (pos < body.length()) {
            int keyStart = body.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd   = body.indexOf('"', keyStart + 1);
            String prIdStr = body.substring(keyStart + 1, keyEnd);

            int colon  = body.indexOf(':', keyEnd + 1);
            int valStart = body.indexOf('{', colon + 1);
            if (valStart < 0) break;

            String moduleObj = extractBalanced(body, valStart);

            try {
                int prId = Integer.parseInt(prIdStr.trim());
                applyModulePlacements(prId, moduleObj);
            } catch (NumberFormatException e) {
                System.err.println("[Bridge] Non-integer request ID: " + prIdStr);
            }

            pos = valStart + moduleObj.length();
        }
    }

    /** Apply module→device assignments for one request. */
    private void applyModulePlacements(int prId, String moduleObj) {
        PlacementRequest matchedPr = findPrById(prId);
        if (matchedPr == null) {
            System.err.println("[Bridge] Unknown requestId " + prId + " in Python response.");
            return;
        }
        Application app = applicationInfo.get(matchedPr.getApplicationId());

        String body = moduleObj.substring(1, moduleObj.length() - 1).trim();
        int pos = 0;
        while (pos < body.length()) {
            int keyStart = body.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd     = body.indexOf('"', keyStart + 1);
            String modName = body.substring(keyStart + 1, keyEnd);

            int colon      = body.indexOf(':', keyEnd + 1);
            if (colon < 0) break;

            int valEnd = body.indexOf(',', colon + 1);
            if (valEnd < 0) valEnd = body.length();
            String deviceIdStr = body.substring(colon + 1, valEnd).trim()
                                     .replaceAll("[^0-9\\-]", "");

            try {
                int deviceId = Integer.parseInt(deviceIdStr);

                Map<String, Integer> placed = mappedMicroservices.get(prId);
                if (placed != null && placed.containsKey(modName)) {
                    pos = valEnd + 1;
                    continue;
                }

                FogDevice device = findDeviceById(deviceId);
                if (device == null) {
                    System.err.println("[Bridge] Unknown deviceId " + deviceId
                            + " for module '" + modName + "'; skipping.");
                } else {
                    recordPlacement(prId, modName, deviceId, app);
                }
            } catch (NumberFormatException e) {
                System.err.println("[Bridge] Non-integer deviceId for module " + modName);
            }

            pos = valEnd + 1;
        }
    }

    /** Record a placement into the tracking maps used by generatePlacementMap(). */
    private void recordPlacement(int prId, String moduleName, int deviceId, Application app) {
        mappedMicroservices.get(prId).put(moduleName, deviceId);

        if (!currentModuleMap.get(deviceId).contains(moduleName))
            currentModuleMap.get(deviceId).add(moduleName);

        double mips = getModuleMips(moduleName, app);
        getCurrentCpuLoad().put(deviceId,
                getCurrentCpuLoad().getOrDefault(deviceId, 0.0) + mips);

        currentModuleLoadMap.get(deviceId).merge(moduleName, mips, Double::sum);

        currentModuleInstanceNum.get(deviceId).merge(moduleName, 1, Integer::sum);

        moduleToApp.put(moduleName, app.getAppId());

        String devName = findDeviceById(deviceId).getName();
        System.out.println("[Bridge] Placed '" + moduleName + "' on '" + devName + "'");

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step",       stepCounter - 1);
        entry.put("requestId",  prId);
        entry.put("module",     moduleName);
        entry.put("device",     devName);
        entry.put("deviceId",   deviceId);
        PLACEMENT_LOG.add(entry);
    }

    /** Returns the MIPS requirement of a named module, 0 if not found. */
    private double getModuleMips(String moduleName, Application app) {
        for (AppModule m : app.getModules()) {
            if (m.getName().equals(moduleName)) return m.getMips();
        }
        return 0.0;
    }

    private FogDevice findDeviceByName(String name) {
        for (FogDevice d : fogDevices) {
            if (d.getName().equals(name)) return d;
        }
        return null;
    }

    private FogDevice findDeviceById(int id) {
        for (FogDevice d : fogDevices) {
            if (d.getId() == id) return d;
        }
        return null;
    }

    private PlacementRequest findPrById(int prId) {
        for (PlacementRequest pr : placementRequests) {
            if (pr.getPlacementRequestId() == prId) return pr;
        }
        return null;
    }

    /** Brace-balanced substring starting at {@code start}. */
    private static String extractBalanced(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
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
        // intentionally empty
    }
}
