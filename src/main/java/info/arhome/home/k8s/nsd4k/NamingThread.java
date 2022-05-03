package info.arhome.home.k8s.nsd4k;

import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import okhttp3.Call;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class NamingThread implements Runnable {

    static class ZoneInfo {
        public HashMap<String, List<String>> aRecords = new HashMap<>();

        public boolean eq(ZoneInfo other) {
            return aRecords.equals(other.aRecords);
        }
    }

    final ConfigDto config;

    final ZoneInfo zoneInfo;
    final ZoneInfo oldZoneInfo;

    public NamingThread(ConfigDto _config) {
        config = _config;

        zoneInfo = new ZoneInfo();
        oldZoneInfo = new ZoneInfo();
    }

    void zoneUpdateThreadFn() {
        while (true) {
            try {
                synchronized (zoneInfo) {
                    if (! zoneInfo.eq(oldZoneInfo)) {
                        //Copy all records
                        oldZoneInfo.aRecords.clear();
                        oldZoneInfo.aRecords.putAll(zoneInfo.aRecords);

                        //Update DNS zone
                        try (ZoneWriter zoneWriter = new ZoneWriter(config)) {
                            for (Map.Entry<String, List<String>> entry : zoneInfo.aRecords.entrySet()) {
                                zoneWriter.addAEntries(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("ERROR: Zone update failed: " + e);
                e.printStackTrace();
            }
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
                System.err.println("ERROR: failed to sleep: " + e);
                e.printStackTrace();
                System.exit(2);
            }
        }
    }

    void loop() {
        try {
            //Connect to Kubernetes API
            ApiClient client;
            try {
                client = Config.defaultClient();
            } catch (IOException e) {
                throw new PrettyException("Unable to connect to apiserver", K8sUtil.processException(e));
            }
            Configuration.setDefaultApiClient(client);

            CoreV1Api coreV1Api = new CoreV1Api();

            //Watch
            System.out.println("New watch");
            Call call;
            try {
                call = coreV1Api.listServiceForAllNamespacesCall(
                        null, null, null, null,
                        null, null, null, null,
                        null, true, null);
            } catch (Exception e) {
                throw new PrettyException("Failed to create watch call on services", K8sUtil.processException(e));
            }

            try (Watch<V1Service> v1ServiceWatch = Watch.createWatch(client, call,
                    new TypeToken<Watch.Response<V1Service>>() {
                    }.getType())) {

                for (Watch.Response<V1Service> serviceResponse : v1ServiceWatch) {
                    V1Service service = serviceResponse.object;
                    V1ObjectMeta serviceMetadata = service.getMetadata();
                    assert (serviceMetadata != null);
                    Map<String, String> labels = serviceMetadata.getLabels();
                    V1ServiceSpec serviceSpec = service.getSpec();
                    assert (serviceSpec != null);

                    String namespace = Objects.requireNonNullElse(serviceMetadata.getNamespace(), "NULL");
                    String name = service.getMetadata().getName();
                    List<String> externalIPs = new ArrayList<>();
                    V1ServiceStatus serviceStatus = service.getStatus();
                    if (serviceStatus != null) {
                        V1LoadBalancerStatus loadBalancerStatus = serviceStatus.getLoadBalancer();
                        if (loadBalancerStatus != null) {
                            List<V1LoadBalancerIngress> ingresses = loadBalancerStatus.getIngress();
                            if (ingresses != null) {
                                for (V1LoadBalancerIngress ingress : ingresses) {
                                    externalIPs.add(ingress.getIp());
                                }
                            }
                        }
                    }

                    String dnsName = null;
                    if (labels != null)
                        dnsName = labels.get(K8sUtil.getLabelPrefix() + "/name");

                    if (dnsName == null)
                        dnsName = name + "." + namespace;

                    synchronized (zoneInfo) {
                        //Update A records
                        if (! externalIPs.isEmpty()) {
                            if (serviceResponse.type.equals("DELETED")) {
                                zoneInfo.aRecords.remove(dnsName, externalIPs);
                            } else {
                                zoneInfo.aRecords.put(dnsName, externalIPs);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                throw new PrettyException("Failed to execute watch on services", K8sUtil.processException(e));
            }
        } catch (Exception e) {
            System.err.println("NamingThread: ERROR: " + e);
            e.printStackTrace();

        }
    }

    @Override
    public void run() {
        //Copy only the template
        try {
            ZoneWriter zoneWriter = new ZoneWriter(config);
            zoneWriter.close();
        } catch (Exception se) {
            throw new PrettyException("Unable to write initial zone file", se);
        }

        //Start zone update thread
        Thread zoneUpdateThread = new Thread(this::zoneUpdateThreadFn);
        zoneUpdateThread.setDaemon(true);
        zoneUpdateThread.start();

        //Kubernetes watch loop
        while (true) {
            loop();
            try {
                Thread.sleep(15000);
            } catch (Exception e) {
                throw new PrettyException("Thread.sleep failed", e);
            }
        }
    }
}
