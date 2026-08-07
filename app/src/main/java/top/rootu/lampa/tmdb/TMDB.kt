package top.rootu.lampa.tmdb

import android.net.Uri
import androidx.core.net.toUri
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import top.rootu.lampa.App
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.MainActivity
import top.rootu.lampa.helpers.D1VAuth
import top.rootu.lampa.helpers.Helpers.debugLog
import top.rootu.lampa.helpers.Helpers.getJson
import top.rootu.lampa.helpers.Prefs.appLang
import top.rootu.lampa.helpers.Prefs.appUrl
import top.rootu.lampa.helpers.Prefs.tmdbApiUrl
import top.rootu.lampa.helpers.Prefs.tmdbImgUrl
import top.rootu.lampa.helpers.capitalizeFirstLetter
import top.rootu.lampa.net.HttpHelper
import top.rootu.lampa.tmdb.models.entity.Entities
import top.rootu.lampa.tmdb.models.entity.Entity
import top.rootu.lampa.tmdb.models.entity.Genre
import java.io.IOException
import java.util.Locale

object TMDB {
    /**
     * D1Vision: за TMDB ходим ТОЛЬКО через прокси своего сервера (`<host>/tmdb/api`,
     * `<host>/tmdb/img`) — наружу ходит сервер, он же кеширует и подставляет свой ключ
     * (поэтому `api_key` из клиента убран).
     *
     * Это ДЕФОЛТЫ для prefs: реальные адреса оболочка зеркалит из веб-страницы
     * (`AndroidJS.storageChange` → `baseUrlApiTMDB`/`baseUrlImageTMDB`). Раньше дефолты
     * вели на api.themoviedb.org, и свежая установка / headless-старт стучались наружу.
     */
    val APIURL: String get() = "${serverBase()}/tmdb/api/3/"
    val IMGURL: String get() = "${serverBase()}/tmdb/img/"

    /**
     * База TMDB-прокси: активный хост оболочки (победитель гонки [top.rootu.lampa.helpers.HostResolver]),
     * иначе сохранённый/зашитый адрес сервера, иначе первый bootstrap-хост из
     * `BuildConfig.fallbackHosts` (свежая установка, хост ещё не выбран).
     */
    private fun serverBase(): String {
        val active = MainActivity.LAMPA_URL.trim().trimEnd('/')
        if (active.isNotEmpty()) return active
        val saved = App.context.appUrl.trim().trimEnd('/')
        if (saved.isNotEmpty()) return saved
        return BuildConfig.fallbackHosts.split(",")
            .map { it.trim().trimEnd('/') }
            .firstOrNull { it.isNotEmpty() } ?: ""
    }

    // Таймаут запросов к своему серверу (был зашит в клиенте Quad9)
    private const val REQUEST_TIMEOUT_MS = 15000

    /**
     * Клиент для запросов к своему серверу (TMDB-прокси + постеры через Glide).
     *
     * DoH-резолвер Quad9 убран: он видел даже обращения к нашему домену, а в LAN DNS не
     * нужен вовсе — берём системный резолвер. Ключ периметра дописывается интерцептором:
     * вне дома прокси закрыт им же, а UA-токен нужен серверу для форса платформы.
     */
    val okHttp: OkHttpClient by lazy {
        HttpHelper.getOkHttpClient(REQUEST_TIMEOUT_MS).newBuilder()
            .dns(Dns.SYSTEM)
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url().toString()
                val signed = D1VAuth.sign(url) ?: url
                chain.proceed(
                    request.newBuilder()
                        .header("User-Agent", HttpHelper.userAgent)
                        .apply { if (signed != url) url(signed) }
                        .build()
                )
            }
            .build()
    }

    private var movieGenres: List<Genre?> = emptyList()
    private var tvGenres: List<Genre?> = emptyList()
    private val _genres by lazy {
        val ret = hashMapOf<Int, String>()
        populateGenres(movieGenres, ret)
        populateGenres(tvGenres, ret)
        ret
    }

    /* return lowercase 2-digit lang tag */
    fun getLang(): String {
        val appLang = App.context.appLang
        if (appLang.isNotEmpty())
            appLang.apply {
                val languageCode = this
                var loc = Locale(languageCode.lowercase())
                if (languageCode.split("-").size > 1) {
                    val language = languageCode.split("-")[0].lowercase()
                    val country = languageCode.split("-")[1].uppercase()
                    loc = Locale(language, country)
                }
                return loc.language
            }

        val lang = Locale.getDefault().language
        return when {
            lang.equals("IW", ignoreCase = true) -> {
                "he"
            }

            lang.equals("IN", ignoreCase = true) -> {
                "id"
            }

            lang.equals("JI", ignoreCase = true) -> {
                "yi"
            }

            lang.equals("LV", ignoreCase = true) -> {
                "en" // FIXME: Empty Genre Names on LV, so force EN for TMDB requests
            }

            else -> {
                lang
            }
        }
    }

    // https://developers.themoviedb.org/3/genres/get-movie-list
    // https://developers.themoviedb.org/3/genres/get-tv-list
    fun initGenres() {
        try {
            movieGenres = fetchGenres("genre/movie/list") ?: emptyList()
            tvGenres = fetchGenres("genre/tv/list") ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fetchGenres(endpoint: String): List<Genre>? {
        return video(endpoint)?.genres
    }

    private fun populateGenres(genreList: List<Genre?>, ret: HashMap<Int, String>) {
        for (genre in genreList) {
            genre?.let {
                if (!genre.name.isNullOrEmpty()) {
                    ret[genre.id] = genre.name.capitalizeFirstLetter()
                }
            }
        }
    }

    val genres: Map<Int, String> get() = _genres

    fun videos(endpoint: String, params: MutableMap<String, String>): Entities? {
        val apiUrl = App.context.tmdbApiUrl
        val apiUri = apiUrl.toUri()
        // Manually handle the authority part to prevent encoding of the port colon
        val authority = "${apiUri.host}${if (apiUri.port != -1) ":${apiUri.port}" else ""}"
        val basePath = apiUri.path?.removeSuffix("/") ?: "3"
        val urlBuilder = Uri.Builder()
            .scheme(apiUri.scheme)
            .encodedAuthority(authority)  // Use encodedAuthority instead of authority to prevent double encoding
            .path("$basePath/$endpoint")
        // api_key не шлём: ключ подставляет наш сервер (см. шапку файла)
        params["language"] = getLang()
        for (param in params) {
            urlBuilder.appendQueryParameter(param.key, param.value)
        }
        // Add all original query parameters
        apiUri.queryParameterNames.forEach { paramName ->
            apiUri.getQueryParameter(paramName)?.let { paramValue ->
                urlBuilder.appendQueryParameter(paramName, paramValue)
            }
        }

        var body: String? = null
        val link = urlBuilder.build().toString()
        debugLog("TMDB videos($endpoint) apiUri[$apiUri] link[$link]")
        try {
            val request = Request.Builder()
                .url(link)
                .build()
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                body = response.body()?.string()
                response.body()?.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        debugLog("TMDB body: $body")
        if (body.isNullOrEmpty())
            return null

        val entities = getJson(body, Entities::class.java)
        val ret = mutableListOf<Entity>()

        entities?.results?.forEach {
            if (it.media_type == null)
                fixEntity(it)
            if (it.media_type == "movie" || it.media_type == "tv") {
                val ent = video("${it.media_type}/${it.id}")
                ent?.let {
                    fixEntity(ent)
                    ret.add(ent)
                }
            }
        }
        entities?.results = ret
        return entities
    }

    fun video(endpoint: String): Entity? {
        val appLang = getLang()
        return videoDetail(endpoint, appLang)
    }

    private fun videoDetail(endpoint: String, lang: String = ""): Entity? {
        val apiUrl = App.context.tmdbApiUrl
        val apiUri = apiUrl.toUri()
        // Manually handle the authority part to prevent encoding of the port colon
        val authority = "${apiUri.host}${if (apiUri.port != -1) ":${apiUri.port}" else ""}"
        val basePath = apiUri.path?.removeSuffix("/") ?: "3"
        val urlBuilder = Uri.Builder()
            .scheme(apiUri.scheme)
            .encodedAuthority(authority)  // Use encodedAuthority instead of authority to prevent double encoding
            .path("$basePath/$endpoint")
        // api_key не шлём: ключ подставляет наш сервер (см. шапку файла)
        val params = mutableMapOf<String, String>()
        if (lang.isBlank())
            params["language"] = getLang()
        else params["language"] = lang
        params["append_to_response"] = "videos,images,alternative_titles"
        params["include_image_language"] = "${getLang()},ru,en,null"
        for (param in params) {
            urlBuilder.appendQueryParameter(param.key, param.value)
        }
        // Add all original query parameters
        apiUri.queryParameterNames.forEach { paramName ->
            apiUri.getQueryParameter(paramName)?.let { paramValue ->
                urlBuilder.appendQueryParameter(paramName, paramValue)
            }
        }

        var body: String? = null
        val link = urlBuilder.build().toString()
        // debugLog("TMDB videoDetail($endpoint) apiUri[$apiUri] link[$link]")
        try {
            val request = Request.Builder()
                .url(link)
                .build()
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                body = response.body()?.string()
                response.body()?.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // debugLog("TMDB body: $body")
        if (body.isNullOrEmpty())
            return null

        val ent = getJson(body, Entity::class.java)
        ent?.let { fixEntity(it) }

        return ent
    }

    private fun fixEntity(ent: Entity) {
        if (ent.title == null && ent.name == null)
            return
        // media types
        if (ent.media_type.isNullOrEmpty()) {
            if (ent.title.isNullOrEmpty())
                ent.media_type = "tv"
            else if (ent.name.isNullOrEmpty())
                ent.media_type = "movie"
        }
        // titles
        if (ent.title.isNullOrEmpty() && !ent.name.isNullOrEmpty())
            ent.title = ent.name
        if (ent.original_title.isNullOrEmpty() && !ent.original_name.isNullOrEmpty())
            ent.original_title = ent.original_name
        // release_date
        if (!ent.release_date.isNullOrEmpty() && ent.release_date?.length!! >= 4)
            ent.year = ent.release_date?.substring(0, 4) ?: ""
        else if (!ent.first_air_date.isNullOrEmpty() && ent.first_air_date?.length!! >= 4)
            ent.year = ent.first_air_date?.substring(0, 4) ?: ""
        if (ent.release_date.isNullOrEmpty() && !ent.first_air_date.isNullOrEmpty())
            ent.release_date = ent.first_air_date
        // images
        ent.poster_path = imageUrl(ent.poster_path).replace("original", "w342")
        ent.backdrop_path = imageUrl(ent.backdrop_path).replace("original", "w1280")
        ent.images?.let { img ->
            for (i in img.backdrops.indices)
                ent.images!!.backdrops[i].file_path =
                    imageUrl(img.backdrops[i].file_path).replace("original", "w1280")

            for (i in img.posters.indices)
                ent.images!!.posters[i].file_path =
                    imageUrl(img.posters[i].file_path).replace("original", "w342")
        }
        ent.production_companies?.let {
            it.forEach { co ->
                co.logo_path = imageUrl(co.logo_path).replace("original", "w185")
            }
        }
        ent.seasons?.let { sn ->
            sn.forEach {
                it.poster_path = imageUrl(it.poster_path).replace("original", "w342")
            }
        }
    }

    fun imageUrl(path: String?): String {
        path?.let {
            if (it.startsWith("http"))
                return it
        }
        if (path.isNullOrEmpty())
            return ""

        // "https://image.tmdb.org/t/p/original$path"
        val imgUrl = App.context.tmdbImgUrl
        // "http://proxy.host:1488/tmdb/img/?account_email=mail%40gmail.com&uid=133t"
        val imgUri = imgUrl.toUri()
        // Manually handle the authority part to prevent encoding of the port colon
        val authority = "${imgUri.host}${if (imgUri.port != -1) ":${imgUri.port}" else ""}"
        // Remove trailing slash from the original path if present
        val basePath = imgUri.path?.removeSuffix("/") ?: ""
        // Create Uri.Builder with base components
        val builder = Uri.Builder()
            .scheme(imgUri.scheme)
            .encodedAuthority(authority)  // Use encodedAuthority instead of authority to prevent double encoding
            .path("$basePath/t/p/original$path")
        // Add all original query parameters
        imgUri.queryParameterNames.forEach { paramName ->
            imgUri.getQueryParameter(paramName)?.let { paramValue ->
                builder.appendQueryParameter(paramName, paramValue)
            }
        }
        // debugLog("TMDB imageUrl($path) imgUri[$imgUri] link[${builder.build()}]")
        return builder.build().toString()
    }
}