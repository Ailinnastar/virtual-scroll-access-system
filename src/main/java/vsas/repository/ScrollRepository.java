package vsas.repository;

import java.util.List;
import java.util.Optional;
import vsas.model.Scroll;
import vsas.model.ScrollStatistics;

// TODO DATA-02: persist metadata, binary bytes and historical counters across restarts.
public interface ScrollRepository {
    Optional<Scroll> findById(String id);
    List<Scroll> findAll();
    byte[] readContent(String id);
    void save(Scroll scroll, byte[] content);
    void delete(String id);
    void recordUpload(String id);
    void recordDownload(String id);
    List<ScrollStatistics> statistics();
}
