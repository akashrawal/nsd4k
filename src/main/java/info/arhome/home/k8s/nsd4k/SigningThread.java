package info.arhome.home.k8s.nsd4k;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.PatchUtils;
import io.kubernetes.client.util.Watch;
import okhttp3.Call;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class SigningThread implements Runnable{

    private final ConfigDto config;

    private final CA ca;
    private final ObjectMapper objectMapper;

    public SigningThread(ConfigDto _config) throws IOException {
        config = _config;

        ca = new CA(config);
        objectMapper = new ObjectMapper();
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
                call = coreV1Api.listSecretForAllNamespacesCall(
                        null, null, null, null,
                        null, null, null, null,
                        null, true, null);
            } catch (Exception e) {
                throw new PrettyException("Failed to create watch call on services", K8sUtil.processException(e));
            }

            try (Watch<V1Secret> v1SecretWatch = Watch.createWatch(client, call,
                    new TypeToken<Watch.Response<V1Secret>>() {
                    }.getType())) {

                for (Watch.Response<V1Secret> secretResponse : v1SecretWatch) {
                    V1Secret secret = secretResponse.object;
                    if (secretResponse.type.equals("ADDED")) {
                        V1ObjectMeta secretMeta = secret.getMetadata();
                        if (secretMeta == null)
                            continue;
                        Map<String, String> labels = secretMeta.getLabels();
                        if (labels == null)
                            continue;
                        Map<String, byte[]> secretData = secret.getData();
                        if (secretData == null)
                            secretData = Collections.emptyMap();

                        //Certificate name
                        String name = labels.get(this.getClass().getPackageName() + "/name");
                        String absname = labels.get(this.getClass().getPackageName() + "/absname");
                        if (name != null) {
                            if (name.equals("default") || name.equals("")) {
                                name = secretMeta.getName();
                            }
                            name = name + "." + secretMeta.getNamespace();
                        } else if (absname != null) {
                            name = absname;
                        } else {
                            continue;
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
                }
            } catch (Exception e) {
                throw new PrettyException("Failed to execute watch on services", K8sUtil.processException(e));
            }
        } catch (Exception e) {
            System.err.println("SigningThread: ERROR: " + e);
            e.printStackTrace();

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
