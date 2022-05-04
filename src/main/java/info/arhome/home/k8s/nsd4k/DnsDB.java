package info.arhome.home.k8s.nsd4k;

import java.util.HashMap;
import java.util.List;

public class DnsDB {
    public final HashMap<String, List<String>> aRecords;

    public DnsDB() {
        aRecords = new HashMap<>();
    }
}
