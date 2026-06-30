package org.fog.test.perfeval;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.*;
import org.fog.placement.MicroservicesController;
import org.fog.placement.PlacementLogicFactory;
import org.fog.placement.PythonBridgePlacementLogic;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.MicroservicePlacementConfig;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;

import org.fog.application.selectivity.SeededSelectivity;

import java.util.*;

/**
 * Industry 4.0 simulation scenario demonstrating microservice placement via an
 * external Python agent connected through a TCP socket bridge.
 *
 * Topology
 * ────────
 *   Cloud  (CLOUD, level 0)
 *     └── FogGW-0 … FogGW-4  (FCN, level 1)  – 5 industrial edge gateways
 *           └── IoT-0-0 … IoT-2-2  (CLIENT, level 2)  – 9 IoT devices
 *                 (3 clients per gateway for GW-0/1/2; GW-3/4 are spare compute)
 *
 * Application: "industrial_iot"
 * ─────────────────────────────
 *   Three microservices model a typical factory monitoring pipeline:
 *     1. data_preprocessor  – lightweight filter/aggregation  (pre-placed on CLIENT)
 *     2. smart_analyzer      – ML inference on sensor streams  (Python decides)
 *     3. actuator_controller – generates control commands       (Python decides)
 *
 *   Data flow:
 *     IoT_SENSOR ─UP→ data_preprocessor ─UP→ smart_analyzer
 *                                             smart_analyzer ─DOWN→ actuator_controller
 *                                             actuator_controller ─DOWN→ data_preprocessor
 *                                             data_preprocessor ─DOWN→ ACTUATOR
 *
 * Placement algorithm
 * ───────────────────
 *   PlacementLogicFactory.PYTHON_BRIDGE_PLACEMENT delegates every decision to
 *   the Python agent running on localhost:5555.  Switch the constant to
 *   CLUSTERED_MICROSERVICES_PLACEMENT for the built-in baseline comparison.
 *
 * How to run
 * ──────────
 *   1. Start the Python agent:   cd python_agent && python server.py
 *   2. Run this main() method from your IDE or: javac + java
 *
 * @author M-H-Boroumandnia
 */
public class IndustrialIoTSimulation {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor>    sensors    = new ArrayList<>();
    static List<Actuator>  actuators  = new ArrayList<>();

    /** Application identifier shared by all sensors/actuators/modules. */
    static final String APP_ID = "industrial_iot";

    /**
     * Shared application reference used by addIoTClient() to link sensors and
     * actuators to the application object (required by Sensor.transmit()).
     * Set once in main() before createFogDevices() is called.
     */
    static Application application;

    /** Loop ID of the sensor-to-actuator AppLoop, used to read TimeKeeper's measured latency. */
    static int e2eLoopId = -1;

    /**
     * Placement algorithm to use.
     * Swap to PlacementLogicFactory.CLUSTERED_MICROSERVICES_PLACEMENT for
     * the built-in heuristic baseline (no Python agent required).
     */
//    static final int PLACEMENT_ALGO = PlacementLogicFactory.CLUSTERED_MICROSERVICES_PLACEMENT;
    static final int PLACEMENT_ALGO = PlacementLogicFactory.PYTHON_BRIDGE_PLACEMENT;

    // ── Topology parameters ──────────────────────────────────────────────────
    /** Number of industrial edge gateways (fog nodes). Range: 5–15 per spec. */
    static final int NUM_FOG_GATEWAYS    = 5;
    /**
     * IoT clients per gateway.  With FOG_GW_MIPS=4000 each gateway fits exactly
     * one SA+AC pair (2800 MIPS), so 5 gateways absorb 5 of the 9 requests and
     * the remaining 4 overflow to cloud.  This creates the capacity scarcity
     * needed for heuristic vs GA to produce meaningfully different placements.
     */
    static final int CLIENTS_PER_GATEWAY = 3;  // 9 total IoT devices
    /** MIPS for each fog gateway.  4000 = one SA+AC pair (2800 MIPS) per gateway. */
    static final int FOG_GW_MIPS = 4000;
    /** Uplink latency (ms) from fog gateway to cloud. */
    static final int GW_TO_CLOUD_LATENCY  = 100;
    /** Uplink latency (ms) from IoT client to its gateway. */
    static final int IOT_TO_GW_LATENCY    = 20;

    static final long SIM_SEED = 42L;

    // ── Sensor parameters ────────────────────────────────────────────────────
    /** Seconds between successive sensor transmissions (deterministic). */
    static final double SENSOR_PERIOD_S = 5.0;

    // ── Cluster latency (not used in this flat topology, kept for API compat) ──
    static final double CLUSTER_LATENCY = 2.0;


    public static void main(String[] args) {
        Log.printLine("Starting IndustrialIoTSimulation (Industry 4.0 scenario)...");

        try {
            Log.disable();
            int num_user    = 1;
            Calendar cal    = Calendar.getInstance();
            boolean tracing = false;

            CloudSim.init(num_user, cal, tracing);

            FogBroker broker = new FogBroker("industrial-broker");

            /**
             * Application model: three microservices forming a sensor-to-actuator
             * pipeline typical of industrial IoT control loops.
             */
            Application app = createApplication(APP_ID, broker.getId());
            application = app; // stored so addIoTClient() can call sensor.setApp()
            app.setUserId(broker.getId());

            /**
             * Fog device topology:
             *   Cloud → 5 industrial edge gateways (FCN) → 9 IoT clients (CLIENT)
             * All placement requests bubble up to the cloud, which uses the Python bridge.
             */
            createFogDevices(broker.getId());

            /**
             * Routing configuration.
             * The cloud is the single orchestrator: it monitors ALL fog devices and
             * serves as the FON for every FCN gateway and every IoT client.
             */
            FogDevice cloud = getFogDeviceByName("cloud");

            // Cloud monitors every device in the topology
            Map<Integer, List<FogDevice>> monitored = new HashMap<>();
            monitored.put(cloud.getId(), new ArrayList<>(fogDevices));

            // Assign FON IDs so that placement requests route to the cloud
            for (FogDevice fd : fogDevices) {
                MicroserviceFogDevice mfd = (MicroserviceFogDevice) fd;
                if (mfd.getDeviceType().equals(MicroserviceFogDevice.CLOUD)) {
                    mfd.setFonID(cloud.getId());         // cloud is its own FON
                } else {
                    mfd.setFonID(cloud.getId());         // all others delegate to cloud
                }
            }

            /**
             * Configure placement mode.
             * STATIC: placement decisions happen before simulation time advances,
             * so the Python agent is called exactly once (synchronously).
             */
            MicroservicePlacementConfig.SIMULATION_MODE         = "STATIC";
            MicroservicePlacementConfig.PR_PROCESSING_MODE      = MicroservicePlacementConfig.SEQUENTIAL;
            MicroservicePlacementConfig.ENABLE_RESOURCE_DATA_SHARING = false;

            List<Integer> clusterLevels = new ArrayList<>(); // no static clustering in flat topology

            List<Application> appList = new ArrayList<>();
            appList.add(app);

            MicroservicesController controller = new MicroservicesController(
                    "industrial-controller", fogDevices, sensors, appList,
                    clusterLevels, CLUSTER_LATENCY, PLACEMENT_ALGO, monitored);

            /**
             * Generate one placement request per IoT sensor.
             * data_preprocessor is pre-placed on the sensor's CLIENT device because
             * it runs directly on the IoT gateway (lightweight filtering at the edge).
             */
            List<PlacementRequest> placementRequests = new ArrayList<>();
            for (Sensor s : sensors) {
                Map<String, Integer> prePlaced = new HashMap<>();
                prePlaced.put("data_preprocessor", s.getGatewayDeviceId()); // pinned to edge
                PlacementRequest pr = new PlacementRequest(
                        s.getAppId(), s.getId(), s.getGatewayDeviceId(), prePlaced);
                placementRequests.add(pr);
            }

            controller.submitPlacementRequests(placementRequests, 0);

            TimeKeeper.getInstance().setSimulationStartTime(
                    Calendar.getInstance().getTimeInMillis());

            
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                sendResultsToPython(fogDevices, PythonBridgePlacementLogic.PLACEMENT_LOG,
                        PythonBridgePlacementLogic.DEFAULT_HOST,
                        PythonBridgePlacementLogic.DEFAULT_PORT)
            ));

            CloudSim.startSimulation();

        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Simulation terminated with an error.");
        }
    }

    // -------------------------------------------------------------------------
    // Fog-device topology creation
    // -------------------------------------------------------------------------

    /**
     * Creates the full fog hierarchy and populates {@code fogDevices}.
     *
     * Hierarchy:
     *   Cloud (CLOUD, level 0)
     *     └── FogGW-0…4  (FCN, level 1)
     *           └── IoT-0-0…2-2  (CLIENT, level 2)  [only for GW 0, 1, 2]
     *
     * @param userId broker ID used to link sensors and actuators
     */
    private static void createFogDevices(int userId) {
        // ── Cloud: apex of the hierarchy, high compute, acts as central orchestrator ──
        MicroserviceFogDevice cloud = createFogDevice(
                "cloud",
                44800,  // MIPS
                40000,  // RAM (MB)
                100,    // uplink BW (Mbps)
                10000,  // downlink BW (Mbps)
                0,      // level
                0.01,   // cost per MIPS
                16 * 103, 16 * 83.25,
                MicroserviceFogDevice.CLOUD);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        // ── Industrial edge gateways: FCN nodes at the factory floor level ──
        for (int i = 0; i < NUM_FOG_GATEWAYS; i++) {
            MicroserviceFogDevice gw = createFogDevice(
                    "FogGW-" + i,
                    FOG_GW_MIPS, // MIPS  – constrained industrial gateway
                    8192,        // RAM (MB)
                    10000,  // uplink BW (Mbps)
                    10000,  // downlink BW (Mbps)
                    1,      // level
                    0.0,
                    107.339, 83.4333,
                    MicroserviceFogDevice.FCN);
            gw.setParentId(cloud.getId());
            gw.setUplinkLatency(GW_TO_CLOUD_LATENCY); // latency from edge gateway to cloud
            fogDevices.add(gw);

            // Attach IoT client devices to the first three gateways
            if (i < 3) {
                for (int j = 0; j < CLIENTS_PER_GATEWAY; j++) {
                    addIoTClient(userId, i + "-" + j, gw.getId());
                }
            }
        }
    }

    /**
     * Creates a single IoT CLIENT device with one sensor and one actuator attached.
     *
     * @param userId   broker ID
     * @param id       string suffix used to generate unique device/sensor/actuator names
     * @param parentId ID of the gateway this client connects to
     */
    private static void addIoTClient(int userId, String id, int parentId) {
        MicroserviceFogDevice iotDevice = createFogDevice(
                "IoT-" + id,
                1000,   // MIPS  – constrained IoT device
                2048,   // RAM (MB)
                18750,  // uplink BW
                250,    // downlink BW
                2,      // level
                0.0,
                87.53, 82.44,
                MicroserviceFogDevice.CLIENT);
        iotDevice.setParentId(parentId);
        iotDevice.setUplinkLatency(IOT_TO_GW_LATENCY); // latency from IoT device to gateway
        fogDevices.add(iotDevice);

        // ── IoT sensor: emits raw readings periodically ──
        Sensor sensor = new Sensor(
                "sensor-" + id,          // unique name
                "IoT_SENSOR",            // tuple type produced
                userId,
                APP_ID,
                new DeterministicDistribution(SENSOR_PERIOD_S));  // fixed 5-second interval
        sensor.setGatewayDeviceId(iotDevice.getId());
        sensor.setLatency(1.0);          // sensor-to-device connection latency (ms)
        sensor.setApp(application);      // required by Sensor.transmit() to read the app's DAG
        sensors.add(sensor);

        // ── Industrial actuator: receives control commands ──
        Actuator actuator = new Actuator("actuator-" + id, userId, APP_ID, "ACTUATOR");
        actuator.setGatewayDeviceId(iotDevice.getId());
        actuator.setLatency(1.0);        // actuator-to-device connection latency (ms)
        actuator.setApp(application);    // required to correctly route actuator tuples
        actuators.add(actuator);
    }

    /**
     * Creates a MicroserviceFogDevice with the given hardware parameters.
     *
     * @param nodeName    unique device name
     * @param mips        processing speed (MIPS)
     * @param ram         RAM in MB
     * @param upBw        uplink bandwidth (Mbps)
     * @param downBw      downlink bandwidth (Mbps)
     * @param level       hierarchy level (0 = cloud apex)
     * @param ratePerMips cost coefficient per MIPS consumed
     * @param busyPower   power draw at full load (W)
     * @param idlePower   power draw at idle (W)
     * @param deviceType  one of MicroserviceFogDevice.{CLOUD, FON, FCN, CLIENT}
     * @return configured MicroserviceFogDevice
     */
    private static MicroserviceFogDevice createFogDevice(
            String nodeName, long mips, int ram, long upBw, long downBw,
            int level, double ratePerMips, double busyPower, double idlePower,
            String deviceType) {

        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips))); // single-core PE

        int  hostId  = FogUtils.generateEntityId();
        long storage = 1_000_000L; // 1 GB storage (not bottleneck in this scenario)
        int  bw      = 10_000;

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerOverbooking(bw),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(busyPower, idlePower));

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        String arch    = "x86";
        String os      = "Linux";
        String vmm     = "Xen";
        double tz      = 10.0;
        double cost    = 3.0;
        double costMem = 0.05;
        double costStr = 0.001;
        double costBw  = 0.0;
        LinkedList<Storage> storageList = new LinkedList<>();

        FogDeviceCharacteristics chars = new FogDeviceCharacteristics(
                arch, os, vmm, host, tz, cost, costMem, costStr, costBw);

        MicroserviceFogDevice device = null;
        try {
            device = new MicroserviceFogDevice(
                    nodeName, chars,
                    new AppModuleAllocationPolicy(hostList),
                    storageList,
                    10,           // scheduling interval
                    upBw, downBw,
                    1_250_000,    // inter-cluster bandwidth
                    0,
                    ratePerMips,
                    deviceType);
        } catch (Exception e) {
            e.printStackTrace();
        }

        assert device != null;
        device.setLevel(level);
        return device;
    }

    // -------------------------------------------------------------------------
    // Application model
    // -------------------------------------------------------------------------

    /**
     * Builds the three-microservice Industry 4.0 application graph.
     *
     * Module responsibilities:
     *   data_preprocessor  – Aggregates and filters raw sensor readings (edge, low latency)
     *   smart_analyzer      – Runs inference on filtered data (fog/cloud, compute-heavy)
     *   actuator_controller – Generates PID/bang-bang control commands (fog, low latency)
     *
     * @param appId  application identifier
     * @param userId broker ID
     * @return fully configured Application
     */
    @SuppressWarnings("serial")
    private static Application createApplication(String appId, int userId) {
        Application app = Application.createApplication(appId, userId);

        /*
         * Adding modules (graph vertices).
         * Parameters: name, RAM (MB), MIPS, bandwidth (kbps)
         */
        app.addAppModule("data_preprocessor",  128,  500, 200);  // lightweight edge filter
        app.addAppModule("smart_analyzer",      512, 2000, 500);  // ML inference, compute-heavy
        app.addAppModule("actuator_controller", 256,  800, 300);  // control loop, latency-sensitive

        /*
         * Connecting modules with directed data-flow edges.
         *
         * addAppEdge(src, dst, tupleCpuLen, tupleNwLen, tupleType, direction, edgeType)
         * addAppEdge(src, dst, tupleCpuLen, tupleNwLen, period, tupleType, direction, edgeType)  ← periodic
         */
        // Raw sensor data enters the pipeline at the edge
        app.addAppEdge("IoT_SENSOR",        "data_preprocessor",  2000, 500,
            "IoT_SENSOR",       Tuple.UP, AppEdge.SENSOR);

        // Filtered data travels up to the smart analyzer for inference
        app.addAppEdge("data_preprocessor", "smart_analyzer",      3500, 500,
                "FILTERED_DATA",    Tuple.UP, AppEdge.MODULE);

        // Inference result emitted for every filtered sample received
        app.addAppEdge("smart_analyzer",    "actuator_controller", 100, 1000,
                "ANALYSIS_RESULT",  Tuple.UP, AppEdge.MODULE);

        // Control commands flow back down to the preprocessor for local fast-loop feedback
        app.addAppEdge("actuator_controller", "data_preprocessor", 14, 500,
                "CONTROL_CMD",      Tuple.DOWN, AppEdge.MODULE);

        // Final actuator commands reach the physical actuator
        app.addAppEdge("data_preprocessor",   "ACTUATOR",          1000, 500,
                "ACTUATOR_CMD",     Tuple.DOWN, AppEdge.ACTUATOR);

        /*
         * Selectivity: fraction of output tuples emitted per input tuple.
         */
        app.addTupleMapping("data_preprocessor",  "IoT_SENSOR",      "FILTERED_DATA",
                new SeededSelectivity(0.8, SIM_SEED));   // 20% of raw data is filtered out

        app.addTupleMapping("smart_analyzer",      "FILTERED_DATA",   "ANALYSIS_RESULT",
                new FractionalSelectivity(1.0));   // every filtered sample produces an analysis

        app.addTupleMapping("actuator_controller", "ANALYSIS_RESULT", "CONTROL_CMD",
                new FractionalSelectivity(1.0));   // every analysis produces a control command

        app.addTupleMapping("data_preprocessor",  "CONTROL_CMD",     "ACTUATOR_CMD",
                new FractionalSelectivity(1.0));   // every control command reaches the actuator

        /*
         * Application loop used by TimeKeeper to measure end-to-end latency.
         * Monitors the full sensor-to-actuator path.
         */
        final AppLoop e2eLoop = new AppLoop(new ArrayList<String>() {{
            add("IoT_SENSOR");
            add("data_preprocessor");
            add("smart_analyzer");
            add("actuator_controller");
            add("data_preprocessor");
            add("ACTUATOR");
        }});
        app.setLoops(new ArrayList<AppLoop>() {{ add(e2eLoop); }});
        e2eLoopId = e2eLoop.getLoopId();

        // No special (forced) placements – Python agent decides where smart_analyzer
        // and actuator_controller live.
        app.createDAG();
        return app;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /** Looks up a fog device by name in the global device list. */
    private static FogDevice getFogDeviceByName(String name) {
        for (FogDevice fd : fogDevices) {
            if (fd.getName().equals(name)) return fd;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Results reporting
    // -------------------------------------------------------------------------

    /**
     * Sends a structured results message to the Python agent after the simulation
     * ends, then receives an acknowledgement and closes the connection.
     *
     * The Python server saves this to python_agent/results/<timestamp>.json.
     *
     * Results message schema:
     * {
     *   "type": "results",
     *   "simulationTime": <float>,       // CloudSim clock at end of simulation
     *   "totalEnergy": <float>,          // sum across all devices
     *   "energyPerDevice": [
     *     { "name": <str>, "level": <int>, "energy": <float> }, ...
     *   ],
     *   "cloudCost": <float>,            // cloud execution cost
     *   "loopDelay": <float|null>,       // TimeKeeper-measured avg E2E loop latency (ms)
     *   "loopSampleCount": <int>,        // number of completed loop traversals averaged into loopDelay
     *   "tupleCpuDelays": {              // TimeKeeper per-tuple-type avg CPU execution time (ms)
     *     "<tupleType>": <float>, ...
     *   },
     *   "numRequests": <int>,            // total placement requests processed
     *   "placements": [
     *     { "step": <int>, "requestId": <int>, "module": <str>,
     *       "device": <str>, "deviceId": <int> }, ...
     *   ]
     * }
     *
     * @param devices        all fog devices (for energy readings)
     * @param placementLog   placement decisions recorded by the bridge
     * @param host           Python server host
     * @param port           Python server port
     */
    private static void sendResultsToPython(
            List<FogDevice> devices,
            List<Map<String, Object>> placementLog,
            String host, int port) {

        try (java.net.Socket socket = new java.net.Socket(host, port)) {
            socket.setSoTimeout(15_000);
            java.io.PrintWriter out = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), "UTF-8"));

            // ── Build results JSON manually (no external library needed) ──
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"type\":\"results\",");
            sb.append("\"simulationTime\":").append(org.cloudbus.cloudsim.core.CloudSim.clock()).append(",");

            // Energy per device
            double totalEnergy = 0;
            sb.append("\"energyPerDevice\":[");
            boolean firstDev = true;
            for (FogDevice fd : devices) {
                if (!firstDev) sb.append(",");
                firstDev = false;
                double e = fd.getEnergyConsumption();
                totalEnergy += e;
                sb.append("{")
                  .append("\"name\":\"").append(fd.getName()).append("\",")
                  .append("\"level\":").append(fd.getLevel()).append(",")
                  .append("\"energy\":").append(e)
                  .append("}");
            }
            sb.append("],");
            sb.append("\"totalEnergy\":").append(totalEnergy).append(",");

            // Cloud cost (getTotalCost() is available on FogDevice via PowerDatacenter)
            FogDevice cloud = getFogDeviceByName("cloud");
            double cloudCost = (cloud != null) ? cloud.getTotalCost() : 0.0;
            sb.append("\"cloudCost\":").append(cloudCost).append(",");

            // E2E loop latency, as measured by TimeKeeper across the actual tuple chain
            Double loopDelay = TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(e2eLoopId);
            sb.append("\"loopDelay\":").append(loopDelay != null ? loopDelay : "null").append(",");

            Integer loopSampleCount = TimeKeeper.getInstance().getLoopIdToCurrentNum().get(e2eLoopId);
            sb.append("\"loopSampleCount\":").append(loopSampleCount != null ? loopSampleCount : 0).append(",");

            // Per-tuple-type CPU execution delay (queueing + processing time on the PE)
            sb.append("\"tupleCpuDelays\":{");
            boolean firstTupleType = true;
            for (Map.Entry<String, Double> entry : TimeKeeper.getInstance().getTupleTypeToAverageCpuTime().entrySet()) {
                if (!firstTupleType) sb.append(",");
                firstTupleType = false;
                sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            }
            sb.append("},");

            sb.append("\"numRequests\":").append(placementLog.stream()
                    .map(e -> e.get("requestId")).distinct().count()).append(",");

            // Placement decisions
            sb.append("\"placements\":[");
            boolean firstP = true;
            for (Map<String, Object> entry : placementLog) {
                if (!firstP) sb.append(",");
                firstP = false;
                sb.append("{")
                  .append("\"step\":").append(entry.get("step")).append(",")
                  .append("\"requestId\":").append(entry.get("requestId")).append(",")
                  .append("\"module\":\"").append(entry.get("module")).append("\",")
                  .append("\"device\":\"").append(entry.get("device")).append("\",")
                  .append("\"deviceId\":").append(entry.get("deviceId"))
                  .append("}");
            }
            sb.append("]}");

            // ── Send and wait for acknowledgement ──
            out.println(sb.toString());
            String ack = in.readLine();
            System.out.println("[Bridge] Results sent. Server ack: " + ack);

        } catch (Exception e) {
            System.err.println("[Bridge] Could not send results to Python: " + e.getMessage());
        }
        // Socket is closed automatically by try-with-resources
        System.out.println("[Bridge] Connection closed.");
    }
}
