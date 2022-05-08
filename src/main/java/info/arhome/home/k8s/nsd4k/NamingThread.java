package info.arhome.home.k8s.nsd4k;

import com.google.gson.reflect.TypeToken;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1LoadBalancerIngress;
import io.kubernetes.client.openapi.models.V1LoadBalancerStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1ServiceStatus;
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

    public NamingThread(ConfigDto _config, DnsDB _dnsDB) {
        config = _config;
        dnsDB = _dnsDB;
    }

    void processService(V1Service service, K8sUtil.WatchEvent eventType) {
        V1ObjectMeta serviceMetadata = service.getMetadata();
        assert (serviceMetadata != null);
        Map<String, String> labels = serviceMetadata.getLabels();
        V1ServiceSpec serviceSpec = service.getSpec();
        assert (serviceSpec != null);

        DnsDB.Svc entry = new DnsDB.Svc();

        entry.namespace = Objects.requireNonNullElse(serviceMetadata.getNamespace(), "NULL");
        entry.name = service.getMetadata().getName();
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
        entry.aRecords = externalIPs;

        {
            String _dnsName = null;
            if (labels != null)
                _dnsName = labels.get(K8sUtil.getLabelPrefix() + "/name");

            if (_dnsName == null)
                _dnsName = entry.name + "." + entry.namespace;
            entry.dnsName = _dnsName;
        }

        final boolean remove = (eventType == K8sUtil.WatchEvent.DELETED) || (externalIPs.isEmpty());
        synchronized (dnsDB) {
            //Update A records
            if (remove) {
                dnsDB.remove(entry);
            } else {
                dnsDB.addreplace(entry);
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

            //List
            log.info("List");
            V1ServiceList serviceList = coreV1Api.listServiceForAllNamespaces(
                    null, null, null, null, null,
                    null, null, null, null, null);
            V1ListMeta serviceListMeta = serviceList.getMetadata();
            assert(serviceListMeta != null);

            K8sUtil.WatchChecker<V1Service> checker = new K8sUtil.WatchChecker<>();
            checker.resourceVersion = serviceListMeta.getResourceVersion();
            synchronized (dnsDB) {
                dnsDB.clear();
                for (V1Service service : serviceList.getItems()) {
                    processService(service, null);
                }
            }

            //Watch
            while (true) {
                log.info("Watch begin at {}", checker.resourceVersion);
                Call call;
                try {
                    call = coreV1Api.listServiceForAllNamespacesCall(
                            true, null, null, null,
                            null, null, checker.resourceVersion, null,
                            null, true, null);
                } catch (Exception e) {
                    throw new PrettyException("Failed to create watch call on services", K8sUtil.processException(e));
                }

                try (Watch<V1Service> v1ServiceWatch = Watch.createWatch(client, call,
                        new TypeToken<Watch.Response<V1Service>>() {
                        }.getType())) {

                    for (Watch.Response<V1Service> serviceResponse : v1ServiceWatch) {
                        if (checker.check(serviceResponse))
                            continue;

                        processService(checker.object, checker.event);
                    }
                } catch (Exception e) {
                    throw new PrettyException("Failed to execute watch on services", K8sUtil.processException(e));
                }
                log.info("Watch end");
            }
        } catch (Exception e) {
            log.error("Loop iteration failed", e);
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
