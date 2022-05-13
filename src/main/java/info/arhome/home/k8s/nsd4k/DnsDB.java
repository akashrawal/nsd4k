package info.arhome.home.k8s.nsd4k;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DnsDB {
    private static final Logger log = LoggerFactory.getLogger(DnsDB.class);

    public static class Entry {
        public String kind;
        public String namespace;
        public String name;

        public final Map<String, List<String>> aRecords = new HashMap<>();

        @Override
        public int hashCode() {
            return Objects.hash(kind, name, namespace);
        }

        @Override
        public boolean equals(Object _other) {
            if (_other instanceof Entry other)
                return kind.equals(other.kind) && name.equals(other.name) && namespace.equals(other.namespace);
            return false;
        }

        @Override
        public String toString() {
            return "{" + kind + ": " + namespace + "/" + name +  ", aRecords: " + aRecords + "}";
        }
    }

    //Entries
    private final HashMap<Entry, Entry> entries;

    //Indexes
    public final Index<String, String> aRecords;

    public void remove(Entry entry) {
        Entry existing = entries.get(entry);
        if (existing != null) {
            log.info("AUDIT: Removing {}", existing);
            entries.remove(existing);

            aRecords.change(existing.aRecords, -1);
        }
    }

    public void addreplace(Entry entry) {
        remove(entry);
        log.info("AUDIT: Adding {}", entry);
        entries.put(entry, entry);

        aRecords.change(entry.aRecords, 1);
    }

    public void clear(String kind) {
        log.info("AUDIT: clearing kind: {}", kind);
        for (Entry entry : entries.keySet()) {
            if (entry.kind.equals(kind)) {
                remove(entry);
            }
        }
    }

    public List<Entry> list(String kind) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.keySet()) {
            if (entry.kind.equals(kind)) {
                result.add(entry);
            }
        }

        return result;
    }

    public DnsDB() {
        entries = new HashMap<>();
        aRecords = new Index<>();
    }
}
