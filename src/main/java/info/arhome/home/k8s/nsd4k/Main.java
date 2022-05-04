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

        try {
            SigningThread signingThread = new SigningThread(config);
            Thread signingThreadRunner = new Thread(signingThread);
            signingThreadRunner.start();
        } catch (Exception e) {
            throw new RuntimeException("Unable to create SigningThread", e);
        }

        final DnsDB dnsDB = new DnsDB();
        try {
            new DnsServer(config, dnsDB);
        } catch (Exception e) {
            throw new RuntimeException("Unable to create DnsServer", e);
        }

        new NamingThread(config, dnsDB).run();
    }
}
