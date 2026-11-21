package com.sirrlevi.guardian.child

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Base64
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.AppCategories
import com.sirrlevi.guardian.core.GuardianPaths
import com.sirrlevi.guardian.core.PkgKey
import java.io.ByteArrayOutputStream

/** Installed apps with their real names, categories and icons, uploaded so the parent sees the same picture the child sees. */
internal object AppCatalog {
    data class Entry(
        val packageName: String,
        val name: String,
        val category: String,
        val system: Boolean,
        val enabled: Boolean,
        val installedAt: Long,
        val updatedAt: Long,
        val version: String
    )

    fun collect(context: Context): List<Entry> {
        val pm = context.packageManager
        val out = ArrayList<Entry>()
        for (info in pm.getInstalledApplications(0)) {
            val pkg = info.packageName
            if (pkg == context.packageName) continue
            val launchable = pm.getLaunchIntentForPackage(pkg) != null
            val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!launchable && system) continue
            if (!launchable && !info.enabled) continue
            val pkgInfo = try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { null }
            val label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { pkg }
            out.add(
                Entry(
                    packageName = pkg,
                    name = label,
                    category = categoryOf(info, system),
                    system = system,
                    enabled = info.enabled,
                    installedAt = pkgInfo?.firstInstallTime ?: 0L,
                    updatedAt = pkgInfo?.lastUpdateTime ?: 0L,
                    version = pkgInfo?.versionName.orEmpty()
                )
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun categoryOf(info: ApplicationInfo, system: Boolean): String {
        AppCategories.hintFor(info.packageName)?.let { return it }
        if ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return "games"
        return when (info.category) {
            ApplicationInfo.CATEGORY_GAME -> "games"
            ApplicationInfo.CATEGORY_AUDIO -> "music"
            ApplicationInfo.CATEGORY_VIDEO -> "video"
            ApplicationInfo.CATEGORY_IMAGE -> "photo"
            ApplicationInfo.CATEGORY_SOCIAL -> "social"
            ApplicationInfo.CATEGORY_NEWS -> "news"
            ApplicationInfo.CATEGORY_MAPS -> "maps"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
            else -> if (system) "tools" else "other"
        }
    }

    @Suppress("DEPRECATION")
    fun iconBase64(context: Context, packageName: String): String? = try {
        val size = 72
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bitmap.compress(format, 80, out)
        bitmap.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Throwable) { null }

    /**
     * Uploads the app list (only when it changed) and any icons the parent does not have yet.
     * Returns true when something was sent.
     */
    fun sync(context: Context, cloud: ChildCloud, force: Boolean = false): Boolean {
        val session = cloud.session() ?: return false
        val family = cloud.familyId
        if (family.isBlank()) return false
        val prefs = context.getSharedPreferences("guardian_child", Context.MODE_PRIVATE)
        val entries = collect(context)

        val catalog = JsonObject()
        entries.forEach { e ->
            catalog.add(PkgKey.encode(e.packageName), JsonObject().apply {
                addProperty("n", e.name)
                addProperty("c", e.category)
                addProperty("s", e.system)
                addProperty("e", e.enabled)
                addProperty("it", e.installedAt)
                addProperty("ut", e.updatedAt)
                addProperty("v", e.version)
            })
        }
        var sent = false
        val hash = catalog.toString().hashCode()
        if (force || prefs.getInt("catalog_hash", 0) != hash) {
            cloud.api.put(GuardianPaths.catalog(family, session.uid), catalog, session)
            prefs.edit().putInt("catalog_hash", hash).apply()
            sent = true
        }

        val known = prefs.getStringSet("icon_sigs", emptySet()).orEmpty()
        val knownMap = known.mapNotNull { line ->
            val i = line.lastIndexOf('|')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        val pending = entries.filter { force || knownMap[it.packageName] != it.updatedAt.toString() }
        if (pending.isNotEmpty()) {
            val newSigs = HashMap(knownMap)
            pending.chunked(20).forEach { chunk ->
                val batch = JsonObject()
                val done = ArrayList<Entry>()
                chunk.forEach { e ->
                    val icon = iconBase64(context, e.packageName)
                    if (icon != null) {
                        batch.addProperty(PkgKey.encode(e.packageName), icon)
                        done.add(e)
                    }
                }
                if (batch.size() > 0) {
                    cloud.api.patch(GuardianPaths.icons(family, session.uid), batch, session)
                    done.forEach { newSigs[it.packageName] = it.updatedAt.toString() }
                    sent = true
                }
            }
            val live = entries.map { it.packageName }.toSet()
            prefs.edit().putStringSet(
                "icon_sigs",
                newSigs.filterKeys { it in live }.map { it.key + "|" + it.value }.toSet()
            ).apply()
        }
        return sent
    }
}
