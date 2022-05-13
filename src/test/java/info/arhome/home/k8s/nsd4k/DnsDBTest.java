package info.arhome.home.k8s.nsd4k;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DnsDBTest {
    DnsDB db;

    DnsDB.Entry makeEntry(String name) {
        DnsDB.Entry entry = new DnsDB.Entry();
        entry.kind = "Service";
        entry.namespace = "default";
        entry.name = name;
        return entry;
    }

    void check(String name, String... values) {
        List<String> result = db.aRecords.get(name);
        Collections.sort(result);
        assertEquals(result, Arrays.asList(values));
    }

    void initDB() {
        db = new DnsDB();

        DnsDB.Entry entry = makeEntry("svc1");
        entry.aRecords.put("svc1", Arrays.asList("172.16.1.1", "172.16.1.2"));
        db.addreplace(entry);
    }

    @Test
    void testAdd() {
        initDB();

        DnsDB.Entry entry = makeEntry("svc2");
        entry.aRecords.put("svc2", Arrays.asList("172.16.1.2", "172.16.1.3"));
        db.addreplace(entry);

        check("svc1", "172.16.1.1", "172.16.1.2");
        check("svc2", "172.16.1.2", "172.16.1.3");
    }

    @Test
    void testRemove() {
        initDB();
        db.remove(makeEntry("svc1"));
        assert(db.aRecords.get("svc1") == null);
    }

    @Test
    void testReplace() {
        initDB();

        DnsDB.Entry entry = makeEntry("svc1");
        entry.aRecords.put("svc1", Arrays.asList("172.16.1.2", "172.16.1.3"));
        db.addreplace(entry);

        check("svc1", "172.16.1.2", "172.16.1.3");
    }
}
