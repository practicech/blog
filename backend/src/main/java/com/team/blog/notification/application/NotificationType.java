package com.team.blog.notification.application;

import java.util.List;

/** 알림 종류(25 §2). 끌 수 있는 종류는 {@link #MUTABLE}만. */
public enum NotificationType {
    COMMENT, REPLY, LIKE, FOLLOW, NEW_POST, REPORT_RESOLVED, CONTENT_HIDDEN,
    /** 친구 요청(025, 강성찬 개인 확장): 처리해야 하는 요청이라 끌 수 없다. */
    FRIEND_REQUEST,
    /** 친구 공개였던 글이 처음 전체 공개됨(026, 강성찬 개인 확장): 친구에게 응원 알림. "새 글" 설정을 따른다. */
    FIRST_PUBLIC,
    /** 친구가 오늘의 미션 글을 올림(057, 강성찬 개인 확장). "새 글" 설정을 따른다. */
    MISSION_DONE,
    /** 비공개 계정에 팔로우 요청(073, 강성찬 개인 확장): 처리해야 하는 요청이라 끌 수 없다. */
    FOLLOW_REQUEST,
    /** 새 문의(090, 강성찬 개인 확장): 관리자에게. 처리해야 하는 일이라 끌 수 없다. */
    INQUIRY,
    /** 내 문의에 답장이 옴(099, 강성찬 개인 확장): 보낸 회원에게. 끌 수 없다. */
    INQUIRY_REPLIED,
    /** 함께 쓰기에 초대받음(111 후속, 강성찬 개인 확장). 수락할 일이라 끌 수 없다. */
    CLUB_INVITE,
    /** 내 함께 쓰기에 참여 요청이 옴(111 후속, 강성찬 개인 확장). 승인할 일이라 끌 수 없다. */
    CLUB_REQUEST,
    /** 팔로우하는 사람이 블로그 주소를 바꿈(강성찬 개인 확장, 인스타처럼). */
    HANDLE_CHANGED;

    public static final List<NotificationType> MUTABLE = List.of(COMMENT, REPLY, LIKE, FOLLOW, NEW_POST);

    public boolean isMutable() {
        return MUTABLE.contains(this);
    }

    /** 받는 사람이 그 글을 지금 읽을 수 있어야 만드는 종류(FR-008 ⑤). */
    public boolean needsReadablePost() {
        return this == COMMENT || this == REPLY || this == LIKE || this == NEW_POST || this == FIRST_PUBLIC
                || this == MISSION_DONE;
    }
}
