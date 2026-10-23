package com.team.blog.notification.application;

import com.team.blog.shared.event.CommentCreated;
import com.team.blog.shared.event.CommentDeleted;
import com.team.blog.shared.event.ContentHidden;
import com.team.blog.shared.event.ReportsResolved;
import com.team.blog.shared.event.FollowRequestClosed;
import com.team.blog.shared.event.CommentLiked;
import com.team.blog.shared.event.CommentUnliked;
import com.team.blog.shared.event.FollowRequested;
import com.team.blog.shared.event.HandleChanged;
import com.team.blog.shared.event.InquiryReceived;
import com.team.blog.shared.event.InquiryReplied;
import com.team.blog.shared.event.MemberFollowed;
import com.team.blog.shared.event.MemberUnfollowed;
import com.team.blog.shared.event.PostLiked;
import com.team.blog.shared.event.MissionCompleted;
import com.team.blog.shared.event.ClubNotice;
import com.team.blog.shared.event.PostPublished;
import com.team.blog.shared.event.PostsPublishedTogether;
import com.team.blog.shared.event.PostUnliked;
import com.team.blog.shared.event.PostVisibilityChanged;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 업무 사건 → 알림(25 §4, 20 §4). 커밋 뒤에만 받고(롤백이면 없음), 처리는 {@link NotificationDispatcher}가 요청과 떼어 한다.
 * 사건에는 식별자만 있어 받는 사람·글 상태는 처리할 때 다시 읽는다. 신고 결과·숨김(022)은 운영 알림(끌 수 없음, 행동한 사람 없음)이다.
 */
@Component
public class NotificationListeners {

    private final NotificationWriter writer;
    private final NotificationDispatcher dispatcher;
    private final JdbcTemplate jdbc;

    public NotificationListeners(NotificationWriter writer, NotificationDispatcher dispatcher, JdbcTemplate jdbc) {
        this.writer = writer;
        this.dispatcher = dispatcher;
        this.jdbc = jdbc;
    }

    private long postAuthor(long postId) {
        return jdbc.queryForObject("SELECT author_id FROM post WHERE id = ?", Long.class, postId); // NOSONAR 오탐: Spring 조회 결과는 null이 아니다(빈 목록이나 예외)
    }

    /** 글 작성자에게 댓글, 답한 댓글의 작성자에게 답글. 둘이 같으면 답글만(FR-005). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CommentCreated e) {
        dispatcher.submit("comment " + e.commentId(), () -> {
            long postAuthor = postAuthor(e.postId());
            Long replyReceiver = e.parentId() == null ? null : e.replyTargetAuthorId();
            if (replyReceiver != null) {
                writer.single(replyReceiver, NotificationType.REPLY, e.postId(), e.commentId(), e.authorId());
            }
            if (replyReceiver == null || replyReceiver != postAuthor) {
                writer.single(postAuthor, NotificationType.COMMENT, e.postId(), e.commentId(), e.authorId());
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CommentDeleted e) {
        dispatcher.submit("comment deleted " + e.commentId(), () -> writer.deleteForComment(e.commentId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PostLiked e) {
        dispatcher.submit("like " + e.postId(), () -> writer.addToGroup(e.authorId(), NotificationType.LIKE,
                NotificationWriter.likeGroup(e.postId()), e.postId(), e.likerId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PostUnliked e) {
        dispatcher.submit("unlike " + e.postId(), () -> writer.removeFromGroup(postAuthor(e.postId()),
                NotificationWriter.likeGroup(e.postId()), e.likerId()));
    }

    /** 089 강성찬 개인 확장: 댓글 좋아요는 글 좋아요와 같은 LIKE 알림(같은 끄기 설정), 댓글마다 묶는다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CommentLiked e) {
        dispatcher.submit("comment like " + e.commentId(), () -> writer.addToGroup(e.authorId(), NotificationType.LIKE,
                NotificationWriter.commentLikeGroup(e.commentId()), e.postId(), e.commentId(), e.likerId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(CommentUnliked e) {
        dispatcher.submit("comment unlike " + e.commentId(), () -> writer.removeFromGroup(e.authorId(),
                NotificationWriter.commentLikeGroup(e.commentId()), e.likerId()));
    }

    /** 090 강성찬 개인 확장: 새 문의 → 관리자 모두에게(운영 알림). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(InquiryReceived e) {
        dispatcher.submit("inquiry " + e.inquiryId(), () -> jdbc.queryForList(
                "SELECT id FROM member WHERE role = 'ADMIN' AND withdrawn_at IS NULL", Long.class)
                .forEach(admin -> writer.operational(admin, NotificationType.INQUIRY, null, null, null, null)));
    }

    /** 099 강성찬 개인 확장: 문의 답장 → 보낸 회원에게. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(InquiryReplied e) {
        dispatcher.submit("inquiry replied " + e.inquiryId(), () -> writer.inquiryReplied(e.memberId(), e.inquiryId()));
    }

    /** 111 후속 강성찬 개인 확장: 함께 쓰기 초대 → 받은 사람, 참여 요청 → 만든 사람. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ClubNotice e) {
        dispatcher.submit("club " + e.clubId() + " " + e.receiverId(), () -> writer.club(e.receiverId(),
                e.invite() ? NotificationType.CLUB_INVITE : NotificationType.CLUB_REQUEST, e.clubId(), e.actorId()));
    }

    /** 강성찬 개인 확장: 블로그 주소 변경 → 팔로워에게. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(HandleChanged e) {
        dispatcher.submit("handle changed " + e.memberId(), () -> writer.handleChanged(e.memberId(), e.oldHandle()));
    }

    /** 057 강성찬 개인 확장: 오늘의 미션 글 → 친구에게. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(MissionCompleted e) {
        dispatcher.submit("mission done " + e.postId(), () -> writer.missionDone(e.postId(), e.authorId()));
    }

    /** 처음 전체 공개될 때 한 번(FR-006): 공개로 발행했거나, 공개 범위 변경으로 처음 공개됐을 때. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PostPublished e) {
        // 113 후속: 한 번에 여러 글을 발행하는 중이면 글마다 알리지 않고 끝나고 한 번(PostsPublishedTogether)
        if ("PUBLIC".equals(e.visibility()) && !PostsPublishedTogether.batching()) {
            dispatcher.submit("new post " + e.postId(), () -> writer.newPost(e.postId(), e.authorId()));
        }
    }

    /** 113 후속: 임시글 한 번에 발행 → 팔로워에게 "새 글 N편" 한 번(가장 나중에 발행한 글로 연결). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PostsPublishedTogether e) {
        long last = e.publicPostIds().get(e.publicPostIds().size() - 1);
        dispatcher.submit("new posts " + last, () -> writer.newPost(last, e.authorId(), e.publicPostIds().size()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PostVisibilityChanged e) {
        if (e.firstPublic()) {
            dispatcher.submit("new post " + e.postId(), () -> {
                if ("FRIENDS".equals(e.from())) {
                    writer.firstPublicCheer(e.postId(), e.authorId()); // 026 강성찬 개인 확장
                }
                writer.newPost(e.postId(), e.authorId());
            });
        }
    }

    /** 018: 새 팔로워 묶음(7일에 한 번), 언팔로우는 안 읽은 묶음에서만 뺀다(상대에게 알리지 않음). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(MemberFollowed e) {
        dispatcher.submit("follow " + e.followeeId(), () -> writer.addToGroup(e.followeeId(), NotificationType.FOLLOW,
                NotificationWriter.FOLLOW_GROUP, null, e.followerId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(MemberUnfollowed e) {
        dispatcher.submit("unfollow " + e.followeeId(), () -> writer.removeFromGroup(e.followeeId(),
                NotificationWriter.FOLLOW_GROUP, e.followerId()));
    }

    /** 073 비공개 계정 팔로우 요청: 받는 사람에게 묶음 알림. 수락·거절·취소는 그 사람을 묶음에서 뺄 뿐 새 알림이 없다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(FollowRequested e) {
        dispatcher.submit("follow-request " + e.targetId(), () -> writer.addToGroup(e.targetId(), NotificationType.FOLLOW_REQUEST,
                NotificationWriter.FOLLOW_REQUEST_GROUP, null, e.requesterId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(FollowRequestClosed e) {
        dispatcher.submit("follow-request-closed " + e.targetId(), () -> writer.removeFromGroup(e.targetId(),
                NotificationWriter.FOLLOW_REQUEST_GROUP, e.requesterId()));
    }

    /** 022: 숨김 → 작성자에게 1건(신고자·관리자 정보 없음). 댓글이면 그 댓글로 생긴 댓글·답글 알림을 지운다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ContentHidden e) {
        dispatcher.submit("hidden " + e.postId(), () -> {
            if (e.commentId() != null) {
                writer.deleteForComment(e.commentId());
            }
            writer.operational(e.authorId(), NotificationType.CONTENT_HIDDEN, e.postId(), e.commentId(), null, null);
        });
    }

    /** 022: 신고마다(=신고자마다) 처리 결과 1건. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ReportsResolved e) {
        dispatcher.submit("reports resolved", () -> e.reports().forEach(r ->
                writer.operational(r.reporterId(), NotificationType.REPORT_RESOLVED, null, null, r.reportId(), e.result())));
    }
}
