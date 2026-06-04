package com.example.nearme.ui
import com.example.nearme.util.rememberTotalUnreadCount
import com.example.nearme.util.rememberUnreadFor  // only needed in ChatsListScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*
import com.example.nearme.util.rememberTotalUnreadCount
import com.example.nearme.util.rememberUnreadFor
import androidx.compose.ui.res.stringResource
import com.example.nearme.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable


@Composable
fun ChatsListScreen(
    viewModel: ChatsListViewModel = viewModel(),
    onChatClick: (String, String) -> Unit,
    onNavigateTab: (NavTab) -> Unit
) {
    val chats by viewModel.chats.collectAsState()
    val unread by rememberTotalUnreadCount()
    var chatToDelete by remember { mutableStateOf<ChatSummary?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {

        // ── Header ───────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chats_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
        }

        // ── Body ─────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            if (chats.isEmpty()) {
                EmptyChatsState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(chats) { chat ->
                        ChatRow(
                            chat = chat,
                            onClick = onChatClick,
                            onLongPress = { chatToDelete = it }
                        )
                    }
                }
            }
        }

        // ── Bottom nav ───────────────────────────────
        NearMeBottomNav(
            selected = NavTab.CHATS,
            chatsUnreadCount = unread,
            onTabSelected = onNavigateTab
        )
    }
    // ── Delete chat confirmation ────────────────────
    chatToDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatToDelete = null },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = {
                Text(stringResource(R.string.delete_chat_message, chat.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(chat.conversationId)
                    chatToDelete = null
                }) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = Color(0xFFE24B4A)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatSummary,
    onClick: (String, String) -> Unit,
    onLongPress: (ChatSummary) -> Unit
) {
    val unreadForThisChat by rememberUnreadFor(chat.conversationId)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(chat.conversationId, chat.displayName) },
                onLongClick = { onLongPress(chat) }
            ),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar + presence dot
            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            brush = Brush.linearGradient(
                                listOf(Color(0xFF4C1D95), Color(0xFF0369A1))
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = chat.displayName.take(2).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                }
                if (chat.isOnline) {
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp)
                            .background(Color(0xFF1D9E75), CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chat.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (unreadForThisChat > 0) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                                .background(Color(0xFFE24B4A), RoundedCornerShape(10.dp))
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (unreadForThisChat > 99) "99+" else unreadForThisChat.toString(),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = formatTime(chat.timestamp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = chat.lastMessage,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun EmptyChatsState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("💬", fontSize = 36.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.chats_empty_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.chats_empty_subtitle),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "10:32" if today, else "12 Mar" */
private fun formatTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val isToday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val fmt = if (isToday) SimpleDateFormat("HH:mm", Locale.getDefault())
    else SimpleDateFormat("dd MMM", Locale.getDefault())
    return fmt.format(Date(timestamp))
}
