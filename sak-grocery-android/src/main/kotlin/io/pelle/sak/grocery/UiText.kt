package io.pelle.sak.grocery

import androidx.annotation.StringRes

// a text the user reads: one of the app (res/values/strings.xml, values-de for german) with its
// arguments, read in the language of the phone only by the ui; or one the backend sent, already in
// that language (Accept-Language). The model stays without android
sealed interface UiText {
    data class Res(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plain(
        val text: String,
    ) : UiText
}

fun uiText(
    @StringRes id: Int,
    vararg args: Any,
): UiText = UiText.Res(id, args.toList())
