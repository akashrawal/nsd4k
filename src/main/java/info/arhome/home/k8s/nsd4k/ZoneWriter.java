package info.arhome.home.k8s.nsd4k;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class ZoneWriter implements AutoCloseable {
    private final ConfigDto config;

    private final PrintWriter output;

    public ZoneWriter(ConfigDto _config) throws IOException {
        config = _config;

        PrintWriter _output = null;
        boolean success = false;
        try {
            _output = new PrintWriter(Files.newBufferedWriter(Paths.get(config.zoneOutput)));
            try (Reader r = Files.newBufferedReader(Paths.get(config.zoneTemplate))) {
                r.transferTo(_output);
            }

            success = true;
        } finally {
            if (! success) {
                if (_output != null)
                    _output.close();
            }
        }
        output = _output;
    }

    public void addEntry(String name, String recordClass, String type, String value) {
        output.printf("%-22s %-7s %-7s %-39s ;\n\n", name, type, value);
    }

    public void addAEntries(String name, List<String> ipaddrs) {
        for (String ipaddr : ipaddrs) {
            String type = "A";
            if (ipaddr.contains(":"))
                type = "AAAA";
            addEntry(name, "IN", type, ipaddr);
        }
    }

    @Override
    public void close() {
        output.close();
        //Execute the DNS reload command
        try {
            Process p = new ProcessBuilder(config.reloadCmd).inheritIO().start();
            int status = p.waitFor();
            if (status != 0)
                throw new Exception("exit status: " + status);
        } catch (Exception e) {
            System.err.println("WARNING: Failed to execute DNS reload command: " + e);
        }
    }
}
