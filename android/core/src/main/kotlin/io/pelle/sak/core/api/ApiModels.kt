package io.pelle.sak.core.api

import kotlinx.serialization.Serializable

// the bodies of the api of sak-backend every app reads and sends: the deployment, the sign-in, the
// pages, the files and the errors; copies of the classes of the backend with the fields the apps use
// only, unknown fields are ignored (ApiJson)

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
    // the app the signin page of the web app hands the link on to, e.g. grocery-android; no
    // default, a default value is not sent
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

// a file the file storage keeps, e.g. a photo
@Serializable
data class StoredFileResponse(
    val id: Int,
)

@Serializable
data class ApiErrorMessage(
    val message: String,
)

@Serializable
data class ApiErrorResponse(
    val errors: List<ApiErrorMessage>,
)
