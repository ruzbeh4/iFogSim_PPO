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
import org.fog.application.selectivity.SeededSelectivity;
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

import java.util.*;

/**
 * Industry 4.0 simulation scenario for Project 8 requirements.
 *
 * Topology (Scaled for GA & PPO Trade-off)
 * ────────
 * Cloud  (CLOUD, level 0) - Infinite compute, high latency
 * └── FogGW-0 … FogGW-9  (FCN, level 1)  – 10 industrial edge gateways
 * └── IoT-0-0 … IoT-9-14  (CLIENT, level 2)  – 150 total IoT devices
 *
 * Tasks
 * ────────
 * Each IoT client generates two types of tasks:
 * 1. Periodic Monitoring (5.0s interval)
 * 2. Critical Alerts (45.0s interval)
 */
public class IndustrialIoTSimulation2 {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor>    sensors    = new ArrayList<>();
    static List<Actuator>  actuators  = new ArrayList<>();

    static final String APP_ID = "industrial_iot";
    static Application application;

    // Global seed for deterministic GA and PPO comparisons
    static final long SIM_SEED = 42L;

    static final int PLACEMENT_ALGO = PlacementLogicFactory.PYTHON_BRIDGE_PLACEMENT;

    // ── Topology parameters (Project 8 Document Limits) ──────────────────────
    static final int NUM_FOG_GATEWAYS    = 10;  // Allowed: 5 to 15
    static final int CLIENTS_PER_GATEWAY = 15;  // Total: 150 IoT devices (Allowed: 100-200)

    // ── MIPS Bottleneck Setup ────────────────────────────────────────────────
    // Cloud has massive capacity to handle offloaded tasks
    static final int CLOUD_MIPS = 500_000;
    // Gateway holds exactly 5 module pairs (2800 MIPS each). 10 overflow to cloud.
    static final int FOG_GW_MIPS = 15_000;

    static final int GW_TO_CLOUD_LATENCY  = 100;
    static final int IOT_TO_GW_LATENCY    = 20;

    // ── Sensor parameters (Two Task Types) ───────────────────────────────────
    static final double SENSOR_PERIODIC_S = 5.0;
    static final double SENSOR_CRITICAL_S = 45.0;

    static final double CLUSTER_LATENCY = 2.0;

    public static void main(String[] args) {
        Log.printLine("Starting IndustrialIoTSimulation2 (Project 8 Scaled Scenario)...");

        try {
            Log.disable();
            int num_user    = 1;
            Calendar cal    = Calendar.getInstance();
            boolean tracing = false;

            CloudSim.init(num_user, cal, tracing);

            FogBroker broker = new FogBroker("industrial-broker");

            Application app = createApplication(APP_ID, broker.getId());
            application = app;
            app.setUserId(broker.getId());

            createFogDevices(broker.getId());

            FogDevice cloud = getFogDeviceByName("cloud");

            Map<Integer, List<FogDevice>> monitored = new HashMap<>();
            monitored.put(cloud.getId(), new ArrayList<>(fogDevices));

            for (FogDevice fd : fogDevices) {
                MicroserviceFogDevice mfd = (MicroserviceFogDevice) fd;
                if (mfd.getDeviceType().equals(MicroserviceFogDevice.CLOUD)) {
                    mfd.setFonID(cloud.getId());
                } else {
                    mfd.setFonID(cloud.getId());
                }
            }

            // STATIC mode allows Genetic Algorithm to define initial placement
            MicroservicePlacementConfig.SIMULATION_MODE         = "STATIC";
            MicroservicePlacementConfig.PR_PROCESSING_MODE      = MicroservicePlacementConfig.SEQUENTIAL;
            MicroservicePlacementConfig.ENABLE_RESOURCE_DATA_SHARING = false;

            List<Integer> clusterLevels = new ArrayList<>();
            List<Application> appList = new ArrayList<>();
            appList.add(app);

            MicroservicesController controller = new MicroservicesController(
                    "industrial-controller", fogDevices, sensors, appList,
                    clusterLevels, CLUSTER_LATENCY, PLACEMENT_ALGO, monitored);

            List<PlacementRequest> placementRequests = new ArrayList<>();

            // Generate placement requests per IoT client (Not per sensor, to avoid duplicates)
            for (int i = 0; i < NUM_FOG_GATEWAYS; i++) {
                for (int j = 0; j < CLIENTS_PER_GATEWAY; j++) {
                    String deviceName = "IoT-" + i + "-" + j;
                    FogDevice iotDev = getFogDeviceByName(deviceName);

                    Map<String, Integer> prePlaced = new HashMap<>();
                    prePlaced.put("data_preprocessor", iotDev.getId()); // Pinned to edge

                    // Use a unique ID for the placement request based on the device ID
                    PlacementRequest pr = new PlacementRequest(
                            APP_ID, iotDev.getId(), iotDev.getId(), prePlaced);
                    placementRequests.add(pr);
                }
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

    private static void createFogDevices(int userId) {
        MicroserviceFogDevice cloud = createFogDevice("cloud", CLOUD_MIPS, 128000, 1000, 10000, 0, 0.01, 16 * 103, 16 * 83.25, MicroserviceFogDevice.CLOUD);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        for (int i = 0; i < NUM_FOG_GATEWAYS; i++) {
            MicroserviceFogDevice gw = createFogDevice("FogGW-" + i, FOG_GW_MIPS, 16384, 10000, 10000, 1, 0.0, 107.339, 83.4333, MicroserviceFogDevice.FCN);
            gw.setParentId(cloud.getId());
            gw.setUplinkLatency(GW_TO_CLOUD_LATENCY);
            fogDevices.add(gw);

            for (int j = 0; j < CLIENTS_PER_GATEWAY; j++) {
                addIoTClient(userId, i + "-" + j, gw.getId());
            }
        }
    }

    private static void addIoTClient(int userId, String id, int parentId) {
        MicroserviceFogDevice iotDevice = createFogDevice("IoT-" + id, 1000, 2048, 18750, 250, 2, 0.0, 87.53, 82.44, MicroserviceFogDevice.CLIENT);
        iotDevice.setParentId(parentId);
        iotDevice.setUplinkLatency(IOT_TO_GW_LATENCY);
        fogDevices.add(iotDevice);

        // 1. Periodic Sensor (Low Priority)
        Sensor periodicSensor = new Sensor("sensor-periodic-" + id, "IoT_SENSOR", userId, APP_ID, new DeterministicDistribution(SENSOR_PERIODIC_S));
        periodicSensor.setGatewayDeviceId(iotDevice.getId());
        periodicSensor.setLatency(1.0);
        periodicSensor.setApp(application);
        sensors.add(periodicSensor);

        // 2. Critical Sensor (High Priority / Emergency)
        Sensor criticalSensor = new Sensor("sensor-critical-" + id, "IoT_SENSOR", userId, APP_ID, new DeterministicDistribution(SENSOR_CRITICAL_S));
        criticalSensor.setGatewayDeviceId(iotDevice.getId());
        criticalSensor.setLatency(1.0);
        criticalSensor.setApp(application);
        sensors.add(criticalSensor);

        Actuator actuator = new Actuator("actuator-" + id, userId, APP_ID, "ACTUATOR");
        actuator.setGatewayDeviceId(iotDevice.getId());
        actuator.setLatency(1.0);
        actuator.setApp(application);
        actuators.add(actuator);
    }

    private static MicroserviceFogDevice createFogDevice(
            String nodeName, long mips, int ram, long upBw, long downBw,
            int level, double ratePerMips, double busyPower, double idlePower,
            String deviceType) {

        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int  hostId  = FogUtils.generateEntityId();
        long storage = 1_000_000L;
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
                    10,
                    upBw, downBw,
                    1_250_000,
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

    @SuppressWarnings("serial")
    private static Application createApplication(String appId, int userId) {
        Application app = Application.createApplication(appId, userId);

        app.addAppModule("data_preprocessor",  128,  500, 200);
        app.addAppModule("smart_analyzer",      512, 2000, 500);
        app.addAppModule("actuator_controller", 256,  800, 300);

        app.addAppEdge("IoT_SENSOR",        "data_preprocessor",  2000, 500, "IoT_SENSOR",       Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("data_preprocessor", "smart_analyzer",      3500, 500, "FILTERED_DATA",    Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("smart_analyzer",    "actuator_controller", 100, 1000, "ANALYSIS_RESULT",  Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("actuator_controller", "data_preprocessor", 14, 500, "CONTROL_CMD",      Tuple.DOWN, AppEdge.MODULE);
        app.addAppEdge("data_preprocessor",   "ACTUATOR",          1000, 500, "ACTUATOR_CMD",     Tuple.DOWN, AppEdge.ACTUATOR);

        // Using SeededSelectivity to maintain deterministic runs
        app.addTupleMapping("data_preprocessor",  "IoT_SENSOR",      "FILTERED_DATA", new SeededSelectivity(0.8, SIM_SEED));
        app.addTupleMapping("smart_analyzer",      "FILTERED_DATA",   "ANALYSIS_RESULT", new FractionalSelectivity(1.0));
        app.addTupleMapping("actuator_controller", "ANALYSIS_RESULT", "CONTROL_CMD", new FractionalSelectivity(1.0));
        app.addTupleMapping("data_preprocessor",  "CONTROL_CMD",     "ACTUATOR_CMD", new FractionalSelectivity(1.0));

        final AppLoop e2eLoop = new AppLoop(new ArrayList<String>() {{
            add("IoT_SENSOR");
            add("data_preprocessor");
            add("smart_analyzer");
            add("actuator_controller");
            add("data_preprocessor");
            add("ACTUATOR");
        }});
        app.setLoops(new ArrayList<AppLoop>() {{ add(e2eLoop); }});

        app.createDAG();
        return app;
    }

    private static FogDevice getFogDeviceByName(String name) {
        for (FogDevice fd : fogDevices) {
            if (fd.getName().equals(name)) return fd;
        }
        return null;
    }

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

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"type\":\"results\",");
            sb.append("\"simulationTime\":").append(org.cloudbus.cloudsim.core.CloudSim.clock()).append(",");

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

            FogDevice cloud = getFogDeviceByName("cloud");
            double cloudCost = (cloud != null) ? cloud.getTotalCost() : 0.0;
            sb.append("\"cloudCost\":").append(cloudCost).append(",");

            sb.append("\"numRequests\":").append(placementLog.stream()
                    .map(e -> e.get("requestId")).distinct().count()).append(",");

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

            out.println(sb.toString());
            String ack = in.readLine();
            System.out.println("[Bridge] Results sent. Server ack: " + ack);

        } catch (Exception e) {
            System.err.println("[Bridge] Could not send results to Python: " + e.getMessage());
        }
        System.out.println("[Bridge] Connection closed.");
    }
}