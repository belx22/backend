package cm.afriland.titres.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cm.afriland.titres.error.ApiException;
import cm.afriland.titres.security.AuthUser;
import cm.afriland.titres.support.PageResponse;
import cm.afriland.titres.support.Pagination;

/**
 * Module 8 — Notifications in-app (CSFT §M2-S03).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final String COL_MESSAGE = "message";

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record NotificationResponse(UUID id, String type, String titre, String message,
                                String reference, boolean lu, OffsetDateTime createdAt) {
    }

    private static final RowMapper<NotificationResponse> MAPPER = (rs, n) -> new NotificationResponse(
            rs.getObject("id", UUID.class),
            rs.getString("type"),
            rs.getString("titre"),
            rs.getString(COL_MESSAGE),
            rs.getString("reference"),
            rs.getBoolean("lu"),
            rs.getObject("created_at", OffsetDateTime.class));

    /** {@code GET /notifications} — notifications de l'utilisateur connecte (paginees). */
    @GetMapping
    public PageResponse<NotificationResponse> list(AuthUser user,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        Pagination pg = Pagination.of(page, size);
        List<NotificationResponse> data = jdbc.query(
                "SELECT id, type, titre, message, reference, lu, created_at FROM notifications "
                        + "WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                MAPPER, user.id(), pg.limit(), pg.offset());
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ?", Long.class, user.id());
        return pg.build(data, total);
    }

    /** {@code GET /notifications/unread-count} — nombre de notifications non lues. */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(AuthUser user) {
        long count = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND lu = FALSE",
                Long.class, user.id());
        return Map.of("count", count);
    }

    /** {@code POST /notifications/:id/read} — marque une notification comme lue. */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(AuthUser user, @PathVariable UUID id) {
        int affected = jdbc.update(
                "UPDATE notifications SET lu = TRUE WHERE id = ? AND user_id = ?", id, user.id());
        if (affected == 0) {
            throw ApiException.notFound("Notification introuvable.");
        }
        return Map.of(COL_MESSAGE, "Notification marquée comme lue.");
    }

    /** {@code POST /notifications/read-all} — marque toutes les notifications comme lues. */
    @PostMapping("/read-all")
    public Map<String, Object> markAllRead(AuthUser user) {
        jdbc.update("UPDATE notifications SET lu = TRUE WHERE user_id = ? AND lu = FALSE", user.id());
        return Map.of(COL_MESSAGE, "Toutes les notifications sont marquées comme lues.");
    }
}
