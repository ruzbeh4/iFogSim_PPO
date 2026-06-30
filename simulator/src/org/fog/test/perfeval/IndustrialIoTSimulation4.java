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
import org.fog.placement.PPOBridgePlacementLogic;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.MicroservicePlacementConfig;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.SeededExponentialDistribution;

import java.util.*;

/**
 * Project 8: Industry 4.0 Simulation — PPO / DRL scenario.
 * Topology: 10 Gateways, 150 IoT Devices (Mixed Static/Mobile), Dual Sensors.
 *
 * Same topology/application/mobility setup as IndustrialIoTSimulation3, but
 * wired to PPOBridgePlacementLogic under PERIODIC mode instead of the static
 * one-shot PythonBridgePlacementLogic: the placement logic is invoked
 * repeatedly throughout the run (state → action → reward → repeat) so an
 * external PPO/DRL agent can keep deciding placements and migrations as
 * traffic load and device mobility change over time.
 */
public class IndustrialIoTSimulation4 {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor>    sensors    = new ArrayList<>();
    static List<Actuator>  actuators  = new ArrayList<>();

    static final String APP_ID = "industrial_iot";
    static Application application;

    /** Loop ID of the sensor-to-actuator AppLoop, used to read TimeKeeper's measured latency. */
    static int e2eLoopId = -1;

    // =====================================================================
    // 🎛️ SEED DASHBOARD
    // =====================================================================
    static final long SEED_SELECTIVITY  = 101L; // Controls edge tuple filtration
    static final long SEED_TRAFFIC      = 202L; // Controls bursty sensor emission
    static final long SEED_DEVICE_TYPE  = 404L; // Controls fixed vs mobile assignment
    static final long SEED_MOBILITY     = 303L; // Seeds the random-walk mobility traces
    // =====================================================================

    // ── Mobility (lightweight seeded random-walk position tracking) ─────────
    // Positions don't affect placement or gateway assignment yet (STATIC mode);
    // this just precomputes a reproducible trajectory per MobileRobot so it's
    // ready to feed into a PPO state representation.
    static final int    MOBILITY_STEPS     = 20;   // random-walk steps per device
    static final double MOBILITY_STEP_M    = 5.0;  // meters per step
    /** deviceName → list of [x, y] positions (meters, relative to start) over the walk. */
    static final Map<String, List<double[]>> mobilityTraces = new LinkedHashMap<>();

    static final int PLACEMENT_ALGO = PlacementLogicFactory.PPO_BRIDGE_PLACEMENT;

    // ── Topology Limits ──
    static final int NUM_FOG_GATEWAYS    = 10;
    static final int CLIENTS_PER_GATEWAY = 15;  // 150 total IoT devices

    // ── Compute ──
    static final int CLOUD_MIPS  = 500_000;
    static final int FOG_GW_MIPS = 15_000;

    static final int GW_TO_CLOUD_LATENCY = 100;
    static final int IOT_TO_GW_LATENCY   = 20;

    // ── Traffic Means (Seconds) ──
    static final double PERIODIC_MEAN_S = 5.0;
    static final double CRITICAL_MEAN_S = 45.0;

    static final double CLUSTER_LATENCY = 2.0;

    public static void main(String[] args) {
        Log.printLine("Starting Project 8 Industry 4.0 Simulation (PPO scenario)...");

        try {
            Log.disable();
            CloudSim.init(1, Calendar.getInstance(), false);

            FogBroker broker = new FogBroker("industrial-broker");

            Application app = createApplication(APP_ID, broker.getId());
            application = app;
            app.setUserId(broker.getId());

            createFogDevices(broker.getId());

            FogDevice cloud = getFogDeviceByName("cloud");
            Map<Integer, List<FogDevice>> monitored = new HashMap<>();
            monitored.put(cloud.getId(), new ArrayList<>(fogDevices));

            for (FogDevice fd : fogDevices) {
                ((MicroserviceFogDevice) fd).setFonID(cloud.getId());
            }

            MicroservicePlacementConfig.SIMULATION_MODE = "STATIC";
            // PERIODIC re-invokes the placement logic every PLACEMENT_INTERVAL simulated time
            // units for the whole run (not just once at t=0) — this is what gives the PPO
            // bridge its step() cadence so it can keep deciding/migrating over time.
            MicroservicePlacementConfig.PR_PROCESSING_MODE = MicroservicePlacementConfig.PERIODIC;
            MicroservicePlacementConfig.ENABLE_RESOURCE_DATA_SHARING = false;

            MicroservicesController controller = new MicroservicesController(
                    "industrial-controller", fogDevices, sensors, Arrays.asList(app),
                    new ArrayList<>(), CLUSTER_LATENCY, PLACEMENT_ALGO, monitored);

            // Generate one placement request per physical IoT Client
            List<PlacementRequest> placementRequests = new ArrayList<>();
            for (FogDevice dev : fogDevices) {
                if (((MicroserviceFogDevice) dev).getDeviceType().equals(MicroserviceFogDevice.CLIENT)) {
                    Map<String, Integer> prePlaced = new HashMap<>();
                    prePlaced.put("data_preprocessor", dev.getId());

                    PlacementRequest pr = new PlacementRequest(
                            APP_ID, dev.getId(), dev.getId(), prePlaced);
                    placementRequests.add(pr);
                }
            }

            controller.submitPlacementRequests(placementRequests, 0);
            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

            // MicroservicesController.initiatePlacementRequestProcessing() only auto-fires the
            // first PROCESS_PRS tick on devices typed MicroserviceFogDevice.FON. Our orchestrator
            // is typed CLOUD (same as every other scenario here), so under PERIODIC mode the
            // periodic heartbeat would otherwise never start. Kick off the first tick ourselves;
            // processPlacementRequests() self-reschedules every PLACEMENT_INTERVAL after that.
            CloudSim.send(cloud.getId(), cloud.getId(), 0, org.fog.utils.FogEvents.PROCESS_PRS, null);

            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    sendResultsToPython(fogDevices, PPOBridgePlacementLogic.PLACEMENT_LOG,
                            PPOBridgePlacementLogic.DEFAULT_HOST,
                            PPOBridgePlacementLogic.DEFAULT_PORT)
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

        // Seeded RNG to decide if a device is a mobile robot or fixed sensor
        Random typeRandom = new Random(SEED_DEVICE_TYPE);

        for (int i = 0; i < NUM_FOG_GATEWAYS; i++) {
            MicroserviceFogDevice gw = createFogDevice("FogGW-" + i, FOG_GW_MIPS, 16384, 10000, 10000, 1, 0.0, 107.339, 83.4333, MicroserviceFogDevice.FCN);
            gw.setParentId(cloud.getId());
            gw.setUplinkLatency(GW_TO_CLOUD_LATENCY);
            fogDevices.add(gw);

            for (int j = 0; j < CLIENTS_PER_GATEWAY; j++) {
                // 66% chance to be mobile, 33% chance to be fixed. Completely reproducible.
                boolean isMobile = typeRandom.nextDouble() < 0.66;
                String deviceName = (isMobile ? "MobileRobot-" : "FixedSensor-") + i + "-" + j;
                addIoTClient(userId, deviceName, gw.getId(), isMobile);
            }
        }
    }

    private static void addIoTClient(int userId, String deviceName, int parentId, boolean isMobile) {
        MicroserviceFogDevice iotDevice = createFogDevice(deviceName, 1000, 2048, 18750, 250, 2, 0.0, 87.53, 82.44, MicroserviceFogDevice.CLIENT);
        iotDevice.setParentId(parentId);
        iotDevice.setUplinkLatency(IOT_TO_GW_LATENCY);
        fogDevices.add(iotDevice);

        if (isMobile) {
            mobilityTraces.put(deviceName, generateRandomWalk(iotDevice.getId()));
        }

        long deviceTrafficSeed = SEED_TRAFFIC + iotDevice.getId();

        Sensor periodicSensor = new Sensor("sensor-periodic-" + deviceName, "IoT_SENSOR", userId, APP_ID,
                new SeededExponentialDistribution(PERIODIC_MEAN_S, deviceTrafficSeed));
        periodicSensor.setGatewayDeviceId(iotDevice.getId());
        periodicSensor.setLatency(1.0);
        periodicSensor.setApp(application);
        sensors.add(periodicSensor);

        Sensor criticalSensor = new Sensor("sensor-critical-" + deviceName, "IoT_SENSOR", userId, APP_ID,
                new SeededExponentialDistribution(CRITICAL_MEAN_S, deviceTrafficSeed + 1000));
        criticalSensor.setGatewayDeviceId(iotDevice.getId());
        criticalSensor.setLatency(1.0);
        criticalSensor.setApp(application);
        sensors.add(criticalSensor);

        Actuator actuator = new Actuator("actuator-" + deviceName, userId, APP_ID, "ACTUATOR");
        actuator.setGatewayDeviceId(iotDevice.getId());
        actuator.setLatency(1.0);
        actuator.setApp(application);
        actuators.add(actuator);
    }

    /**
     * Generates a reproducible 2D random-walk trajectory for a mobile device.
     * Each step moves MOBILITY_STEP_M meters in a uniformly random direction.
     * Seeded by SEED_MOBILITY + deviceId so every device gets a distinct but
     * deterministic path across runs.
     */
    private static List<double[]> generateRandomWalk(int deviceId) {
        Random rnd = new Random(SEED_MOBILITY + deviceId);
        List<double[]> trace = new ArrayList<>(MOBILITY_STEPS);
        double x = 0.0, y = 0.0;
        trace.add(new double[]{x, y});
        for (int i = 1; i < MOBILITY_STEPS; i++) {
            double angle = rnd.nextDouble() * 2 * Math.PI;
            x += Math.cos(angle) * MOBILITY_STEP_M;
            y += Math.sin(angle) * MOBILITY_STEP_M;
            trace.add(new double[]{x, y});
        }
        return trace;
    }

    private static MicroserviceFogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower, String deviceType) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        PowerHost host = new PowerHost(FogUtils.generateEntityId(), new RamProvisionerSimple(ram), new BwProvisionerOverbooking(10_000), 1_000_000L, peList, new StreamOperatorScheduler(peList), new FogLinearPowerModel(busyPower, idlePower));
        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics chars = new FogDeviceCharacteristics("x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);
        MicroserviceFogDevice device = null;
        try { device = new MicroserviceFogDevice(nodeName, chars, new AppModuleAllocationPolicy(hostList), new LinkedList<>(), 10, upBw, downBw, 1_250_000, 0, ratePerMips, deviceType); }
        catch (Exception e) { e.printStackTrace(); }

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

        app.addTupleMapping("data_preprocessor",  "IoT_SENSOR",      "FILTERED_DATA", new SeededSelectivity(0.8, SEED_SELECTIVITY));
        app.addTupleMapping("smart_analyzer",      "FILTERED_DATA",   "ANALYSIS_RESULT", new FractionalSelectivity(1.0));
        app.addTupleMapping("actuator_controller", "ANALYSIS_RESULT", "CONTROL_CMD", new FractionalSelectivity(1.0));
        app.addTupleMapping("data_preprocessor",  "CONTROL_CMD",     "ACTUATOR_CMD", new FractionalSelectivity(1.0));

        final AppLoop e2eLoop = new AppLoop(new ArrayList<String>() {{ add("IoT_SENSOR"); add("data_preprocessor"); add("smart_analyzer"); add("actuator_controller"); add("data_preprocessor"); add("ACTUATOR"); }});
        app.setLoops(new ArrayList<AppLoop>() {{ add(e2eLoop); }});
        e2eLoopId = e2eLoop.getLoopId();
        app.createDAG();
        return app;
    }

    private static FogDevice getFogDeviceByName(String name) {
        for (FogDevice fd : fogDevices) { if (fd.getName().equals(name)) return fd; }
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

            // Seeded random-walk position traces for mobile devices (meters, relative to start)
            sb.append("\"mobility\":{");
            boolean firstTrace = true;
            for (Map.Entry<String, List<double[]>> entry : mobilityTraces.entrySet()) {
                if (!firstTrace) sb.append(",");
                firstTrace = false;
                sb.append("\"").append(entry.getKey()).append("\":[");
                boolean firstPoint = true;
                for (double[] point : entry.getValue()) {
                    if (!firstPoint) sb.append(",");
                    firstPoint = false;
                    sb.append("[").append(point[0]).append(",").append(point[1]).append("]");
                }
                sb.append("]");
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
            sb.append("],");

            // Migration history (PDF "Service Migration Count" metric)
            sb.append("\"migrationCount\":").append(PPOBridgePlacementLogic.MIGRATION_LOG.size()).append(",");
            sb.append("\"migrations\":[");
            boolean firstMig = true;
            for (Map<String, Object> entry : PPOBridgePlacementLogic.MIGRATION_LOG) {
                if (!firstMig) sb.append(",");
                firstMig = false;
                sb.append("{")
                        .append("\"step\":").append(entry.get("step")).append(",")
                        .append("\"requestId\":").append(entry.get("requestId")).append(",")
                        .append("\"module\":\"").append(entry.get("module")).append("\",")
                        .append("\"fromDevice\":\"").append(entry.get("fromDevice")).append("\",")
                        .append("\"toDevice\":\"").append(entry.get("toDevice")).append("\"")
                        .append("}");
            }
            sb.append("],");

            // Per-step reward history (PDF "Convergence Curve" metric)
            sb.append("\"stepRewards\":[");
            boolean firstStep = true;
            for (Map<String, Object> entry : PPOBridgePlacementLogic.STEP_LOG) {
                if (!firstStep) sb.append(",");
                firstStep = false;
                sb.append("{")
                        .append("\"step\":").append(entry.get("step")).append(",")
                        .append("\"simTime\":").append(entry.get("simTime")).append(",")
                        .append("\"reward\":").append(entry.get("reward")).append(",")
                        .append("\"done\":").append(entry.get("done"))
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
