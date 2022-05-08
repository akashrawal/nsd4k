package info.arhome.home.k8s.nsd4k;

import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.util.Watch;

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

    public enum WatchEvent {
        ADDED("ADDED"),
        MODIFIED("MODIFIED"),
        DELETED("DELETED"),
        BOOKMARK("BOOKMARK"),
        ERROR("ERROR");

        public final String string;
        public String toString() {
            return string;
        }
        WatchEvent(String _string) {
            string = _string;
        }

        public static WatchEvent get(String string) {
            for (WatchEvent event : WatchEvent.values()) {
                if (event.string.equals(string))
                    return event;
            }
            throw new PrettyException("Unrecognized event " + string);
        }
    }

    public static class WatchChecker<T extends KubernetesObject> {
        public String resourceVersion;
        public T object;
        public WatchEvent event;
        public boolean check(Watch.Response<T> objectResponse) {
            event = WatchEvent.get(objectResponse.type);
            if (event == WatchEvent.ERROR) {
                throw new PrettyException(objectResponse.status.toString());
            }
            object = objectResponse.object;
            resourceVersion = object.getMetadata().getResourceVersion();

            return event == WatchEvent.BOOKMARK;
        }
    }
}
