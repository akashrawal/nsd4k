package info.arhome.home.k8s.nsd4k;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.reflect.TypeToken;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1LoadBalancerIngress;
import io.kubernetes.client.openapi.models.V1LoadBalancerStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1ServiceStatus;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.PatchUtils;
import io.kubernetes.client.util.Watch;
import okhttp3.Call;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class ServiceThread implements Runnable {
    static final Logger log = LoggerFactory.getLogger(ServiceThread.class);

    final ConfigDto config;
    final DnsDB dnsDB;
    final CA ca;

    final ObjectMapper objectMapper;

    Instant certRotateTime;

    public ServiceThread(ConfigDto _config, DnsDB _dnsDB) throws IOException {
        config = _config;
        dnsDB = _dnsDB;
        ca = new CA(config);

        objectMapper = new ObjectMapper();

        certRotateTime = null;
    }

    private static final String SERVICE_ENTRY_KIND = "Service";

    private static class SvcEntry extends DnsDB.Entry {
        public String secret;
        public String dnsName;
        public List<String> externalIPs;

        public SvcEntry(V1Service service) {
            V1ObjectMeta serviceMetadata = service.getMetadata();
            assert (serviceMetadata != null);
            Map<String, String> labels = serviceMetadata.getLabels();
            V1ServiceSpec serviceSpec = service.getSpec();
            assert (serviceSpec != null);

            kind = SERVICE_ENTRY_KIND;
            namespace = Objects.requireNonNullElse(serviceMetadata.getNamespace(), "NULL");
            name = service.getMetadata().getName();
            externalIPs = new ArrayList<>();
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

            {
                String _dnsName = null;
                if (labels != null)
                    _dnsName = labels.get(K8sUtil.getLabelPrefix() + "/name");

                if (_dnsName == null)
                    _dnsName = name + "." + namespace;
                dnsName = _dnsName;
            }
            aRecords.put(dnsName, externalIPs);

            if (labels != null)
                secret = labels.get(K8sUtil.getLabelPrefix() + "/secret");
        }
    }

    void updateCertificate(SvcEntry entry, CoreV1Api coreV1Api) {
        if (entry.secret != null) {
            log.info("AUDIT: Adding TLS certificates for service {} in secret {}",
                    entry, entry.secret);
            try {
                CA.Cert cert = ca.getCert(entry.dnsName);

                //Create patch
                Map<String, byte[]> secretDataModel = new HashMap<>();
                secretDataModel.put(CA.CERT_FILE, Files.readAllBytes(cert.getCertificatePem()));
                secretDataModel.put(CA.KEY_FILE, Files.readAllBytes(cert.getPrivateKeyPem()));
                V1Patch secretPatch = new V1Patch("[{\"op\":\"replace\",\"path\":\"/data\",\"value\":"
                        + objectMapper.writeValueAsString(secretDataModel) + "}]");

                //Apply patch
                PatchUtils.patch(V1Secret.class,
                        () -> coreV1Api.patchNamespacedSecretCall
                                (entry.secret, entry.namespace, secretPatch,
                                        null, null, null, null, null),
                        V1Patch.PATCH_FORMAT_JSON_PATCH, coreV1Api.getApiClient());
            } catch (Exception e) {
                log.warn("Unable to update secret with certificate", K8sUtil.processException(e));
            }
        }
    }

    void processService(V1Service service, CoreV1Api coreV1Api, K8sUtil.WatchEvent eventType) {
        SvcEntry entry = new SvcEntry(service);

        final boolean remove = (eventType == K8sUtil.WatchEvent.DELETED);
        synchronized (dnsDB) {
            //Update dnsDB
            if (remove) {
                dnsDB.remove(entry);
            } else {
                dnsDB.addreplace(entry);
            }
        }

        if (!remove) {
            updateCertificate(entry, coreV1Api);
        }
    }

    void rotateCerts(CoreV1Api coreV1Api) {
        if (certRotateTime != null) {
            if (Instant.now().isAfter(certRotateTime)) {
                log.info("AUDIT: Start rotating all certificates");
                List<DnsDB.Entry> entryList;
                synchronized (dnsDB) {
                    entryList = dnsDB.list(SERVICE_ENTRY_KIND);
                }
                for (DnsDB.Entry _entry : entryList) {
                    SvcEntry entry = (SvcEntry) _entry;
                    updateCertificate(entry, coreV1Api);
                }
                log.info("AUDIT: End rotating all certificates");
                certRotateTime = null;
            }
        }
        if (certRotateTime == null)
            certRotateTime = Instant.now().plus(Duration.ofDays(1));
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
                dnsDB.clear(SERVICE_ENTRY_KIND);
                for (V1Service service : serviceList.getItems()) {
                    processService(service, coreV1Api, null);
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

                        processService(checker.object, coreV1Api, checker.event);
                    }
                } catch (Exception e) {
                    throw new PrettyException("Failed to execute watch on services", K8sUtil.processException(e));
                }
                log.info("Watch end");

                rotateCerts(coreV1Api);
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
