package com.team.blog.notification.application;

import com.team.blog.post.application.PostAccessPolicy;
import com.team.blog.post.application.visibility.PostFacts;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.shared.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 저장(25 §4). 만들기 전에 항상 ① 본인 행동 ② 받는 사람 탈퇴 유예 ③ 행동한 사람 탈퇴 유예 ④ 끈 종류(운영 알림 제외)
 * ⑤ 글을 읽을 수 있는지를 처리 시점의 최신 상태로 확인한다(FR-008). 묶음은 유일 인덱스 + {@code notification_actor} PK로
 * 동시에 와도 하나·한 번씩(FR-012).
 */
@Component
public class NotificationWriter {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PostAccessPolicy accessPolicy;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationWriter(JdbcTemplate jdbc, TransactionTemplate tx, PostAccessPolicy accessPolicy,
                              NotificationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    private record MemberState(String role, boolean withdrawn) {
    }

    private Optional<MemberState> member(long id) {
        return jdbc.query("SELECT role, withdrawn_at IS NOT NULL AS w FROM member WHERE id = ?",
                (rs, n) -> new MemberState(rs.getString("role"), rs.getBoolean("w")), id).stream().findFirst();
    }

    /** ①~⑤. {@code actorId}가 없으면(운영 알림) ①·③을 건너뛴다. */
    boolean eligible(long receiverId, Long actorId, NotificationType type, Long postId) {
        if (actorId != null && actorId == receiverId) {
            return false;
        }
        Optional<MemberState> receiver = member(receiverId);
        if (receiver.isEmpty() || receiver.get().withdrawn()) {
            return false;
        }
        if (actorId != null && member(actorId).map(MemberState::withdrawn).orElse(true)) {
            return false;
        }
        if (type.isMutable() && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notification_mute WHERE member_id = ? AND type = ?)", Boolean.class,
                receiverId, type.name()))) {
            return false;
        }
        if (type.needsReadablePost()) {
            return postId != null && readable(new CurrentUser(receiverId, receiver.get().role()), postId);
        }
        return true;
    }

    private boolean readable(CurrentUser viewer, long postId) {
        record Row(long authorId, String status, String visibility, boolean authorWithdrawn, boolean hidden) {
        }
        return jdbc.query("""
                SELECT p.author_id, p.status, p.visibility, m.withdrawn_at IS NOT NULL AS w, p.hidden_at IS NOT NULL AS h
                FROM post p JOIN member m ON m.id = p.author_id WHERE p.id = ? AND p.deleted_at IS NULL
                """, (rs, n) -> new Row(rs.getLong("author_id"), rs.getString("status"), rs.getString("visibility"),
                rs.getBoolean("w"), rs.getBoolean("h")), postId).stream().findFirst()
                .filter(r -> !r.hidden() && "PUBLISHED".equals(r.status()))
                .filter(r -> accessPolicy.canRead(Optional.of(viewer), new PostFacts(postId, r.authorId(),
                        PostStatus.valueOf(r.status()), r.visibility(), r.authorWithdrawn(), r.hidden())))
                .isPresent();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    /** 하나짜리 알림(댓글·답글). */
    public boolean single(long receiverId, NotificationType type, long postId, Long commentId, long actorId) {
        if (!eligible(receiverId, actorId, type, postId)) {
            return false;
        }
        Timestamp now = now();
        jdbc.update("""
                INSERT INTO notification (receiver_id, type, post_id, comment_id, last_actor_id, actor_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                """, receiverId, type.name(), postId, commentId, actorId, now, now);
        return true;
    }

    /** 운영 알림(신고 결과·숨김): 끌 수 없고 행동한 사람을 담지 않는다(FR-021). */
    public boolean operational(long receiverId, NotificationType type, Long postId, Long commentId, Long reportId,
                               String result) {
        if (!eligible(receiverId, null, type, postId)) {
            return false;
        }
        Timestamp now = now();
        jdbc.update("""
                INSERT INTO notification (receiver_id, type, post_id, comment_id, report_id, result, actor_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, receiverId, type.name(), postId, commentId, reportId, result, now, now);
        return true;
    }

    /** 099 문의 답장 알림: 운영 알림처럼 끌 수 없고, 그 문의로 가게 번호를 담는다. */
    public boolean inquiryReplied(long receiverId, long inquiryId) {
        if (!eligible(receiverId, null, NotificationType.INQUIRY_REPLIED, null)) {
            return false;
        }
        Timestamp now = now();
        jdbc.update("""
                INSERT INTO notification (receiver_id, type, inquiry_id, actor_count, created_at, updated_at)
                VALUES (?, 'INQUIRY_REPLIED', ?, 0, ?, ?)
                """, receiverId, inquiryId, now, now);
        return true;
    }

    /** 111 후속: 함께 쓰기 초대·참여 요청 알림. 그 함께 쓰기 번호를 담는다(지우면 함께 사라짐). */
    public boolean club(long receiverId, NotificationType type, long clubId, long actorId) {
        if (!eligible(receiverId, actorId, type, null)) {
            return false;
        }
        Timestamp now = now();
        jdbc.update("""
                INSERT INTO notification (receiver_id, type, club_id, last_actor_id, actor_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """, receiverId, type.name(), clubId, actorId, now, now);
        return true;
    }

    /** 주소 변경: 팔로워 모두에게 한 건씩(새 글 알림처럼 한 번에). */
    public int handleChanged(long memberId, String oldHandle) {
        if (member(memberId).map(MemberState::withdrawn).orElse(true)) {
            return 0;
        }
        Timestamp now = now();
        return jdbc.update("""
                INSERT INTO notification (receiver_id, type, old_handle, last_actor_id, actor_count, created_at, updated_at)
                SELECT f.follower_id, 'HANDLE_CHANGED', ?, ?, 1, ?, ?
                FROM follow f JOIN member r ON r.id = f.follower_id
                WHERE f.followee_id = ? AND r.withdrawn_at IS NULL
                """, oldHandle, memberId, now, now, memberId);
    }

    public static String likeGroup(long postId) {
        return "LIKE:post:" + postId;
    }

    public static String commentLikeGroup(long commentId) {
        return "LIKE:comment:" + commentId;
    }

    public static final String FOLLOW_GROUP = "FOLLOW";

    /** 팔로우 요청 묶음(073). */
    public static final String FOLLOW_REQUEST_GROUP = "FOLLOW_REQUEST";

    /**
     * 묶음에 더하기(25 §4-2). 좋아요는 이 글로 들어간 적이 있으면(읽음 무관), 팔로우는 7일 안에 들어간 적이 있으면 넣지 않는다.
     *
     * @return 사람이 새로 더해졌으면 true
     */
    public boolean addToGroup(long receiverId, NotificationType type, String groupKey, Long postId, long actorId) {
        return addToGroup(receiverId, type, groupKey, postId, null, actorId);
    }

    /** {@code commentId}: 089 댓글 좋아요 묶음이면 그 댓글. */
    public boolean addToGroup(long receiverId, NotificationType type, String groupKey, Long postId, Long commentId,
                              long actorId) {
        if (!eligible(receiverId, actorId, type, postId)) {
            return false;
        }
        Instant now = clock.instant();
        Instant since = (type == NotificationType.FOLLOW || type == NotificationType.FRIEND_REQUEST || type == NotificationType.FOLLOW_REQUEST) ? now.minus(properties.followDedupe()) : Instant.EPOCH;
        return Boolean.TRUE.equals(tx.execute(status -> {
            Boolean seen = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM notification_actor na JOIN notification n ON n.id = na.notification_id
                                   WHERE n.receiver_id = ? AND n.group_key = ? AND na.actor_id = ? AND na.created_at >= ?)
                    """, Boolean.class, receiverId, groupKey, actorId, Timestamp.from(since));
            if (Boolean.TRUE.equals(seen)) {
                return false;
            }
            Long id = jdbc.queryForObject("""
                    INSERT INTO notification (receiver_id, type, post_id, comment_id, group_key, actor_count, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                    ON CONFLICT (receiver_id, group_key) WHERE read_at IS NULL AND group_key IS NOT NULL
                    DO UPDATE SET updated_at = notification.updated_at
                    RETURNING id
                    """, Long.class, receiverId, type.name(), postId, commentId, groupKey, Timestamp.from(now), Timestamp.from(now));
            int added = jdbc.update("""
                    INSERT INTO notification_actor (notification_id, actor_id, created_at) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, id, actorId, Timestamp.from(now));
            if (added == 1) {
                jdbc.update("""
                        UPDATE notification SET actor_count = actor_count + 1, last_actor_id = ?, updated_at = ? WHERE id = ?
                        """, actorId, Timestamp.from(now), id);
            }
            return added == 1;
        }));
    }

    /** 취소(좋아요 취소·언팔로우): 안 읽은 묶음에서만 빼고 다시 계산, 0명이면 삭제(FR-013). */
    public void removeFromGroup(long receiverId, String groupKey, long actorId) {
        tx.executeWithoutResult(status -> {
            List<Long> ids = jdbc.queryForList("""
                    DELETE FROM notification_actor na USING notification n
                    WHERE n.id = na.notification_id AND n.receiver_id = ? AND n.group_key = ? AND n.read_at IS NULL
                      AND na.actor_id = ?
                    RETURNING na.notification_id
                    """, Long.class, receiverId, groupKey, actorId);
            ids.forEach(this::recount);
        });
    }

    /** 인원·대표 이름(남은 사람 중 가장 최근)을 다시 계산, 0명이면 삭제. */
    void recount(long notificationId) {
        jdbc.update("""
                UPDATE notification n SET
                    actor_count = (SELECT count(*) FROM notification_actor a WHERE a.notification_id = n.id),
                    last_actor_id = (SELECT a.actor_id FROM notification_actor a WHERE a.notification_id = n.id
                                     ORDER BY a.created_at DESC, a.actor_id DESC LIMIT 1)
                WHERE n.id = ?
                """, notificationId);
        jdbc.update("DELETE FROM notification WHERE id = ? AND actor_count = 0", notificationId);
    }

    /** 새 글: 팔로워에게 한 문장으로(25 §4-1). 처리 시점에 글이 공개·발행 상태인지 다시 본다. */
    public int newPost(long postId, long authorId) {
        return newPost(postId, authorId, 1);
    }

    /** 113 후속: {@code count}편을 한 번에 올렸으면 한 건에 편 수를 적는다(새 글 알림의 actor_count = 함께 올린 글 수). */
    public int newPost(long postId, long authorId, int count) {
        if (member(authorId).map(MemberState::withdrawn).orElse(true)) {
            return 0;
        }
        Boolean open = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM post p WHERE p.id = ? AND p.author_id = ? AND p.status = 'PUBLISHED'
                               AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL AND p.hidden_at IS NULL)
                """, Boolean.class, postId, authorId);
        if (!Boolean.TRUE.equals(open)) {
            return 0;
        }
        Timestamp now = now();
        return jdbc.update("""
                INSERT INTO notification (receiver_id, type, post_id, last_actor_id, actor_count, created_at, updated_at)
                SELECT f.follower_id, 'NEW_POST', ?, ?, ?, ?, ?
                FROM follow f JOIN member r ON r.id = f.follower_id
                WHERE f.followee_id = ? AND r.withdrawn_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM notification_mute nm WHERE nm.member_id = f.follower_id AND nm.type = 'NEW_POST')
                  AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.receiver_id = f.follower_id AND n.post_id = ?
                                  AND n.type IN ('FIRST_PUBLIC', 'MISSION_DONE'))
                """, postId, authorId, Math.max(1, count), now, now, authorId, postId);
    }

    /**
     * 첫 공개 응원(026, 강성찬 개인 확장): 친구 공개였던 글이 처음 전체 공개되면 글쓴이가 고른 친구(069)에게 1건씩. "새 글" 알림을 끈 친구는 받지 않고,
     * 같은 글의 새 글 알림은 이것이 대신한다({@link #newPost}보다 먼저 부른다).
     */
    public int firstPublicCheer(long postId, long authorId) {
        if (member(authorId).map(MemberState::withdrawn).orElse(true)) {
            return 0;
        }
        Timestamp now = now();
        return jdbc.update("""
                INSERT INTO notification (receiver_id, type, post_id, last_actor_id, actor_count, created_at, updated_at)
                SELECT r.id, 'FIRST_PUBLIC', p.id, ?, 1, ?, ?
                FROM post p
                JOIN close_friend cf ON cf.owner_id = p.author_id
                JOIN member r ON r.id = cf.friend_id
                WHERE p.id = ? AND p.author_id = ? AND p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC'
                  AND p.deleted_at IS NULL AND p.hidden_at IS NULL AND r.withdrawn_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM notification_mute nm WHERE nm.member_id = r.id AND nm.type = 'NEW_POST')
                """, authorId, now, now, postId, authorId);
    }

    /**
     * 오늘의 미션 글(057, 강성찬 개인 확장): 전체 공개·친구 공개로 처음 발행되면 글쓴이가 고른 친구(069)에게 1건씩. "새 글" 알림을 끈 친구와
     * 같은 글로 새 글 알림을 이미 받은 친구는 받지 않는다(새 글 알림도 이 알림을 받은 사람은 건너뛴다).
     */
    public int missionDone(long postId, long authorId) {
        if (member(authorId).map(MemberState::withdrawn).orElse(true)) {
            return 0;
        }
        Timestamp now = now();
        return jdbc.update("""
                INSERT INTO notification (receiver_id, type, post_id, last_actor_id, actor_count, created_at, updated_at)
                SELECT r.id, 'MISSION_DONE', p.id, ?, 1, ?, ?
                FROM post p
                JOIN close_friend cf ON cf.owner_id = p.author_id
                JOIN member r ON r.id = cf.friend_id
                WHERE p.id = ? AND p.author_id = ? AND p.status = 'PUBLISHED' AND p.visibility IN ('PUBLIC', 'FRIENDS')
                  AND p.deleted_at IS NULL AND p.hidden_at IS NULL AND r.withdrawn_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM notification_mute nm WHERE nm.member_id = r.id AND nm.type = 'NEW_POST')
                  AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.receiver_id = r.id AND n.post_id = p.id
                                  AND n.type IN ('NEW_POST', 'MISSION_DONE'))
                """, authorId, now, now, postId, authorId);
    }

    /** 댓글이 지워지거나(자리만 남음 포함) 숨겨지면 그 댓글로 생긴 댓글·답글 알림 삭제(FR-014). */
    public int deleteForComment(long commentId) {
        return jdbc.update("DELETE FROM notification WHERE comment_id = ? AND type IN ('COMMENT', 'REPLY')", commentId);
    }
}
