package info.arhome.home.k8s.nsd4k;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Index<T, U> {
    private final Map<T, Map<U, Integer>> map = new HashMap<>();

    private static <U> void changeMultiset(Map<U, Integer> multiset, U key, int delta) {
        Integer count = multiset.get(key);
        if (count == null)
            count = 0;
        count += delta;
        if (count == 0)
            multiset.remove(key);
        else
            multiset.put(key, count);
    }

    public void change(Map<T, List<U>> entries, int delta) {
        for (Map.Entry<T, List<U>> oneEntry : entries.entrySet()) {
            if (!oneEntry.getValue().isEmpty()) {
                Map<U, Integer> multiset = map.get(oneEntry.getKey());

                if (multiset == null) {
                    multiset = new HashMap<>();
                    map.put(oneEntry.getKey(), multiset);
                }

                for (U element : oneEntry.getValue()) {
                    changeMultiset(multiset, element, delta);
                }

                if (multiset.isEmpty()) {
                    map.remove(oneEntry.getKey());
                }
            }
        }
    }

    public List<U> get(T key) {
        Map<U, Integer> multiset = map.get(key);
        if (multiset != null) {
            return new ArrayList<>(multiset.keySet());
        }
        return null;
    }
}
