package org.fog.placement;

import org.apache.commons.math3.util.Pair;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.AppEdge;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.FogDevice;
import org.fog.entities.MicroserviceFogDevice;
import org.fog.entities.PlacementRequest;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.utils.FogEvents;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.stream.Collectors;

/**
 * Training-only controller that applies seeded client mobility without
 * changing the shared PPO / GA placement flow.
 */
public class TrainingMobilityController extends MicroservicesController {

    private final Map<Integer, NavigableMap<Double, Integer>> mobilityParentSchedule;
    protected Map<Integer, Map<String, PlacementRequest>> perClientDevicePrs = new HashMap<>();

    public TrainingMobilityController(String name,
                                      List<FogDevice> fogDevices,
                                      List<Sensor> sensors,
                                      List<Application> applications,
                                      List<Integer> clusterLevels,
                                      Double clusterLatency,
                                      int placementLogic,
                                      Map<Integer, List<FogDevice>> monitored,
                                      Map<Integer, NavigableMap<Double, Integer>> mobilityParentSchedule) {
        super(name, fogDevices, sensors, applications, clusterLevels, clusterLatency, placementLogic, monitored);
        this.mobilityParentSchedule = mobilityParentSchedule;
    }

    @Override
    public void startEntity() {
        super.startEntity();
        sendNow(getId(), FogEvents.MOBILITY_SUBMIT);
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case FogEvents.MOBILITY_SUBMIT:
                processMobilityData();
                break;
            case FogEvents.MOBILITY_MANAGEMENT:
                processMobility(ev);
                break;
            default:
                super.processEvent(ev);
                break;
        }
    }

    @Override
    public void submitPlacementRequests(List<PlacementRequest> placementRequests, int delay) {
        for (PlacementRequest p : placementRequests) {
            placementRequestDelayMap.put(p, delay);

            int clientDeviceId = p.getGatewayDeviceId();
            String app = p.getApplicationId();
            if (perClientDevicePrs.containsKey(clientDeviceId)) {
                perClientDevicePrs.get(clientDeviceId).put(app, p);
            } else {
                Map<String, PlacementRequest> map = new HashMap<>();
                map.put(app, p);
                perClientDevicePrs.put(clientDeviceId, map);
            }
        }
    }

    private void processMobilityData() {
        for (Map.Entry<Integer, NavigableMap<Double, Integer>> entry : mobilityParentSchedule.entrySet()) {
            for (double timeEntry : entry.getValue().keySet()) {
                if (timeEntry > 0.0) {
                    send(getId(), timeEntry, FogEvents.MOBILITY_MANAGEMENT, entry.getKey());
                }
            }
        }
    }

    private void processMobility(SimEvent ev) {
        Integer mobileDeviceId = (Integer) ev.getData();
        FogDevice fogDevice = getFogDeviceById(mobileDeviceId);
        if (fogDevice == null) return;

        NavigableMap<Double, Integer> schedule = mobilityParentSchedule.get(mobileDeviceId);
        if (schedule == null || schedule.isEmpty()) return;

        Map.Entry<Double, Integer> step = schedule.floorEntry(CloudSim.clock() + 1e-9);
        if (step == null) return;

        FogDevice prevParent = getFogDeviceById(fogDevice.getParentId());
        FogDevice newParent = getFogDeviceById(step.getValue());
        if (prevParent == null || newParent == null || prevParent.getId() == newParent.getId()) {
            return;
        }

        System.out.println(CloudSim.clock() + " Starting Mobility Management for " + fogDevice.getName());
        setNewOrchestratorNode(fogDevice, newParent);

        List<Integer> newParentPath = getPathsToCloud(newParent.getId());
        List<Integer> prevParentPath = getPathsToCloud(prevParent.getId());
        int commonAncestor = determineAncestor(newParentPath, prevParentPath);

        fogDevice.setParentId(newParent.getId());
        System.out.println("Child " + fogDevice.getName() + "\t----->\tParent " + newParent.getName());
        newParent.getChildToLatencyMap().put(fogDevice.getId(), fogDevice.getUplinkLatency());
        newParent.addChild(fogDevice.getId());
        prevParent.removeChild(fogDevice.getId());

        for (String applicationName : fogDevice.getActiveApplications()) {
            Map<String, Integer> migratingModules = getModulesToMigrate(fogDevice, commonAncestor, applicationName);
            HashMap<String, Double> upDelays = new HashMap<>();
            HashMap<String, Double> downDelays = new HashMap<>();

            for (String moduleName : migratingModules.keySet()) {
                double upDelay = getUpDelay(migratingModules.get(moduleName), commonAncestor,
                        applications.get(applicationName).getModuleByName(moduleName));
                double downDelay = getDownDelay(newParent.getId(), commonAncestor,
                        applications.get(applicationName).getModuleByName(moduleName));
                upDelays.put(moduleName, upDelay);
                downDelays.put(moduleName, downDelay);

                JSONObject jsonSend = new JSONObject();
                jsonSend.put("module", applications.get(applicationName).getModuleByName(moduleName));
                jsonSend.put("delay", upDelay);

                JSONObject jsonReceive = new JSONObject();
                jsonReceive.put("module", new AppModule(applications.get(applicationName).getModuleByName(moduleName)));
                jsonReceive.put("delay", downDelay);
                jsonReceive.put("application", applications.get(applicationName));

                send(migratingModules.get(moduleName), upDelay, FogEvents.MODULE_SEND, jsonSend);
                send(newParent.getId(), downDelay, FogEvents.MODULE_RECEIVE, jsonReceive);
                System.out.println("Migrating " + moduleName + " from " + prevParent.getName() + " to " + newParent.getName());
            }

            serviceDiscoveryUpdate(fogDevice, migratingModules, applicationName, newParent.getId(), upDelays, downDelays);
            for (String moduleName : migratingModules.keySet()) {
                perClientDevicePrs.get(fogDevice.getId()).get(applicationName).getPlacedMicroservices().put(moduleName, newParent.getId());
            }
        }

        updateRoutingTable(fogDevice);
    }

    private void setNewOrchestratorNode(FogDevice fogDevice, FogDevice newParent) {
        int parentId = newParent.getId();
        while (parentId != -1) {
            if (((MicroserviceFogDevice) newParent).getDeviceType().equals(MicroserviceFogDevice.FON)) {
                int currentFon = ((MicroserviceFogDevice) fogDevice).getFonId();
                if (currentFon != parentId) {
                    ((MicroserviceFogDevice) getFogDeviceById(currentFon)).removeMonitoredDevice(fogDevice);
                    ((MicroserviceFogDevice) fogDevice).setFonID(parentId);
                    ((MicroserviceFogDevice) getFogDeviceById(parentId)).addMonitoredDevice(fogDevice);
                    System.out.println("Orchestrator Node for device : " + fogDevice.getId() + " updated to " + parentId);
                }
                break;
            } else {
                parentId = newParent.getParentId();
                if (parentId != -1) {
                    newParent = getFogDeviceById(parentId);
                }
            }
        }
    }

    private void updateRoutingTable(FogDevice fogDevice) {
        for (FogDevice f : fogDevices) {
            if (f.getId() != fogDevice.getId()) {
                ((MicroserviceFogDevice) fogDevice).updateRoutingTable(f.getId(), fogDevice.getParentId());

                int nextId = ((MicroserviceFogDevice) f).getRoutingTable().get(fogDevice.getParentId());
                if (f.getId() != nextId) {
                    ((MicroserviceFogDevice) f).updateRoutingTable(fogDevice.getId(), nextId);
                } else {
                    ((MicroserviceFogDevice) f).updateRoutingTable(fogDevice.getId(), fogDevice.getId());
                }
            }
        }
    }

    private void serviceDiscoveryUpdate(FogDevice fogDevice,
                                        Map<String, Integer> migratingModules,
                                        String applicationName,
                                        int newParent,
                                        HashMap<String, Double> upDelays,
                                        HashMap<String, Double> downDelays) {
        PlacementRequest pr = perClientDevicePrs.get(fogDevice.getId()).get(applicationName);

        for (String m : migratingModules.keySet()) {
            List<String> clientMs = getClientMicroservices(m, applicationName);
            for (String clientM : clientMs) {
                JSONObject serviceDiscoveryRemove = new JSONObject();
                serviceDiscoveryRemove.put("service data", new Pair<>(m, migratingModules.get(m)));
                serviceDiscoveryRemove.put("action", "REMOVE");
                send(pr.getPlacedMicroservices().get(clientM), downDelays.get(m), FogEvents.UPDATE_SERVICE_DISCOVERY, serviceDiscoveryRemove);
            }
        }

        for (String m : pr.getPlacedMicroservices().keySet()) {
            if (pr.getPlacedMicroservices().get(m) == fogDevice.getId()) {
                List<String> services = getServiceMicroservice(m, applicationName);
                for (String service : services) {
                    if (migratingModules.containsKey(service)) {
                        JSONObject serviceDiscoveryAdd = new JSONObject();
                        serviceDiscoveryAdd.put("service data", new Pair<>(service, newParent));
                        serviceDiscoveryAdd.put("action", "ADD");
                        send(fogDevice.getId(), upDelays.get(service), FogEvents.UPDATE_SERVICE_DISCOVERY, serviceDiscoveryAdd);
                    }
                }
            }
        }

        for (String m : migratingModules.keySet()) {
            List<String> services = getServiceMicroservice(m, applicationName);
            for (String service : services) {
                if (migratingModules.containsKey(service)) {
                    JSONObject serviceDiscoveryAdd = new JSONObject();
                    serviceDiscoveryAdd.put("service data", new Pair<>(service, newParent));
                    serviceDiscoveryAdd.put("action", "ADD");
                    send(newParent, upDelays.get(service), FogEvents.UPDATE_SERVICE_DISCOVERY, serviceDiscoveryAdd);
                } else {
                    int d = pr.getPlacedMicroservices().get(service);
                    JSONObject serviceDiscoveryAdd = new JSONObject();
                    serviceDiscoveryAdd.put("service data", new Pair<>(service, d));
                    serviceDiscoveryAdd.put("action", "ADD");
                    sendNow(newParent, FogEvents.UPDATE_SERVICE_DISCOVERY, serviceDiscoveryAdd);
                }
            }
        }
    }

    private List<String> getClientMicroservices(String m, String applicationName) {
        List<String> services = new ArrayList<>();
        Application app = applications.get(applicationName);
        for (AppEdge appEdge : app.getEdges()) {
            if (appEdge.getDestination().equals(m) && appEdge.getDirection() == Tuple.UP) {
                if (app.getModuleNames().contains(appEdge.getSource())) {
                    services.add(appEdge.getSource());
                }
            }
        }
        return services;
    }

    private List<String> getServiceMicroservice(String m, String applicationName) {
        List<String> services = new ArrayList<>();
        Application app = applications.get(applicationName);
        for (AppEdge appEdge : app.getEdges()) {
            if (appEdge.getSource().equals(m) && appEdge.getDirection() == Tuple.UP) {
                if (app.getModuleNames().contains(appEdge.getDestination())) {
                    services.add(appEdge.getDestination());
                }
            }
        }
        return services;
    }

    private Map<String, Integer> getModulesToMigrate(FogDevice mobileDevice, int commonAncestor, String applicationName) {
        Map<String, Integer> migratingModules = new HashMap<>();
        PlacementRequest pr = perClientDevicePrs.get(mobileDevice.getId()).get(applicationName);
        for (String microservice : pr.getPlacedMicroservices().keySet()) {
            int deviceid = pr.getPlacedMicroservices().get(microservice);
            if (deviceid != mobileDevice.getId() && beforeCommonAncestor(deviceid, commonAncestor)) {
                migratingModules.put(microservice, deviceid);
            }
        }
        return migratingModules;
    }

    private boolean beforeCommonAncestor(Integer deviceid, int commonAncestor) {
        FogDevice f = getFogDeviceById(deviceid);
        if (f.getId() == commonAncestor) return false;
        while (f.getParentId() != -1) {
            f = getFogDeviceById(f.getParentId());
            if (f.getId() == commonAncestor) return true;
        }
        return false;
    }

    private double getDownDelay(int deviceID, int commonAncestorID, AppModule module) {
        double networkDelay = 0.0;
        while (deviceID != commonAncestorID) {
            networkDelay += module.getSize() / getFogDeviceById(deviceID).getDownlinkBandwidth();
            deviceID = getFogDeviceById(deviceID).getParentId();
        }
        return networkDelay;
    }

    private double getUpDelay(int deviceID, int commonAncestorID, AppModule module) {
        double networkDelay = 0.0;
        while (deviceID != commonAncestorID) {
            networkDelay += module.getSize() / getFogDeviceById(deviceID).getUplinkBandwidth();
            deviceID = getFogDeviceById(deviceID).getParentId();
        }
        return networkDelay;
    }

    private int determineAncestor(List<Integer> newParentPath, List<Integer> prevParentPath) {
        List<Integer> common = newParentPath.stream().filter(prevParentPath::contains).collect(Collectors.toList());
        return common.get(0);
    }

    private List<Integer> getPathsToCloud(int deviceID) {
        List<Integer> path = new ArrayList<>();
        while (deviceID != -1) {
            path.add(deviceID);
            FogDevice device = getFogDeviceById(deviceID);
            if (device == null) break;
            deviceID = device.getParentId();
        }
        return path;
    }
}
