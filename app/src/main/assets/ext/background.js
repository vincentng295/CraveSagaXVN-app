// Service worker (MV3) của bản extension Chrome PC.
// Việc quay tab nằm trong offscreen document (offscreen.html/js) vì service worker không giữ được MediaRecorder;
// file này chỉ tạo offscreen document khi cần và chuyển tiếp các lệnh RECORD_* từ popup sang đó.
const OFFSCREEN_URL = 'offscreen.html';

async function hasOffscreen() {
    const ctx = await chrome.runtime.getContexts({
        contextTypes: ['OFFSCREEN_DOCUMENT'],
        documentUrls: [chrome.runtime.getURL(OFFSCREEN_URL)]
    });
    return ctx.length > 0;
}

async function ensureOffscreen() {
    if (await hasOffscreen()) return;
    await chrome.offscreen.createDocument({
        url: OFFSCREEN_URL,
        reasons: ['USER_MEDIA'],
        justification: 'Ghi hình + âm thanh của tab game để xuất file .webm'
    });
}

async function forward(message) {
    return chrome.runtime.sendMessage({ ...message, target: 'offscreen' });
}

async function handle(message) {
    switch (message.type) {
        case 'RECORD_STATUS':
            // Chưa có offscreen document nghĩa là chưa từng quay -> khỏi tạo chỉ để hỏi trạng thái.
            if (!(await hasOffscreen())) return { recording: false, startTime: null };
            return forward({ type: 'RECORD_STATUS' });
        case 'RECORD_START':
            await ensureOffscreen();
            return forward({ type: 'RECORD_START', streamId: message.streamId });
        case 'RECORD_STOP':
            if (!(await hasOffscreen())) return { ok: true, alreadyStopped: true };
            return forward({ type: 'RECORD_STOP' });
    }
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    // Bỏ qua tin gửi cho offscreen (do chính file này chuyển tiếp) và tin không phải lệnh quay.
    if (!message || message.target === 'offscreen' || !/^RECORD_(STATUS|START|STOP)$/.test(message.type)) return;
    handle(message)
        .then((res) => sendResponse(res || { ok: false, error: 'Không có phản hồi từ trình quay.' }))
        .catch((e) => sendResponse({ ok: false, error: (e && e.message) || String(e) }));
    return true; // phản hồi bất đồng bộ
});
