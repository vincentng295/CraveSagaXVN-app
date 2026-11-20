# Crave Saga X VN – bản Android (APK)

Crave Saga X Tiếng Việt Cho Android

- Mở app → chọn phiên bản game (Nutaku / Erolabs) → trang đăng nhập (chỉ hiện form) → đăng nhập xong tự vào game trong WebView. Liên kết khác mở bằng trình duyệt máy.
- Nút ☰ nổi ở mép phải màn hình (chạm để mở, kéo dọc để dời) → menu bên phải chính là `popup.html` của extension.
- Màn hình chọn game có nút **Từ điển / Cài đặt dịch** (= `options.html`), popup cũng mở được trang này.

- **Dịch Gemma chạy nền:** nút "Dịch lại bằng Gemma" trong Quản lý từ điển chạy bằng foreground service native, có thông báo tiến độ (nút Dừng) và thông báo kết quả/lỗi; vẫn tiếp tục khi thoát trang, thoát game hoặc đóng app.
- Màn hình chọn game có nút **Bỏ qua tối ưu hóa pin** để Android không ngắt việc dịch nền.

## Build APK

**Cách 1 – GitHub Actions (không cần cài gì):** push project lên GitHub, workflow `Build APK` sẽ tạo `CraveSagaX_VN.apk`
(trong Artifacts và release `apk-latest`).

**Cách 2 – Android Studio:** `File > Open` thư mục này, đợi Gradle sync, `Build > Build APK(s)`.
(Cần JDK 17, Android SDK 35.)

**Cách 3 – dòng lệnh:** `gradle :app:assembleRelease` (Gradle 8.9+), APK ở `app/build/outputs/apk/release/`.

Yêu cầu máy: Android 7.0+ và Android System WebView / Chrome bản mới (cần WebMessageListener).

## Extension Chrome (PC)

CI đóng gói thêm `CraveSagaX_VN_crx.zip` và đẩy lên cả `apk-latest` lẫn `apk-release`.
Cách cài: giải nén → mở `chrome://extensions` → bật **Developer mode** → **Load unpacked** → chọn thư mục vừa giải nén.

