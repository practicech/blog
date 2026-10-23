package com.team.blog.notification.application;

import com.team.blog.account.application.AuthorDisplay;
import com.team.blog.post.application.CardDates;
import com.team.blog.post.application.FeedCursor;
import com.team.blog.post.application.PostAccessPolicy;
import com.team.blog.post.application.visibility.PostFacts;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.shared.security.CurrentUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 알림 목록·안 읽은 수(25 §5). 목록은 알림 + 행동한 사람 + 글·작성자 + 댓글을 한 번에 읽고(FR-030), 읽기 판정은 그 결과로
 * {@link PostAccessPolicy}에 맡긴다. 정렬 {@code updated_at DESC, id DESC}, 커서 {@code (updated_at, id)}.
 */
@Service
public class NotificationQuery {

    public static final String UNAVAILABLE = "볼 수 없는 글이에요";

    private final JdbcTemplate jdbc;
    private final PostAccessPolicy accessPolicy;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationQuery(JdbcTemplate jdbc, PostAccessPolicy accessPolicy, NotificationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    public long unreadCount(long memberId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND read_at IS NULL", Long.class,
                memberId);
        return n == null ? 0 : n;
    }

    public NotificationPage page(CurrentUser user, String cursor, int size) {
        Optional<FeedCursor> after = FeedCursor.decode(cursor);
        int limit = Math.max(1, Math.min(size, properties.pageSize()));
        List<Object> args = new ArrayList<>();
        args.add(user.memberId());
        String where = "";
        if (after.isPresent()) {
            where = " AND (n.updated_at, n.id) < (?, ?)";
            args.add(Timestamp.from(after.get().firstPublicAt()));
            args.add(after.get().id());
        }
        args.add(limit + 1);
        List<NotificationItem> rows = select(user, where, args);
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            NotificationItem last = rows.get(limit - 1);
            next = new FeedCursor(last.updatedAt(), last.id()).encode();
        }
        return new NotificationPage(rows, next);
    }

    /** 본인 알림 하나(읽음 처리 뒤 이동 주소). */
    public Optional<NotificationItem> one(CurrentUser user, long id) {
        List<Object> args = new ArrayList<>(List.of(user.memberId(), id, 1));
        return select(user, " AND n.id = ?", args).stream().findFirst();
    }

    private List<NotificationItem> select(CurrentUser user, String where, List<Object> args) {
        Instant now = clock.instant();
        return jdbc.query("""
                SELECT n.id, n.type, n.read_at, n.updated_at, n.actor_count, n.result, n.post_id, n.comment_id, n.inquiry_id, n.club_id, wc.name AS club_name, n.old_handle,
                       a.handle AS a_handle, a.nickname AS a_nickname, a.profile_image_url AS a_picture, a.withdrawn_at AS a_withdrawn,
                       n.last_actor_id,
                       p.title, p.status, p.visibility, p.author_id, p.deleted_at AS p_deleted, p.hidden_at AS p_hidden,
                       pm.handle AS p_handle, pm.withdrawn_at AS p_author_withdrawn,
                       c.content, c.deleted_at AS c_deleted, c.hidden_at AS c_hidden,
                       (SELECT s.title FROM series_post sp JOIN series s ON s.id = sp.series_id WHERE sp.post_id = n.post_id) AS series_title
                FROM notification n
                LEFT JOIN member a ON a.id = n.last_actor_id
                LEFT JOIN post p ON p.id = n.post_id
                LEFT JOIN member pm ON pm.id = p.author_id
                LEFT JOIN comment c ON c.id = n.comment_id
                LEFT JOIN writing_club wc ON wc.id = n.club_id
                WHERE n.receiver_id = ?""" + where + """

                ORDER BY n.updated_at DESC, n.id DESC
                LIMIT ?
                """, (rs, i) -> item(rs, user, now), args.toArray());
    }

    /** 행동한 사람: 화면용 정보(탈퇴·없음이면 null 또는 탈퇴 표시)와 문장에 넣을 짧은 이름. */
    private record ActorPart(NotificationItem.Actor actor, String name) {
    }

    /** 알림이 가리키는 글: 제목·주소와 받는 사람이 읽을 수 있는지, 숨겨진 본인 글인지. */
    private record PostPart(String title, String url, boolean readable, boolean ownHidden) {
    }

    /** 화면에 보일 글 참조·미리보기·문장·이동 주소. */
    private record Body(NotificationItem.PostRef post, String preview, String message, String url) {
    }

    private NotificationItem item(ResultSet rs, CurrentUser user, Instant now) throws SQLException {
        NotificationType type = NotificationType.valueOf(rs.getString("type"));
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        boolean read = rs.getTimestamp("read_at") != null;
        ActorPart actor = actor(rs);
        int count = rs.getInt("actor_count");
        Integer others = (type == NotificationType.LIKE || type == NotificationType.FOLLOW || type == NotificationType.FRIEND_REQUEST || type == NotificationType.FOLLOW_REQUEST) ? Math.max(0, count - 1) : null;
        Body body = body(rs, type, postPart(rs, user), actor.name(), others);
        boolean noActor = type == NotificationType.REPORT_RESOLVED || type == NotificationType.CONTENT_HIDDEN || type == NotificationType.INQUIRY
                || type == NotificationType.INQUIRY_REPLIED;
        return new NotificationItem(rs.getLong("id"), type.name(), read, updatedAt, CardDates.label(updatedAt, now),
                noActor ? null : actor.actor(), others, body.post(), body.preview(), rs.getString("result"), body.message(), body.url());
    }

    private static ActorPart actor(ResultSet rs) throws SQLException {
        if (rs.getObject("last_actor_id") == null) {
            return new ActorPart(null, AuthorDisplay.WITHDRAWN_LABEL);
        }
        Timestamp w = rs.getTimestamp("a_withdrawn");
        AuthorDisplay d = AuthorDisplay.of(rs.getString("a_handle"), rs.getString("a_nickname"),
                w == null ? null : w.toInstant(), rs.getString("a_picture"));
        NotificationItem.Actor actor = d.withdrawn() ? new NotificationItem.Actor(AuthorDisplay.WITHDRAWN_LABEL, null, null, true)
                : new NotificationItem.Actor(d.nickname(), d.handle(), d.profileImageUrl(), false);
        return new ActorPart(actor, d.shortLabel());
    }

    private PostPart postPart(ResultSet rs, CurrentUser user) throws SQLException {
        long postId = rs.getLong("post_id");
        boolean noPost = rs.wasNull();
        String status = rs.getString("status");
        if (noPost || status == null) {
            return new PostPart(null, null, false, false);
        }
        boolean authorWithdrawn = rs.getTimestamp("p_author_withdrawn") != null;
        boolean deleted = rs.getTimestamp("p_deleted") != null;
        boolean hidden = rs.getTimestamp("p_hidden") != null;
        long authorId = rs.getLong("author_id");
        PostFacts facts = new PostFacts(postId, authorId, PostStatus.valueOf(status),
                rs.getString("visibility"), authorWithdrawn, false);
        boolean canRead = !deleted && PostStatus.PUBLISHED.name().equals(status)
                && accessPolicy.canRead(Optional.of(user), facts);
        return new PostPart(rs.getString("title"), com.team.blog.shared.web.BlogPaths.of(rs.getString("p_handle")) + "/posts/" + postId,
                canRead && !hidden, canRead && hidden && authorId == user.memberId());
    }

    private Body body(ResultSet rs, NotificationType type, PostPart p, String actorName, Integer others) throws SQLException {
        Long commentId = rs.getObject("comment_id") == null ? null : rs.getLong("comment_id");
        String commentUrl = commentId == null || p.url() == null ? p.url() : p.url() + "?comment=" + commentId + "#comment-" + commentId;
        return switch (type) {
            case COMMENT -> onComment(rs, p, commentUrl, pv -> actorName + "님이 「" + p.title() + "」에 댓글을 남겼어요: " + pv);
            case REPLY -> onComment(rs, p, commentUrl, pv -> actorName + "님이 회원님의 댓글에 답글을 남겼어요: " + pv);
            // 089: 댓글 좋아요
            case LIKE -> commentId != null
                    ? onComment(rs, p, commentUrl, pv -> who(actorName, others) + " 회원님의 댓글을 좋아해요: " + pv)
                    : onPost(p, who(actorName, others) + " 「" + p.title() + "」을(를) 좋아해요");
            case FIRST_PUBLIC -> onPost(p, actorName + "님이 친구에게만 보여 주던 「" + p.title() + "」을(를) 모두에게 공개했어요");
            case MISSION_DONE -> onPost(p, actorName + "님이 오늘의 미션 글을 올렸어요: 「" + p.title() + "」");
            // 108 강성찬 개인 확장: 시리즈에 든 글이면 "새 편"으로(읽을 때 시리즈를 보므로 발행 뒤 넣어도 맞다)
            // 113 후속: 한 번에 여러 편(actor_count = 함께 올린 글 수)
            case NEW_POST -> rs.getInt("actor_count") > 1
                    ? onPost(p, actorName + "님이 새 글 " + rs.getInt("actor_count") + "편을 올렸어요: 「" + p.title() + "」 외 " + (rs.getInt("actor_count") - 1) + "편")
                    : onPost(p, rs.getString("series_title") == null ? actorName + "님이 새 글을 올렸어요: 「" + p.title() + "」"
                    : actorName + "님의 시리즈 「" + rs.getString("series_title") + "」 새 편: 「" + p.title() + "」");
            case FOLLOW -> new Body(null, null, who(actorName, others) + " 회원님을 팔로우해요", "/me/followers");
            case FRIEND_REQUEST -> new Body(null, null, who(actorName, others) + " 친구 요청을 보냈어요", "/settings/friends");
            case FOLLOW_REQUEST -> new Body(null, null, who(actorName, others) + " 팔로우를 요청했어요", "/settings/privacy");
            case INQUIRY -> new Body(null, null, "새 문의가 왔어요. 문의함에서 확인해 주세요", "/admin/inquiries");
            case INQUIRY_REPLIED -> new Body(null, null, "보내신 문의에 답장이 왔어요", "/contact/mine/" + rs.getLong("inquiry_id"));
            case CLUB_INVITE -> new Body(null, null, actorName + "님이 함께 쓰기 「" + rs.getString("club_name") + "」에 초대했어요",
                    "/missions/clubs/" + rs.getLong("club_id"));
            case CLUB_REQUEST -> new Body(null, null, actorName + "님이 함께 쓰기 「" + rs.getString("club_name") + "」에 참여를 요청했어요",
                    "/missions/clubs/" + rs.getLong("club_id"));
            case HANDLE_CHANGED -> new Body(null, null, actorName + "님이 블로그 주소를 바꿨어요: "
                    + rs.getString("old_handle") + " → " + rs.getString("a_handle"),
                    rs.getString("a_handle") == null ? null : com.team.blog.shared.web.BlogPaths.of(rs.getString("a_handle")));
            case REPORT_RESOLVED -> new Body(null, null, "ACTION_TAKEN".equals(rs.getString("result"))
                    ? "신고하신 내용을 검토해 조치했어요. 알려 주셔서 고마워요"
                    : "신고하신 내용을 검토했지만 운영 정책 위반은 아니었어요", null);
            case CONTENT_HIDDEN -> contentHidden(p, commentId, commentUrl);
            default -> new Body(null, null, "", null);
        };
    }

    private static Body unavailable() {
        return new Body(new NotificationItem.PostRef(null, null, true), null, UNAVAILABLE, null);
    }

    private static Body onPost(PostPart p, String message) {
        return p.readable() ? new Body(new NotificationItem.PostRef(p.title(), p.url(), null), null, message, p.url()) : unavailable();
    }

    private Body onComment(ResultSet rs, PostPart p, String commentUrl, UnaryOperator<String> message) throws SQLException {
        String content = rs.getString("content");
        boolean commentOk = content != null && rs.getTimestamp("c_deleted") == null && rs.getTimestamp("c_hidden") == null;
        if (!p.readable() || !commentOk) {
            return unavailable();
        }
        String preview = preview(content);
        return new Body(new NotificationItem.PostRef(p.title(), p.url(), null), preview, message.apply(preview), commentUrl);
    }

    private static Body contentHidden(PostPart p, Long commentId, String commentUrl) {
        boolean visible = p.readable() || p.ownHidden();
        if (commentId != null) {
            return new Body(null, null, "회원님의 댓글이 운영 정책에 따라 숨겨졌어요", visible ? commentUrl : null);
        }
        if (visible) {
            return new Body(new NotificationItem.PostRef(p.title(), p.url(), null), null,
                    "회원님의 글「" + p.title() + "」이(가) 운영 정책에 따라 숨겨졌어요", p.url());
        }
        return new Body(null, null, "회원님의 글이 운영 정책에 따라 숨겨졌어요", null);
    }

    private static String who(String actorName, Integer others) {
        return others == null || others == 0 ? actorName + "님이" : actorName + "님 외 " + others + "명이";
    }

    private String preview(String content) {
        String flat = content.replaceAll("\\s+", " ").strip();
        int max = properties.previewLength();
        if (flat.codePointCount(0, flat.length()) <= max) {
            return flat;
        }
        return flat.substring(0, flat.offsetByCodePoints(0, max)) + "…";
    }
}
