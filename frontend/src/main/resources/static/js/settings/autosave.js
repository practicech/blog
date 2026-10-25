/*
 * 10-25 강성찬 개인 확장: 설정 › 알림의 스위치는 누르면 바로 저장한다(공개 범위·화면 테마와 같게).
 * data-autosave="#상태칸"이 붙은 폼만. 스크립트가 없으면 [저장] 버튼으로 예전처럼 보낸다.
 * 빠르게 여러 번 눌러도 누른 순서대로 저장되게 요청을 하나씩 잇고, 실패하면 [저장] 버튼을 다시 보여 직접 보낼 수 있게 한다.
 */
(function () {
  'use strict';
  var forms = document.querySelectorAll('form[data-autosave]');
  forms.forEach(function (form) {
    var status = document.querySelector(form.getAttribute('data-autosave'));
    var button = form.querySelector('button[type="submit"]');
    if (button) { button.hidden = true; }
    var queue = Promise.resolve();
    form.addEventListener('change', function () {
      var body = new URLSearchParams(new FormData(form));
      queue = queue.then(function () {
        return fetch(form.action, {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body
        }).then(function (res) {
          // 저장되면 서버가 /settings로 돌려보낸다. 로그인이 풀려 /login으로 갔으면 저장 안 된 것
          if (!res.ok || (res.redirected && new URL(res.url).pathname !== '/settings')) { throw new Error(); }
          if (status) { status.dataset.toast = 'success'; status.textContent = ''; status.textContent = '저장했어요'; }
        }).catch(function () {
          if (button) { button.hidden = false; }
          if (status) { status.dataset.toast = 'error'; status.textContent = '저장하지 못했어요. [저장]을 눌러 다시 보내 주세요'; }
        });
      });
    });
  });
  // "누르면 바로 저장돼요" 안내는 바로 저장이 실제로 켜졌을 때만 보인다
  if (forms.length) {
    document.querySelectorAll('[data-autosave-hint]').forEach(function (el) { el.hidden = false; });
  }
})();
