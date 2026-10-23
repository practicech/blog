package com.team.blog.account.application;

import com.team.blog.account.domain.AuthIdentity;
import com.team.blog.account.domain.Handle;
import com.team.blog.account.domain.HandleRules;
import com.team.blog.account.domain.HandleViolation;
import com.team.blog.account.domain.Member;
import com.team.blog.account.domain.Provider;
import com.team.blog.account.infra.AuthIdentityRepository;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.error.HandleViolationException;
import com.team.blog.shared.error.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.team.blog.shared.event.HandleChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 074 강성찬 개인 확장: 블로그 주소(아이디) 바꾸기(인스타처럼). 공통 규칙(FR-011)에서 주소를 바꾸는 유일한 경로다.
 * <ul>
 *   <li>소셜 가입의 접두어({@code go-}/{@code gi-}) 없이 아무 주소나 고른다. 검사는 가입과 같다(형식·예약어·금칙어·중복·남이 잡아 둔 옛 주소).
 *       접두어는 가입 때 이메일·소셜 주소가 겹치지 않게 하려는 것뿐이고 로그인은 주소가 아니라 {@code auth_identity}로 구분한다.</li>
 *   <li>{@code changeWindow} 동안 두 번까지.</li>
 *   <li>예전 주소는 {@code handle_history}에 남겨 {@code handleHold} 동안 본인만 되돌릴 수 있고, 옛 주소로 오면 새 주소로 넘긴다
 *       ({@code OldHandleRedirectFilter}). 다른 사람이 그 주소를 가져가면 기록이 지워져 넘김도 끝난다.</li>
 * </ul>
 */
@Service
public class HandleChangeService {

    /** 기간 안에 두 번 바꿈 → 다음 가능 시각. */
    public static class TooSoonException extends RuntimeException {
        private final Instant nextAllowedAt;

        public TooSoonException(Instant nextAllowedAt) {
            super("HANDLE_CHANGE_TOO_SOON");
            this.nextAllowedAt = nextAllowedAt;
        }

        public Instant nextAllowedAt() {
            return nextAllowedAt;
        }
    }

    private static final int CHANGES_PER_WINDOW = 2;

    private final MemberRepository memberRepository;
    private final AuthIdentityRepository identities;
    private final HandleService handleService;
    private final JdbcTemplate jdbc;
    private final InstaAccountProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public HandleChangeService(MemberRepository memberRepository, AuthIdentityRepository identities,
                               HandleService handleService, JdbcTemplate jdbc, InstaAccountProperties properties, Clock clock,
                               ApplicationEventPublisher events) {
        this.events = events;
        this.identities = identities;
        this.memberRepository = memberRepository;
        this.handleService = handleService;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * @return 새 주소(같은 값이면 지금 주소)
     * @throws TooSoonException 기간 안에 이미 두 번 @throws HandleViolationException 규칙 위반
     */
    @Transactional
    public String change(long memberId, String raw) {
        if (!properties.enabled()) {
            throw new NotFoundException();
        }
        Member member = memberRepository.findByIdForUpdate(memberId).orElseThrow(NotFoundException::new);
        String current = member.getHandle();
        if (HandleRules.normalizeInput(raw).equals(current)) {
            return current;
        }
        Instant now = clock.instant();
        Optional<Instant> next = nextAllowedAt(memberId, now);
        if (next.isPresent()) {
            throw new TooSoonException(next.get());
        }
        Provider provider = identities.findByMemberId(memberId).map(AuthIdentity::getProvider).orElse(Provider.LOCAL);
        Handle handle = handleService.validateForChange(raw, provider, memberId);
        // 잡아 둔 기간이 끝난 남의 옛 주소면 그 기록을 지운다(넘김도 끝). 자기 옛 주소로 되돌리면 기록은 남겨 횟수에 센다
        // (지금 주인이 그 주소라 넘기지는 않는다)
        jdbc.update("DELETE FROM handle_history WHERE old_handle = ? AND member_id <> ?", handle.toString(), memberId);
        jdbc.update("""
                INSERT INTO handle_history (old_handle, member_id, changed_at, hold_until) VALUES (?, ?, ?, ?)
                ON CONFLICT (old_handle) DO UPDATE SET member_id = EXCLUDED.member_id,
                    changed_at = EXCLUDED.changed_at, hold_until = EXCLUDED.hold_until""",
                current, memberId, Timestamp.from(now), Timestamp.from(now.plus(properties.handleHold())));
        try {
            jdbc.update("UPDATE member SET handle = ?, updated_at = ? WHERE id = ?",
                    handle.toString(), Timestamp.from(now), memberId);
        } catch (DuplicateKeyException e) {
            throw new HandleViolationException(HandleViolation.HANDLE_DUPLICATE);
        }
        events.publishEvent(new HandleChanged(memberId, current));
        return handle.toString();
    }

    /** 지금 바꿀 수 있으면 empty, 아니면 다음 가능 시각. */
    @Transactional(readOnly = true)
    public Optional<Instant> nextAllowedAt(long memberId) {
        return nextAllowedAt(memberId, clock.instant());
    }

    // ponytail: 옛 주소 하나당 한 줄이라, 같은 옛 주소를 기간 안에 다시 떠나면 한 번으로 센다. 정확히 세려면 변경 로그 테이블이 필요.
    private Optional<Instant> nextAllowedAt(long memberId, Instant now) {
        List<Timestamp> recent = jdbc.queryForList("""
                SELECT changed_at FROM handle_history WHERE member_id = ? AND changed_at > ?
                ORDER BY changed_at DESC""", Timestamp.class, memberId, Timestamp.from(now.minus(properties.changeWindow())));
        if (recent.size() < CHANGES_PER_WINDOW) { // NOSONAR 오탐: Spring 조회 결과는 null이 아니다(빈 목록이나 예외)
            return Optional.empty();
        }
        return Optional.of(recent.get(CHANGES_PER_WINDOW - 1).toInstant().plus(properties.changeWindow()));
    }
}
