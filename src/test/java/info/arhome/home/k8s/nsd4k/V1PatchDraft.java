package info.arhome.home.k8s.nsd4k;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class V1PatchDraft {
    static class V1SecretPatchTestEntry {
        public String op;
        public String path;
        public byte[] value;
    }

    @Test
    public void test() throws JsonProcessingException {
        ArrayList<V1SecretPatchTestEntry> patch = new ArrayList<>();
        V1SecretPatchTestEntry patchEntry = new V1SecretPatchTestEntry();
        patchEntry.op = "add";
        patchEntry.path = "/data/cert.pem";
        patchEntry.value = "CERTIFICATE".getBytes(StandardCharsets.UTF_8);
        patch.add(patchEntry);

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonPatch = objectMapper.writeValueAsString(patch);

        System.out.println(jsonPatch);
    }
}
