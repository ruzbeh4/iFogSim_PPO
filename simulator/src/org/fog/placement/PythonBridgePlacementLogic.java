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

/**
 * Placement logic that delegates scheduling decisions to an external Python agent
 * over a plain TCP socket using newline-terminated JSON messages.
 *
 * Protocol (one exchange per placement round):
 *   Java   →  Python : single JSON line describing the full simulation state
 *   Python →  Java   : single JSON line containing the placement decision
 *
 * A fresh TCP connection is opened for every placement round so that multiple
 * FON devices can share the same Python server without connection conflicts.
 * The Python server must be running BEFORE the simulation starts (default: localhost:5555).
 *
 * ── State message schema (Java → Python) ─────────────────────────────────────
 * {
 *   "step": <int>,
 *   "devices": [
 *     { "id":<int>, "name":<str>, "level":<int>, "parentId":<int>,
 *       "availableMips":<float>, "availableRam":<float>, "currentLoad":<float> },
 *     ...
 *   ],
 *   "requests": [
 *     { "requestId":<int>, "appId":<str>, "gatewayDeviceId":<int>,
 *       "alreadyPlaced": { "<moduleName>":<deviceId>, ... },
 *       "pendingModules": [
 *         { "name":<str>, "requiredMips":<float>, "requiredRam":<float> }, ...
 *       ]
 *     }, ...
 *   ],
 *   "allModules": ["<moduleName>", ...]
 * }
 *
 * ── Action message schema (Python → Java) ────────────────────────────────────
 * {
 *   "placement": {
 *     "<requestId>": { "<moduleName>": <deviceId>, ... },
 *     ...
 *   }
 * }
 *
 * @author M-H-Boroumandnia
 */
public class PythonBridgePlacementLogic extends ClusteredMicroservicePlacementLogic {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------
    public static final String DEFAULT_HOST      = "localhost";
    public static final int    DEFAULT_PORT      = 5555;
    /** Milliseconds the Java side waits for the Python agent before timing out. */
    private static final int    SOCKET_TIMEOUT_MS = 10_000;

    private final String host;
    private final int    port;

    /** Monotonically increasing counter included in each state message. */
    private int stepCounter = 0;

    /**
     * Simulation-wide placement log, accumulated across every bridge instance and
     * every placement round.  Read by IndustrialIoTSimulation.sendResultsToPython()
     * to include full placement history in the final results message.
     *
     * Each entry: step | requestId | moduleName | deviceName | deviceId
     */
    public static final List<Map<String, Object>> PLACEMENT_LOG = new ArrayList<>();

    /** Clears the log between simulation runs (call before startSimulation()). */
    public static void clearLog() { PLACEMENT_LOG.clear(); }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public PythonBridgePlacementLogic(int fonId) {
        this(fonId, DEFAULT_HOST, DEFAULT_PORT);
    }

    public PythonBridgePlacementLogic(int fonId, String host, int port) {
        super(fonId);
        this.host = host;
        this.port = port;
    }

    // -------------------------------------------------------------------------
    // Core override – replaces the local greedy algorithm with a Python call
    // -------------------------------------------------------------------------

    /**
     * Overrides the parent's module-mapping step. Instead of placing modules
     * locally, this method:
     *  1. Handles "special" (cloud-forced) placements the same way as the parent.
     *  2. Serialises the remaining state to JSON and sends it to the Python agent.
     *  3. Receives the Python agent's placement decision and records it in the
     *     shared tracking maps read by generatePlacementMap() afterwards.
     */
    @Override
    public void mapModules() {
        // ── 1. Initialise per-request tracking & force-place special modules ──
        for (PlacementRequest pr : placementRequests) {
            // Copy already-placed modules (e.g. the client module on the IoT device)
            mappedMicroservices.put(pr.getPlacementRequestId(),
                    new HashMap<>(pr.getPlacedMicroservices()));

            // Force-place any module the application declares must live on a
            // named device (e.g. heavy analytics locked to "cloud")
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

        // ── 2. Ask Python agent for remaining placements ──
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

    // -------------------------------------------------------------------------
    // Network I/O
    // -------------------------------------------------------------------------

    /**
     * Opens a fresh TCP connection, sends the state JSON as a single line,
     * reads one response line, then closes the connection.
     *
     * Using a new connection per call lets multiple FON devices share the same
     * Python server without needing any thread-local socket management.
     */
    private String queryPythonAgent(String stateJson) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            PrintWriter    out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            BufferedReader in  = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));

            out.println(stateJson);          // send – println appends '\n'
            return in.readLine();            // block until Python replies with one line
        }
    }

    // -------------------------------------------------------------------------
    // JSON serialisation (hand-rolled – no external library needed)
    // -------------------------------------------------------------------------

    /**
     * Builds the full simulation-state JSON string sent to the Python agent.
     * All values are escaped manually so json-simple is not required.
     */
    private String buildStateJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // ── step ──
        sb.append("\"step\":").append(stepCounter++).append(",");

        // ── devices ──
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

        // ── requests ──
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

            // Already placed modules
            Map<String, Integer> placed = mappedMicroservices.get(pr.getPlacementRequestId());
            sb.append("\"alreadyPlaced\":{");
            if (placed != null) {
                boolean firstP = true;
                for (Map.Entry<String, Integer> e : placed.entrySet()) {
                    if (!firstP) sb.append(",");
                    firstP = false;
                    sb.append("\"").append(escape(e.getKey())).append("\":")
                      .append(e.getValue());
                }
            }
            sb.append("},");

            // Pending modules
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

        // ── allModules ──
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

    // -------------------------------------------------------------------------
    // JSON deserialisation (hand-rolled)
    // -------------------------------------------------------------------------

    /**
     * Parses the Python agent's response and records each placement decision.
     *
     * Expected format (strict – no nested objects beyond what is described):
     *   {"placement":{"<requestId>":{"<moduleName>":<deviceId>,...},...}}
     *
     * This minimal parser relies on the fact that the Python side emits a
     * well-formed, compact JSON object (no pretty-printing).  For a production
     * system, replace with a proper JSON library.
     */
    private void applyPythonDecision(String responseJson) {
        // Strip outer braces and locate the "placement" object
        String inner = responseJson.trim();
        if (!inner.startsWith("{") || !inner.endsWith("}")) {
            System.err.println("[Bridge] Malformed response: " + inner);
            return;
        }

        // Extract the value of "placement": { ... }
        int placementStart = inner.indexOf("\"placement\"");
        if (placementStart < 0) {
            System.err.println("[Bridge] Response missing 'placement' key.");
            return;
        }
        // Find the colon after "placement", then the opening '{' of its value
        int colonIdx = inner.indexOf(':', placementStart);
        int objStart = inner.indexOf('{', colonIdx + 1);
        if (objStart < 0) return;

        // The placement value runs from objStart to the matching closing '}'
        String placementObj = extractBalanced(inner, objStart);

        // Iterate over each "<requestId>": { ... } entry
        parsePlacementEntries(placementObj);
    }

    /**
     * Parses the top-level entries of the placement object.
     * Each entry looks like  "101":{"smart_analyzer":3,"actuator_controller":1}
     */
    private void parsePlacementEntries(String placementObj) {
        // Remove outer braces
        String body = placementObj.substring(1, placementObj.length() - 1).trim();
        int pos = 0;
        while (pos < body.length()) {
            // Find the next quoted key (request ID)
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

    /**
     * Applies individual module→device assignments for one placement request.
     * moduleObj looks like  {"smart_analyzer":3,"actuator_controller":1}
     */
    private void applyModulePlacements(int prId, String moduleObj) {
        // Find the matching PlacementRequest
        PlacementRequest matchedPr = findPrById(prId);
        if (matchedPr == null) {
            System.err.println("[Bridge] Unknown requestId " + prId + " in Python response.");
            return;
        }
        Application app = applicationInfo.get(matchedPr.getApplicationId());

        // Remove outer braces
        String body = moduleObj.substring(1, moduleObj.length() - 1).trim();
        int pos = 0;
        while (pos < body.length()) {
            int keyStart = body.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd     = body.indexOf('"', keyStart + 1);
            String modName = body.substring(keyStart + 1, keyEnd);

            int colon      = body.indexOf(':', keyEnd + 1);
            if (colon < 0) break;

            // Value is an integer (deviceId) – read until ',' or end
            int valEnd = body.indexOf(',', colon + 1);
            if (valEnd < 0) valEnd = body.length();
            String deviceIdStr = body.substring(colon + 1, valEnd).trim()
                                     .replaceAll("[^0-9\\-]", "");

            try {
                int deviceId = Integer.parseInt(deviceIdStr);

                // Skip if already placed by special-placement logic
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

    // -------------------------------------------------------------------------
    // Tracking maps helper
    // -------------------------------------------------------------------------

    /**
     * Records a single module→device assignment into all four tracking maps
     * that the parent's generatePlacementMap() reads afterwards.
     */
    private void recordPlacement(int prId, String moduleName, int deviceId, Application app) {
        // Primary placement map for this request
        mappedMicroservices.get(prId).put(moduleName, deviceId);

        // Per-device module list
        if (!currentModuleMap.get(deviceId).contains(moduleName))
            currentModuleMap.get(deviceId).add(moduleName);

        // CPU load accounting
        double mips = getModuleMips(moduleName, app);
        getCurrentCpuLoad().put(deviceId,
                getCurrentCpuLoad().getOrDefault(deviceId, 0.0) + mips);

        // Per-device module load map
        currentModuleLoadMap.get(deviceId).merge(moduleName, mips, Double::sum);

        // Per-device module instance count (used by updateResources)
        currentModuleInstanceNum.get(deviceId).merge(moduleName, 1, Integer::sum);

        // Module → application mapping (used by generatePlacementMap & updateResources)
        moduleToApp.put(moduleName, app.getAppId());

        String devName = findDeviceById(deviceId).getName();
        System.out.println("[Bridge] Placed '" + moduleName + "' on '" + devName + "'");

        // Append to simulation-wide placement history for the results report
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step",       stepCounter - 1); // stepCounter already incremented
        entry.put("requestId",  prId);
        entry.put("module",     moduleName);
        entry.put("device",     devName);
        entry.put("deviceId",   deviceId);
        PLACEMENT_LOG.add(entry);
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

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

    /**
     * Returns the balanced brace-delimited substring starting at {@code start}.
     * e.g. extractBalanced("{a:{b:1},c:2}", 0) → "{a:{b:1},c:2}"
     */
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
        return s.substring(start); // malformed – return remainder
    }

    /**
     * Escapes a string for safe embedding inside a JSON string literal.
     * Handles the only characters that appear in device/module names in this project.
     */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * Called after generatePlacementMap(); nothing to clean up – the socket is
     * closed inside queryPythonAgent() via try-with-resources.
     */
    @Override
    public void postProcessing() {
        // intentionally empty
    }
}
