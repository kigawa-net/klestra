package dev.kigawa.klestra

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A null command is an internal no-op written at the beginning of each leader term. */
@Serializable
data class LogEntry(val term: Long, val command: String?)

@Serializable
sealed class ConsensusMessage {
    abstract val term: Long

    @Serializable
    @SerialName("RequestVote")
    data class RequestVote(
        override val term: Long,
        val lastLogIndex: Int,
        val lastLogTerm: Long,
    ) : ConsensusMessage()

    @Serializable
    @SerialName("VoteResponse")
    data class VoteResponse(override val term: Long, val granted: Boolean) : ConsensusMessage()

    @Serializable
    @SerialName("AppendEntries")
    data class AppendEntries(
        override val term: Long,
        val requestId: Long,
        val prevLogIndex: Int,
        val prevLogTerm: Long,
        val entries: List<LogEntry>,
        val leaderCommit: Int,
    ) : ConsensusMessage()

    @Serializable
    @SerialName("AppendResponse")
    data class AppendResponse(
        override val term: Long,
        val requestId: Long,
        val success: Boolean,
        val matchIndex: Int,
    ) : ConsensusMessage()
}

data class OutboundMessage(val to: String, val message: ConsensusMessage)
