package com.team.blog.discovery.application;

import java.sql.Date;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 잔디·스트릭(031, 강성찬 개인 확장): 최근 1년 날짜별 기록 수와 연속 기록. 누구나 보는 화면이라 공개 글(최초 공개일)과
 * 공개 글에 단 댓글만 센다(친구·링크·그룹·비공개 글의 활동은 드러내지 않는다). 따로 저장하지 않고 화면을 열 때 센다(작성자 한 명, 1년).
 */
@Service
public class ActivityQuery {

    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    /** 칸 하나: 날짜, 글 수, 댓글 수, 진하기 0~4, 그날 오늘의 미션을 공개 글로 마쳤는지. */
    public record Day(LocalDate date, int posts, int comments, int level, boolean mission) {

        public String label() {
            return LABEL.format(date) + (posts + comments == 0 ? " · 기록 없음" : " · 글 " + posts + " · 댓글 " + comments)
                    + (mission ? " · 미션 완료" : "");
        }
    }

    /** 일요일부터 시작하는 주 53개, 오늘 기준 연속·최고 연속 일수, 1년 기록한 날 수. */
    public record Grass(List<List<Day>> weeks, int currentStreak, int bestStreak, int activeDays) {

        /** 첫 화면 "내 잔디" 한 줄: 최근 n주. */
        public List<List<Day>> recentWeeks(int n) {
            return weeks.subList(Math.max(0, weeks.size() - n), weeks.size());
        }
    }

    private final JdbcTemplate jdbc;
    private final ActivityProperties properties;
    private final Clock clock;

    public ActivityQuery(JdbcTemplate jdbc, ActivityProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public Grass grass(long memberId) {
        ZoneId zone = ZoneId.of(properties.zone());
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate start = today.minusWeeks(52).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        Map<LocalDate, int[]> counts = new HashMap<>();
        java.util.Set<LocalDate> missionDays = new java.util.HashSet<>();
        String tz = properties.zone();
        jdbc.query("""
                SELECT d, sum(p) AS posts, sum(c) AS comments, sum(m) AS missions FROM (
                  SELECT (p.first_public_at AT TIME ZONE ?)::date AS d, 1 AS p, 0 AS c, 0 AS m FROM post p
                  WHERE p.author_id = ? AND p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL
                    AND p.hidden_at IS NULL AND p.first_public_at >= ?
                  UNION ALL
                  SELECT (c.created_at AT TIME ZONE ?)::date, 0, 1, 0 FROM comment c JOIN post p ON p.id = c.post_id
                  WHERE c.author_id = ? AND c.deleted_at IS NULL AND c.hidden_at IS NULL AND c.created_at >= ?
                    AND p.visibility = 'PUBLIC' AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL AND p.hidden_at IS NULL
                  UNION ALL
                  SELECT dt.day, 0, 0, 1 FROM mission_post mp JOIN daily_topic dt ON dt.id = mp.topic_id JOIN post p ON p.id = mp.post_id
                  WHERE mp.member_id = ? AND mp.completed_at IS NOT NULL AND (mp.completed_at AT TIME ZONE 'Asia/Seoul')::date = dt.day
                    AND dt.day >= ? AND p.visibility = 'PUBLIC' AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL AND p.hidden_at IS NULL
                ) t GROUP BY d
                """, rs -> {
            LocalDate day = rs.getDate("d").toLocalDate();
            if (rs.getInt("missions") > 0) {
                missionDays.add(day);
            }
            if (rs.getInt("posts") + rs.getInt("comments") > 0) {
                counts.put(day, new int[] {rs.getInt("posts"), rs.getInt("comments")});
            }
        }, tz, memberId, Date.valueOf(start.minusDays(1)), tz, memberId, Date.valueOf(start.minusDays(1)), memberId, Date.valueOf(start));
        List<List<Day>> weeks = new ArrayList<>();
        int active = 0;
        for (LocalDate weekStart = start; !weekStart.isAfter(today); weekStart = weekStart.plusWeeks(1)) {
            List<Day> week = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate d = weekStart.plusDays(i);
                if (d.isAfter(today)) {
                    break;
                }
                int[] c = counts.getOrDefault(d, new int[2]);
                int total = c[0] * 2 + c[1]; // 글은 댓글 두 개만큼
                int level = total == 0 ? 0 : total <= 1 ? 1 : total <= 3 ? 2 : total <= 6 ? 3 : 4;
                if (total > 0) {
                    active++;
                }
                week.add(new Day(d, c[0], c[1], level, missionDays.contains(d)));
            }
            weeks.add(week);
        }
        // 연속: 오늘(없으면 어제)부터 거꾸로
        int current = 0;
        LocalDate d = counts.containsKey(today) ? today : today.minusDays(1);
        while (counts.containsKey(d)) {
            current++;
            d = d.minusDays(1);
        }
        int best = 0;
        int run = 0;
        for (LocalDate x = start; !x.isAfter(today); x = x.plusDays(1)) {
            run = counts.containsKey(x) ? run + 1 : 0;
            best = Math.max(best, run);
        }
        return new Grass(weeks, current, best, active);
    }
}
