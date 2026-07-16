package org.fog.test.perfeval;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
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
import org.fog.placement.SharedPolicyPPOBridgePlacementLogic;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.MicroservicePlacementConfig;
import org.fog.utils.TimeKeeper;
import org.fog.utils.TrainingLog;
import org.fog.utils.distribution.SeededExponentialDistribution;
import org.fog.utils.distribution.SeededUniformDistribution;

import java.util.*;

/**
 * TRAINING ENVIRONMENT – identical to IndustrialIoTSimulation4
 * but seeds are derived from episode number.
 */
public class IndustrialIoTSimulationTrain {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor>    sensors    = new ArrayList<>();
    static List<Actuator>  actuators  = new ArrayList<>();

    static final String APP_ID = "industrial_iot";
    static Application application;
    static int e2eLoopId = -1;
    static int criticalE2eLoopId = -1;

    // ── Topology Limits ──
    static int NUM_FOG_GATEWAYS    = 10;
    static int CLIENTS_PER_GATEWAY = 15;
    static final int CLOUD_MIPS  = 500_000;
    static int FOG_GW_MIPS = 15_000;
    static final int GW_TO_CLOUD_LATENCY = 100;
    static final int IOT_TO_GW_LATENCY   = 20;
    // At the required 15+ fog / 150+ gadget scale, each fog gateway serves
    // ten clients. This seeded workload retains a 1:9 critical/normal mix
    // while keeping the serial service queues below saturation.
    static final double PERIODIC_MEAN_S = 25.0;
    static final double CRITICAL_MEAN_S = 225.0;
	// Industrial-control latency budget: seeded per critical event, not a
	// launcher setting. This creates mixed tight/relaxed critical workloads.
	static final double CRITICAL_DEADLINE_MIN_MS = 300.0;
	static final double CRITICAL_DEADLINE_MAX_MS = 500.0;

    // Dynamic Seeds (set in main)
    static long SEED_SELECTIVITY;
    static long SEED_TRAFFIC;
    static long SEED_DEVICE_TYPE;
    static long SEED_MOBILITY;
    static double MOBILE_SHARE = 0.66;
    static boolean SHARED_POLICY = false;
    static long EPISODE_SEED = 1L;

    public static void main(String[] args) {
        long episodeSeed = 1L;
        if (args.length > 0) {
            try { episodeSeed = Long.parseLong(args[0]); } catch (Exception e) {}
        }

        EPISODE_SEED = episodeSeed;
        SHARED_POLICY = Boolean.getBoolean("ifogsim.shared.policy");
        if (SHARED_POLICY) TrainingLog.configure();
        System.setProperty("ifogsim.episode.seed", Long.toString(episodeSeed));
        System.out.println("Starting Training Episode: " + episodeSeed
                + (SHARED_POLICY ? " (shared service policy)" : ""));

        if (SHARED_POLICY) {
            SplittableRandom seedStream = new SplittableRandom(episodeSeed);
            SEED_SELECTIVITY = seedStream.nextLong();
            SEED_TRAFFIC     = seedStream.nextLong();
            SEED_DEVICE_TYPE = seedStream.nextLong();
            SEED_MOBILITY    = seedStream.nextLong();
            // Domain randomisation: topology size, mobile share, capacity and
            // traffic/selectivity all change reproducibly with episodeSeed.
            // Assignment-scale topology: 15–16 fog gateways and 150–160
            // gadgets. The seed still randomizes the gateway count, mobility,
            // capacity and workload characteristics from episode to episode.
            NUM_FOG_GATEWAYS = 15 + seedStream.nextInt(2);      // 15..16
            CLIENTS_PER_GATEWAY = 10;                            // 150..160 total
            MOBILE_SHARE = 0.40 + seedStream.nextDouble() * 0.40;
			// A gateway serves CLIENTS_PER_GATEWAY requests. The placement layer
			// reserves 500 + 2000 + 800 MIPS when all services are local, so the
			// old fixed 18–24k gateway was structurally unable to host its own
			// workload (10–18 requests) and forced chronic cloud queues. Size the
			// gateway from the seeded topology and retain 20% migration headroom.
			FOG_GW_MIPS = Math.max(30_000, (int) Math.ceil(
					CLIENTS_PER_GATEWAY * 3_300.0 * 1.20));
            MicroservicePlacementConfig.PLACEMENT_INTERVAL =
                    Double.parseDouble(System.getProperty("ifogsim.placement.interval", "10"));
            org.fog.utils.Config.MAX_SIMULATION_TIME =
                    Integer.getInteger("ifogsim.simulation.time", 600);
            SharedPolicyPPOBridgePlacementLogic.clearSharedLogs();
            System.out.println("Training topology: gateways=" + NUM_FOG_GATEWAYS
                    + ", clients=" + (NUM_FOG_GATEWAYS * CLIENTS_PER_GATEWAY)
                    + ", mobileShare=" + String.format(Locale.ROOT, "%.2f", MOBILE_SHARE)
                    + ", fogMips=" + FOG_GW_MIPS);
        } else {
            // Preserve the original training entry's seed mapping.
            SEED_SELECTIVITY = episodeSeed * 101L;
            SEED_TRAFFIC     = episodeSeed * 202L;
            SEED_DEVICE_TYPE = episodeSeed * 404L;
            SEED_MOBILITY    = episodeSeed * 303L;
            PPOBridgePlacementLogic.clearLogs();
        }

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
            MicroservicePlacementConfig.PR_PROCESSING_MODE = MicroservicePlacementConfig.PERIODIC;
            MicroservicePlacementConfig.ENABLE_RESOURCE_DATA_SHARING = false;

            MicroservicesController controller = new MicroservicesController(
                    "industrial-controller", fogDevices, sensors, Arrays.asList(app),
                    new ArrayList<>(), 2.0,
                    SHARED_POLICY ? PlacementLogicFactory.SHARED_PPO_BRIDGE_PLACEMENT
                            : PlacementLogicFactory.PPO_BRIDGE_PLACEMENT,
                    monitored);

            List<PlacementRequest> placementRequests = new ArrayList<>();
            for (FogDevice dev : fogDevices) {
                if (((MicroserviceFogDevice) dev).getDeviceType().equals(MicroserviceFogDevice.CLIENT)) {
                    // Preserve the existing GA initialization schema: the local
                    // preprocessor starts on its client. It is still included in
                    // the shared PPO actor set and may migrate on later steps.
                    Map<String, Integer> prePlaced = new HashMap<>();
                    prePlaced.put("data_preprocessor", dev.getId());
                    placementRequests.add(new PlacementRequest(APP_ID, dev.getId(), dev.getId(), prePlaced));
                }
            }

            controller.submitPlacementRequests(placementRequests, 0);
            TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

            // Kick off PPO heartbeat
            CloudSim.send(cloud.getId(), cloud.getId(), 0, org.fog.utils.FogEvents.PROCESS_PRS, null);

            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    sendResultsToPython(fogDevices, PPOBridgePlacementLogic.PLACEMENT_LOG,
                            System.getProperty("ifogsim.bridge.host", PPOBridgePlacementLogic.DEFAULT_HOST),
                            Integer.getInteger("ifogsim.bridge.port", PPOBridgePlacementLogic.DEFAULT_PORT))
            ));

            CloudSim.startSimulation();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void createFogDevices(int userId) {
        MicroserviceFogDevice cloud = createFogDevice("cloud", CLOUD_MIPS, 128000, 1000, 10000, 0, 0.01, 16 * 103, 16 * 83.25, MicroserviceFogDevice.CLOUD);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        Random typeRandom = new Random(SEED_DEVICE_TYPE);
        for (int i = 0; i < NUM_FOG_GATEWAYS; i++) {
            MicroserviceFogDevice gw = createFogDevice("FogGW-" + i, FOG_GW_MIPS, 16384, 10000, 10000, 1, 0.0, 107.339, 83.4333, MicroserviceFogDevice.FCN);
            gw.setParentId(cloud.getId());
            gw.setUplinkLatency(GW_TO_CLOUD_LATENCY);
            fogDevices.add(gw);

            for (int j = 0; j < CLIENTS_PER_GATEWAY; j++) {
                boolean isMobile = typeRandom.nextDouble() < MOBILE_SHARE;
                String deviceName = (isMobile ? "MobileRobot-" : "FixedSensor-") + i + "-" + j;
                addIoTClient(userId, deviceName, gw.getId());
            }
        }
    }

    private static void addIoTClient(int userId, String deviceName, int parentId) {
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

        Sensor criticalSensor = new Sensor("sensor-critical-" + deviceName, "CRITICAL_IOT_SENSOR", userId, APP_ID,
                new SeededExponentialDistribution(CRITICAL_MEAN_S, deviceTrafficSeed + 1000));
        criticalSensor.setGatewayDeviceId(iotDevice.getId());
        criticalSensor.setLatency(1.0);
        criticalSensor.setApp(application);
		criticalSensor.setTaskDeadlineDistribution(new SeededUniformDistribution(
				CRITICAL_DEADLINE_MIN_MS, CRITICAL_DEADLINE_MAX_MS,
				SEED_TRAFFIC + 10_000L + iotDevice.getId()));
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
        try { device = new MicroserviceFogDevice(nodeName, chars, new AppModuleAllocationPolicy(hostList), new LinkedList<>(), 10, upBw, downBw, 1_250_000, 0, ratePerMips, deviceType); } catch (Exception e) {}
        device.setLevel(level);
        return device;
    }

    @SuppressWarnings("serial")
    private static Application createApplication(String appId, int userId) {
        Application app = Application.createApplication(appId, userId);
        app.addAppModule("data_preprocessor",  128,  500, 200);
        app.addAppModule("smart_analyzer",      512, 2000, 500);
        app.addAppModule("actuator_controller", 256,  800, 300);

        app.addAppEdge("IoT_SENSOR", "data_preprocessor", 2000, 500, "IoT_SENSOR", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("CRITICAL_IOT_SENSOR", "data_preprocessor", 2000, 500,
                "CRITICAL_IOT_SENSOR", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("data_preprocessor", "smart_analyzer", 3500, 500, "FILTERED_DATA", Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("smart_analyzer", "actuator_controller", 100, 1000, "ANALYSIS_RESULT", Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("actuator_controller", "data_preprocessor", 14, 500, "CONTROL_CMD", Tuple.DOWN, AppEdge.MODULE);
        app.addAppEdge("data_preprocessor", "ACTUATOR", 1000, 500, "ACTUATOR_CMD", Tuple.DOWN, AppEdge.ACTUATOR);

        app.addTupleMapping("data_preprocessor", "IoT_SENSOR", "FILTERED_DATA", new SeededSelectivity(0.8, SEED_SELECTIVITY));
        app.addTupleMapping("data_preprocessor", "CRITICAL_IOT_SENSOR", "FILTERED_DATA",
                new SeededSelectivity(0.8, SEED_SELECTIVITY + 1));
        app.addTupleMapping("smart_analyzer", "FILTERED_DATA", "ANALYSIS_RESULT", new FractionalSelectivity(1.0));
        app.addTupleMapping("actuator_controller", "ANALYSIS_RESULT", "CONTROL_CMD", new FractionalSelectivity(1.0));
        app.addTupleMapping("data_preprocessor", "CONTROL_CMD", "ACTUATOR_CMD", new FractionalSelectivity(1.0));

        final AppLoop e2eLoop = new AppLoop(new ArrayList<String>() {{ add("IoT_SENSOR"); add("data_preprocessor"); add("smart_analyzer"); add("actuator_controller"); add("data_preprocessor"); add("ACTUATOR"); }});
        final AppLoop criticalE2eLoop = new AppLoop(new ArrayList<String>() {{ add("CRITICAL_IOT_SENSOR"); add("data_preprocessor"); add("smart_analyzer"); add("actuator_controller"); add("data_preprocessor"); add("ACTUATOR"); }});
        app.setLoops(new ArrayList<AppLoop>() {{ add(e2eLoop); add(criticalE2eLoop); }});
        e2eLoopId = e2eLoop.getLoopId();
        criticalE2eLoopId = criticalE2eLoop.getLoopId();
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

        // ---- Full results (same as IndustrialIoTSimulation4) ----
        try (java.net.Socket socket = new java.net.Socket(host, port)) {
			TimeKeeper.getInstance().finalizeCriticalTasks();
            // A shared PPO update/checkpoint runs synchronously on Python after
            // this message. It can legitimately take longer than the legacy
            // bridge's 15-second result acknowledgement timeout.
            socket.setSoTimeout(SHARED_POLICY
                    ? Integer.getInteger("ifogsim.results.timeout.ms", 180_000)
                    : 15_000);
            java.io.PrintWriter out = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), "UTF-8"));

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"type\":\"results\",");
            sb.append("\"episodeSeed\":").append(EPISODE_SEED).append(",");
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

            Double normalLoopDelay = TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(e2eLoopId);
            Double criticalLoopDelay = TimeKeeper.getInstance().getLoopIdToCurrentAverage().get(criticalE2eLoopId);
            int normalSamples = TimeKeeper.getInstance().getLoopIdToCurrentNum().getOrDefault(e2eLoopId, 0);
            int criticalSamples = TimeKeeper.getInstance().getLoopIdToCurrentNum().getOrDefault(criticalE2eLoopId, 0);
            int completedSamples = normalSamples + criticalSamples;
            Double averageLatency = completedSamples == 0 ? null
                    : ((normalLoopDelay == null ? 0.0 : normalLoopDelay * normalSamples)
                    + (criticalLoopDelay == null ? 0.0 : criticalLoopDelay * criticalSamples)) / completedSamples;
            // loopDelay is retained for existing consumers and now means the
            // completed-task weighted average latency across both task classes.
            sb.append("\"loopDelay\":").append(averageLatency != null ? averageLatency : "null").append(",");
            sb.append("\"averageLatency\":").append(averageLatency != null ? averageLatency : "null").append(",");
            sb.append("\"normalLoopDelay\":").append(normalLoopDelay != null ? normalLoopDelay : "null").append(",");
            sb.append("\"criticalLoopDelay\":").append(criticalLoopDelay != null ? criticalLoopDelay : "null").append(",");
            sb.append("\"loopSampleCount\":").append(completedSamples).append(",");

            int criticalTasks = TimeKeeper.getInstance().getCriticalTasksEmitted();
            int criticalTasksOnTime = TimeKeeper.getInstance().getCriticalTasksOnTime();
            int criticalTasksMissed = TimeKeeper.getInstance().getCriticalTasksMissed();
			int criticalTasksPending = TimeKeeper.getInstance().getCriticalTasksPending();
			int criticalTasksEvaluated = criticalTasksOnTime + criticalTasksMissed;
            double criticalSuccessRate = criticalTasksEvaluated == 0 ? 0.0
                    : (double) criticalTasksOnTime / criticalTasksEvaluated;
			sb.append("\"criticalDeadlineMeanMs\":")
					.append(TimeKeeper.getInstance().getCriticalDeadlineMeanMs()).append(",");
            sb.append("\"criticalTasks\":").append(criticalTasks).append(",");
            sb.append("\"criticalTasksOnTime\":").append(criticalTasksOnTime).append(",");
            sb.append("\"criticalTasksMissed\":").append(criticalTasksMissed).append(",");
			sb.append("\"criticalTasksPending\":").append(criticalTasksPending).append(",");
			sb.append("\"criticalTasksEvaluated\":").append(criticalTasksEvaluated).append(",");
            sb.append("\"criticalDeadlineSuccessRate\":").append(criticalSuccessRate).append(",");

            double meanReward = SharedPolicyPPOBridgePlacementLogic.ACTOR_REWARD_COUNT == 0
                    ? 0.0 : SharedPolicyPPOBridgePlacementLogic.ACTOR_REWARD_SUM
                    / SharedPolicyPPOBridgePlacementLogic.ACTOR_REWARD_COUNT;
            sb.append("\"meanLocalReward\":").append(meanReward).append(",");

            sb.append("\"tupleCpuDelays\":{");
            boolean firstTupleType = true;
            for (Map.Entry<String, Double> entry : TimeKeeper.getInstance().getTupleTypeToAverageCpuTime().entrySet()) {
                if (!firstTupleType) sb.append(",");
                firstTupleType = false;
                sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            }
            sb.append("},");

            // Mobility traces (optional, but keep structure)
            sb.append("\"mobility\":{},");

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
            sb.append("],");

            sb.append("\"migrationCount\":").append(PPOBridgePlacementLogic.MIGRATION_LOG.size()).append(",");
            sb.append("\"acceptedMigrations\":")
                    .append(SHARED_POLICY ? SharedPolicyPPOBridgePlacementLogic.ACCEPTED_MIGRATIONS
                            : PPOBridgePlacementLogic.MIGRATION_LOG.size()).append(",");
            sb.append("\"rejectedMigrations\":")
                    .append(SHARED_POLICY ? SharedPolicyPPOBridgePlacementLogic.REJECTED_MIGRATIONS : 0)
                    .append(",");
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

            out.println(sb.toString());

            if (SHARED_POLICY) {
                TrainingLog.episodeSummary(EPISODE_SEED,
                        org.cloudbus.cloudsim.core.CloudSim.clock(), totalEnergy, cloudCost,
                        averageLatency, meanReward, placementLog.size(),
                        SharedPolicyPPOBridgePlacementLogic.ACCEPTED_MIGRATIONS,
                        SharedPolicyPPOBridgePlacementLogic.REJECTED_MIGRATIONS,
						criticalTasks, criticalTasksEvaluated, criticalTasksPending,
						criticalTasksMissed, criticalSuccessRate);
            }

            // Wait for Python only after the simulator-side report is visible.
            // This preserves a useful trajectory report even if Python later
            // fails while updating or saving a checkpoint.
            in.readLine();

        } catch (Exception e) {
            System.err.println("[Bridge] Could not send results to Python: " + e.getMessage());
        }
    }
}
