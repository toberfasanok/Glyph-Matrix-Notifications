package com.tober.glyphmatrix.notifications

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.tober.glyphmatrix.notifications.ui.theme.GlyphMatrixNotificationsTheme

private data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Bitmap?
)

@OptIn(ExperimentalMaterial3Api::class)
class AppSelectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GlyphMatrixNotificationsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TopAppBar(title = { Text(text = "Choose an app") })

                        var query by remember { mutableStateOf("") }

                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )

                        var apps by remember { mutableStateOf(listOf<AppEntry>()) }

                        LaunchedEffect(true) {
                            apps = getApps()
                        }

                        val filtered = apps.filter {
                            query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
                        }

                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(filtered) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val resultIntent = Intent().apply {
                                                putExtra(Constants.APP_GLYPH_PKG, entry.pkg)
                                            }
                                            setResult(RESULT_OK, resultIntent)
                                            finish()
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    entry.icon?.let { bmp ->
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.padding(start = 12.dp)) {
                                        Text(text = entry.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(text = entry.pkg, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }

                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                            horizontalArrangement = Arrangement.End) {
                            Button(onClick = {
                                setResult(RESULT_CANCELED)
                                finish()
                            }) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun getApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = packageManager
        val apps = mutableListOf<AppEntry>()

        try {
            val launchIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = pm.queryIntentActivities(launchIntent, PackageManager.ResolveInfoFlags.of(0))

            val seen = mutableSetOf<String>()

            for (resolveInfo in resolveInfos) {
                val pkg = resolveInfo.activityInfo.packageName

                if (!seen.add(pkg)) continue

                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)

                    val isAndroid = pkg.startsWith("com.android.") || pkg.startsWith("android.")
                    val isPureSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

                    if (isAndroid) continue
                    if (isPureSystem) continue

                    val label = resolveInfo.loadLabel(pm).toString()
                    val icon = resolveInfo.loadIcon(pm)
                    val bitmap = iconToBitmap(icon)
                    apps.add(AppEntry(pkg, label, bitmap))
                } catch (_: Throwable) {}
            }

            try {
                val appInfos = pm.getInstalledApplications(0)

                for (appInfo in appInfos) {
                    val pkg = appInfo.packageName

                    if (!seen.add(pkg)) continue

                    try {
                        pm.getLaunchIntentForPackage(pkg) ?: continue

                        val isAndroid = pkg.startsWith("com.android.") || pkg.startsWith("android.")
                        val isPureSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

                        if (isAndroid) continue
                        if (isPureSystem) continue

                        val label = pm.getApplicationLabel(appInfo).toString()
                        val bitmap = appIconToBitmap(appInfo)
                        apps.add(AppEntry(pkg, label, bitmap))
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}

        apps.sortedBy { it.label.lowercase() }
    }

    private fun iconToBitmap(icon: Drawable?): Bitmap? {
        if (icon == null) return null

        return try {
            if (icon is BitmapDrawable) return icon.bitmap

            val width = icon.intrinsicWidth.coerceAtLeast(1)
            val height = icon.intrinsicHeight.coerceAtLeast(1)
            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)

            icon.setBounds(0, 0, canvas.width, canvas.height)
            icon.draw(canvas)
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun appIconToBitmap(appInfo: ApplicationInfo): Bitmap? {
        return try {
            val icon: Drawable = packageManager.getApplicationIcon(appInfo)

            iconToBitmap(icon)
        } catch (_: Throwable) {
            null
        }
    }
}
