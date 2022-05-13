package info.arhome.home.k8s.nsd4k;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Main {
    public static void main(String[] args) {
        //Load configuration
        final ConfigDto config;
        try {
            ObjectMapper mapper = new ObjectMapper();
            if (args[0].equals("-f")) {
                config = mapper.readValue(Paths.get(args[1]).toFile(), ConfigDto.class);
            } else {
                config = mapper.readValue(args[0].getBytes(StandardCharsets.UTF_8), ConfigDto.class);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read configuration", e);
        }

        final DnsDB dnsDB = new DnsDB();
        try {
            new DnsServer(config, dnsDB);
        } catch (Exception e) {
            throw new RuntimeException("Unable to create DnsServer", e);
        }

        try {
            Thread t = new Thread(new ConfigMapThread(config, dnsDB));
            t.start();
        } catch(Exception e) {
            throw new RuntimeException("Unable to create ConfigMapThread", e);
        }

        try {
            new ServiceThread(config, dnsDB).run();
        } catch(Exception e) {
            throw new RuntimeException("Unable to create NamingThread", e);
        }
    }
}
