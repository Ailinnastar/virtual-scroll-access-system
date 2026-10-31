package vsas.model;

import java.time.LocalDate;

// TODO SS-03: define matching rules; null fields mean no filter.
public record ScrollFilter(String uploaderId, String scrollId, String name,
                           LocalDate uploadDate) { }
