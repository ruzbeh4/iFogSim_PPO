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
import org.fog.utils.distribution.SeededExponentialDistribution;

import java.util.*;

/**
 * Project 8: Industry 4.0 Simulation
 * Topology: 10 Gateways, 150 IoT Devices (Mixed Static/Mobile), Dual Sensors
 */
public class IndustrialIoTSimulation3 {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor>    sensors    = new ArrayList<>();
    static List<Actuator>  actuators  = new ArrayList<>();

    static final String APP_ID = "industrial_iot";
    static Application application;

    // =====================================================================
    // 🎛️ SEED DASHBOARD
    // =====================================================================
    static final long SEED_SELECTIVITY  = 101L; // Controls edge tuple filtration
    static final long SEED_TRAFFIC      = 202L; // Controls bursty sensor emission
    static final long SEED_DEVICE_TYPE  = 404L; // Controls fixed vs mobile assignment
    static final long SEED_MOBILITY     = 303L; // Reserved for Dynamic Mode PPO
    // =====================================================================

    static final int PLACEMENT_ALGO = PlacementLogicFactory.PYTHON_BRIDGE_PLACEMENT;

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
        Log.printLine("Starting Project 8 Industry 4.0 Simulation...");

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
            MicroservicePlacementConfig.PR_PROCESSING_MODE = MicroservicePlacementConfig.SEQUENTIAL;
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