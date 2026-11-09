package dev.kigawa.klestra

import kotlinx.serialization.Serializable

/** Raft roles. OBSERVER replicates the log but never votes or becomes leader. */
@Serializable
enum class AgentMode {
    FOLLOWER,
    CANDIDATE,
    LEADER,
    OBSERVER,
}
