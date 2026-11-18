// Chạy trong WebView của menu bên phải (popup.html) và trang Options (options.html).
// Không sửa file gốc của extension: mọi chỉnh sửa giao diện làm ở đây.
(function () {
    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    ready(function () {
        var isPopup = /popup\.html$/.test(location.pathname);

        var style = document.createElement('style');
        style.textContent =
            'html{-webkit-text-size-adjust:100%}' +
            (isPopup
                ? 'body{width:auto !important;box-sizing:border-box;min-height:100vh}'
                : 'body{padding:14px !important}') +
            'button,a{-webkit-tap-highlight-color:transparent}';
        document.head.appendChild(style);

        if (!isPopup) return;

        // Quay tab bằng tabCapture/offscreen chỉ có trên Chrome desktop -> ẩn trên Android.
        var rec = document.getElementById('btn-record-toggle');
        var recSection = rec && rec.closest('.section');
        if (recSection) recSection.style.display = 'none';

        // Danh sách "Mở game theo nhà phát hành" -> thay bằng điều khiển của app.
        var links = document.querySelector('.game-links');
        var linksSection = links && links.closest('.section');
        if (linksSection) {
            linksSection.innerHTML =
                '<div class="section-title">Ứng dụng</div>' +
                '<div class="game-links">' +
                '<a href="#" id="app-reload" class="btn-game" style="background-color:#2563eb"><span>🔄 Tải lại trang game</span><span></span></a>' +
                '<a href="#" id="app-pick" class="btn-game" style="background-color:#6b7280"><span>↩ Đổi phiên bản game</span><span></span></a>' +
                '</div>';
            document.getElementById('app-reload').addEventListener('click', function (e) {
                e.preventDefault();
                window.chrome.__app('reload');
            });
            document.getElementById('app-pick').addEventListener('click', function (e) {
                e.preventDefault();
                window.chrome.__app('pick');
            });
        }

        // Dòng hướng dẫn nhập cache nhắc tới F12 -> không có trên Android.
        var st = document.getElementById('fastcache-import-status');
        if (st) {
            new MutationObserver(function () {
                if (/F12/.test(st.innerText)) st.innerText = 'Đã gửi file vào trang game.';
            }).observe(st, { childList: true, characterData: true, subtree: true });
        }
    });
})();
