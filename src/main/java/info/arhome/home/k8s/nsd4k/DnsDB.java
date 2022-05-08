package info.arhome.home.k8s.nsd4k;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class DnsDB {
    private static final Logger log = LoggerFactory.getLogger(DnsDB.class);

    public static class Svc {
        public String name;
        public String namespace;
        public String dnsName;
        public List<String> aRecords;

        @Override
        public int hashCode() {
            return Objects.hash(name, namespace);
        }

        @Override
        public boolean equals(Object _other) {
            if (_other instanceof Svc other)
                return name.equals(other.name) && namespace.equals(other.namespace);
            return false;
        }

        @Override
        public String toString() {
            return "{service: " + name + "/" + namespace + ", dnsName: " + dnsName + ", aRecords: " + aRecords + "}";
        }
    }

    private final HashMap<Svc, Svc> svcMap;

    //Indexes
    public final HashMap<String, List<String>> aRecords;

    public void remove(Svc svc) {
        Svc existing = svcMap.get(svc);
        if (existing != null) {
            log.info("AUDIT: Removing {}", existing);
            svcMap.remove(existing);
            aRecords.remove(existing.dnsName);
        }
    }

    public void addreplace(Svc svc) {
        remove(svc);
        log.info("AUDIT: Adding {}", svc);
        svcMap.put(svc, svc);
        aRecords.put(svc.dnsName, svc.aRecords);
    }

    public void clear() {
        log.info("AUDIT: clearing");
        svcMap.clear();
        aRecords.clear();
    }

    public DnsDB() {
        aRecords = new HashMap<>();
        svcMap = new HashMap<>();
    }
}
