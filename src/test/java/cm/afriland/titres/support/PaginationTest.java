package cm.afriland.titres.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {

    // ─── Pagination.of ────────────────────────────────────────────────────────

    @Test
    void of_null_null_defaults_page1_size20_offset0() {
        Pagination p = Pagination.of(null, null);
        assertEquals(20, p.limit());
        assertEquals(0,  p.offset());
    }

    @Test
    void of_page1_size10_offset0() {
        Pagination p = Pagination.of(1, 10);
        assertEquals(10, p.limit());
        assertEquals(0,  p.offset());
    }

    @Test
    void of_page2_size10_offset10() {
        Pagination p = Pagination.of(2, 10);
        assertEquals(10, p.limit());
        assertEquals(10, p.offset());
    }

    @Test
    void of_page3_size5_offset10() {
        Pagination p = Pagination.of(3, 5);
        assertEquals(5,  p.limit());
        assertEquals(10, p.offset());
    }

    @Test
    void of_size_null_defaut_20() {
        Pagination p = Pagination.of(1, null);
        assertEquals(20, p.limit());
    }

    @Test
    void of_size_0_clamp_a_1() {
        Pagination p = Pagination.of(1, 0);
        assertEquals(1, p.limit());
    }

    @Test
    void of_size_negatif_clamp_a_1() {
        Pagination p = Pagination.of(1, -5);
        assertEquals(1, p.limit());
    }

    @Test
    void of_size_200_clamp_a_100() {
        Pagination p = Pagination.of(1, 200);
        assertEquals(100, p.limit());
    }

    @Test
    void of_size_100_exactement_autorise() {
        Pagination p = Pagination.of(1, 100);
        assertEquals(100, p.limit());
    }

    @Test
    void of_page_0_clamp_a_1_offset_0() {
        Pagination p = Pagination.of(0, 10);
        assertEquals(0, p.offset());
    }

    @Test
    void of_page_negatif_clamp_a_1() {
        Pagination p = Pagination.of(-3, 10);
        assertEquals(0, p.offset());
    }

    // ─── Pagination.build ─────────────────────────────────────────────────────

    @Test
    void build_page1_contenu_correct() {
        Pagination p = Pagination.of(1, 20);
        List<String> data = List.of("a", "b", "c");
        PageResponse<String> r = p.build(data, 3);

        assertEquals(1,    r.page());
        assertEquals(20,   r.size());
        assertEquals(3,    r.total());
        assertEquals(data, r.data());
    }

    @Test
    void build_page2_numero_de_page_correct() {
        Pagination p = Pagination.of(2, 10);
        PageResponse<Integer> r = p.build(List.of(1, 2), 100);

        assertEquals(2,   r.page());
        assertEquals(10,  r.size());
        assertEquals(100, r.total());
    }

    @Test
    void build_page3_size5_total_50() {
        Pagination p = Pagination.of(3, 5);
        PageResponse<String> r = p.build(List.of("x"), 50);

        assertEquals(3,  r.page());
        assertEquals(5,  r.size());
        assertEquals(50, r.total());
    }

    @Test
    void build_data_vide_total_0() {
        Pagination p = Pagination.of(1, 20);
        PageResponse<Object> r = p.build(List.of(), 0);

        assertTrue(r.data().isEmpty());
        assertEquals(0, r.total());
    }

    // ─── PageResponse record ──────────────────────────────────────────────────

    @Test
    void pageResponse_expose_champs() {
        PageResponse<String> r = new PageResponse<>(List.of("x"), 2, 10, 100);
        assertEquals(List.of("x"), r.data());
        assertEquals(2,   r.page());
        assertEquals(10,  r.size());
        assertEquals(100, r.total());
    }
}
