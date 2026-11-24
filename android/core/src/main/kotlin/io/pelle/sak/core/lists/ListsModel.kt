package io.pelle.sak.core.lists

import androidx.annotation.StringRes
import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.SakSetup
import io.pelle.sak.core.UiText
import io.pelle.sak.core.api.ApiException
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.session.Stored
import io.pelle.sak.core.session.openList
import io.pelle.sak.core.session.withOpenList
import io.pelle.sak.core.uiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

// what ListsModel reads and changes of the screen of the lists of an app: L the list as the backend
// answers it, S the settings of a list. Each with… returns the screen of the app, changed
interface ListsAppScreen<L, S : ListForm<S>> : AppScreen {
    val lists: List<L>

    // the id of the list open, null on the home
    val open: Int?

    // the settings of a list over the home or the list, null without
    val settings: S?

    // the entry typed is sent to the list open
    val adding: Boolean

    fun withLists(lists: List<L>): ListsAppScreen<L, S>

    fun withOpen(open: Int?): ListsAppScreen<L, S>

    fun withSettings(settings: S?): ListsAppScreen<L, S>

    fun withError(error: UiText?): ListsAppScreen<L, S>

    fun withLoading(loading: Boolean): ListsAppScreen<L, S>

    fun withAdding(adding: Boolean): ListsAppScreen<L, S>
}

// the texts of a list-based app, by their resource: what the screens of its lists show (<app>.common),
// and the failures of its lists, used when the backend sends none. One for each app, for its model and
// its ui
data class ListTexts(
    // the name of the app, the title of the home
    @StringRes val appName: Int,
    // the plus of the home and the title of the settings of a new list
    @StringRes val create: Int,
    // the back arrow of a list, to the home
    @StringRes val allLists: Int,
    @StringRes val chooseIcon: Int,
    @StringRes val noIcon: Int,
    @StringRes val deleteList: Int,
    @StringRes val notCreated: Int,
    @StringRes val notSaved: Int,
    @StringRes val notDeleted: Int,
    @StringRes val notLoaded: Int,
    // the list of the settings is gone, or its owner no longer shares it
    @StringRes val gone: Int,
)

// the logic of a list-based app on top of SakModel: the lists and the list open, create, change and
// delete one. A shared list of another user changes its icon, only its owner renames, shares, unshares
// and deletes it (LIST-2). It shows a list or the home (show) and loads the lists with the entries of the
// list open (load); while the app is seen it hears the changes of the lists (LIST-4). V is the screen of
// the lists of the app; the app makes it (appScreen), resets it for the list shown (opened) and loads the
// entries of the list open (loaded). It hooks into the settings opened and saved and into the changes
// heard, e.g. grocery for its categories
abstract class ListsModel<A, L, S : ListForm<S>, V : ListsAppScreen<L, S>>(
    setup: SakSetup,
    apiOf: (url: String, token: String?) -> A,
    private val texts: ListTexts,
    // a list as the home shows it
    private val summaryOf: (L) -> ListSummary,
    // the settings of a list with its fields
    private val settingsOf: (ListFields) -> S,
) : SakModel<A>(setup, apiOf),
    ListsActions where A : SakApi, A : ListsApi<L> {
    private var loading: Job? = null

    // counts up with every show: an answer for a list that is no longer shown changes nothing
    private var shown = 0

    // the changes of the lists (LIST-4): a change of the list open, or of any list on the home, loads
    // again, and so does a reconnect, the stream may have missed changes
    private val changes =
        ChangeStream(
            scope,
            clock,
            session = {
                state.first { it is ListsAppScreen<*, *> }
                store.load().takeIf { it.apiUrl != null }
            },
            changes = { stored, opened -> apiOf(stored.apiUrl.orEmpty(), stored.token).changes(opened) },
            ended = { e, token -> sessionEnded(e, token) },
            onReconnect = { reconnected() },
            onChange = { listChanged(it) },
        )

    // the screen of the lists, null when another screen is shown
    protected abstract fun listsScreen(): V?

    // the screen of the lists of a session, with the list open last (LIST-3) or the home
    protected abstract fun appScreen(
        user: String,
        open: Int?,
    ): V

    // the screen as the list open (an id) or the home (null) shows and starts to load: what belonged to
    // the list before goes
    protected abstract fun opened(
        lists: V,
        open: Int?,
    ): ListsAppScreen<L, S>

    // the entries of the list open, null on the home or when the list went, loaded after the lists:
    // the change of the screen that shows them
    protected abstract suspend fun loaded(
        api: A,
        open: Int?,
    ): (V) -> ListsAppScreen<L, S>

    // what a load waits for besides the changes committed after their undo, e.g. the requests of a
    // change of an entry still sent
    protected open fun loadWaits(): List<Job> = emptyList()

    // the settings of the list id opened; nothing by default
    protected open fun settingsOpened(id: Int) {}

    // the settings are about to be saved, before their busy shows; nothing by default
    protected open fun savingSettings() {}

    // the stream of the changes is open again: what changed meanwhile was missed, all loads again
    protected open fun reconnected() = load()

    // the list id changed: the list open loads again, the home on a change of any list
    protected open fun listChanged(id: Int) {
        val lists = listsScreen() ?: return
        if (lists.open == null || lists.open == id) load()
    }

    // the changes of the lists only while the app is seen (LIST-4)
    override fun foreground() = changes.start()

    override fun background() {
        super.background()
        changes.stop()
    }

    override fun showApp(stored: Stored) {
        state.value = appScreen(stored.email.orEmpty(), stored.openList)
        load()
    }

    // the user signs out: what waits for its undo is sent first (SakModel), a load goes
    override suspend fun signingOut() {
        loading?.cancel()
    }

    override fun sessionGone() = changes.restart()

    // a list of the home opens, the next start opens it again (LIST-3)
    override fun open(id: Int) {
        val lists = listsScreen() ?: return
        if (lists.open != null || lists.lists.none { summaryOf(it).id == id }) return
        show(id)
    }

    // the list open (an id) or the home (null) shows, the next start shows it again (LIST-3); what
    // waits for its undo is sent first, the load waits for it. An entry still sent and the answers
    // still due for the list before change nothing here
    protected fun show(open: Int?) {
        commitUndos()
        val lists = listsScreen() ?: return
        shown++
        state.value = opened(lists, open).withOpen(open).withAdding(false).withLoading(true).withError(null)
        scope.launch { store.update { it.withOpenList(open) } }
        load()
    }

    override fun refresh() {
        listsState { it.withLoading(true).withError(null) }
        load()
    }

    // the lists, then the entries of the one open, once after the changes committed and what the app
    // waits for (loadWaits). A list that is gone leads to the home, also on the next start; a session
    // that ended to the sign-in
    protected fun load() {
        loading?.cancel()
        val waits = committedUndos() + loadWaits()
        loading =
            scope.launch {
                waits.joinAll()
                request(texts.notLoaded, { error -> listsState { it.withLoading(false).withError(error) } }) { api ->
                    val lists = api.lists()
                    val wanted = (listsScreen() ?: return@request).open
                    val open = wanted?.takeIf { id -> lists.any { summaryOf(it).id == id } }
                    // the list went
                    if (wanted != null && open == null) store.update { it.withOpenList(null) }
                    val change = loaded(api, open)
                    listsState { change(it).withLists(lists).withOpen(open).withLoading(false) }
                }
            }
    }

    // the entry typed (text) is added to the list open by call, one at a time: sending is the screen
    // while it is sent, added the screen once it was, its field cleared; the list loads then. A failure
    // keeps what was typed, to send it again. The answer once another list shows changes nothing
    protected fun addEntry(
        text: String,
        @StringRes failure: Int,
        sending: (V) -> ListsAppScreen<L, S>,
        added: (V) -> ListsAppScreen<L, S>,
        call: suspend (api: A, list: Int) -> Unit,
    ) {
        val lists = listsScreen() ?: return
        val list = lists.open
        if (text.isBlank() || list == null || lists.adding) return
        state.value = sending(lists).withAdding(true).withError(null)
        val since = shown
        scope.launch {
            request(failure, { error -> if (since == shown) listsState { it.withAdding(false).withError(error) } }) { call(it, list) } ?: return@launch
            // another list shows meanwhile: its field stays as it is
            if (since != shown) return@launch
            listsState { added(it).withAdding(false) }
            load()
        }
    }

    override fun newList() {
        val lists = listsScreen() ?: return
        if (lists.open == null) state.value = lists.withSettings(settingsOf(ListFields()))
    }

    // the settings of a list the user sees, their own or a shared one of another user (LIST-2), from
    // its menu on the home or from the list itself; back and save return there
    override fun openSettings(id: Int) {
        val lists = listsScreen() ?: return
        val list = lists.lists.map(summaryOf).firstOrNull { it.id == id } ?: return
        state.value = lists.withSettings(settingsOf(ListFields(list.id, list.name, list.shared, list.icon, mine = list.mine)))
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
                settings.edited { if (it.mine) it.copy(name = name, shared = shared, icon = icon) else it.copy(icon = icon) },
            )
    }

    // a new list is created and opens, a changed one shows where its settings were opened; a failure,
    // e.g. a list of the name exists, stays on the settings with the reason
    override fun saveSettings() {
        val fields = listsScreen()?.settings?.fields ?: return
        val name = fields.name.trim()
        if (name.isEmpty() || fields.busy) return
        savingSettings()
        val lists = listsScreen() ?: return
        val settings = lists.settings ?: return
        val id = settings.fields.id
        val shared = settings.fields.shared
        val icon = settings.fields.icon
        state.value = lists.withSettings(settings.sending(busy = true, error = null))
        settingsRequest(settings, if (id == null) texts.notCreated else texts.notSaved) { api ->
            if (id == null) {
                val created = api.createList(name, shared, icon)
                listsState { it.withLists(it.lists + created).withSettings(null) }
                show(summaryOf(created).id)
            } else {
                try {
                    api.updateList(id, name, shared, icon)
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
        val id = settings.fields.id ?: return
        if (settings.fields.busy || !settings.fields.mine) return
        state.value = lists.withSettings(settings.sending(busy = true, error = null))
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

    // changes the screen of the lists, nothing when another screen is shown
    protected fun listsState(change: (V) -> ListsAppScreen<L, S>) {
        listsScreen()?.let { state.value = change(it) }
    }

    // a request of the settings; a failure shows on the settings, as they are now, no longer busy
    private fun settingsRequest(
        settings: S,
        @StringRes failure: Int,
        call: suspend (A) -> Unit,
    ) {
        scope.launch {
            request(failure, { error ->
                listsState { lists -> lists.withSettings((lists.settings ?: settings).sending(busy = false, error = error)) }
            }, call)
        }
    }

    private fun S.edited(change: (ListFields) -> ListFields): S = withFields(change(fields))

    // the settings while they are sent, or refused with the reason
    private fun S.sending(
        busy: Boolean,
        error: UiText?,
    ): S = edited { it.copy(busy = busy, error = error) }
}
