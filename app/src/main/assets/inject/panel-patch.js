// Chạy trong WebView của menu bên phải (popup.html) và trang Options (options.html).
// Không sửa file gốc của extension: mọi chỉnh sửa giao diện làm ở đây.
(function () {
    // ---- Luôn dùng giao diện tối (bỏ nền sáng + nút đổi Sáng/Tối) ----
    var root = document.documentElement;
    function forceDark() {
        if (root.getAttribute('data-theme') !== 'dark') root.setAttribute('data-theme', 'dark');
    }
    forceDark();
    // popup.js / options.js đọc darkMode từ storage rồi đặt lại data-theme -> giữ nguyên "dark".
    new MutationObserver(forceDark).observe(root, { attributes: true, attributeFilter: ['data-theme'] });

    var dark = document.createElement('style');
    dark.textContent =
        'html[data-theme="dark"]{color-scheme:dark;' +
        '--bg-page:#0f1220;--bg-container:#1a1f36;--bg-card:#1a1f36;--bg-input:#0f1220;--bg-th:#0f1220;' +
        '--border-color:#2a3050;--border-input:#3a4166;--bg-chip:#262c4a;--copy-btn-bg:#262c4a;--copy-btn-bg-hover:#323a5f;' +
        '--qr-border:#2a3050}' +
        'html,body{background:#0f1220 !important}' +
        '#btn-theme-toggle{display:none !important}';
    root.appendChild(dark);
})();

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

        // ---- Giao diện cho điện thoại: chữ lớn, vùng bấm >= 48px ----
        var big = document.createElement('style');
        big.textContent = isPopup ? [
            'body{padding:18px 16px 32px !important;font-size:16px !important;line-height:1.4}',
            '.header{gap:12px !important;margin-bottom:20px !important;padding-bottom:14px !important}',
            '.header img{width:44px !important;height:44px !important;border-radius:10px !important}',
            '.header h2{font-size:20px !important;letter-spacing:.2px}',
            '.section{margin-bottom:24px !important}',
            '.section-title{font-size:13px !important;letter-spacing:.8px !important;margin:0 2px 10px !important}',
            '.toggle-wrap{padding:14px 16px !important;border-radius:14px !important;min-height:56px;box-sizing:border-box}',
            '.toggle-label{font-size:16px !important}',
            '.switch{width:58px !important;height:34px !important;flex-shrink:0}',
            '.slider{border-radius:34px !important;background-color:#3a4166}',
            '.slider:before{height:26px !important;width:26px !important;left:4px !important;bottom:4px !important}',
            'input:checked + .slider{background-color:#10b981 !important}',
            'input:checked + .slider:before{transform:translateX(24px) !important}',
            '.engine-hint,.rec-hint{font-size:13px !important;line-height:1.5;margin-top:10px !important;padding:0 2px}',
            '.engine-card,.chap-card,.donation-card{border-radius:14px !important;padding:14px !important}',
            '.engine-select,.engine-key-input,.chap-remark-input{font-size:16px !important;padding:12px 14px !important;min-height:48px;border-radius:10px !important;margin-bottom:0 !important}',
            '.engine-card .engine-select{margin-bottom:10px !important}',
            '.engine-key-row,.chap-remark-row{flex-wrap:wrap !important;gap:10px !important}',
            '.engine-key-input{flex:1 1 100% !important}',
            '.btn-chap-save,.btn-engine-save,#btn-get-api-key{font-size:15px !important;padding:13px 16px !important;min-height:50px;border-radius:12px !important;border:none;font-weight:700}',
            '#btn-get-api-key{flex:1 1 0;background:#374151;color:#fff}',
            '.btn-engine-save{flex:1 1 0}',
            '.chap-remark-input{flex:1 1 0 !important}',
            'div[style*="gap:6px"]{gap:10px !important;margin-top:10px !important}',
            '#btn-fastcache-clear{margin-top:10px !important}',
            '.btn-game{font-size:16px !important;padding:14px 16px !important;min-height:52px;border-radius:12px !important;box-sizing:border-box;font-weight:700 !important}',
            '.game-links{gap:10px !important}',
            '.section[style*="flex-direction"]{gap:10px !important}',
            '.chap-file-badge{font-size:13px !important;padding:7px 10px !important;border-radius:8px !important}',
            '.chap-file-row{margin-bottom:12px !important}',
            '.chap-empty{font-size:14px !important}',
            '.copy-btn{font-size:13px !important;padding:9px 16px !important;border-radius:8px !important;min-height:38px;font-weight:700}',
            '.bank-info{font-size:15px !important;gap:12px !important;padding:14px !important;border-radius:10px !important}',
            '.donation-qr{max-width:260px !important;background:#fff;border-radius:10px !important;margin-bottom:12px !important}',
            '.donation-note{font-size:13px !important;margin-top:12px !important}',
            'button:active,.btn-game:active{transform:scale(.98);filter:brightness(1.12)}',
            '.toggle-wrap{cursor:pointer}'
        ].join('') : [
            // ---- Trang "Quản lý Từ điển": bố cục dọc cho điện thoại, mỗi câu thoại là 1 thẻ ----
            'html,body{overflow-x:hidden}',
            'body{padding:10px !important;font-size:16px;line-height:1.4}',
            '.container{padding:16px !important;max-width:none !important;border-radius:16px !important;box-shadow:none !important;background:#1a1f36 !important}',
            '.header{flex-direction:column;align-items:stretch !important;gap:12px !important;padding-bottom:14px !important;margin-bottom:16px !important;border-bottom-color:#2a3050 !important}',
            '.title h1{font-size:22px !important;line-height:1.25}',
            '.title p{font-size:14px !important;line-height:1.5}',
            '.header-actions{flex-direction:column;align-items:stretch !important;width:100%;gap:0 !important}',
            '.header-actions .toolbar{width:100%}',
            '.toolbar{gap:10px !important;margin-bottom:14px !important}',
            // nút
            '.btn{min-height:48px;padding:12px 18px !important;font-size:15px !important;border-radius:12px !important;white-space:normal !important;line-height:1.25}',
            '.header-actions .btn{flex:1 1 calc(50% - 5px)}',
            '#btn-search,#btn-clear-all{flex:0 0 auto}',
            '#btn-save-engine,#btn-retranslate-gemma,#btn-retranslate-gemma-retry{flex:1 1 100%}',
            // ô nhập / chọn: tràn hết bề ngang, chữ 16px để Android không tự zoom
            '.select-box,.search-box{flex:1 1 100% !important;width:100% !important;max-width:none !important;min-width:0 !important;box-sizing:border-box !important;min-height:48px;padding:12px 14px !important;border-radius:12px !important;font-size:16px !important}',
            '#search-input,#sort-select{flex:1 1 0 !important;width:auto !important}',
            // khối chọn công cụ dịch
            '.toolbar[style*="align-items"]{padding:14px !important;border-radius:14px !important;background:#0f1220 !important;border-color:#2a3050 !important}',
            '.toolbar[style*="align-items"] label{flex:1 1 100%;font-size:14px !important}',
            // bảng -> danh sách thẻ
            'table{display:block !important;width:100% !important;margin:0 !important}',
            'colgroup,thead{display:none !important}',
            'tbody{display:block !important}',
            'tbody tr{display:flex !important;flex-wrap:wrap;align-items:center;gap:10px;background:#0f1220 !important;border:1px solid #2a3050;border-radius:14px;padding:14px;margin:0 0 12px !important}',
            'tbody tr:hover{background:#0f1220 !important}',
            'tbody td{display:block;width:auto !important;border:0 !important;padding:0 !important;font-size:15px !important;min-width:0}',
            'td:nth-child(1){order:1;max-width:62%}',
            'td:nth-child(2){order:2}',
            'td:nth-child(5){order:3;margin-left:auto}',
            'td:nth-child(3){order:4;flex:1 1 100%;margin-top:4px;line-height:1.5;font-weight:400}',
            'td:nth-child(3) strong{font-weight:500}',
            'td:nth-child(4){order:5;flex:1 1 100%}',
            'td:nth-child(6){order:6;flex:1 1 100%}',
            'td:nth-child(3)::before,td:nth-child(4)::before{display:block;margin-bottom:6px;font-size:11px;font-weight:700;letter-spacing:.8px;color:#8b93b8}',
            'td:nth-child(3)::before{content:"GỐC"}',
            'td:nth-child(4)::before{content:"BẢN DỊCH"}',
            '.chap-badge{font-size:12px !important;padding:5px 10px !important;border-radius:8px !important}',
            '.speaker-badge{font-size:13px !important;padding:5px 12px !important}',
            '.time-cell{font-size:12px !important}',
            '.input-edit{min-height:84px !important;padding:12px !important;border-radius:10px !important;font-size:16px !important;line-height:1.5 !important}',
            '.actions{gap:10px !important;width:100%}',
            '.actions .btn{flex:1;padding:12px 10px !important}',
            '.status-bar{flex-wrap:wrap;gap:6px 16px;font-size:13px !important}',
            'button:active{transform:scale(.98);filter:brightness(1.12)}'
        ].join('');
        document.head.appendChild(big);

        // Chạm cả hàng công tắc là bật/tắt (công tắc nhỏ khó bấm).
        if (isPopup) {
            Array.prototype.forEach.call(document.querySelectorAll('.toggle-wrap'), function (row) {
                row.addEventListener('click', function (e) {
                    if (e.target.closest('label.switch')) return;
                    var cb = row.querySelector('input[type=checkbox]');
                    if (cb) cb.click();
                });
            });
        }

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
                '<a href="#" id="app-forget" class="btn-game" style="background-color:#7f1d1d"><span>🔑 Xóa tài khoản đã lưu</span><span></span></a>' +
                '</div>';
            document.getElementById('app-reload').addEventListener('click', function (e) {
                e.preventDefault();
                window.chrome.__app('reload');
            });
            document.getElementById('app-forget').addEventListener('click', function (e) {
                e.preventDefault();
                window.chrome.__app('forget_login');
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
