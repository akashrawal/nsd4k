package info.arhome.home.k8s.nsd4k;

import io.kubernetes.client.openapi.ApiException;

import java.util.ArrayList;
import java.util.Collections;

public class K8sUtil {
    public static Exception processException(Exception e) {
        if (e instanceof ApiException apie) {
            String msg = "ApiException: " + apie.getCode() + " " + apie.getResponseBody();

            return new PrettyException(msg, e);
        } else {
            return e;
        }
    }

    private static String labelPrefix = null;
    public static String getLabelPrefix() {
        if (labelPrefix == null) {
            String[] parts = K8sUtil.class.getPackageName().split("\\.");
            ArrayList<String> partsList = new ArrayList<>();
            Collections.addAll(partsList, parts);
            Collections.reverse(partsList);
            labelPrefix = String.join(".", partsList);
        }
        return labelPrefix;
    }
}
