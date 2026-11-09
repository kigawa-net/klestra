package dev.kigawa.klestra

import kotlinx.serialization.Serializable

/** Kubernetes-style custom resource data; installation of a CRD is a separate concern. */
@Serializable
data class KlestraConfig(
    val metadata: ObjectMeta,
    val spec: KlestraSpec,
    val apiVersion: String = "klestra.kigawa.dev/v1alpha1",
    val kind: String = "KlestraConfig",
) {
    init {
        require(apiVersion == "klestra.kigawa.dev/v1alpha1")
        require(kind == "KlestraConfig")
    }
}

@Serializable
data class ObjectMeta(val name: String, val namespace: String = "default") {
    init {
        require(name.isNotBlank())
        require(namespace.isNotBlank())
    }
}

@Serializable
data class QuorumMember(
    val id: String,
    val endpoint: String,
    val voting: Boolean = true,
) {
    init {
        require(id.isNotBlank())
        require(endpoint.isNotBlank())
    }
}

@Serializable
data class KlestraSpec(
    val nodeId: String,
    val members: List<QuorumMember>,
    val electionPolicy: ElectionPolicy = ElectionPolicy.Automatic(),
) {
    init {
        require(nodeId.isNotBlank())
        require(members.map { it.id }.distinct().size == members.size) { "Duplicate member IDs" }
        require(members.any { it.id == nodeId }) { "Local node must be a member" }
        require(members.any { it.voting }) { "At least one voting member is required" }
    }
}
