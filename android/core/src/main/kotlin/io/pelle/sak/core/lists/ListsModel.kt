package io.pelle.sak.core.lists

import androidx.annotation.StringRes
import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.session.SessionStore
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.core.uiText
import io.pelle.sak.core.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Clock

// what ListsModel reads and changes of the screen of the lists of an app: L the list as the backend
// answers it, S the settings of a list. The screen of the app returns itself from each with
interface ListsAppScreen<L, S : ListForm> : AppScreen {
    val lists: List<L>

    // the id of the list open, null on the home
    val open: Int?

    // the settings of a list over the home or the list, null without
    val settings: S?

    fun withLists(lists: List<L>): ListsAppScreen<L, S>

    fun withOpen(open: Int?): ListsAppScreen<L, S>

    fun withSettings(settings: S?): ListsAppScreen<L, S>

    fun withError(error: UiText?): ListsAppScreen<L, S>
}

// the texts of an app for the failures of its lists, used when the backend sends none
data class ListTexts(
    @StringRes val notCreated: Int,
    @StringRes val notSaved: Int,
    @StringRes val notDeleted: Int,
    // the list of the settings is gone, or its owner no longer shares it
    @StringRes val gone: Int,
)

// the logic of a list-based app (grocery, todo) on top of SakModel: a list of the home opens, the
// settings of a new list or of one the user sees, saved (created or changed) or deleted, and a list
// of the user's own deleted from the menu of its row on the home. A shared list of another user
// changes its icon, only its owner renames, shares, unshares and deletes it (LIST-2). The app shows
// a list or the home (show), loads the lists (load) and makes its settings; it hooks into the
// settings opened and saved, e.g. grocery for its categories
abstract class ListsModel<A, L, S : ListForm>(
    store: SessionStore,
    apiOf: (url: String, token: String?) -> A,
    scope: CoroutineScope,
    allowUrl: Boolean,
    clock: Clock,
    appVersion: String,
    updater: Updater?,
    private val texts: ListTexts,
    // a list as the home shows it
    private val summaryOf: (L) -> ListSummary,
) : SakModel<A>(store, apiOf, scope, allowUrl, clock, appVersion, updater),
    ListsActions where A : SakApi, A : ListsApi<L> {
    // the screen of the lists, null when another screen is shown
    protected abstract fun listsScreen(): ListsAppScreen<L, S>?

    // the settings of a new list (null) or of a list the user sees
    protected abstract fun settingsOf(list: ListSummary?): S

    protected abstract fun edited(
        settings: S,
        name: String,
        shared: Boolean,
        icon: String?,
    ): S

    // the settings while they are sent, or refused with the reason
    protected abstract fun sending(
        settings: S,
        busy: Boolean,
        error: UiText?,
    ): S

    // the list open (an id) or the home (null) shows, the next start shows it again (LIST-3)
    protected abstract fun show(open: Int?)

    // the lists and the list open load, after the request it waits for
    protected abstract fun load(after: Job? = null)

    // the settings of the list id opened; nothing by default
    protected open fun settingsOpened(id: Int) {}

    // the settings are about to be saved, before their busy shows; nothing by default
    protected open fun savingSettings() {}

    // a list of the home opens, the next start opens it again (LIST-3)
    override fun open(id: Int) {
        val lists = listsScreen() ?: return
        if (lists.open != null || lists.lists.none { summaryOf(it).id == id }) return
        show(id)
    }

    // the settings of a new list, from the home
    override fun newList() {
        val lists = listsScreen() ?: return
        if (lists.open == null) state.value = lists.withSettings(settingsOf(null))
    }

    // the settings of a list the user sees, their own or a shared one of another user (LIST-2), from
    // its menu on the home or from the list itself; back and save return there
    override fun openSettings(id: Int) {
        val lists = listsScreen() ?: return
        val list = lists.lists.map(summaryOf).firstOrNull { it.id == id } ?: return
        state.value = lists.withSettings(settingsOf(list))
        settingsOpened(id)
    }

    override fun editSettings(
        name: String,
        shared: Boolean,
        icon: String?,
    ) {
        val lists = listsScreen() ?: return
        val settings = lists.settings ?: return
        // only the owner renames, shares or unshares a list
        state.value =
            lists.withSettings(
                edited(settings, if (settings.mine) name else settings.name, if (settings.mine) shared else settings.shared, icon),
            )
    }

    // a new list is created and opens, a changed one shows where its settings were opened; a failure,
    // e.g. a list of the name exists, stays on the settings with the reason
    override fun saveSettings() {
        val name = listsScreen()?.settings?.name?.trim() ?: return
        if (name.isEmpty() || listsScreen()?.settings?.busy != false) return
        savingSettings()
        val lists = listsScreen() ?: return
        val settings = lists.settings ?: return
        val id = settings.id
        state.value = lists.withSettings(sending(settings, busy = true, error = null))
        settingsRequest(settings, if (id == null) texts.notCreated else texts.notSaved) { api ->
            if (id == null) {
                val created = api.createList(name, settings.shared, settings.icon)
                listsState { it.withLists(it.lists + created).withSettings(null) }
                show(summaryOf(created).id)
            } else {
                try {
                    api.updateList(id, name, settings.shared, settings.icon)
                } catch (e: ApiException) {
                    // the owner unshared or deleted it while the settings were open: they close
                    if (e.status != 404) throw e
                    listsState { it.withSettings(null) }
                    failed(uiText(texts.gone))
                    return@settingsRequest
                }
                listsState { it.withSettings(null) }
                load()
            }
        }
    }

    // the list of the settings goes with its entries, after the user confirmed it (UX-4.1); opened
    // from the list, the home shows, also on the next start
    override fun deleteList() {
        val lists = listsScreen() ?: return
        val settings = lists.settings ?: return
        val id = settings.id ?: return
        if (settings.busy || !settings.mine) return
        state.value = lists.withSettings(sending(settings, busy = true, error = null))
        settingsRequest(settings, texts.notDeleted) { api ->
            api.deleteList(id)
            if (listsScreen()?.open == id) store.update { it.withOpenList(null) }
            listsState { current ->
                current.withLists(current.lists.filter { summaryOf(it).id != id }).withSettings(null).withOpen(current.open.takeIf { it != id })
            }
            load()
        }
    }

    // a list of the user's own from the menu of its row on the home, after the user confirmed it
    // (UX-12), the last one too (LIST-1). A failure shows on the home
    override fun deleteList(id: Int) {
        val lists = listsScreen() ?: return
        if (lists.lists.none { summaryOf(it).let { list -> list.id == id && list.mine } }) return
        scope.launch {
            request(texts.notDeleted, { error -> listsState { it.withError(error) } }) { it.deleteList(id) } ?: return@launch
            listsState { current -> current.withLists(current.lists.filter { summaryOf(it).id != id }) }
            load()
        }
    }

    // a failure of a change of the list open shows on it, which loads again as the backend has it
    protected fun failed(error: UiText) {
        val lists = listsScreen() ?: return
        state.value = lists.withError(error)
        load()
    }

    // a request of the settings; a failure shows on the settings, as they are now, no longer busy
    private fun settingsRequest(
        settings: S,
        @StringRes failure: Int,
        call: suspend (A) -> Unit,
    ) {
        scope.launch {
            request(failure, { error ->
                listsState { lists -> lists.withSettings(sending(lists.settings ?: settings, busy = false, error = error)) }
            }, call)
        }
    }

    // changes the screen of the lists, nothing when another screen is shown
    private inline fun listsState(change: (ListsAppScreen<L, S>) -> ListsAppScreen<L, S>) {
        listsScreen()?.let { state.value = change(it) }
    }
}
