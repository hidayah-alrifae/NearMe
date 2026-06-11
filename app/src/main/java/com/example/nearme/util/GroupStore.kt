package com.example.nearme.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class GroupMember(val shortId: String, val name: String)

data class GroupInfo(
    val groupId: String,
    val groupName: String,
    val hubShortId: String,
    val isHub: Boolean,
    val members: List<GroupMember>
)

/**
 * Stores group metadata (name, hub, member list) in SharedPreferences as JSON.
 * The Message table already holds the actual messages — this only tracks
 * "which groups exist and who is in them". One process-wide StateFlow so the
 * group screen updates live as members join/leave.
 */
object GroupStore {
    private const val PREFS = "nearme_groups"
    private const val KEY = "groups"

    private val _groups = MutableStateFlow<List<GroupInfo>>(emptyList())
    val groups: StateFlow<List<GroupInfo>> = _groups

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        _groups.value = parse(prefs(context).getString(KEY, null) ?: "[]")
    }

    private fun parse(raw: String): List<GroupInfo> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val mArr = o.getJSONArray("members")
            val members = (0 until mArr.length()).map { j ->
                val m = mArr.getJSONObject(j)
                GroupMember(m.getString("shortId"), m.getString("name"))
            }
            GroupInfo(
                groupId = o.getString("groupId"),
                groupName = o.getString("groupName"),
                hubShortId = o.getString("hubShortId"),
                isHub = o.getBoolean("isHub"),
                members = members
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        _groups.value.forEach { g ->
            val o = JSONObject()
            o.put("groupId", g.groupId)
            o.put("groupName", g.groupName)
            o.put("hubShortId", g.hubShortId)
            o.put("isHub", g.isHub)
            val mArr = JSONArray()
            g.members.forEach { m ->
                mArr.put(JSONObject().put("shortId", m.shortId).put("name", m.name))
            }
            o.put("members", mArr)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun get(groupId: String): GroupInfo? =
        _groups.value.firstOrNull { it.groupId == groupId }

    fun upsert(context: Context, info: GroupInfo) {
        val list = _groups.value.toMutableList()
        val idx = list.indexOfFirst { it.groupId == info.groupId }
        if (idx >= 0) list[idx] = info else list.add(info)
        _groups.value = list
        persist(context)
    }

    fun addMember(context: Context, groupId: String, member: GroupMember) {
        val g = get(groupId) ?: return
        if (g.members.any { it.shortId == member.shortId }) return
        upsert(context, g.copy(members = g.members + member))
    }

    fun removeMember(context: Context, groupId: String, shortId: String) {
        val g = get(groupId) ?: return
        upsert(context, g.copy(members = g.members.filterNot { it.shortId == shortId }))
    }

    fun setMembers(context: Context, groupId: String, members: List<GroupMember>) {
        val g = get(groupId) ?: return
        upsert(context, g.copy(members = members))
    }

    fun delete(context: Context, groupId: String) {
        _groups.value = _groups.value.filterNot { it.groupId == groupId }
        persist(context)
    }
}
