package app.ondesk

object SharedLink {
    fun validate(text: String): String {
        require(text.toByteArray().size in 1..4096 && text.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
        val uri = try { java.net.URI(text) } catch (error: java.net.URISyntaxException) { throw IllegalArgumentException("Некорректная ссылка", error) }
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535))
        return text
    }
    fun extract(text: String): String {
        require(text.length <= 65536)
        val trimmed = text.trim()
        runCatching { validate(trimmed) }.getOrNull()?.let { return it }
        val candidates = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE).findAll(trimmed).map { it.value }.distinct().toList()
        require(candidates.size == 1) { "Поделитесь одной HTTP/HTTPS ссылкой" }
        return validate(candidates.single())
    }
}
