package cm.afriland.titres.support;

import java.util.List;

/**
 * Parametres de pagination resolus : {@code limit} et {@code offset}.
 *
 * {@code size} est plafonne a 100 pour eviter qu'un client ne demande des
 * pages demesurees ; {@code page} commence a 1.
 */
public record Pagination(long limit, long offset) {

    public static Pagination of(Integer page, Integer size) {
        long s = (size == null) ? 20 : Math.clamp(size, 1, 100);
        long p = (page == null) ? 1 : Math.max(1, page);
        return new Pagination(s, (p - 1) * s);
    }

    /** Construit la reponse paginee a partir des donnees et du total. */
    public <T> PageResponse<T> build(List<T> data, long total) {
        return new PageResponse<>(data, offset / limit + 1, limit, total);
    }
}
