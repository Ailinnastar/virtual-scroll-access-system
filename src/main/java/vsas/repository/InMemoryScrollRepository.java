package vsas.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import vsas.model.Scroll;
import vsas.model.ScrollStatistics;

/** Process-local scroll store used by tests and the embedded UI server. */
public final class InMemoryScrollRepository implements ScrollRepository {
    private final Map<String, Scroll> scrolls = new LinkedHashMap<>();
    private final Map<String, byte[]> contents = new LinkedHashMap<>();
    private final Map<String, long[]> statistics = new LinkedHashMap<>();
    private final Set<String> removedIds = new LinkedHashSet<>();

    @Override
    public synchronized Optional<Scroll> findById(String id) {
        return Optional.ofNullable(scrolls.get(id));
    }

    @Override
    public synchronized List<Scroll> findAll() {
        return List.copyOf(scrolls.values());
    }

    @Override
    public synchronized byte[] readContent(String id) {
        byte[] content = contents.get(id);
        if (content == null) throw new NoSuchElementException("Scroll was not found");
        return content.clone();
    }

    @Override
    public synchronized void save(Scroll scroll, byte[] content) {
        scrolls.put(scroll.id(), scroll);
        contents.put(scroll.id(), content.clone());
    }

    @Override
    public synchronized void delete(String id) {
        scrolls.remove(id);
        contents.remove(id);
        statistics.putIfAbsent(id, new long[2]);
        removedIds.add(id);
    }

    @Override
    public synchronized void recordUpload(String id) {
        statistics.computeIfAbsent(id, ignored -> new long[2])[0]++;
        removedIds.remove(id);
    }

    @Override
    public synchronized void recordDownload(String id) {
        statistics.computeIfAbsent(id, ignored -> new long[2])[1]++;
    }

    @Override
    public synchronized List<ScrollStatistics> statistics() {
        List<ScrollStatistics> rows = new ArrayList<>();
        statistics.forEach((id, counts) -> rows.add(
                new ScrollStatistics(id, counts[0], counts[1], removedIds.contains(id))));
        return List.copyOf(rows);
    }
}
