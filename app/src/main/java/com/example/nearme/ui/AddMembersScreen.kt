package com.example.nearme.ui

import android.annotation.SuppressLint
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nearme.R
import com.example.nearme.util.GroupStore

@SuppressLint("NewApi")
@Composable
fun AddMembersScreen(
    groupId: String,
    onBack: () -> Unit,
    viewModel: GroupChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    LaunchedEffect(groupId) { viewModel.openGroup(groupId) }

    val group by viewModel.group.collectAsState()
    val nearbyUsers by viewModel.nearbyUsers.collectAsState()
    val selected = remember { mutableStateListOf<String>() }

    // Exclude people already in the group.
    val existingIds = group?.members?.map { it.shortId }?.toSet() ?: emptySet()
    val available = nearbyUsers.filter { it.shortId !in existingIds }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { onBack() }.padding(end = 8.dp))
            Text(stringResource(R.string.group_add_title), fontSize = 18.sp,
                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.group_select_members), fontSize = 13.sp,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(available) { user ->
                val isChecked = selected.contains(user.shortId)
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (isChecked) selected.remove(user.shortId) else selected.add(user.shortId)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(user.displayName.take(2).uppercase(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(user.displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Checkbox(checked = isChecked, onCheckedChange = {
                            if (it) selected.add(user.shortId) else selected.remove(user.shortId)
                        })
                    }
                }
            }
        }

        Button(
            onClick = {
                viewModel.addMembers(selected.toList())
                onBack()
            },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text(stringResource(R.string.group_add_button)) }
    }
}
