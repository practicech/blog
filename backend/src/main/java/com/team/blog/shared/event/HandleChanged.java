package com.team.blog.shared.event;

/** 강성찬 개인 확장: 회원이 블로그 주소(아이디)를 {@code oldHandle}에서 바꿈. 알림이 팔로워에게 알린다. */
public record HandleChanged(long memberId, String oldHandle) implements DomainEvent {
}
