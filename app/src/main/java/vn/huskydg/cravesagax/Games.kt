package vn.huskydg.cravesagax

/**
 * Bước đăng nhập trước khi vào game.
 * [url] là trang đăng nhập; [formSelector] là phần tử duy nhất được hiển thị trên trang đó.
 * Khi trang chuyển hướng khỏi [url] (đăng nhập thành công) app sẽ lưu tài khoản (mã hóa) và tự vào [Game.url].
 */
data class LoginFlow(
    val url: String,
    val formSelector: String,
    /** Ô email/tên đăng nhập và ô mật khẩu (để lưu và điền sẵn tài khoản). */
    val userSelector: String,
    val passSelector: String
)

/** Một phiên bản game (nhà phát hành) hiển thị ở màn hình chọn. */
data class Game(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val accent: Int,
    val login: LoginFlow? = null
)

object Games {
    // Muốn thêm/bớt phiên bản chỉ cần sửa danh sách này.
    // FANZA (tạm bỏ): https://play.games.dmm.co.jp/game/cravesagax
    val all: List<Game> = listOf(
        Game(
            id = "nutaku",
            title = "Nutaku",
            subtitle = "Bản quốc tế (tiếng Anh) – nutaku.net",
            url = "https://www.nutaku.net/games/crave-saga-x/play",
            accent = 0xFFE11D48.toInt(),
            login = LoginFlow(
                url = "https://www.nutaku.net/login",
                formSelector = "form.js-login-modal-form",
                userSelector = "input[name=email]",
                passSelector = "input[name=password]"
            )
        ),
        Game(
            id = "erolabs",
            title = "Erolabs",
            subtitle = "Bản quốc tế (tiếng Anh) – ero-labs.com",
            url = "https://www.ero-labs.com/en/cloud_game.html?id=47&connect_type=1&connection_id=30",
            accent = 0xFF7C3AED.toInt(),
            login = LoginFlow(
                url = "https://www.ero-labs.com/en/login.html",
                formSelector = "#login > main > section:nth-child(1)",
                userSelector = "#loginemail",
                passSelector = "#loginpassword"
            )
        )
    )

    fun byId(id: String?): Game? = all.firstOrNull { it.id == id }
}
