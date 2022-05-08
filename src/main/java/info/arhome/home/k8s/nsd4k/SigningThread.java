package info.arhome.home.k8s.nsd4k;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.reflect.TypeToken;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
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

public class SigningThread implements Runnable{
    Logger log = LoggerFactory.getLogger(SigningThread.class);

    private final CA ca;
    private final ObjectMapper objectMapper;

    public SigningThread(ConfigDto config) throws IOException {
        ca = new CA(config);
        objectMapper = new ObjectMapper();
    }

    private void processSecret(V1Secret secret, CoreV1Api coreV1Api, ApiClient client) throws IOException {
        V1ObjectMeta secretMeta = secret.getMetadata();
        if (secretMeta == null)
            return;
        Map<String, String> labels = secretMeta.getLabels();
        if (labels == null)
            return;

        //Certificate name
        String name = labels.get(K8sUtil.getLabelPrefix() + "/name");
        String absname = labels.get(K8sUtil.getLabelPrefix() + "/absname");
        if (name != null) {
            if (name.equals("default") || name.equals("")) {
                name = secretMeta.getName();
            }
            name = name + "." + secretMeta.getNamespace();
        } else if (absname != null) {
            name = absname;
        } else {
            return;
        }

        //Get the certificate to use
        CA.Cert cert = ca.getCert(name);

        //Make the patch
        Map<String, byte[]> secretDataModel = new HashMap<>();
        secretDataModel.put(CA.CERT_FILE, Files.readAllBytes(cert.getCertificatePem()));
        secretDataModel.put(CA.KEY_FILE, Files.readAllBytes(cert.getPrivateKeyPem()));
        V1Patch secretPatch = new V1Patch("[{\"op\":\"replace\",\"path\":\"/data\",\"value\":"
                + objectMapper.writeValueAsString(secretDataModel) + "}]");

        //Apply patch
        try {
            log.info("AUDIT: Adding certificate({}) --> secret({}/{})",
                    name, secretMeta.getNamespace(), secretMeta.getName());
            PatchUtils.patch(V1Secret.class,
                    () -> coreV1Api.patchNamespacedSecretCall
                            (secretMeta.getName(), secretMeta.getNamespace(), secretPatch,
                                    null, null, null, null, null),
                    V1Patch.PATCH_FORMAT_JSON_PATCH, client);
        } catch (Exception e) {
            throw new PrettyException("Failed to patch secret " + secretMeta.getName(),
                    K8sUtil.processException(e));
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

            //List age
            Instant expire = Instant.now().plus(Duration.ofDays(1));

            //List
            log.info("List");
            V1SecretList secretList = coreV1Api.listSecretForAllNamespaces(
                    null, null, null, null,
                    null, null, null, null,
                    null, null);
            V1ListMeta secretListMeta = secretList.getMetadata();
            assert(secretListMeta != null);
            K8sUtil.WatchChecker<V1Secret> checker = new K8sUtil.WatchChecker<>();
            checker.resourceVersion = secretListMeta.getResourceVersion();
            for (V1Secret secret : secretList.getItems()) {
                processSecret(secret, coreV1Api, client);
            }

            //Watch
            while (Instant.now().isBefore(expire)) {
                log.info("Watch begin at {}", checker.resourceVersion);
                Call call;
                try {
                    call = coreV1Api.listSecretForAllNamespacesCall(
                            true, null, null, null,
                            null, null, checker.resourceVersion, null,
                            null, true, null);
                } catch (Exception e) {
                    throw new PrettyException("Failed to create watch call on secrets", K8sUtil.processException(e));
                }

                try (Watch<V1Secret> v1SecretWatch = Watch.createWatch(client, call,
                        new TypeToken<Watch.Response<V1Secret>>() {
                        }.getType())) {

                    for (Watch.Response<V1Secret> secretResponse : v1SecretWatch) {
                        if (checker.check(secretResponse))
                            continue;

                        if (checker.event == K8sUtil.WatchEvent.ADDED) {
                            processSecret(checker.object, coreV1Api, client);
                        }
                    }
                    log.info("Watch end");
                } catch (Exception e) {
                    throw new PrettyException("Failed to execute watch on secrets", K8sUtil.processException(e));
                }
            }

            log.info("Secret age expired, reloading all certificates");
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
