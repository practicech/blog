-- 10-23 chan(강성찬 개인 확장): 블로그 주소(아이디)를 바꾸면 팔로워에게 알린다(인스타처럼). 알림에 바꾸기 전 주소를 남긴다.
ALTER TABLE notification ADD COLUMN old_handle varchar(39) NULL;
ALTER TABLE notification DROP CONSTRAINT ck_notification_type;
ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (type IN ('COMMENT', 'REPLY', 'LIKE', 'FOLLOW', 'NEW_POST',
    'REPORT_RESOLVED', 'CONTENT_HIDDEN', 'FRIEND_REQUEST', 'FIRST_PUBLIC', 'MISSION_DONE', 'FOLLOW_REQUEST', 'INQUIRY',
    'INQUIRY_REPLIED', 'CLUB_INVITE', 'CLUB_REQUEST', 'HANDLE_CHANGED'));

COMMENT ON COLUMN notification.old_handle IS '주소 변경 알림(HANDLE_CHANGED)의 바꾸기 전 블로그 주소';
