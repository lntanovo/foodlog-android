package com.foodlog.app.cloud

import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI

data class CloudConfig(
    val baseUrl: String = "https://daily-todo-d3gq5mama7a468d02.api.tcloudbasegateway.com",
    val bucket: String = "foodlog-backups",
) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.orEmpty().trim('/').isEmpty()) {
            "云服务地址必须是 HTTPS 服务根地址"
        }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9-]{0,62}"))) { "云备份存储名称无效" }
    }

    companion object {
        const val MAX_COMPRESSED_BYTES: Long = 64L * 1024 * 1024
    }
}

class CloudException(message: String, val httpStatus: Int? = null) : IOException(message)

@Serializable
data class CloudSession(
    val uid: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val generation: String,
) {
    val identity: CloudIdentity get() = CloudIdentity(uid, username, generation)
    override fun toString(): String = "CloudSession(credentials=redacted)"
}

data class CloudIdentity(val uid: String, val username: String, val generation: String)
