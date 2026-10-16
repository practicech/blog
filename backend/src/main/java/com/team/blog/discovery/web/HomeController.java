package com.team.blog.discovery.web;

import com.team.blog.discovery.application.ActivityQuery;
import com.team.blog.post.application.CardPage;
import com.team.blog.post.application.PostListQuery;
import com.team.blog.shared.error.PostContentException;
import com.team.blog.shared.security.CurrentUserProvider;
import java.time.Clock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 홈({@code GET /}): 전체 공개 글 목록 첫 9개를 서버가 완성해서 보낸다(009 FR-008). 스크립트가 없으면 [더 보기]가
 * {@code /?cursor=…} 링크로 다음 9개를 보여 준다. 잘못된 커서면 처음부터 보여 준다(API는 400).
 * 019의 트렌딩 탭은 086(강성찬 개인 확장)부터 탐색에 있다({@code /?tab=trending} → {@code /explore?tab=trending}).
 * 로그아웃 직후에는 1회용 플래시 {@code logoutCleanupMemberId}가 들어와 브라우저 임시 데이터 정리를 한 번 더 실행한다(001).
 */
@Controller
public class HomeController {

    /** 시안(10-08) 소개 사진: static/images/hero.webp가 있으면 배경으로, 없으면 그린 고양이 그림. */
    private static final boolean HERO_PHOTO = new org.springframework.core.io.ClassPathResource("static/images/hero.webp").exists();

    private final PostListQuery listQuery;
    private final Clock clock;
    private final ActivityQuery activityQuery;
    private final CurrentUserProvider currentUserProvider;

    public HomeController(PostListQuery listQuery, Clock clock, ActivityQuery activityQuery, CurrentUserProvider currentUserProvider) {
        this.listQuery = listQuery;
        this.clock = clock;
        this.activityQuery = activityQuery;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/")
    public String home(@RequestParam(value = "cursor", required = false) String cursor,
                       @RequestParam(value = "tab", required = false) String tab, Model model) {
        // 086 강성찬 개인 확장: 트렌딩은 탐색으로 옮겼다(예전 주소는 넘김)
        if ("trending".equals(tab)) {
            return "redirect:/explore?tab=trending";
        }
        CardPage page;
        try {
            page = listQuery.feed(cursor);
        } catch (PostContentException e) {
            page = listQuery.feed(null);
            cursor = null;
        }
        CardModel.fill(model, page, clock.instant());
        model.addAttribute("continued", cursor != null && !cursor.isEmpty());
        model.addAttribute("listApi", "/api/posts");
        model.addAttribute("heroPhoto", HERO_PHOTO);
        // 글그린 = 글 잔디: 로그인하면 첫 화면 맨 위에 내 잔디 한 줄
        if (activityQuery.enabled() && (cursor == null || cursor.isEmpty())) {
            currentUserProvider.current().ifPresent(u -> model.addAttribute("myGrass", activityQuery.grass(u.memberId())));
        }
        return "home";
    }
}
