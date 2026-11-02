package io.pelle.sak.grocery.api

import kotlinx.serialization.Serializable

// the bodies of the api of sak-backend the app reads and sends, copies of the request and response
// classes of the backend with the fields the app uses only; unknown fields are ignored (ApiJson)

@Serializable
data class InfoResponse(
    // the name of the deployment
    val name: String,
    // multi: the users sign in by e-mail and the admin by password; single: only the admin, by password
    val userMode: String,
    // the version of sak it runs, 1.4.2; the app updates itself to it. None without one
    val version: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    // android: the signin page of the web app hands the link on to this app; no default,
    // a default value is not sent
    val client: String,
)

@Serializable
data class ExchangeRequest(
    val token: String,
)

@Serializable
data class PasswordLoginRequest(
    val password: String,
)

@Serializable
data class ExchangeResponse(
    val sessionToken: String,
    // the e-mail address, or admin for the password login
    val email: String,
    val expiresAt: String,
)

@Serializable
data class WhoAmIResponse(
    // the e-mail address, or admin for the password login
    val username: String,
    // the name an admin gave the user, null without one
    val name: String? = null,
)

@Serializable
data class PaginationResponse(
    val offset: Long,
    val limit: Int,
    val total: Long,
)

@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val pagination: PaginationResponse,
)

@Serializable
data class GroceryListResponse(
    val id: Int,
    val name: String,
    val shared: Boolean,
    // of the signed in user
    val mine: Boolean,
    // how many items are to buy on it
    val items: Int,
    // one emoji, none shows the first letter of the name
    val icon: String? = null,
)

@Serializable
data class GroceryListRequest(
    val name: String,
    // of all users; no default, a default value is not sent
    val shared: Boolean,
    // one emoji, null for none
    val icon: String?,
)

@Serializable
data class GroceryItemResponse(
    val id: Int,
    val listId: Int,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    // a category of its list, none when missing (GROCERY-13)
    val categoryId: Int? = null,
    // the file of its photo, none without one
    val photoId: Int? = null,
    // when it was bought, none while it is to buy (GROCERY-3); only the items of a list with the bought ones have it
    val checkedAt: String? = null,
)

// a file the file storage keeps, e.g. a photo
@Serializable
data class StoredFileResponse(
    val id: Int,
)

// the photo of an item: a file the user uploaded that is an image
@Serializable
data class GroceryPhotoRequest(
    val fileId: Int,
)

// a new item, always on a list; amount, unit and category are left out, the backend has none for them
@Serializable
data class GroceryNewItemRequest(
    val listId: Int,
    val name: String,
)

// a category of a list (GROCERY-13); a list answers them in the order its groups show
@Serializable
data class GroceryCategoryResponse(
    val id: Int,
    val name: String,
    // one emoji, none without one
    val icon: String? = null,
)

// the name and the icon of a category of a list; no default, a default value is not sent
@Serializable
data class GroceryCategoryRequest(
    val name: String,
    // one emoji, null for none
    val icon: String?,
)

// the ids of all categories of a list, each once, in their new order
@Serializable
data class GroceryCategoryOrderRequest(
    val ids: List<Int>,
)

// the values of an item that is changed, to buy or bought; no defaults, null is sent: a missing
// category takes the category away from the item
@Serializable
data class GroceryItemRequest(
    val name: String,
    val amount: Double?,
    val unit: String?,
    val categoryId: Int?,
)

// the category of an item, one of its list, null for none; no default, null is sent
@Serializable
data class GroceryItemCategoryRequest(
    val categoryId: Int?,
)

@Serializable
data class ApiErrorMessage(
    val message: String,
)

@Serializable
data class ApiErrorResponse(
    val errors: List<ApiErrorMessage>,
)
