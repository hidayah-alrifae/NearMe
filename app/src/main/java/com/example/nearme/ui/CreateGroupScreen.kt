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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nearme.R

@SuppressLint("NewApi")
@Composable
fun CreateGroupScreen(
    viewModel: CreateGroupViewModel = viewModel(),
    onCreated: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val nearbyUsers by viewModel.nearbyUsers.collectAsState()
    var groupName by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { onBack() }.padding(end = 10.dp))
            Text(stringResource(R.string.group_create_title), fontSize = 20.sp,
                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }

        OutlinedTextField(
            value = groupName, onValueChange = { groupName = it },
            label = { Text(stringResource(R.string.group_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.group_select_members), fontSize = 13.sp,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(nearbyUsers) { user ->
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
                val name = groupName.trim().ifBlank { "Group" }
                val groupId = viewModel.createGroup(name, selected.toList())
                onCreated(groupId, name)
            },
            enabled = groupName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text(stringResource(R.string.group_create_button)) }
    }
}
