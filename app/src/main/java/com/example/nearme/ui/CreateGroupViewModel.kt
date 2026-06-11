package com.example.nearme.ui

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import com.example.nearme.model.UserProfile
import com.example.nearme.repository.NearMeRepository
import kotlinx.coroutines.flow.StateFlow

@RequiresApi(Build.VERSION_CODES.Q)
class CreateGroupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NearMeRepository.getInstance(application)
    val nearbyUsers: StateFlow<List<UserProfile>> = repository.discoveredUsers

    /** Create the group, queue invites for the selected people, return the new groupId. */
    fun createGroup(name: String, selected: List<String>): String {
        val groupId = repository.createGroup(name)
        if (selected.isNotEmpty()) repository.inviteMembersToGroup(groupId, selected)
        return groupId
    }
}

