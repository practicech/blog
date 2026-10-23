package com.team.blog.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.blog.notification.application.NotificationCleanup;
import com.team.blog.notification.application.NotificationType;
import com.team.blog.notification.application.NotificationWriter;
import com.team.blog.shared.event.PostPublished;
import com.team.blog.support.IntegrationTestBase;
import com.team.blog.support.TestAuth;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** 017 인앱 알림. */
class NotificationIT extends IntegrationTestBase {

    private static final Instant T = Instant.parse("2026-10-08T00:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NotificationWriter writer;

    @Autowired
    NotificationCleanup cleanup;

    @Autowired
    ApplicationEventPublisher publisher;

    private long member(String handle) {
        return members.localMember(handle, handle.substring(0, Math.min(10, handle.length())), handle + "@example.com",
                "Blog#2026ok", true);
    }

    private long comment(long who, long post, String content, Long replyTo) throws Exception {
        String body = replyTo == null ? "{\"content\":\"" + content + "\"}"
                : "{\"content\":\"" + content + "\",\"replyToCommentId\":" + replyTo + "}";
        MvcResult r = mockMvc.perform(post("/api/posts/{id}/comments", post).with(csrf()).with(TestAuth.member(who))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        return Long.parseLong(r.getResponse().getContentAsString().replaceAll("^\\{\"id\":(\\d+).*$", "$1"));
    }

    private List<Map<String, Object>> rows(long receiver) {
        return jdbc.queryForList("SELECT * FROM notification WHERE receiver_id = ? ORDER BY id", receiver);
    }

    private String list(long who) throws Exception {
        return mockMvc.perform(get("/api/notifications").with(TestAuth.member(who))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void commentAndReplyRecipientsSelfExclusionAndDeletion() throws Exception {
        clock.set(T);
        long a = member("ntauthor");
        long b = member("ntb");
        long c = member("ntc");
        long d = member("ntd");
        long p = posts.published(a, "제목글", "본문", 1, T);
        long bc = comment(b, p, "B의 댓글입니다", null);
        assertThat(rows(a)).singleElement().satisfies(n -> assertThat(n).containsEntry("type", "COMMENT")
                .containsEntry("comment_id", bc)
                .containsEntry("last_actor_id", b));
        long cc = comment(c, p, "C의 답글", bc);
        assertThat(rows(b)).singleElement().satisfies(n -> assertThat(n).containsEntry("type", "REPLY"));
        assertThat(rows(a)).hasSize(2);
        comment(d, p, "D가 C에게", cc);
        assertThat(rows(c)).singleElement().satisfies(n -> assertThat(n).containsEntry("type", "REPLY"));
        assertThat(rows(b)).hasSize(1);
        // 답글 대상이 글 작성자면 답글 하나만
        long ac = comment(a, p, "작성자 댓글", null);
        assertThat(rows(a)).hasSize(3);
        comment(b, p, "작성자에게 답글", ac);
        assertThat(rows(a)).hasSize(4).last().satisfies(n -> assertThat(n).containsEntry("type", "REPLY"));
        // 자기 댓글에 답글: 알림 없음
        comment(a, p, "내가 나에게", ac);
        assertThat(rows(a)).hasSize(4);

        String json = list(a);
        assertThat(json).contains("ntb님이 회원님의 댓글에 답글을 남겼어요: 작성자에게 답글")
                .contains("ntb님이 「제목글」에 댓글을 남겼어요: B의 댓글입니다")
                .contains("/ntauthor/posts/" + p + "?comment=" + bc + "#comment-" + bc);
        // 댓글 삭제(답글 있어 자리만 남음)도 알림 삭제
        mockMvc.perform(delete("/api/comments/{id}", bc).with(csrf()).with(TestAuth.member(b))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE comment_id = ?", Integer.class, bc)).isZero();
    }

    @Test
    void readUnreadCountDeleteAndOwnership() throws Exception {
        clock.set(T);
        long a = member("ntread");
        long b = member("ntreadb");
        long admin = member("ntadmin");
        long p = posts.published(a, "글", "본문", 1, T);
        for (int i = 0; i < 3; i++) {
            clock.set(T.plusSeconds(i));
            comment(b, p, "댓글" + i, null);
        }
        mockMvc.perform(get("/api/notifications/unread-count").with(TestAuth.member(a)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.count").value(3));
        List<Long> ids = jdbc.queryForList("SELECT id FROM notification WHERE receiver_id = ? ORDER BY id", Long.class, a);
        mockMvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        for (var req : List.of(patch("/api/notifications/{id}/read", ids.get(0)), delete("/api/notifications/{id}", ids.get(0)))) {
            mockMvc.perform(req.with(csrf()).with(TestAuth.member(b))).andExpect(status().isNotFound());
            mockMvc.perform(req.with(csrf()).with(TestAuth.admin(admin))).andExpect(status().isNotFound());
        }
        mockMvc.perform(patch("/api/notifications/{id}/read", ids.get(0)).with(csrf()).with(TestAuth.member(a)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/notifications/unread-count").with(TestAuth.member(a))).andExpect(jsonPath("$.count").value(2));
        // 최신순, 커서로 이어도 중복 없음
        String first = mockMvc.perform(get("/api/notifications?size=2").with(TestAuth.member(a)))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(2))).andExpect(jsonPath("$.items[1].id").value(ids.get(1)))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andReturn().getResponse().getContentAsString();
        String cursor = first.replaceAll("^.*\"nextCursor\":\"([^\"]+)\".*$", "$1");
        mockMvc.perform(get("/api/notifications?size=2&cursor=" + cursor).with(TestAuth.member(a)))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(ids.get(0)))
                .andExpect(jsonPath("$.items[0].read").value(true)).andExpect(jsonPath("$.nextCursor").doesNotExist());
        mockMvc.perform(post("/api/notifications/read-all").with(csrf()).with(TestAuth.member(a)))
                .andExpect(jsonPath("$.updated").value(2));
        mockMvc.perform(delete("/api/notifications/{id}", ids.get(1)).with(csrf()).with(TestAuth.member(a)))
                .andExpect(status().isNoContent());
        assertThat(rows(a)).hasSize(2);
        // 인증 전 회원도 본인 알림은 본다
        long unverified = members.localMember("ntunv", "ntunv", "ntunv@example.com", "Blog#2026ok", false);
        mockMvc.perform(get("/api/notifications").with(TestAuth.member(unverified))).andExpect(status().isOk());
        // 화면: 폼으로 읽음 후 이동
        mockMvc.perform(post("/notifications/{id}/open", ids.get(2)).with(csrf()).with(TestAuth.member(a)))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/ntread/posts/" + p + "?comment="
                        + rows(a).get(1).get("comment_id") + "#comment-" + rows(a).get(1).get("comment_id")));
        String page = mockMvc.perform(get("/notifications").with(TestAuth.member(a))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("모두 읽음").contains("알림 삭제");
        mockMvc.perform(get("/notifications")).andExpect(status().isSeeOther());
    }

    @Test
    void likesGroupConcurrentlyAndUngroupOnlyUnread() throws Exception {
        clock.set(T);
        long a = member("ntlike");
        long p = posts.published(a, "좋은글", "본문", 1, T);
        List<Long> likers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            likers.add(member("ntliker" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (long liker : likers) {
            futures.add(pool.submit(() -> {
                start.await();
                return mockMvc.perform(put("/api/posts/{id}/like", p).with(csrf()).with(TestAuth.member(liker)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> f : futures) {
            assertThat(f.get()).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(rows(a)).singleElement().satisfies(n -> assertThat(n).containsEntry("actor_count", 10)
                .containsEntry("group_key", "LIKE:post:" + p));
        assertThat(list(a)).contains("외 9명이 「좋은글」을(를) 좋아해요").contains("\"othersCount\":9");
        long x = likers.get(0);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(delete("/api/posts/{id}/like", p).with(csrf()).with(TestAuth.member(x))).andExpect(status().isOk());
            mockMvc.perform(put("/api/posts/{id}/like", p).with(csrf()).with(TestAuth.member(x))).andExpect(status().isOk());
        }
        assertThat(rows(a)).singleElement().satisfies(n -> assertThat(n).containsEntry("actor_count", 10));
        // 취소: 안 읽은 묶음에서 빠지고 대표는 남은 사람 중 최근
        clock.set(T.plusSeconds(60));
        long last = likers.get(9);
        mockMvc.perform(delete("/api/posts/{id}/like", p).with(csrf()).with(TestAuth.member(x))).andExpect(status().isOk());
        assertThat(rows(a).get(0)).containsEntry("actor_count", 9);
        // 읽은 뒤: 취소해도 그대로, 들어간 적 있는 사람은 다시 안 넣음, 새 사람은 새 묶음
        mockMvc.perform(post("/api/notifications/read-all").with(csrf()).with(TestAuth.member(a))).andExpect(status().isOk());
        mockMvc.perform(delete("/api/posts/{id}/like", p).with(csrf()).with(TestAuth.member(last))).andExpect(status().isOk());
        mockMvc.perform(put("/api/posts/{id}/like", p).with(csrf()).with(TestAuth.member(last))).andExpect(status().isOk());
        assertThat(rows(a)).singleElement().satisfies(n -> assertThat(n).containsEntry("actor_count", 9));
        mockMvc.perform(put("/api/posts/{id}/like", p).with(csrf()).with(TestAuth.member(x))).andExpect(status().isOk());
        assertThat(rows(a)).hasSize(2).last().satisfies(n -> {
            assertThat(n).containsEntry("actor_count", 1);
            assertThat(n.get("read_at")).isNull();
        });
        // 0명이면 삭제
        mockMvc.perform(delete("/api/posts/{id}/like", p).with(csrf()).with(TestAuth.member(x))).andExpect(status().isOk());
        assertThat(rows(a)).hasSize(1);
        // 본인 좋아요는 자기 글 400이라 알림 없음, 탈퇴 유예 회원의 행동도 없음
        long leaving = members.withdrawing("ntleaving", "ntleaving", T);
        writer.addToGroup(a, NotificationType.LIKE, NotificationWriter.likeGroup(p), p, leaving);
        assertThat(rows(a)).hasSize(1);
    }

    @Test
    void handleChangeNotifiesFollowersWithOldAndNewAddress() throws Exception {
        clock.set(T);
        long me = member("ntoldname");
        long f = member("ntfan");
        long stranger = member("ntstranger");
        jdbc.update("INSERT INTO follow (follower_id, followee_id) VALUES (?, ?)", f, me);
        mockMvc.perform(put("/api/me/handle").with(csrf()).with(TestAuth.member(me))
                .contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"ntnewname\"}")).andExpect(status().isOk());
        assertThat(list(f)).contains("님이 블로그 주소를 바꿨어요: ntoldname → ntnewname", "\"url\":\"/ntnewname\"");
        assertThat(rows(stranger)).isEmpty();
        assertThat(rows(me)).isEmpty();
    }

    @Test
    void followGroupDedupesWithinSevenDays() {
        clock.set(T);
        long a = member("ntfollow");
        long f1 = member("ntfa");
        long f2 = member("ntfb");
        assertThat(writer.addToGroup(a, NotificationType.FOLLOW, NotificationWriter.FOLLOW_GROUP, null, f1)).isTrue();
        writer.removeFromGroup(a, NotificationWriter.FOLLOW_GROUP, f1);
        assertThat(rows(a)).isEmpty();
        assertThat(writer.addToGroup(a, NotificationType.FOLLOW, NotificationWriter.FOLLOW_GROUP, null, f1)).isTrue();
        jdbc.update("UPDATE notification SET read_at = ? WHERE receiver_id = ?", Timestamp.from(T), a);
        assertThat(writer.addToGroup(a, NotificationType.FOLLOW, NotificationWriter.FOLLOW_GROUP, null, f1)).isFalse();
        assertThat(writer.addToGroup(a, NotificationType.FOLLOW, NotificationWriter.FOLLOW_GROUP, null, f2)).isTrue();
        clock.set(T.plus(Duration.ofDays(8)));
        jdbc.update("UPDATE notification SET read_at = ? WHERE receiver_id = ?", Timestamp.from(T), a);
        assertThat(writer.addToGroup(a, NotificationType.FOLLOW, NotificationWriter.FOLLOW_GROUP, null, f1)).isTrue();
        assertThat(rows(a)).hasSize(3);
    }

    @Test
    void newPostOnceForFollowersRespectingMuteAndWithdrawal() throws Exception {
        clock.set(T);
        long a = member("ntwriter");
        long f1 = member("ntfol1");
        long f2 = member("ntfol2");
        long muted = member("ntfolmute");
        long leaving = members.withdrawing("ntfolleave", "ntfolleave", T);
        for (long f : new long[] {f1, f2, muted, leaving}) {
            jdbc.update("INSERT INTO follow (follower_id, followee_id) VALUES (?, ?)", f, a);
        }
        mockMvc.perform(put("/api/me/notification-settings").with(csrf()).with(TestAuth.member(muted))
                .contentType(MediaType.APPLICATION_JSON).content("{\"NEW_POST\":false,\"REPORT_RESOLVED\":false}"))
                .andExpect(jsonPath("$.NEW_POST").value(false)).andExpect(jsonPath("$.COMMENT").value(true))
                .andExpect(jsonPath("$.REPORT_RESOLVED").doesNotExist());
        long p = posts.published(a, "새글", "본문", 1, T);
        jdbc.update("UPDATE post SET visibility = 'PRIVATE', first_public_at = NULL WHERE id = ?", p);
        // 비공개로 발행: 알림 없음 → 처음 공개로 바꿀 때 한 번
        publisher.publishEvent(new PostPublished(p, a, "PRIVATE", null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE type = 'NEW_POST'", Integer.class)).isZero();
        for (String v : new String[] {"PUBLIC", "PRIVATE", "PUBLIC"}) {
            mockMvc.perform(patch("/api/posts/{id}/visibility", p).with(csrf()).with(TestAuth.member(a))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"" + v + "\"}")).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForList("SELECT receiver_id FROM notification WHERE type = 'NEW_POST' ORDER BY receiver_id",
                Long.class)).containsExactly(f1, f2);
        assertThat(list(f1)).contains("ntwriter님이 새 글을 올렸어요: 「새글」");
        // 처리할 때 이미 비공개면 만들지 않음
        long q = posts.published(a, "또글", "본문", 1, T);
        jdbc.update("UPDATE post SET visibility = 'PRIVATE' WHERE id = ?", q);
        publisher.publishEvent(new PostPublished(q, a, "PUBLIC", T));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE post_id = ?", Integer.class, q)).isZero();
        // 지금 상태로 보여주기: 글이 비공개가 되면 제목 없이
        jdbc.update("UPDATE post SET visibility = 'PRIVATE' WHERE id = ?", p);
        String json = list(f1);
        assertThat(json).contains("볼 수 없는 글이에요").contains("\"unavailable\":true").doesNotContain("새글")
                .doesNotContain("\"url\"");
        jdbc.update("UPDATE post SET visibility = 'PUBLIC' WHERE id = ?", p);
        // 082: 알림의 이름은 아이디(지금 아이디로)
        jdbc.update("UPDATE member SET handle = 'renamedid' WHERE id = ?", a);
        assertThat(list(f1)).contains("renamedid님이 새 글을 올렸어요");
        // 108: 시리즈에 든 글이면 "새 편"으로(알림을 읽을 때 본다)
        long sid = jdbc.queryForObject("INSERT INTO series (member_id, title) VALUES (?, '알림 시리즈') RETURNING id", Long.class, a);
        jdbc.update("INSERT INTO series_post (post_id, series_id, position) VALUES (?, ?, 1)", p, sid);
        assertThat(list(f1)).contains("renamedid님의 시리즈 「알림 시리즈」 새 편: 「새글」");
        jdbc.update("UPDATE member SET status = 'WITHDRAWN', withdrawn_at = ? WHERE id = ?", Timestamp.from(T), a);
        assertThat(list(f1)).contains("볼 수 없는 글이에요").doesNotContain("renamedid님");
    }

    @Test
    void operationalNotificationsIgnoreMuteAndHaveNoActor() throws Exception {
        clock.set(T);
        long a = member("ntops");
        long p = posts.published(a, "숨김글", "본문", 1, T);
        mockMvc.perform(put("/api/me/notification-settings").with(csrf()).with(TestAuth.member(a))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"COMMENT\":false,\"REPLY\":false,\"LIKE\":false,\"FOLLOW\":false,\"NEW_POST\":false}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE post SET hidden_at = ? WHERE id = ?", Timestamp.from(T), p);
        writer.operational(a, NotificationType.CONTENT_HIDDEN, p, null, null, null);
        writer.operational(a, NotificationType.REPORT_RESOLVED, null, null, null, "NO_VIOLATION");
        String json = list(a);
        assertThat(json).contains("회원님의 글「숨김글」이(가) 운영 정책에 따라 숨겨졌어요")
                .contains("신고하신 내용을 검토했지만 운영 정책 위반은 아니었어요").doesNotContain("\"actor\"");
        String settings = mockMvc.perform(get("/settings").with(TestAuth.member(a))).andReturn().getResponse().getContentAsString();
        assertThat(settings).contains("운영 알림(신고 결과·숨김)은 끌 수 없어요").contains("팔로우한 사람의 새 글");
        mockMvc.perform(post("/settings/notifications").with(csrf()).with(TestAuth.member(a)).param("LIKE", "on"))
                .andExpect(status().isSeeOther());
        mockMvc.perform(get("/api/me/notification-settings").with(TestAuth.member(a)))
                .andExpect(jsonPath("$.LIKE").value(true)).andExpect(jsonPath("$.COMMENT").value(false));
    }

    @Test
    void cleanupRetentionCapAndWithdrawalPurge() {
        clock.set(T);
        long a = member("ntclean");
        long b = member("ntcleanb");
        long c = member("ntcleanc");
        long p = posts.published(a, "글", "본문", 1, T);
        jdbc.update("""
                INSERT INTO notification (receiver_id, type, result, actor_count, created_at, updated_at)
                SELECT ?, 'REPORT_RESOLVED', 'NO_VIOLATION', 0, ?::timestamptz - (g || ' seconds')::interval, ?::timestamptz - (g || ' seconds')::interval
                FROM generate_series(1, 1005) g
                """, b, Timestamp.from(T), Timestamp.from(T));
        jdbc.update("""
                INSERT INTO notification (receiver_id, type, result, actor_count, created_at, updated_at)
                VALUES (?, 'REPORT_RESOLVED', 'ACTION_TAKEN', 0, ?, ?)
                """, c, Timestamp.from(T.minus(Duration.ofDays(91))), Timestamp.from(T.minus(Duration.ofDays(91))));
        assertThat(cleanup.purge()).isEqualTo(6);
        assertThat(rows(b)).hasSize(1000);
        assertThat(rows(c)).isEmpty();
        // 탈퇴 정리: b가 받은 것 삭제, b가 묶음에서 빠짐, b가 일으킨 하나짜리 삭제
        writer.addToGroup(a, NotificationType.LIKE, NotificationWriter.likeGroup(p), p, c);
        clock.set(T.plusSeconds(5));
        writer.addToGroup(a, NotificationType.LIKE, NotificationWriter.likeGroup(p), p, b);
        writer.single(a, NotificationType.COMMENT, p, null, b);
        cleanup.purgeWithdrawn(b);
        assertThat(rows(b)).isEmpty();
        assertThat(rows(a)).singleElement().satisfies(n -> assertThat(n).containsEntry("actor_count", 1)
                .containsEntry("last_actor_id", c));
    }
}
