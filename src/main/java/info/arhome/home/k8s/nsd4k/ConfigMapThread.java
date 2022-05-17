package info.arhome.home.k8s.nsd4k;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import okhttp3.Call;
import com.google.gson.reflect.TypeToken;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigMapThread implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ConfigMapThread.class);

    private static final String CONFIG_MAP_ENTRY_KIND = "ConfigMap";

    private static final String LABEL_SELECTOR = K8sUtil.getLabelPrefix() + "/dns=dns";

    private final DnsDB dnsDB;
    private final ObjectMapper objectMapper;
    private final Set<String> privilegedNamespaces;

    public ConfigMapThread(ConfigDto config, DnsDB _dnsDB) {
        dnsDB = _dnsDB;

        objectMapper = new ObjectMapper();
        privilegedNamespaces = new HashSet<>();
        Collections.addAll(privilegedNamespaces, config.privilegedNamespaces);
    }

    private static class DnsEntriesDto {
        public Map<String, List<String>> A;
    }

    private class ConfigMapEntry extends DnsDB.Entry {
        DnsEntriesDto dnsEntries = null;

        public ConfigMapEntry(V1ConfigMap configMap) {
            V1ObjectMeta metadata = configMap.getMetadata();
            assert(metadata != null);
            Map<String, String> labels = metadata.getLabels();

            kind = CONFIG_MAP_ENTRY_KIND;
            namespace = metadata.getNamespace();
            name = metadata.getName();

            String suffix = "." + namespace;
            if (privilegedNamespaces.contains(namespace) && labels != null) {
                String altSuffix = labels.get(K8sUtil.getLabelPrefix() + "/suffix");
                if (altSuffix != null)
                    suffix = altSuffix;
            }

            Map<String, String> data = configMap.getData();
            if (data != null) {
                String entriesJson = data.get("entries");
                if (entriesJson != null) {
                    try {
                        dnsEntries = objectMapper.readValue(entriesJson, DnsEntriesDto.class);
                        for (Map.Entry<String, List<String>> oneEntry : dnsEntries.A.entrySet())
                            aRecords.put(oneEntry.getKey() + suffix, oneEntry.getValue());
                    } catch (Exception e) {
                        log.warn("AUDIT: Unable to parse ConfigMap {}/{}", namespace, name, e);
                    }
                }
            }
        }
    }

    void processConfigMap(V1ConfigMap configMap, K8sUtil.WatchEvent eventType) {
        ConfigMapEntry entry = new ConfigMapEntry(configMap);

        final boolean remove = (eventType == K8sUtil.WatchEvent.DELETED);
        synchronized (dnsDB) {
            //Update dnsDB
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
            V1ConfigMapList configMapList = coreV1Api.listConfigMapForAllNamespaces(
                    null, null, null, LABEL_SELECTOR, null,
                    null, null, null, null, null);
            V1ListMeta configMapListMeta = configMapList.getMetadata();
            assert(configMapListMeta != null);

            K8sUtil.WatchChecker<V1ConfigMap> checker = new K8sUtil.WatchChecker<>();
            checker.resourceVersion = configMapListMeta.getResourceVersion();
            synchronized (dnsDB) {
                dnsDB.clear(CONFIG_MAP_ENTRY_KIND);
                for (V1ConfigMap configMap : configMapList.getItems()) {
                    processConfigMap(configMap, null);
                }
            }

            //Watch
            while (true) {
                log.info("Watch begin at {}", checker.resourceVersion);
                Call call;
                try {
                    call = coreV1Api.listConfigMapForAllNamespacesCall(
                            true, null, null, LABEL_SELECTOR,
                            null, null, checker.resourceVersion, null,
                            null, true, null);
                } catch (Exception e) {
                    throw new PrettyException("Failed to create watch call on configMaps", K8sUtil.processException(e));
                }

                try (Watch<V1ConfigMap> v1ConfigMapWatch = Watch.createWatch(client, call,
                        new TypeToken<Watch.Response<V1ConfigMap>>() {
                        }.getType())) {

                    for (Watch.Response<V1ConfigMap> configMapResponse : v1ConfigMapWatch) {
                        if (checker.check(configMapResponse))
                            continue;

                        processConfigMap(checker.object, checker.event);
                    }
                } catch (Exception e) {
                    throw new PrettyException("Failed to execute watch on configMaps", K8sUtil.processException(e));
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
