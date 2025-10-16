package com.tober.glyphmatrix.notifications

import android.content.Intent
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

import com.tober.glyphmatrix.notifications.ui.theme.GlyphMatrixNotificationsTheme

import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class AppGlyph(
    val pkg: String,
    val label: String,
    val glyph: String
)

class MainActivity : ComponentActivity() {
    private val tag = "Main Activity"

    private var hasNotificationAccess by mutableStateOf(false)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            updateNotificationAccessState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        contentResolver.registerContentObserver(Settings.Secure.getUriFor("enabled_notification_listeners"), false, observer)
        hasNotificationAccess = getNotificationAccess()

        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)

        var defaultGlyph by mutableStateOf(preferences.getString(Constants.PREFERENCES_DEFAULT_GLYPH, null))

        val appGlyphs = mutableStateListOf<AppGlyph>().apply { addAll(readAppGlyphs()) }
        var newAppGlyphPkg by mutableStateOf("")
        var newAppGlyphLabel by mutableStateOf("")
        var newAppGlyph by mutableStateOf("")

        val ignoredAppGlyphs = mutableStateListOf<AppGlyph>().apply { addAll(readIgnoredAppGlyphs()) }
        var newIgnoredAppGlyphPkg by mutableStateOf("")
        var newIgnoredAppGlyphLabel by mutableStateOf("")

        val appSelectorActivityLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val pkg = result.data?.getStringExtra(Constants.APP_GLYPH_PKG)

                if (!pkg.isNullOrBlank()) {
                    newAppGlyphPkg = pkg

                    try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        val appLabel = packageManager.getApplicationLabel(appInfo).toString()
                        newAppGlyphLabel = appLabel
                    } catch (_: Throwable) {
                        newAppGlyphLabel = pkg
                    }
                }
            }
        }

        val ignoredAppSelectorActivityLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val pkg = result.data?.getStringExtra(Constants.APP_GLYPH_PKG)

                if (!pkg.isNullOrBlank()) {
                    newIgnoredAppGlyphPkg = pkg

                    try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        val appLabel = packageManager.getApplicationLabel(appInfo).toString()
                        newIgnoredAppGlyphLabel = appLabel
                    } catch (_: Throwable) {
                        newIgnoredAppGlyphLabel = pkg
                    }
                }
            }
        }

        val defaultGlyphImageLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri == null) {
                return@registerForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}

            val newFile = File(filesDir, "default_glyph_${System.currentTimeMillis()}.png")

            try {
                contentResolver.openInputStream(uri).use { inputStream ->
                    if (inputStream == null) {
                        toast("Failed to open selected image")
                        return@registerForActivityResult
                    }

                    FileOutputStream(newFile).use { out ->
                        val buffer = ByteArray(8 * 1024)

                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }

                        out.flush()
                    }
                }

                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(newFile.absolutePath, options)
                val width = options.outWidth
                val height = options.outHeight

                if (width != height) {
                    toast("Image must be 1:1 (square)")
                }
                else {
                    preferences.getString(Constants.PREFERENCES_DEFAULT_GLYPH, null)?.let { oldFile ->
                        try { File(oldFile).takeIf { it.exists() }?.delete() } catch (_: Throwable) {}
                    }

                    preferences.edit { putString(Constants.PREFERENCES_DEFAULT_GLYPH, newFile.absolutePath) }
                    defaultGlyph = newFile.absolutePath
                    toast("Default glyph saved")
                    broadcastPreferencesUpdate()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to save default glyph: $e")
                toast("Failed to save default glyph")
            }
        }

        val appGlyphsImageLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri == null) {
                return@registerForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}

            try {
                val newFile = File(filesDir, "tmp_glyph_${System.currentTimeMillis()}.png")

                filesDir.listFiles()?.filter { it.name.startsWith("tmp_glyph_") && it.name.endsWith(".png") && it.absolutePath != newFile.absolutePath }
                    ?.forEach { try { it.delete() } catch (_: Throwable) {} }

                contentResolver.openInputStream(uri).use { inputStream ->
                    if (inputStream == null) {
                        toast("Failed to open selected image")
                        return@registerForActivityResult
                    }

                    FileOutputStream(newFile).use { out ->
                        val buffer = ByteArray(8 * 1024)

                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }

                        out.flush()
                    }
                }

                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(newFile.absolutePath, options)
                val width = options.outWidth
                val height = options.outHeight

                if (width != height) {
                    toast("Image must be 1:1 (square)")
                }
                else {
                    newAppGlyph = newFile.absolutePath
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to save tmp glyph: $e")
                toast("Failed to save tmp glyph")
            }
        }

        setContent {
            GlyphMatrixNotificationsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top
                    ) {
                        if (!hasNotificationAccess) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Notification access is required for the app to detect notifications and show glyphs automatically. Please grant Notification Access.")

                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        startActivity(intent)
                                    },
                                    modifier = Modifier.padding(top = 12.dp)
                                ) {
                                    Text(text = "Open Notification Access Settings")
                                }
                            }
                        } else {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Glyph Timeout", modifier = Modifier.padding(bottom = 8.dp))

                                var savedGlyphTimeout by rememberSaveable { mutableStateOf(preferences.getLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L).toString()) }

                                OutlinedTextField(
                                    value = savedGlyphTimeout,
                                    onValueChange = { value ->
                                        val filtered = value.filter { it.isDigit() }
                                        savedGlyphTimeout = filtered
                                    },
                                    label = { Text("Timeout (seconds)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.padding(top = 12.dp)
                                )

                                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val timeout = savedGlyphTimeout.toLongOrNull() ?: 5L
                                        preferences.edit { putLong(Constants.PREFERENCES_GLYPH_TIMEOUT, timeout) }
                                        broadcastPreferencesUpdate()
                                        toast("Timeout saved")
                                    }) {
                                        Text(text = "Save")
                                    }

                                    Button(onClick = {
                                        savedGlyphTimeout = "5"
                                        preferences.edit { putLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L) }
                                        broadcastPreferencesUpdate()
                                        toast("Timeout reset")
                                    }) {
                                        Text(text = "Reset")
                                    }
                                }

                                Spacer(modifier = Modifier.height(15.dp))

                                var animateGlyphs by rememberSaveable { mutableStateOf(preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)) }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Animate Glyphs",
                                        style = MaterialTheme.typography.bodyLarge
                                    )

                                    Switch(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        checked = animateGlyphs,
                                        onCheckedChange = { checked ->
                                            animateGlyphs = checked
                                            preferences.edit { putBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, checked) }
                                            broadcastPreferencesUpdate()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                    )
                                }

                                if (animateGlyphs) {
                                    Spacer(modifier = Modifier.height(15.dp))

                                    Text(text = "Animate Speed", modifier = Modifier.padding(bottom = 8.dp))

                                    var savedAnimateSpeed by rememberSaveable { mutableStateOf(preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L).toString()) }

                                    OutlinedTextField(
                                        value = savedAnimateSpeed,
                                        onValueChange = { value ->
                                            val filtered = value.filter { it.isDigit() }
                                            savedAnimateSpeed = filtered
                                        },
                                        label = { Text("Speed (milliseconds)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.padding(top = 12.dp)
                                    )

                                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {
                                            val animateSpeed = savedAnimateSpeed.toLongOrNull() ?: 10L
                                            preferences.edit { putLong(Constants.PREFERENCES_ANIMATE_SPEED, animateSpeed) }
                                            broadcastPreferencesUpdate()
                                            toast("Animate speed saved")
                                        }) {
                                            Text(text = "Save")
                                        }

                                        Button(onClick = {
                                            savedAnimateSpeed = "10"
                                            preferences.edit { putLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L) }
                                            broadcastPreferencesUpdate()
                                            toast("Animate speed reset")
                                        }) {
                                            Text(text = "Reset")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Default Glyph", modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement  = Arrangement.SpaceBetween
                                ) {
                                    val savedDefaultGlyph = remember(defaultGlyph) {
                                        defaultGlyph?.let { path ->
                                            try { BitmapFactory.decodeFile(path) } catch (_: Throwable) { null }
                                        }
                                    }

                                    if (savedDefaultGlyph != null) {
                                        Image(
                                            painter = BitmapPainter(savedDefaultGlyph.asImageBitmap(), filterQuality = FilterQuality.None),
                                            contentDescription = "Default Glyph Preview",
                                            modifier = Modifier
                                                .size(76.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { defaultGlyphImageLauncher.launch(arrayOf("image/*")) }
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(76.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable { defaultGlyphImageLauncher.launch(arrayOf("image/*")) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(text = "+", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }

                                    if (defaultGlyph != null) {
                                        Button(modifier = Modifier.padding(horizontal = 12.dp), onClick = {
                                            preferences.getString(Constants.PREFERENCES_DEFAULT_GLYPH, null)?.let { path ->
                                                try { File(path).takeIf { it.exists() }?.delete() } catch (_: Throwable) {}
                                            }
                                            preferences.edit { remove(Constants.PREFERENCES_DEFAULT_GLYPH) }
                                            defaultGlyph = null
                                            broadcastPreferencesUpdate()
                                            toast("Default glyph removed")
                                        }) {
                                            Text(text = "-")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Ignored App Glyphs", modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))

                                Card(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    val intent = Intent(this@MainActivity, AppSelectorActivity::class.java)
                                                    ignoredAppSelectorActivityLauncher.launch(intent)
                                                }
                                        ) {
                                            Text(
                                                text = if (!newIgnoredAppGlyphLabel.isBlank()) newIgnoredAppGlyphLabel else "Choose an app",
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = if (!newIgnoredAppGlyphPkg.isBlank()) newIgnoredAppGlyphPkg else "-",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        Spacer(modifier = Modifier.size(8.dp))

                                        Button(onClick = {
                                            if (newIgnoredAppGlyphPkg.isBlank()) {
                                                toast("Choose an app")
                                                return@Button
                                            }

                                            ignoredAppGlyphs.removeAll { it.pkg == newIgnoredAppGlyphPkg }
                                            ignoredAppGlyphs.add(AppGlyph(newIgnoredAppGlyphPkg, newIgnoredAppGlyphLabel, ""))
                                            writeIgnoredAppGlyphs(ignoredAppGlyphs)

                                            newIgnoredAppGlyphLabel = ""
                                            newIgnoredAppGlyphPkg = ""
                                            toast("Ignored app glyph saved")
                                        }) {
                                            Text(text = "+")
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                for (item in ignoredAppGlyphs) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(text = item.label, style = MaterialTheme.typography.bodyLarge)
                                                Text(text = item.pkg, style = MaterialTheme.typography.bodySmall)
                                            }

                                            Spacer(modifier = Modifier.size(8.dp))

                                            Button(onClick = {
                                                ignoredAppGlyphs.remove(item)
                                                writeIgnoredAppGlyphs(ignoredAppGlyphs)
                                                toast("Ignored app glyph removed")
                                            }) {
                                                Text(text = "-")
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "App Glyphs", modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))

                                Card(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val tmpAppGlyph = remember(newAppGlyph) {
                                            newAppGlyph.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
                                        }

                                        if (tmpAppGlyph != null) {
                                            Image(
                                                painter = BitmapPainter(tmpAppGlyph.asImageBitmap(), filterQuality = FilterQuality.None),
                                                contentDescription = "App Glyph Preview",
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { appGlyphsImageLauncher.launch(arrayOf("image/*")) }
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable { appGlyphsImageLauncher.launch(arrayOf("image/*")) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(text = "+", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }

                                        Spacer(modifier = Modifier.size(12.dp))

                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    val intent = Intent(this@MainActivity, AppSelectorActivity::class.java)
                                                    appSelectorActivityLauncher.launch(intent)
                                                }
                                        ) {
                                            Text(
                                                text = if (!newAppGlyphLabel.isBlank()) newAppGlyphLabel else "Choose an app",
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = if (!newAppGlyphPkg.isBlank()) newAppGlyphPkg else "-",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        Spacer(modifier = Modifier.size(8.dp))

                                        Button(onClick = {
                                            if (newAppGlyphPkg.isBlank()) {
                                                toast("Choose an app")
                                                return@Button
                                            }
                                            if (newAppGlyph.isBlank()) {
                                                toast("Choose a glyph")
                                                return@Button
                                            }

                                            val safeName = newAppGlyphPkg.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                                            val dest = File(filesDir, "app_glyph_${safeName}_${System.currentTimeMillis()}.png")

                                            try {
                                                File(newAppGlyph).copyTo(dest, overwrite = true)
                                            } catch (e: Exception) {
                                                Log.e(tag, "Failed to save app glyph: $e")
                                                toast("Failed to save app glyph")
                                                return@Button
                                            }

                                            appGlyphs.removeAll { it.pkg == newAppGlyphPkg }
                                            appGlyphs.add(AppGlyph(newAppGlyphPkg, newAppGlyphLabel, dest.absolutePath))
                                            writeAppGlyphs(appGlyphs)

                                            newAppGlyph = ""
                                            newAppGlyphLabel = ""
                                            newAppGlyphPkg = ""
                                            toast("App glyph saved")
                                        }) {
                                            Text(text = "+")
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                for (item in appGlyphs) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val savedAppGlyph = remember(item.glyph) {
                                                try { BitmapFactory.decodeFile(item.glyph) } catch (_: Throwable) { null }
                                            }

                                            if (savedAppGlyph != null) {
                                                Image(
                                                    painter = BitmapPainter(savedAppGlyph.asImageBitmap(), filterQuality = FilterQuality.None),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(56.dp)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(56.dp))
                                            }

                                            Spacer(modifier = Modifier.size(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(text = item.label, style = MaterialTheme.typography.bodyLarge)
                                                Text(text = item.pkg, style = MaterialTheme.typography.bodySmall)
                                            }

                                            Spacer(modifier = Modifier.size(8.dp))

                                            Button(onClick = {
                                                appGlyphs.remove(item)
                                                writeAppGlyphs(appGlyphs)
                                                toast("App glyph removed")
                                            }) {
                                                Text(text = "-")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessState()
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(observer)
    }

    private fun getNotificationAccess(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun updateNotificationAccessState() {
        hasNotificationAccess = getNotificationAccess()
    }

    private fun broadcastPreferencesUpdate() {
        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        val glyphTimeout = preferences.getLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L)
        val defaultGlyph = preferences.getString(Constants.PREFERENCES_DEFAULT_GLYPH, null)
        val appGlyphs = preferences.getString(Constants.PREFERENCES_APP_GLYPHS, null)
        val animateGlyphs = preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)
        val animateSpeed = preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L)

        val intent = Intent(Constants.ACTION_ON_PREFERENCES_UPDATE).apply {
            putExtra(Constants.PREFERENCES_GLYPH_TIMEOUT, glyphTimeout)
            putExtra(Constants.PREFERENCES_DEFAULT_GLYPH, defaultGlyph)
            putExtra(Constants.PREFERENCES_APP_GLYPHS, appGlyphs)
            putExtra(Constants.PREFERENCES_ANIMATE_GLYPHS, animateGlyphs)
            putExtra(Constants.PREFERENCES_ANIMATE_SPEED, animateSpeed)
        }

        sendBroadcast(intent)
    }

    private fun readAppGlyphMappings(preference: String): MutableList<AppGlyph> {
        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        val raw = preferences.getString(preference, null) ?: return mutableListOf()
        val list = mutableListOf<AppGlyph>()

        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val pkg = obj.optString(Constants.APP_GLYPH_PKG)
            val label = obj.optString(Constants.APP_GLYPH_LABEL)
            val glyph = obj.optString(Constants.APP_GLYPH_GLYPH)

            list.add(AppGlyph(pkg, label, glyph))
        }

        return list
    }

    private fun readAppGlyphs(): MutableList<AppGlyph> {
        return readAppGlyphMappings(Constants.PREFERENCES_APP_GLYPHS)
    }

    private fun readIgnoredAppGlyphs(): MutableList<AppGlyph> {
        return readAppGlyphMappings(Constants.PREFERENCES_IGNORED_APP_GLYPHS)
    }

    private fun writeAppGlyphMappings(list: List<AppGlyph>, preference: String) {
        val arr = JSONArray()

        for ((pkg, label, glyph) in list) {
            val obj = JSONObject()
            obj.put(Constants.APP_GLYPH_PKG, pkg)
            obj.put(Constants.APP_GLYPH_LABEL, label)
            obj.put(Constants.APP_GLYPH_GLYPH, glyph)
            arr.put(obj)
        }

        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        preferences.edit { putString(preference, arr.toString()) }

        broadcastPreferencesUpdate()
    }

    private fun writeAppGlyphs(list: List<AppGlyph>) {
        writeAppGlyphMappings(list, Constants.PREFERENCES_APP_GLYPHS)
    }

    private fun writeIgnoredAppGlyphs(list: List<AppGlyph>) {
        writeAppGlyphMappings(list, Constants.PREFERENCES_IGNORED_APP_GLYPHS)
    }

    private fun toast(message: String) {
        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
    }
}
