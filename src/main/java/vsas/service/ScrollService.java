package vsas.service;

import java.nio.file.Path;
import java.util.List;
import vsas.model.Scroll;
import vsas.model.ScrollFilter;
import vsas.model.User;

public interface ScrollService {
    // TODO SM-01: authenticated upload, unique name/ID, binary persistence, statistics.
    Scroll upload(User actor, String name, Path binaryFile);
    // TODO SM-02: only uploader can edit; support metadata/file replacement.
    Scroll update(User actor, String scrollId, String name, Path replacementFile);
    // TODO SM-03: only uploader can remove; retain historical statistics.
    void remove(User actor, String scrollId);
    // TODO SS-01/03: list/search for guests and signed-in users using all four filters.
    List<Scroll> search(ScrollFilter filter);
    // TODO SS-04: preview for all users; agree binary preview format with tutor.
    byte[] preview(String scrollId);
    // TODO SS-02/UM-04: deny guest download; preserve bytes; increment statistics.
    void download(User actor, String scrollId, Path destination);
}
