package top.rootu.lampa.helpers

/**
 * D1Vision: единая страница-заглушка «нет сети» для обоих браузеров
 * (SysView WebView и XWalk). Вынесена из SysView, чтобы XWalk мог показать
 * ту же разметку при ERR_INTERNET_DISCONNECTED (перебор хостов без сети бессмыслен).
 */
object ErrorHtml {
    fun createErrorHtmlPage(
        errorMessage: String,
        iconColor: String = "#D72828",
        textColor: String = "#E6E6E6"
    ): String {
        return """
        <html>
            <body style="margin:0;padding:0;overflow:hidden;">
                <div style="display:table;width:100%;height:100vh;overflow:hidden;">
                    <div align="center" style="display:table-cell;vertical-align:middle;">
                        <svg width="120" height="120"
                             style="overflow:visible;enable-background:new 0 0 120 120"
                             viewBox="0 0 32 32"
                             xmlns="http://www.w3.org/2000/svg">
                            <g>
                                <circle cx="16" cy="16" r="16" style="fill:$iconColor;"/>
                                <path d="M14.5,25h3v-3h-3V25z M14.5,6v13h3V6H14.5z"
                                      style="fill:$textColor;"/>
                            </g>
                        </svg>
                        <br/><br/>
                        <p style="color:$textColor;">$errorMessage</p>
                    </div>
                </div>
            </body>
        </html>
        """.trimIndent()
    }
}
