package com.team.blog.discovery.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.team.blog.discovery.application.ActivityQuery;
import com.team.blog.support.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 031 잔디·스트릭(강성찬 개인 확장): 공개 글·공개 글 댓글만, 연속 일수. */
class ActivityIT extends IntegrationTestBase {

    // 한국 시간 2026-10-09 12:00
    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");

    @Autowired
    ActivityQuery activity;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void streakCountsPublicPostsAndCommentsOnly() throws Exception {
        clock.set(NOW);
        long me = members.localMember("grassme", "grassme", "grassme@example.com", "Blog#2026ok", true);
        posts.published(me, "오늘 글", "본문", 1, NOW.minus(Duration.ofHours(1)));
        long yesterday = posts.published(me, "어제 글", "본문", 1, NOW.minus(Duration.ofDays(1)));
        jdbc.update("INSERT INTO comment (post_id, author_id, content, created_at, updated_at) VALUES (?, ?, '댓글', ?, ?)",
                yesterday, me, Timestamp.from(NOW.minus(Duration.ofDays(2))), Timestamp.from(NOW.minus(Duration.ofDays(2))));
        // 5일 전 비공개 글은 세지 않는다(드러내지 않음) → 연속은 3일
        long hidden = posts.published(me, "비공개", "본문", 1, NOW.minus(Duration.ofDays(3)));
        jdbc.update("UPDATE post SET visibility = 'PRIVATE', first_public_at = NULL WHERE id = ?", hidden);
        posts.published(me, "열흘 전", "본문", 1, NOW.minus(Duration.ofDays(10)));
        ActivityQuery.Grass grass = activity.grass(me);
        assertThat(grass.currentStreak()).isEqualTo(3);
        assertThat(grass.bestStreak()).isEqualTo(3);
        assertThat(grass.activeDays()).isEqualTo(4);
        assertThat(grass.weeks()).hasSizeBetween(52, 54);
        String html = mockMvc.perform(get("/grassme")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("글 잔디").contains("3일 연속").contains("최근 1년 4일").contains("2026.10.09 · 글 1 · 댓글 0");
    }

    @Test
    void missionDoneDayIsMarkedOnlyForPublicPost() {
        clock.set(NOW);
        long me = members.localMember("grassmi", "grassmi", "grassmi@example.com", "Blog#2026ok", true);
        long open = posts.published(me, "미션 글", "본문", 1, NOW.minus(Duration.ofHours(1)));
        long closed = posts.published(me, "비공개 미션 글", "본문", 1, NOW.minus(Duration.ofDays(1)));
        jdbc.update("UPDATE post SET visibility = 'PRIVATE', first_public_at = NULL WHERE id = ?", closed);
        mission(me, open, "2026-10-09", NOW.minus(Duration.ofHours(1)));
        mission(me, closed, "2026-10-08", NOW.minus(Duration.ofDays(1)));
        java.util.Map<String, Boolean> marks = new java.util.HashMap<>();
        activity.grass(me).weeks().forEach(w -> w.forEach(d -> marks.put(d.date().toString(), d.mission())));
        assertThat(marks).containsEntry("2026-10-09", true).containsEntry("2026-10-08", false);
    }

    private void mission(long member, long post, String day, Instant completedAt) {
        Long topic = jdbc.queryForObject("""
                INSERT INTO daily_topic (day, slot, title, hint, source) VALUES (?::date, 1, '주제', '힌트', 'POOL')
                ON CONFLICT (day, slot) DO UPDATE SET title = EXCLUDED.title RETURNING id""", Long.class, day);
        jdbc.update("INSERT INTO mission_post (topic_id, member_id, post_id, completed_at) VALUES (?, ?, ?, ?)",
                topic, member, post, Timestamp.from(completedAt));
    }
}
