package top.rootu.lampa

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.Excludes
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpLibraryGlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import top.rootu.lampa.tmdb.TMDB
import java.io.InputStream

@GlideModule
@Excludes(OkHttpLibraryGlideModule::class)
class LampaGlideModule : AppGlideModule() {
    override fun isManifestParsingEnabled(): Boolean {
        return false
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // Тот же клиент, что у TMDB: системный резолвер (DoH убран) + подпись ключом
        // периметра — постеры теперь тоже идут через наш сервер
        val factory = OkHttpUrlLoader.Factory(TMDB.okHttp)
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            factory
        )
    }
}