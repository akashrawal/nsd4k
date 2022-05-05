package info.arhome.home.k8s.nsd4k;

import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import okhttp3.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class NamingThread implements Runnable {
    static final Logger log = LoggerFactory.getLogger(NamingThread.class);

    final ConfigDto config;
    final DnsDB dnsDB;

    String resourceVersion;

    public NamingThread(ConfigDto _config, DnsDB _dnsDB) {
        config = _config;
        dnsDB = _dnsDB;

        resourceVersion = null;
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
            log.info("Watch begin at {}", resourceVersion);
            Call call;
            try {
                call = coreV1Api.listServiceForAllNamespacesCall(
                        null, null, null, null,
                        null, null, resourceVersion, null,
                        10, true, null);
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

                    resourceVersion = serviceMetadata.getResourceVersion();

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

                    synchronized (dnsDB) {
                        //Update A records
                        if (serviceResponse.type.equals("DELETED")) {
                            log.info("AUDIT: remove {}", dnsName);
                            dnsDB.aRecords.remove(dnsName, externalIPs);
                        } else if (! externalIPs.isEmpty()) {
                            log.info("AUDIT: add {} --> {}", dnsName, externalIPs);
                            dnsDB.aRecords.put(dnsName, externalIPs);
                        }
                    }
                }
                log.info("Watch end");
            } catch (Exception e) {
                throw new PrettyException("Failed to execute watch on services", K8sUtil.processException(e));
            }
        } catch (Exception e) {
            resourceVersion = null;
            synchronized (dnsDB) {
                dnsDB.aRecords.clear();
            }
            log.error("Watch iteration failed", e);
        }
    }

    @Override
    public void run() {
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
