package com.voluene.monobrowser.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersistedTab(
    val id: Long,
    val url: String = "",
    val title: String = "",
    /** Private tabs are never written; the field exists so a stray one can be rejected on load. */
    val isPrivate: Boolean = false,
    /** Excluded from the sleeping-tab policy. */
    val neverSleep: Boolean = false,
    /** Workspace of the tab; files from before workspaces existed load into the main one. */
    val workspaceId: String = WorkspaceOps.MAIN_ID,
    /** "Desktop site" was requested for this tab. */
    val desktopMode: Boolean = false,
)

@Serializable
data class ClosedTab(val url: String, val title: String = "")

/**
 * Tabs (in display order), the active tab, the recently-closed stack and the workspaces; stored as `session.json`.
 * Version 2 added workspaces: a version-1 file (no workspace fields) loads into the single main workspace.
 */
@Serializable
data class SessionData(
    val version: Int = 2,
    val tabs: List<PersistedTab> = emptyList(),
    val activeTabId: Long = 0,
    val nextTabId: Long = 1,
    val recentlyClosed: List<ClosedTab> = emptyList(),
    val workspaces: List<Workspace> = listOf(WorkspaceOps.main()),
    val currentWorkspaceId: String = WorkspaceOps.MAIN_ID,
    /** Last active tab of every workspace (workspace id -> tab id). */
    val activeTabIdByWorkspace: Map<String, Long> = emptyMap(),
)

object SessionCodec {
    const val MAX_TABS = 200
    const val MAX_RECENTLY_CLOSED = 25
    private const val MAX_URL_LENGTH = 4096
    private const val MAX_TITLE_LENGTH = 300

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** URLs that make sense after a restart (desktop `isRestorableUrl`; empty = a blank new tab). */
    fun isRestorableUrl(url: String): Boolean =
        url.length <= MAX_URL_LENGTH &&
            (url.isEmpty() || url.startsWith("http://", true) || url.startsWith("https://", true) ||
                url.equals("about:blank", true))

    fun encode(data: SessionData): String = json.encodeToString(SessionData.serializer(), sanitize(data))

    /** Null when the text is not a valid session. Invalid tabs are dropped; the result is always consistent. */
    fun decode(text: String): SessionData? =
        runCatching { sanitize(json.decodeFromString(SessionData.serializer(), text)) }.getOrNull()

    fun sanitize(data: SessionData): SessionData {
        val workspaces = WorkspaceOps.normalize(data.workspaces)
        val known = workspaces.map { it.id }.toSet()
        val seen = HashSet<Long>()
        val tabs = data.tabs
            .filter { !it.isPrivate && it.id > 0 && isRestorableUrl(it.url) && seen.add(it.id) }
            .take(MAX_TABS)
            .map {
                it.copy(
                    title = it.title.take(MAX_TITLE_LENGTH),
                    workspaceId = if (it.workspaceId in known) it.workspaceId else WorkspaceOps.MAIN_ID,
                )
            }
        val closed = data.recentlyClosed
            .filter { it.url.isNotEmpty() && isRestorableUrl(it.url) }
            .take(MAX_RECENTLY_CLOSED)
            .map { it.copy(title = it.title.take(MAX_TITLE_LENGTH)) }
        val maxId = tabs.maxOfOrNull { it.id } ?: 0L
        val activeByWorkspace = data.activeTabIdByWorkspace.filter { (workspaceId, tabId) ->
            tabs.any { it.id == tabId && it.workspaceId == workspaceId }
        }
        var current = if (data.currentWorkspaceId in known) data.currentWorkspaceId else WorkspaceOps.MAIN_ID
        // The active tab decides the current workspace; without a usable one fall back inside the current workspace.
        // 0 = the current workspace has no tab (the caller opens a new one there).
        val activeTab = tabs.firstOrNull { it.id == data.activeTabId }
        val active = if (activeTab != null) {
            current = activeTab.workspaceId
            activeTab.id
        } else {
            activeByWorkspace[current] ?: tabs.lastOrNull { it.workspaceId == current }?.id ?: 0L
        }
        return SessionData(
            tabs = tabs,
            activeTabId = active,
            nextTabId = maxOf(data.nextTabId, maxId + 1, 1L),
            recentlyClosed = closed,
            workspaces = workspaces,
            currentWorkspaceId = current,
            activeTabIdByWorkspace = activeByWorkspace,
        )
    }
}
