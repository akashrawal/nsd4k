package info.arhome.home.k8s.nsd4k;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IndexTest {
    Map<Integer, List<Integer>> makeEntry(int key, Integer... list) {
        return Collections.singletonMap(key, Arrays.asList(list));
    }

    void checkIndex(Index<Integer, Integer> index, int key, Integer... list) {
        List<Integer> result = index.get(key);
        Collections.sort(result);
        assertEquals(result, Arrays.asList(list));
    }

    @Test
    public void testAdd() {
        Index<Integer, Integer> index = new Index<>();

        index.change(makeEntry(0, 1, 2, 3), 1);
        index.change(makeEntry(0, 3, 4, 5), 1);

        checkIndex(index, 0, 1, 2, 3, 4, 5);
    }

    @Test
    public void testAdd2() {
        Index<Integer, Integer> index = new Index<>();

        index.change(makeEntry(0, 1, 2, 3), 1);
        index.change(makeEntry(1, 3, 4, 5), 1);

        checkIndex(index, 0, 1, 2, 3);
        checkIndex(index, 1, 3, 4, 5);
    }

    @Test
    public void testSub() {
        Index<Integer, Integer> index = new Index<>();

        index.change(makeEntry(0, 1, 2, 3), 1);
        index.change(makeEntry(0, 3, 4, 5), 1);
        index.change(makeEntry(0, 3, 4, 5), -1);

        checkIndex(index, 0, 1, 2, 3);
    }

    @Test
    public void testEmpty() {
        Index<Integer, Integer> index = new Index<>();

        index.change(makeEntry(0, 1, 2, 3), 1);
        index.change(makeEntry(0, 3, 4, 5), 1);
        index.change(makeEntry(0, 1, 2, 3), -1);
        index.change(makeEntry(0, 3, 4, 5), -1);

        List<Integer> result = index.get(0);
        assert(result == null);
    }

    @Test
    public void testEmpty2() {
        Index<Integer, Integer> index = new Index<>();

        index.change(makeEntry(0), 1);

        List<Integer> result = index.get(0);
        assert(result == null);
    }

    @Test
    public void testEmpty3() {
        Index<Integer, Integer> index = new Index<>();

        List<Integer> result = index.get(0);
        assert(result == null);
    }
}
