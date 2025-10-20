package com.tober.glyphmatrix.notifications

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
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

    private var hasAccessibilityServiceAccess by mutableStateOf(false)
    private var hasNotificationAccess by mutableStateOf(false)

    private lateinit var preferences: SharedPreferences

    private var active by mutableStateOf(true)

    private var glyphTimeout by mutableStateOf("5")
    private var animateGlyphs by mutableStateOf(true)
    private var animateSpeed by mutableStateOf("10")

    private var defaultGlyph by mutableStateOf<String?>(null)

    private val appGlyphs = mutableStateListOf<AppGlyph>()
    private var newAppGlyphPkg by mutableStateOf("")
    private var newAppGlyphLabel by mutableStateOf("")
    private var newAppGlyph by mutableStateOf("")

    private val contactGlyphs = mutableStateListOf<AppGlyph>()
    private var newContactGlyphContact by mutableStateOf("")
    private var newContactGlyph by mutableStateOf("")

    private val ignoredApps = mutableStateListOf<AppGlyph>()
    private var newIgnoredAppPkg by mutableStateOf("")
    private var newIgnoredAppLabel by mutableStateOf("")

    private var appSelectorActivityLauncherCallback: ((String) -> Unit)? = null
    private lateinit var appSelectorActivityLauncher: ActivityResultLauncher<Intent>

    private var loadImageLauncherCallback: ((String) -> Unit)? = null
    private lateinit var loadImageLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        hasAccessibilityServiceAccess = getAccessibilityServiceAccess()
        hasNotificationAccess = getNotificationAccess()

        preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)

        active = preferences.getBoolean(Constants.PREFERENCES_ACTIVE, true)

        glyphTimeout = preferences.getLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L).toString()
        animateGlyphs = preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)
        animateSpeed = preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L).toString()

        defaultGlyph = preferences.getString(Constants.PREFERENCES_DEFAULT_GLYPH, null)

        appGlyphs.clear(); appGlyphs.addAll(readAppGlyphs())
        contactGlyphs.clear(); contactGlyphs.addAll(readContactGlyphs())
        ignoredApps.clear(); ignoredApps.addAll(readIgnoredApps())

        appSelectorActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val pkg = result.data?.getStringExtra("pkg")

                if (!pkg.isNullOrBlank()) {
                    try {
                        appSelectorActivityLauncherCallback?.invoke(pkg)
                    } finally {
                        appSelectorActivityLauncherCallback = null
                    }
                }
            }
        }

        loadImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                return@registerForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}

            try {
                val newFile = File(filesDir, "tmp_image_${System.currentTimeMillis()}.png")

                filesDir.listFiles()?.filter { it.name.startsWith("tmp_image_") && it.name.endsWith(".png") && it.absolutePath != newFile.absolutePath }
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
                } else {
                    try {
                        loadImageLauncherCallback?.invoke(newFile.absolutePath)
                    } finally {
                        loadImageLauncherCallback = null
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to load image: $e")
                toast("Failed to load image")
            }
        }

        setContent {
            GlyphMatrixNotificationsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    val focusManager = LocalFocusManager.current
                    val keyboardController = LocalSoftwareKeyboardController.current

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top
                    ) {
                        if (!hasAccessibilityServiceAccess || !hasNotificationAccess) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Accessibility service access and notification access is required for the app to detect notifications and show glyphs automatically.")

                                Spacer(modifier = Modifier.height(25.dp))

                                Text(text = "1. Allow Restricted Settings:", fontWeight = FontWeight.Bold)
                                Text(text = "App Info -> ⋮ (top right) -> Allow Restricted Settings")

                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", packageName, null)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        startActivity(intent)
                                    },
                                    modifier = Modifier.padding(top = 12.dp)
                                ) {
                                    Text(text = "Open App Info")
                                }

                                Spacer(modifier = Modifier.height(25.dp))

                                Text(text = "2. Allow Accessibility Service Access:", fontWeight = FontWeight.Bold)
                                Text(text = "Glyph Matrix Notifications -> Use Glyph Matrix Notifications -> Allow")

                                Button(
                                    onClick = {
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    },
                                    modifier = Modifier.padding(top = 12.dp)
                                ) {
                                    Text(text = "Open Accessibility Service Access Settings")
                                }

                                Spacer(modifier = Modifier.height(25.dp))

                                Text(text = "3. Allow Notification Access", fontWeight = FontWeight.Bold)
                                Text(text = "Glyph Matrix Notifications -> Allow notification access -> Allow")

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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "App Active",
                                        style = MaterialTheme.typography.bodyLarge
                                    )

                                    Switch(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        checked = active,
                                        onCheckedChange = { checked ->
                                            active = checked
                                            preferences.edit { putBoolean(Constants.PREFERENCES_ACTIVE, checked) }
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
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Glyph Timeout", modifier = Modifier.padding(bottom = 8.dp))

                                OutlinedTextField(
                                    value = glyphTimeout,
                                    onValueChange = { value ->
                                        val filtered = value.filter { it.isDigit() }
                                        glyphTimeout = filtered
                                    },
                                    label = { Text("(seconds)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.padding(top = 12.dp)
                                )

                                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(onClick = {
                                        val timeout = glyphTimeout.toLongOrNull() ?: 5L
                                        preferences.edit { putLong(Constants.PREFERENCES_GLYPH_TIMEOUT, timeout) }
                                        broadcastPreferencesUpdate()
                                        toast("Timeout saved")
                                    }) {
                                        Icon(imageVector = Icons.Filled.Save, contentDescription = "Save")
                                    }

                                    IconButton(onClick = {
                                        glyphTimeout = "5"
                                        preferences.edit { putLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L) }
                                        broadcastPreferencesUpdate()
                                        toast("Timeout reset")
                                    }) {
                                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Reset")
                                    }
                                }

                                Spacer(modifier = Modifier.height(15.dp))

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

                                    Text(text = "Animation Speed", modifier = Modifier.padding(bottom = 8.dp))

                                    OutlinedTextField(
                                        value = animateSpeed,
                                        onValueChange = { value ->
                                            val filtered = value.filter { it.isDigit() }
                                            animateSpeed = filtered
                                        },
                                        label = { Text("(milliseconds)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.padding(top = 12.dp)
                                    )

                                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        IconButton(onClick = {
                                            val animateSpeed = animateSpeed.toLongOrNull() ?: 10L
                                            preferences.edit { putLong(Constants.PREFERENCES_ANIMATE_SPEED, animateSpeed) }
                                            broadcastPreferencesUpdate()
                                            toast("Animation speed saved")
                                        }) {
                                            Icon(imageVector = Icons.Filled.Save, contentDescription = "Save")
                                        }

                                        IconButton(onClick = {
                                            animateSpeed = "10"
                                            preferences.edit { putLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L) }
                                            broadcastPreferencesUpdate()
                                            toast("Animation speed reset")
                                        }) {
                                            Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Reset")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Default Glyph", modifier = Modifier.padding(vertical = 8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement  = Arrangement.SpaceBetween
                                ) {
                                    val tmp = remember(defaultGlyph) {
                                        defaultGlyph.takeIf { it?.isNotBlank() ?: false }?.let { BitmapFactory.decodeFile(it) }
                                    }

                                    if (tmp != null) {
                                        Image(
                                            painter = BitmapPainter(tmp.asImageBitmap(), filterQuality = FilterQuality.None),
                                            contentDescription = "Default Glyph Preview",
                                            modifier = Modifier
                                                .size(76.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { saveDefaultGlyph() }
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(76.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable { saveDefaultGlyph() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(text = "+", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }

                                    if (defaultGlyph != null) {
                                        IconButton(onClick = { deleteDefaultGlyph() }) {
                                            Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "App Glyphs", modifier = Modifier.padding(vertical = 8.dp))

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
                                        val tmp = remember(newAppGlyph) {
                                            newAppGlyph.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
                                        }

                                        if (tmp != null) {
                                            Image(
                                                painter = BitmapPainter(tmp.asImageBitmap(), filterQuality = FilterQuality.None),
                                                contentDescription = "App Glyph Preview",
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { loadNewAppGlyph() }
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable { loadNewAppGlyph() },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(text = "+", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }

                                        Column(
                                            modifier = Modifier
                                                .padding(horizontal = 12.dp)
                                                .weight(1f)
                                                .clickable {
                                                    focusManager.clearFocus(force = true)
                                                    keyboardController?.hide()

                                                    loadNewAppGlyphPkg()
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

                                        IconButton(onClick = { createAppGlyph() }) {
                                            Icon(imageVector = Icons.Filled.Save, contentDescription = "Save")
                                        }
                                    }
                                }

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
                                            val tmp = remember(item.glyph) {
                                                item.glyph.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
                                            }

                                            if (tmp != null) {
                                                Image(
                                                    painter = BitmapPainter(tmp.asImageBitmap(), filterQuality = FilterQuality.None),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clickable { updateAppGlyph(item) }
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(56.dp))
                                            }

                                            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                                Text(text = item.label, style = MaterialTheme.typography.bodyLarge)
                                                Text(text = item.pkg, style = MaterialTheme.typography.bodySmall)
                                            }

                                            var expanded by remember { mutableStateOf(false) }

                                            Box {
                                                IconButton(onClick = {
                                                    focusManager.clearFocus(force = true)
                                                    keyboardController?.hide()

                                                    expanded = true
                                                }) {
                                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                                }

                                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                                    DropdownMenuItem(text = { Text("Move Up") }, onClick = { changeAppGlyphOrder(item, -1); expanded = false })
                                                    DropdownMenuItem(text = { Text("Move Down") }, onClick = { changeAppGlyphOrder(item, 1); expanded = false })
                                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { deleteAppGlyph(item); expanded = false })
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Contact Glyphs", modifier = Modifier.padding(vertical = 8.dp))

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            val tmp = remember(newContactGlyph) {
                                                newContactGlyph.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
                                            }

                                            if (tmp != null) {
                                                Image(
                                                    painter = BitmapPainter(tmp.asImageBitmap(), filterQuality = FilterQuality.None),
                                                    contentDescription = "Contact Glyph Preview",
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable { loadNewContactGlyph() }
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                                        .clickable { loadNewContactGlyph() },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(text = "+", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }

                                            IconButton(onClick = {
                                                focusManager.clearFocus(true)
                                                keyboardController?.hide()

                                                createContactGlyph()
                                            }) {
                                                Icon(imageVector = Icons.Filled.Save, contentDescription = "Save")
                                            }
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            OutlinedTextField(
                                                value = newContactGlyphContact,
                                                onValueChange = { newContactGlyphContact = it },
                                                label = { Text("(contact)") },
                                                keyboardOptions = KeyboardOptions.Default,
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                            )
                                        }
                                    }
                                }

                                for (item in contactGlyphs) {
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
                                            val tmp = remember(item.glyph) {
                                                item.glyph.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
                                            }

                                            if (tmp != null) {
                                                Image(
                                                    painter = BitmapPainter(tmp.asImageBitmap(), filterQuality = FilterQuality.None),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clickable { updateContactGlyph(item) }
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(56.dp))
                                            }

                                            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                                Text(text = item.label, style = MaterialTheme.typography.bodyLarge)
                                            }

                                            var expanded by remember { mutableStateOf(false) }

                                            Box {
                                                IconButton(onClick = { expanded = true }) {
                                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                                }

                                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                                    DropdownMenuItem(text = { Text("Move Up") }, onClick = { changeContactGlyphOrder(item, -1); expanded = false })
                                                    DropdownMenuItem(text = { Text("Move Down") }, onClick = { changeContactGlyphOrder(item, 1); expanded = false })
                                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { deleteContactGlyph(item); expanded = false })
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Ignored Apps", modifier = Modifier.padding(vertical = 8.dp))

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
                                                    focusManager.clearFocus(force = true)
                                                    keyboardController?.hide()

                                                    loadNewIgnoredAppPkg()
                                                }
                                        ) {
                                            Text(
                                                text = if (!newIgnoredAppLabel.isBlank()) newIgnoredAppLabel else "Choose an app",
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = if (!newIgnoredAppPkg.isBlank()) newIgnoredAppPkg else "-",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        IconButton(onClick = { createIgnoredApp() }) {
                                            Icon(imageVector = Icons.Filled.Save, contentDescription = "Save")
                                        }
                                    }
                                }

                                for (item in ignoredApps) {
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

                                            var expanded by remember { mutableStateOf(false) }

                                            Box {
                                                IconButton(onClick = {
                                                    focusManager.clearFocus(force = true)
                                                    keyboardController?.hide()

                                                    expanded = true
                                                }) {
                                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                                }

                                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                                    DropdownMenuItem(text = { Text("Move Up") }, onClick = { changeIgnoredAppOrder(item, -1); expanded = false })
                                                    DropdownMenuItem(text = { Text("Move Down") }, onClick = { changeIgnoredAppOrder(item, 1); expanded = false })
                                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { deleteIgnoredApp(item); expanded = false })
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
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityServiceAccess()
        updateNotificationAccess()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun getAccessibilityServiceAccess(): Boolean {
        val expectedComponentName = ComponentName(this, UnlockAccessibilityService::class.java)

        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')

        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val component = colonSplitter.next()
            if (component.equals(expectedComponentName.flattenToString(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun updateAccessibilityServiceAccess() {
        hasAccessibilityServiceAccess = getAccessibilityServiceAccess()
    }

    private fun getNotificationAccess(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun updateNotificationAccess() {
        hasNotificationAccess = getNotificationAccess()
    }

    private fun broadcastPreferencesUpdate() {
        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)

        val active = preferences.getBoolean(Constants.PREFERENCES_ACTIVE, true)

        val glyphTimeout = preferences.getLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L)
        val animateGlyphs = preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)
        val animateSpeed = preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L)

        val defaultGlyph = preferences.getString(Constants.PREFERENCES_DEFAULT_GLYPH, null)
        val appGlyphs = preferences.getString(Constants.PREFERENCES_APP_GLYPHS, null)
        val ignoredApps = preferences.getString(Constants.PREFERENCES_IGNORED_APPS, null)

        val intent = Intent(Constants.ACTION_ON_PREFERENCES_UPDATE).apply {
            putExtra(Constants.PREFERENCES_ACTIVE, active)

            putExtra(Constants.PREFERENCES_GLYPH_TIMEOUT, glyphTimeout)
            putExtra(Constants.PREFERENCES_ANIMATE_GLYPHS, animateGlyphs)
            putExtra(Constants.PREFERENCES_ANIMATE_SPEED, animateSpeed)

            putExtra(Constants.PREFERENCES_DEFAULT_GLYPH, defaultGlyph)
            putExtra(Constants.PREFERENCES_APP_GLYPHS, appGlyphs)
            putExtra(Constants.PREFERENCES_IGNORED_APPS, ignoredApps)
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
            val pkg = obj.optString("pkg")
            val label = obj.optString("label")
            val glyph = obj.optString("glyph")

            list.add(AppGlyph(pkg, label, glyph))
        }

        return list
    }

    private fun readAppGlyphs(): MutableList<AppGlyph> {
        return readAppGlyphMappings(Constants.PREFERENCES_APP_GLYPHS)
    }

    private fun readContactGlyphs(): MutableList<AppGlyph> {
        return readAppGlyphMappings(Constants.PREFERENCES_CONTACT_GLYPHS)
    }

    private fun readIgnoredApps(): MutableList<AppGlyph> {
        return readAppGlyphMappings(Constants.PREFERENCES_IGNORED_APPS)
    }

    private fun writeAppGlyphMappings(list: List<AppGlyph>, preference: String) {
        val arr = JSONArray()

        for ((pkg, label, glyph) in list) {
            val obj = JSONObject()
            obj.put("pkg", pkg)
            obj.put("label", label)
            obj.put("glyph", glyph)
            arr.put(obj)
        }

        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        preferences.edit { putString(preference, arr.toString()) }

        broadcastPreferencesUpdate()
    }

    private fun writeAppGlyphs(list: List<AppGlyph>) {
        writeAppGlyphMappings(list, Constants.PREFERENCES_APP_GLYPHS)
    }

    private fun writeContactGlyphs(list: List<AppGlyph>) {
        writeAppGlyphMappings(list, Constants.PREFERENCES_CONTACT_GLYPHS)
    }

    private fun writeIgnoredApps(list: List<AppGlyph>) {
        writeAppGlyphMappings(list, Constants.PREFERENCES_IGNORED_APPS)
    }

    private fun toast(message: String) {
        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
    }

    private fun saveDefaultGlyph() {
        loadImageLauncherCallback = fun(loaded: String) {
            val newFile = File(filesDir, "default_glyph_${System.currentTimeMillis()}.png")
            try {
                File(loaded).copyTo(newFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to save default glyph: $e")
                toast("Failed to save default glyph")
                return
            }

            preferences.getString(Constants.PREFERENCES_DEFAULT_GLYPH, null)?.let { oldFile ->
                try { File(oldFile).takeIf { it.exists() }?.delete() } catch (_: Throwable) {}
            }

            preferences.edit { putString(Constants.PREFERENCES_DEFAULT_GLYPH, newFile.absolutePath) }
            defaultGlyph = newFile.absolutePath

            toast("Default glyph saved")

            broadcastPreferencesUpdate()
        }

        loadImageLauncher.launch(arrayOf("image/*"))
    }

    private fun deleteDefaultGlyph() {
        preferences.getString(Constants.PREFERENCES_DEFAULT_GLYPH, null)?.let { path ->
            try { File(path).takeIf { it.exists() }?.delete() } catch (_: Throwable) {}
        }

        preferences.edit { remove(Constants.PREFERENCES_DEFAULT_GLYPH) }
        defaultGlyph = null

        toast("Default glyph removed")

        broadcastPreferencesUpdate()
    }

    private fun loadNewAppGlyph() {
        loadImageLauncherCallback = fun(loaded: String) {
            val newFile = File(filesDir, "tmp_app_glyph_${System.currentTimeMillis()}.png")
            try {
                File(loaded).copyTo(newFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to update glyph: $e")
                toast("Failed to update glyph")
                return
            }

            filesDir.listFiles()?.filter { it.name.startsWith("tmp_app_glyph_") && it.name.endsWith(".png") && it.absolutePath != newFile.absolutePath }
                ?.forEach { try { it.delete() } catch (_: Throwable) {} }

            newAppGlyph = newFile.absolutePath
        }

        loadImageLauncher.launch(arrayOf("image/*"))
    }

    private fun loadNewAppGlyphPkg() {
        appSelectorActivityLauncherCallback = fun(loaded: String) {
            newAppGlyphPkg = loaded

            try {
                val appInfo = packageManager.getApplicationInfo(loaded, 0)
                val appLabel = packageManager.getApplicationLabel(appInfo).toString()
                newAppGlyphLabel = appLabel
            } catch (_: Throwable) {
                newAppGlyphLabel = loaded
            }
        }

        val intent = Intent(this@MainActivity, AppSelectorActivity::class.java)
        appSelectorActivityLauncher.launch(intent)
    }

    private fun createAppGlyph() {
        if (newAppGlyphPkg.isBlank()) {
            toast("Choose an app")
            return
        }
        if (newAppGlyph.isBlank()) {
            toast("Choose a glyph")
            return
        }

        val safeName = newAppGlyphPkg.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val newFile = File(filesDir, "app_glyph_${safeName}_${System.currentTimeMillis()}.png")

        try {
            File(newAppGlyph).copyTo(newFile, overwrite = true)
        } catch (e: Exception) {
            Log.e(tag, "Failed to save app glyph: $e")
            toast("Failed to save app glyph")
            return
        }

        appGlyphs.removeAll { it.pkg == newAppGlyphPkg }
        appGlyphs.add(AppGlyph(newAppGlyphPkg, newAppGlyphLabel, newFile.absolutePath))
        writeAppGlyphs(appGlyphs)

        newAppGlyph = ""
        newAppGlyphLabel = ""
        newAppGlyphPkg = ""
        toast("App glyph saved")
    }

    private fun updateAppGlyph(item: AppGlyph) {
        loadImageLauncherCallback = fun(loaded: String) {
            val safeName = loaded.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val newFile = File(filesDir, "app_glyph_${safeName}_${System.currentTimeMillis()}.png")
            try {
                File(loaded).copyTo(newFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to update app glyph: $e")
                toast("Failed to update app glyph")
                return
            }

            val i = appGlyphs.indexOfFirst { it.glyph == item.glyph }
            if (i != -1) {
                appGlyphs[i] = AppGlyph(appGlyphs[i].pkg, appGlyphs[i].label, newFile.absolutePath)
                writeAppGlyphs(appGlyphs)
            }

            try { File(item.glyph).delete() } catch (_: Throwable) {}

            toast("App glyph updated")
        }

        loadImageLauncher.launch(arrayOf("image/*"))
    }

    private fun deleteAppGlyph(item: AppGlyph) {
        appGlyphs.remove(item)
        writeAppGlyphs(appGlyphs)
        toast("App glyph removed")
    }

    private fun changeAppGlyphOrder(item: AppGlyph, n: Int) {
        val i = appGlyphs.indexOf(item)
        val p = i + n

        if (i !in appGlyphs.indices || p !in appGlyphs.indices) return

        val current = appGlyphs[i]
        val next = appGlyphs[p]
        
        appGlyphs[i] = next
        appGlyphs[p] = current

        writeAppGlyphs(appGlyphs)
    }

    private fun loadNewContactGlyph() {
        loadImageLauncherCallback = fun(loaded: String) {
            val newFile = File(filesDir, "tmp_contact_glyph_${System.currentTimeMillis()}.png")
            try {
                File(loaded).copyTo(newFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to update contact glyph: $e")
                toast("Failed to update contact glyph")
                return
            }

            filesDir.listFiles()?.filter { it.name.startsWith("tmp_contact_glyph_") && it.name.endsWith(".png") && it.absolutePath != newFile.absolutePath }
                ?.forEach { try { it.delete() } catch (_: Throwable) {} }

            newContactGlyph = newFile.absolutePath
        }

        loadImageLauncher.launch(arrayOf("image/*"))
    }

    private fun createContactGlyph() {
        if (newContactGlyphContact.isBlank()) {
            toast("Specify a contact")
            return
        }
        if (newContactGlyph.isBlank()) {
            toast("Choose a glyph")
            return
        }

        val safeName = newContactGlyphContact.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val newFile = File(filesDir, "contact_glyph_${safeName}_${System.currentTimeMillis()}.png")

        try {
            File(newContactGlyph).copyTo(newFile, overwrite = true)
        } catch (e: Exception) {
            Log.e(tag, "Failed to save contact glyph: $e")
            toast("Failed to save contact glyph")
            return
        }

        contactGlyphs.removeAll { it.label == newContactGlyphContact }
        contactGlyphs.add(AppGlyph("", newContactGlyphContact, newFile.absolutePath))
        writeContactGlyphs(contactGlyphs)

        newContactGlyph = ""
        newContactGlyphContact = ""
        toast("Contact glyph saved")
    }

    private fun updateContactGlyph(item: AppGlyph) {
        loadImageLauncherCallback = fun(loaded: String) {
            val safeName = loaded.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val newFile = File(filesDir, "contact_glyph_${safeName}_${System.currentTimeMillis()}.png")
            try {
                File(loaded).copyTo(newFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to update contact glyph: $e")
                toast("Failed to update contact glyph")
                return
            }

            val i = contactGlyphs.indexOfFirst { it.glyph == item.glyph }
            if (i != -1) {
                contactGlyphs[i] = AppGlyph(contactGlyphs[i].pkg, contactGlyphs[i].label, newFile.absolutePath)
                writeContactGlyphs(contactGlyphs)
            }

            try { File(item.glyph).delete() } catch (_: Throwable) {}

            toast("Contact glyph updated")
        }

        loadImageLauncher.launch(arrayOf("image/*"))
    }

    private fun deleteContactGlyph(item: AppGlyph) {
        contactGlyphs.remove(item)
        writeContactGlyphs(contactGlyphs)
        toast("Contact glyph removed")
    }

    private fun changeContactGlyphOrder(item: AppGlyph, n: Int) {
        val i = contactGlyphs.indexOf(item)
        val p = i + n

        if (i !in contactGlyphs.indices || p !in contactGlyphs.indices) return

        val current = contactGlyphs[i]
        val next = contactGlyphs[p]
        
        contactGlyphs[i] = next
        contactGlyphs[p] = current

        writeContactGlyphs(contactGlyphs)
    }

    private fun loadNewIgnoredAppPkg() {
        appSelectorActivityLauncherCallback = fun(loaded: String) {
            newIgnoredAppPkg = loaded

            try {
                val appInfo = packageManager.getApplicationInfo(loaded, 0)
                val appLabel = packageManager.getApplicationLabel(appInfo).toString()
                newIgnoredAppLabel = appLabel
            } catch (_: Throwable) {
                newIgnoredAppLabel = loaded
            }
        }

        val intent = Intent(this@MainActivity, AppSelectorActivity::class.java)
        appSelectorActivityLauncher.launch(intent)
    }

    private fun createIgnoredApp() {
        if (newIgnoredAppPkg.isBlank()) {
            toast("Choose an app")
            return
        }

        ignoredApps.removeAll { it.pkg == newIgnoredAppPkg }
        ignoredApps.add(AppGlyph(newIgnoredAppPkg, newIgnoredAppLabel, ""))
        writeIgnoredApps(ignoredApps)

        newIgnoredAppLabel = ""
        newIgnoredAppPkg = ""
        toast("Ignored app saved")
    }

    private fun deleteIgnoredApp(item: AppGlyph) {
        ignoredApps.remove(item)
        writeIgnoredApps(ignoredApps)
        toast("Ignored app removed")
    }

    private fun changeIgnoredAppOrder(item: AppGlyph, n: Int) {
        val i = ignoredApps.indexOf(item)
        val p = i + n

        if (i !in ignoredApps.indices || p !in ignoredApps.indices) return

        val current = ignoredApps[i]
        val next = ignoredApps[p]
        
        ignoredApps[i] = next
        ignoredApps[p] = current

        writeIgnoredApps(ignoredApps)
    }
}
