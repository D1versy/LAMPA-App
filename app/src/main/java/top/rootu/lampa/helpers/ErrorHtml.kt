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

    /**
     * D1Vision: экран «Сервер недоступен» — показывается, когда НИ ОДИН хост
     * (LAN → tv → tv2) не ответил. Тёмный фон (иначе на ТВ мелькает белым),
     * спиннер + текст автопереподключения. Реконнект делается НАТИВНО
     * (перепроба хостов), а не в этой странице — тут только сообщение.
     */
    fun createServerUnavailablePage(
        title: String = "Сервер недоступен",
        subtitle: String = "Не удаётся подключиться ни к одному адресу. Пытаемся переподключиться…"
    ): String {
        return """
        <html>
            <head><meta name="viewport" content="width=device-width,initial-scale=1"/></head>
            <body style="margin:0;padding:0;overflow:hidden;background:#101114;font-family:sans-serif;">
                <style>
                    @keyframes d1vspin{to{transform:rotate(360deg)}}
                    .d1vspin{width:56px;height:56px;border:5px solid #2a2c31;
                        border-top-color:#4a90d9;border-radius:50%;
                        animation:d1vspin 1s linear infinite;margin:0 auto;}
                </style>
                <div style="display:table;width:100%;height:100vh;">
                    <div align="center" style="display:table-cell;vertical-align:middle;padding:0 24px;">
                        <div class="d1vspin"></div>
                        <br/>
                        <p style="color:#E6E6E6;font-size:26px;font-weight:600;margin:8px 0;">$title</p>
                        <p style="color:#9AA0A6;font-size:16px;margin:0;">$subtitle</p>
                    </div>
                </div>
            </body>
        </html>
        """.trimIndent()
    }
}
