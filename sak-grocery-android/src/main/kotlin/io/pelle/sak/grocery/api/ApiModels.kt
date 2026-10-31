package io.pelle.sak.grocery.api

import kotlinx.serialization.Serializable

// the bodies of the api of sak-backend the app reads and sends, copies of the request and response
// classes of the backend with the fields the app uses only; unknown fields are ignored (ApiJson)

@Serializable
data class InfoResponse(
    // the name of the deployment
    val name: String,
)

@Serializable
data class LoginMethodsResponse(
    // the e-mail login, a mail server is configured
    val email: Boolean,
    // the password login of the admin
    val password: Boolean,
)

@Serializable
data class LoginRequest(
    val email: String,
    // android: the signin page of the web app hands the link on to this app (FDR-005); no default,
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
)

@Serializable
data class GroceryItemResponse(
    val id: Int,
    val listId: Int,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    // the name of a category, none when missing
    val category: String? = null,
)

@Serializable
data class GroceryCategoryResponse(
    val name: String,
    val label: String,
)

@Serializable
data class ApiErrorMessage(
    val message: String,
)

@Serializable
data class ApiErrorResponse(
    val errors: List<ApiErrorMessage>,
)
