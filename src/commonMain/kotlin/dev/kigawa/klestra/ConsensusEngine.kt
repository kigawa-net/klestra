package dev.kigawa.klestra

import kotlin.random.Random

/**
 * In-memory, fixed-membership Raft core. Call tick/receive/propose from one event loop.
 * Returned messages must be delivered by the embedding application; no I/O runs here.
 * Index 0 is the empty-log sentinel, so public log indexes are one-based.
 */
class ConsensusEngine(config: KlestraConfig, private val random: Random = Random.Default) {
    val nodeId: String = config.spec.nodeId
    private val members = config.spec.members.associateBy { it.id }
    private val voters = members.values.filter { it.voting }.map { it.id }.toSet()
    private val peers = members.keys - nodeId
    private val policy = config.spec.electionPolicy as ElectionPolicy.Automatic
    val quorumSize: Int = voters.size / 2 + 1

    var mode: AgentMode = if (nodeId in voters) AgentMode.FOLLOWER else AgentMode.OBSERVER
        private set
    var currentTerm: Long = 0
        private set
    var votedFor: String? = null
        private set
    var leaderId: String? = null
        private set
    var commitIndex: Int = 0
        private set

    private val entries = mutableListOf<LogEntry>()
    val log: List<LogEntry> get() = entries.toList()
    val committedCommands: List<String> get() = entries.take(commitIndex).mapNotNull { it.command }
    private var electionRemaining = policy.nextTimeoutMillis(random)
    private var heartbeatRemaining = policy.heartbeatIntervalMillis
    private val votes = mutableSetOf<String>()
    private val nextIndex = mutableMapOf<String, Int>()
    private val matchIndex = mutableMapOf<String, Int>()
    private var sequence: Long = 0
    private val pending = mutableMapOf<String, ConsensusMessage.AppendEntries>()

    fun tick(elapsedMillis: Long): List<OutboundMessage> {
        require(elapsedMillis >= 0)
        if (mode == AgentMode.OBSERVER) return emptyList()
        if (mode == AgentMode.LEADER) {
            if (elapsedMillis < heartbeatRemaining) {
                heartbeatRemaining -= elapsedMillis
                return emptyList()
            }
            heartbeatRemaining = policy.heartbeatIntervalMillis
            return broadcastAppend()
        }
        if (elapsedMillis < electionRemaining) {
            electionRemaining -= elapsedMillis
            return emptyList()
        }
        return startElection()
    }

    /** Append locally; delivery of returned RPCs is needed to reach a quorum. */
    fun propose(command: String): List<OutboundMessage> {
        check(mode == AgentMode.LEADER) { "Only the leader can propose commands" }
        entries += LogEntry(currentTerm, command)
        advanceCommit()
        return broadcastAppend()
    }

    fun receive(from: String, message: ConsensusMessage): List<OutboundMessage> {
        if (from !in peers || message.term <= 0) return emptyList()
        // An observer cannot originate elections or leader RPCs.
        if (from !in voters && (message is ConsensusMessage.RequestVote ||
                message is ConsensusMessage.AppendEntries)) return emptyList()
        if (message.term > currentTerm) stepDown(message.term)
        return when (message) {
            is ConsensusMessage.RequestVote -> onVoteRequest(from, message)
            is ConsensusMessage.VoteResponse -> onVoteResponse(from, message)
            is ConsensusMessage.AppendEntries -> onAppend(from, message)
            is ConsensusMessage.AppendResponse -> onAppendResponse(from, message)
        }
    }

    private fun startElection(): List<OutboundMessage> {
        mode = AgentMode.CANDIDATE
        currentTerm++
        leaderId = null
        votedFor = nodeId
        votes.clear()
        votes += nodeId
        resetElection()
        if (votes.size >= quorumSize) return becomeLeader()
        val request = ConsensusMessage.RequestVote(currentTerm, entries.size, termAt(entries.size))
        return (voters - nodeId).map { OutboundMessage(it, request) }
    }

    private fun onVoteRequest(from: String, request: ConsensusMessage.RequestVote): List<OutboundMessage> {
        val upToDate = request.lastLogTerm > termAt(entries.size) ||
            (request.lastLogTerm == termAt(entries.size) && request.lastLogIndex >= entries.size)
        val grant = nodeId in voters && request.term == currentTerm &&
            (votedFor == null || votedFor == from) && upToDate
        if (grant) {
            votedFor = from
            resetElection()
        }
        return listOf(OutboundMessage(from, ConsensusMessage.VoteResponse(currentTerm, grant)))
    }

    private fun onVoteResponse(from: String, response: ConsensusMessage.VoteResponse): List<OutboundMessage> {
        if (mode != AgentMode.CANDIDATE || response.term != currentTerm || from !in voters ||
            !response.granted) return emptyList()
        votes += from
        return if (votes.size >= quorumSize) becomeLeader() else emptyList()
    }

    private fun becomeLeader(): List<OutboundMessage> {
        mode = AgentMode.LEADER
        leaderId = nodeId
        peers.forEach {
            nextIndex[it] = entries.size + 1
            matchIndex[it] = 0
        }
        // A current-term no-op also permits safe commitment of earlier-term entries.
        entries += LogEntry(currentTerm, null)
        heartbeatRemaining = policy.heartbeatIntervalMillis
        advanceCommit()
        return broadcastAppend()
    }

    private fun onAppend(from: String, request: ConsensusMessage.AppendEntries): List<OutboundMessage> {
        fun respond(success: Boolean, index: Int = 0) = listOf(OutboundMessage(from,
            ConsensusMessage.AppendResponse(currentTerm, request.requestId, success, index)))
        if (request.term < currentTerm) return respond(false)
        stepDown(currentTerm)
        leaderId = from
        resetElection()
        if (request.prevLogIndex !in 0..entries.size ||
            termAt(request.prevLogIndex) != request.prevLogTerm) return respond(false)
        if (request.leaderCommit < 0 || request.entries.any { it.term !in 1..currentTerm }) {
            return respond(false)
        }
        request.entries.forEachIndexed { offset, entry ->
            val index = request.prevLogIndex + offset + 1
            if (index <= entries.size && entries[index - 1] != entry) {
                // A valid Raft leader never overwrites a committed entry.
                if (index <= commitIndex) return respond(false)
                entries.subList(index - 1, entries.size).clear()
            }
            if (index > entries.size) entries += entry
        }
        val matched = request.prevLogIndex + request.entries.size
        commitIndex = maxOf(commitIndex, minOf(request.leaderCommit, matched))
        return respond(true, matched)
    }

    private fun onAppendResponse(from: String, response: ConsensusMessage.AppendResponse): List<OutboundMessage> {
        if (mode != AgentMode.LEADER || response.term != currentTerm) return emptyList()
        val request = pending[from] ?: return emptyList()
        // Correlation prevents delayed failures from undoing newer replication progress.
        if (response.requestId != request.requestId) return emptyList()
        pending.remove(from)
        if (!response.success) {
            nextIndex[from] = maxOf(1, nextIndex.getValue(from) - 1)
            return listOf(appendFor(from))
        }
        val matched = request.prevLogIndex + request.entries.size
        if (response.matchIndex != matched) return emptyList()
        matchIndex[from] = maxOf(matchIndex.getValue(from), matched)
        nextIndex[from] = matchIndex.getValue(from) + 1
        val previousCommit = commitIndex
        advanceCommit()
        if (commitIndex > previousCommit) return broadcastAppend()
        return if (nextIndex.getValue(from) <= entries.size) listOf(appendFor(from)) else emptyList()
    }

    private fun advanceCommit() {
        val replicated = voters.map { if (it == nodeId) entries.size else matchIndex[it] ?: 0 }
            .sortedDescending()[quorumSize - 1]
        if (replicated > commitIndex && termAt(replicated) == currentTerm) commitIndex = replicated
    }

    private fun broadcastAppend(): List<OutboundMessage> = peers.map { appendFor(it) }

    private fun appendFor(peer: String): OutboundMessage {
        val previous = nextIndex.getValue(peer) - 1
        val request = ConsensusMessage.AppendEntries(
            currentTerm, ++sequence, previous, termAt(previous), entries.drop(previous), commitIndex,
        )
        pending[peer] = request
        return OutboundMessage(peer, request)
    }

    private fun stepDown(term: Long) {
        if (term > currentTerm) {
            currentTerm = term
            votedFor = null
        }
        mode = if (nodeId in voters) AgentMode.FOLLOWER else AgentMode.OBSERVER
        leaderId = null
        votes.clear()
        pending.clear()
        resetElection()
    }

    private fun resetElection() {
        electionRemaining = policy.nextTimeoutMillis(random)
    }

    private fun termAt(index: Int): Long = if (index == 0) 0 else entries[index - 1].term
}
