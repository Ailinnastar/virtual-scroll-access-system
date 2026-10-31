package vsas.model;

import java.time.LocalDate;

// TODO SM-01: enforce unique name/ID and persist binary contents separately.
public record Scroll(String id, String name, String uploaderId, LocalDate uploadDate,
                     String originalFileName) { }
