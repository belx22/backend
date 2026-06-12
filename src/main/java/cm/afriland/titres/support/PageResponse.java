package cm.afriland.titres.support;

import java.util.List;

/**
 * Enveloppe standard d'une reponse paginee : {@code {data, page, size, total}}.
 */
public record PageResponse<T>(List<T> data, long page, long size, long total) {
}
