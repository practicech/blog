package com.team.blog.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.blog.mission.application.MissionService;
import com.team.blog.mission.application.TopicPool;
import com.team.blog.support.IntegrationTestBase;
import com.team.blog.support.TestAuth;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** 057 오늘의 미션(강성찬 개인 확장). */
class DailyMissionIT extends IntegrationTestBase {

    /** 한국 시간 2026-10-12 12:00. */
    private static final Instant T = Instant.parse("2026-10-12T03:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-10-12");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MissionService missions;

    private long member(String handle) {
        return members.localMember(handle, handle.substring(0, Math.min(10, handle.length())), handle + "@example.com", "Blog#2026ok", true);
    }

    private long startWriting(long who, long topicId) throws Exception {
        String location = mockMvc.perform(post("/missions/topics/{id}/write", topicId).with(csrf()).with(TestAuth.member(who)))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private void publish(long who, long postId, String visibility) throws Exception {
        mockMvc.perform(post("/api/posts/{id}/publish", postId).with(csrf()).with(TestAuth.member(who))
                .header("Idempotency-Key", "mission-" + postId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"오늘의 미션 글\",\"contentMd\":\"미션 글 본문이에요\",\"tags\":[],\"visibility\":\"" + visibility
                        + "\",\"baseVersion\":0}")).andExpect(status().isOk());
    }

    private String page(String url, Long who) throws Exception {
        return who == null ? mockMvc.perform(get(url)).andReturn().getResponse().getContentAsString()
                : mockMvc.perform(get(url).with(TestAuth.member(who))).andReturn().getResponse().getContentAsString();
    }

    @Test
    void pickTopicWriteAndShareWithFriends() throws Exception {
        clock.set(T);
        long writer = member("mswriter");
        long friend = member("msfriend");
        long stranger = member("msstranger");
        members.befriend(writer, friend);
        // 오늘의 주제 3개가 내장 주제로 만들어진다
        String today = page("/missions", writer);
        List<TopicPool.Topic> expected = TopicPool.forDay(DAY, 3);
        assertThat(today).contains("오늘의 미션").contains(expected.get(0).title()).contains(expected.get(2).title())
                .contains("글그린 추천").contains("이 주제로 쓰기");
        List<MissionService.Topic> topics = missions.topics(DAY);
        assertThat(topics).hasSize(3);
        // 주제를 고르면 제목에 주제가 든 임시글, 다시 눌러도 같은 글
        long postId = startWriting(writer, topics.get(0).id());
        assertThat(startWriting(writer, topics.get(0).id())).isEqualTo(postId);
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, postId)).isEqualTo(topics.get(0).title());
        assertThat(page("/write/" + postId, writer)).contains("🗓 오늘의 미션: " + topics.get(0).title());
        // 친구 공개로 발행 → 완료, 친구에게만 알림
        publish(writer, postId, "FRIENDS");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM mission_post WHERE post_id = ?", Boolean.class, postId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND type = 'MISSION_DONE'",
                Integer.class, friend)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND type = 'MISSION_DONE'",
                Integer.class, stranger)).isZero();
        assertThat(mockMvc.perform(get("/api/notifications").with(TestAuth.member(friend))).andReturn().getResponse()
                .getContentAsString()).contains("오늘의 미션 글을 올렸어요");
        // 친구에게는 "친구들의 오늘 글", 모르는 사람·비회원에게는 안 보임(친구 공개)
        assertThat(page("/missions", friend)).contains("친구들의 오늘 글").contains("오늘의 미션 글").contains("댓글 달기");
        assertThat(page("/missions", stranger)).doesNotContain("오늘의 미션 글");
        assertThat(page("/missions", null)).doesNotContain("오늘의 미션 글");
        // 내 기록과 글 화면 표시
        assertThat(page("/missions", writer)).contains("1번").contains("1일 연속").contains("오늘 미션 완료");
        // 066 배지: 첫 미션만 받음, 나머지는 받는 법이 보인다. 로그인 안 한 사람에게는 배지 칸이 없다
        assertThat(page("/missions", writer)).contains("내 배지").contains("1 / 7").contains("첫 미션").contains("받았어요")
                .contains("3일 연속 완료하면");
        assertThat(page("/missions", null)).doesNotContain("내 배지");
        assertThat(page("/mswriter/posts/" + postId, writer)).contains("🗓 오늘의 미션 · ").contains("미션 완료");
        assertThat(missions.stats(writer).total()).isEqualTo(1);
        // 다음 날: 어제 주제로는 새로 시작할 수 없고, 어제 화면에서 글을 볼 수 있다
        clock.set(T.plusSeconds(24 * 3600));
        mockMvc.perform(post("/missions/topics/{id}/write", topics.get(0).id()).with(csrf()).with(TestAuth.member(writer)))
                .andExpect(status().isBadRequest());
        assertThat(page("/missions?date=2026-10-12", friend)).contains("지난 미션").contains("오늘의 미션 글");
        assertThat(page("/missions", friend)).contains("10월 12일");
        mockMvc.perform(get("/missions?date=2026-12-31")).andExpect(status().isNotFound());
    }

    @Test
    void publicMissionPostNotifiesFriendFollowerOnce() throws Exception {
        clock.set(T);
        long writer = member("msonce");
        long friend = member("msoncefriend");
        members.befriend(writer, friend);
        long topicId = missions.topics(DAY).get(1).id();
        long postId = startWriting(writer, topicId);
        publish(writer, postId, "PUBLIC");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND post_id = ?", Integer.class,
                friend, postId)).isEqualTo(1);
        assertThat(page("/missions", null)).contains("오늘의 미션 글");
    }

    @Test
    void streakAndAiAnswerParsing() {
        LocalDate today = LocalDate.parse("2026-10-12");
        java.util.TreeSet<LocalDate> days = new java.util.TreeSet<>(List.of(today.minusDays(5), today.minusDays(3),
                today.minusDays(2), today.minusDays(1)));
        MissionService.Stats s = statsOf(days, today);
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.streak()).isEqualTo(3);
        assertThat(s.best()).isEqualTo(3);
        assertThat(s.doneToday()).isFalse();
        days.add(today);
        assertThat(statsOf(days, today).streak()).isEqualTo(4);
        // 066 배지: 4번·최고 4일 연속 → 첫 미션, 3일 연속
        assertThat(MissionService.badges(statsOf(days, today))).filteredOn(MissionService.Badge::earned)
                .extracting(MissionService.Badge::name).containsExactly("첫 미션", "3일 연속");
        assertThat(MissionService.badges(new MissionService.Stats(100, 0, 30, false))).allMatch(MissionService.Badge::earned);
        assertThat(MissionService.badges(new MissionService.Stats(0, 0, 0, false))).noneMatch(MissionService.Badge::earned);
        assertThat(TopicPool.size()).isEqualTo(120);
        assertThat(com.team.blog.mission.application.TopicAi.parse("1. 첫 번째 주제|안내 하나\n- 두 번째 주제|안내 둘\n이상한 줄\n**세 번째 주제**|안내 셋", 3))
                .extracting(TopicPool.Topic::title).containsExactly("첫 번째 주제", "두 번째 주제", "세 번째 주제");
        assertThat(TopicPool.forDay(today, 3)).hasSize(3).doesNotHaveDuplicates();
        // AI가 없으면 내장 주제
        clock.set(T);
        assertThat(missions.prepareWithAi(missions.today())).isFalse();
        assertThat(missions.topics(missions.today())).hasSize(3);
    }

    private static MissionService.Stats statsOf(java.util.TreeSet<LocalDate> days, LocalDate today) {
        return MissionService.stats(days, today);
    }
}
