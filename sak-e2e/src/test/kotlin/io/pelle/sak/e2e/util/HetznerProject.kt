package io.pelle.sak.e2e.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.time.Duration
import java.util.Base64

// the label every resource of a deployment carries, its value the name
const val NAME_LABEL = "sak.pelle.io/name"

// a resource of the project that carries the name label: what it is, its name, the deployment
data class LabeledResource(
    val kind: String,
    val name: String,
    val sakName: String,
)

// the Hetzner Cloud project of a token, as far as the tests need it: the resources that carry
// the name label, found and deleted without the wizard, and the dns zones. Only resources whose
// name label matches are ever deleted
class HetznerProject(
    private val token: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    private fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
    ): HttpResponse<String> {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI("$API/$path"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .timeout(Duration.ofMinutes(1))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
    ): JsonObject {
        val response = request(method, path, body)
        check(response.statusCode() in 200..299) { "$method $path answered ${response.statusCode()}: ${response.body()}" }
        return if (response.body().isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(response.body()).jsonObject
    }

    // every page of a list, `key` the name of the list in the answer
    private fun list(
        path: String,
        key: String,
        query: String = "",
    ): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var page: Long? = 1
        while (page != null) {
            val separator = if (query.isEmpty()) "" else "&"
            val answer = call("GET", "$path?$query${separator}page=$page&per_page=50")
            items += answer.getValue(key).jsonArray.map { it.jsonObject }
            page =
                answer["meta"]
                    ?.jsonObject
                    ?.get("pagination")
                    ?.jsonObject
                    ?.get("next_page")
                    ?.jsonPrimitive
                    ?.longOrNull
        }
        return items
    }

    private val labeled = "label_selector=" + URLEncoder.encode(NAME_LABEL, Charsets.UTF_8)

    private fun JsonObject.sakName(): String? = this["labels"]?.jsonObject?.get(NAME_LABEL)?.jsonPrimitive?.content

    private fun JsonObject.id(): Long = getValue("id").jsonPrimitive.longOrNull ?: error("no id in $this")

    private fun JsonObject.name(): String = getValue("name").jsonPrimitive.content

    private fun JsonObject.deleteProtected(): Boolean =
        this["protection"]
            ?.jsonObject
            ?.get("delete")
            ?.jsonPrimitive
            ?.booleanOrNull == true

    /**
     * creates an ssh key without any label, as a user of the project would: a resource sakctl
     * must never change or remove. A key of its own each time, hetzner refuses a fingerprint twice
     */
    fun createForeignSshKey(name: String) {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        // the raw key is the last 32 bytes of its X.509 encoding
        val raw = generator.generateKeyPair().public.encoded.takeLast(32).toByteArray()
        val blob = ByteArrayOutputStream()
        DataOutputStream(blob).use { out ->
            listOf("ssh-ed25519".toByteArray(), raw).forEach {
                out.writeInt(it.size)
                out.write(it)
            }
        }
        val publicKey = "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob.toByteArray())
        call(
            "POST",
            "ssh_keys",
            buildJsonObject {
                put("name", name)
                put("public_key", publicKey)
            },
        )
    }

    /** whether an ssh key of the name exists, labeled or not */
    fun sshKeyExists(name: String): Boolean = list("ssh_keys", "ssh_keys", "name=" + URLEncoder.encode(name, Charsets.UTF_8)).isNotEmpty()

    /** deletes the ssh key of the name, labeled or not: for the keys a test created itself */
    fun deleteSshKey(name: String) {
        list("ssh_keys", "ssh_keys", "name=" + URLEncoder.encode(name, Charsets.UTF_8)).forEach { call("DELETE", "ssh_keys/${it.id()}") }
    }

    /** the names of the dns zones of the project */
    /** the public ipv4 of the server of the deployment `name`; a read only, sakctl creates and removes it */
    fun serverIpv4(name: String): String =
        list("servers", "servers", labeled)
            .single { it.sakName() == name }
            .getValue("public_net")
            .jsonObject
            .getValue("ipv4")
            .jsonObject
            .getValue("ip")
            .jsonPrimitive
            .content

    fun zones(): List<String> = list("zones", "zones").map { it.name() }

    /** every resource and dns record whose name label `matches` */
    fun resources(matches: (String) -> Boolean): List<LabeledResource> {
        val found = mutableListOf<LabeledResource>()
        for ((path, kind) in KINDS) {
            for (item in list(path, path, labeled)) {
                val sakName = item.sakName() ?: continue
                if (matches(sakName)) found += LabeledResource(kind, item.name(), sakName)
            }
        }
        for (zone in zones()) {
            for (rrset in list("zones/$zone/rrsets", "rrsets", labeled)) {
                val sakName = rrset.sakName() ?: continue
                if (matches(sakName)) found += LabeledResource("dns record", "${rrset.name()}.$zone/${rrset.getValue("type").jsonPrimitive.content}", sakName)
            }
        }
        return found
    }

    /**
     * deletes every resource and dns record whose name label `matches`, dependents first: the
     * dns records and the servers, then what the servers used. Never a dns zone
     */
    fun deleteResources(matches: (String) -> Boolean) {
        for (zone in zones()) {
            for (rrset in list("zones/$zone/rrsets", "rrsets", labeled).filter { it.sakName()?.let(matches) == true }) {
                val path = "zones/$zone/rrsets/${rrset.name()}/${rrset.getValue("type").jsonPrimitive.content}"
                if (request("DELETE", path).statusCode() !in listOf(200, 201, 204, 404)) error("DELETE $path failed")
            }
        }
        val servers = list("servers", "servers", labeled).filter { it.sakName()?.let(matches) == true }
        for (server in servers) {
            if (server.deleteProtected()) unprotect("servers", server.id(), rebuild = true)
            call("DELETE", "servers/${server.id()}")
        }
        await("the servers are deleted") { list("servers", "servers", labeled).none { it.sakName()?.let(matches) == true } }

        for (firewall in list("firewalls", "firewalls", labeled).filter { it.sakName()?.let(matches) == true }) {
            val appliedTo = firewall["applied_to"]?.jsonArray ?: JsonArray(emptyList())
            if (appliedTo.isNotEmpty()) {
                call("POST", "firewalls/${firewall.id()}/actions/remove_from_resources", buildJsonObject { put("remove_from", appliedTo) })
            }
            retry("delete firewall ${firewall.name()}") { request("DELETE", "firewalls/${firewall.id()}").statusCode() in listOf(204, 404) }
        }
        for (path in listOf("primary_ips", "volumes")) {
            for (item in list(path, path, labeled).filter { it.sakName()?.let(matches) == true }) {
                if (item.deleteProtected()) unprotect(path, item.id())
                if (path == "volumes" && item["server"] != null && item["server"] != JsonNull) {
                    call("POST", "volumes/${item.id()}/actions/detach")
                }
                retry("delete ${path.removeSuffix("s")} ${item.name()}") { request("DELETE", "$path/${item.id()}").statusCode() in listOf(204, 404) }
            }
        }
        for (key in list("ssh_keys", "ssh_keys", labeled).filter { it.sakName()?.let(matches) == true }) {
            call("DELETE", "ssh_keys/${key.id()}")
        }
    }

    private fun unprotect(
        path: String,
        id: Long,
        rebuild: Boolean = false,
    ) {
        call(
            "POST",
            "$path/$id/actions/change_protection",
            buildJsonObject {
                put("delete", false)
                if (rebuild) put("rebuild", false)
            },
        )
    }

    // a delete the api refuses while an action on the resource still runs, tried again
    private fun retry(
        what: String,
        attempt: () -> Boolean,
    ) = await(what, attempt)

    private fun await(
        what: String,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos()
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out: $what" }
            Thread.sleep(3_000)
        }
    }

    companion object {
        const val API = "https://api.hetzner.cloud/v1"

        // the kinds of resources a deployment consists of, by their path in the api
        private val KINDS =
            listOf(
                "servers" to "server",
                "ssh_keys" to "ssh key",
                "firewalls" to "firewall",
                "volumes" to "volume",
                "primary_ips" to "primary ip",
            )
    }
}
