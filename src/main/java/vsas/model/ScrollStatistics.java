package vsas.model;

/** Upload and download counts. Removed scrolls stay here for the administrator and leave the library. */
public record ScrollStatistics(String scrollId, long uploads, long downloads, boolean removed) {
    public ScrollStatistics(String scrollId, long uploads, long downloads) {
        this(scrollId, uploads, downloads, false);
    }
}
