package com.example.nearme.ui

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.nearme.model.Message
import java.io.File

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    contactName: String
) {
    var inputText by remember { mutableStateOf("") }
    val isConnected by viewModel.isConnected.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Image/video picker launcher — opens the system gallery
    // When the user picks a file, it returns a content:// URI
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.sendFile(uri)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                viewModel.clearActiveConversation()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.restoreActiveConversation()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Top bar
        Text(
            text = contactName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )

        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            reverseLayout = true
        ) {
            items(messages.reversed()) { message ->
                MessageBubble(message)
            }
        }

        // Bottom bar — attach button + text input + send button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attach button — opens gallery picker
            // "*/*" allows images AND videos; use "image/*" for images only
            IconButton(
                onClick = { filePickerLauncher.launch("*/*") },
                enabled = isConnected
            ) {
                Text("📎", style = MaterialTheme.typography.titleLarge)
            }

            // Text input
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Type a message...") },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send button
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                enabled = isConnected
            ) {
                Text(if (isConnected) "Send" else "Connecting...")
            }
        }
    }
}

// a single message bubble
@Composable

fun MessageBubble(message: Message) {
    val alignment = if (message.isFromMe) Alignment.End else Alignment.Start
    val color = if (message.isFromMe) Color(0xFF2196F3) else Color(0xFFE0E0E0)
    val textColor = if (message.isFromMe) Color.White else Color.Black
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(color, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (message.filePath != null && message.mimeType != null) {
                // This is a file message — show image or file info
                Column {
                    if (message.mimeType.startsWith("image/")) {
                        // Load and display the image
                        val file = File(message.filePath)
                        if (file.exists()) {
                            val bitmap = remember(message.filePath) {
                                BitmapFactory.decodeFile(message.filePath)
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = message.content,
                                    modifier = Modifier
                                        .widthIn(max = 220.dp)
                                        .heightIn(max = 280.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Text(text = "📷 ${message.content}", color = textColor)
                            }
                        } else {
                            Text(text = "📷 ${message.content} (file missing)", color = textColor)
                        }
                    } else if (message.mimeType.startsWith("video/")) {
                        // Video — show icon and filename (no inline player)
                        Text(text = "🎬 ${message.content}", color = textColor)
                    } else {
                        // Other file type
                        Text(text = "📄 ${message.content}", color = textColor)
                    }

                    // "Save" button for received files only
                    if (!message.isFromMe) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = { saveFileToDevice(context, message) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                "Save to device",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.isFromMe) Color.White.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            } else {
                // Regular text message — same as before
                Text(text = message.content, color = textColor)
            }
        }
    }
}
/**
 * Saves a received file to the device's shared storage (gallery/Downloads).
 * Uses MediaStore on Android 10+ (no permission needed).
 * Uses direct file copy on Android 9 (needs WRITE_EXTERNAL_STORAGE but
 * we already request it in PermissionScreen).
 */
private fun saveFileToDevice(context: android.content.Context, message: Message) {
    val filePath = message.filePath ?: return
    val mimeType = message.mimeType ?: return
    val sourceFile = File(filePath)
    if (!sourceFile.exists()) {
        Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — use MediaStore (no permission needed)
            val collection = if (mimeType.startsWith("image/")) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else if (mimeType.startsWith("video/")) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, message.content)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (mimeType.startsWith("image/")) Environment.DIRECTORY_PICTURES + "/NearMe"
                    else if (mimeType.startsWith("video/")) Environment.DIRECTORY_MOVIES + "/NearMe"
                    else Environment.DIRECTORY_DOWNLOADS + "/NearMe"
                )
            }

            val uri = context.contentResolver.insert(collection, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Android 9 — copy to external Downloads folder directly
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val nearmeDir = File(downloadsDir, "NearMe")
            nearmeDir.mkdirs()
            val destFile = File(nearmeDir, message.content)
            sourceFile.copyTo(destFile, overwrite = true)
            Toast.makeText(context, "Saved to Downloads/NearMe/", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
