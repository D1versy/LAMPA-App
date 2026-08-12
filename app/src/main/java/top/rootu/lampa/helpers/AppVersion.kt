package top.rootu.lampa.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

data class AppVersion(
    val versionName: String,
    val versionNumber: Long,
)

fun getAppVersion(
    context: Context,
    packageName: String = context.packageName
): AppVersion? {
    return try {
        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        AppVersion(
            // С compileSdk 36 PackageInfo.versionName объявлен @Nullable (у приложений без
            // versionName в манифесте он и правда null) — раньше платформа этого не сообщала.
            versionName = packageInfo.versionName.orEmpty(),
            versionNumber = PackageInfoCompat.getLongVersionCode(packageInfo),
        )
    } catch (_: Exception) {
        null
    }
}