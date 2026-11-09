package dev.kigawa.klestra

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConsensusEngineTest {
    private class Cluster(ids: List<String> = listOf("a", "b", "c"), observers: Set<String> = emptySet()) {
        val members = ids.map { QuorumMember(it, "memory://$it", it !in observers) }
        val nodes = members.associate { member ->
            member.id to ConsensusEngine(config(member.id, members), Random(member.id.hashCode()))
        }
        var isolated: Set<String> = emptySet()

        fun deliver(from: String, messages: List<OutboundMessage>) {
            val queue = ArrayDeque<Pair<String, OutboundMessage>>()
            messages.forEach { queue.addLast(from to it) }
            var count = 0
            while (queue.isNotEmpty()) {
                check(++count < 10_000) { "Transport did not settle" }
                val (sender, outbound) = queue.removeFirst()
                if ((sender in isolated) != (outbound.to in isolated)) continue
                nodes.getValue(outbound.to).receive(sender, outbound.message).forEach {
                    queue.addLast(outbound.to to it)
                }
            }
        }

        fun elect(id: String) = deliver(id, nodes.getValue(id).tick(300))
        fun propose(id: String, value: String) = deliver(id, nodes.getValue(id).propose(value))
        fun heartbeat(id: String) = deliver(id, nodes.getValue(id).tick(100))
    }

    @Test
    fun automaticElectionReplicatesAndCommits() {
        val cluster = Cluster()
        assertTrue(cluster.nodes.getValue("a").tick(299).isEmpty())
        cluster.deliver("a", cluster.nodes.getValue("a").tick(1))
        assertEquals(AgentMode.LEADER, cluster.nodes.getValue("a").mode)
        assertEquals(1, cluster.nodes.values.count { it.mode == AgentMode.LEADER })
        cluster.propose("a", "hello")
        cluster.nodes.values.forEach {
            assertEquals(listOf("hello"), it.committedCommands)
            assertEquals(2, it.commitIndex) // leader no-op, command
        }
    }

    @Test
    fun singleVoterCommitsWithoutNetwork() {
        val cluster = Cluster(listOf("a"))
        cluster.elect("a")
        cluster.propose("a", "local")
        assertEquals(listOf("local"), cluster.nodes.getValue("a").committedCommands)
    }

    @Test
    fun minorityCannotCommitAndNewLeaderRepairsDivergentLog() {
        val cluster = Cluster()
        cluster.elect("a")
        cluster.propose("a", "before")
        cluster.isolated = setOf("a")
        cluster.propose("a", "discarded")
        assertEquals(listOf("before"), cluster.nodes.getValue("a").committedCommands)
        cluster.elect("b")
        assertEquals(AgentMode.LEADER, cluster.nodes.getValue("b").mode)
        cluster.propose("b", "after")
        cluster.isolated = emptySet()
        cluster.heartbeat("b")
        cluster.nodes.values.forEach {
            assertEquals(listOf("before", "after"), it.committedCommands)
            assertEquals(cluster.nodes.getValue("b").log, it.log)
        }
        assertEquals(AgentMode.FOLLOWER, cluster.nodes.getValue("a").mode)
    }

    @Test
    fun observersReplicateButNeverVoteOrStartElection() {
        val cluster = Cluster(listOf("a", "b", "c", "d"), setOf("d"))
        val observer = cluster.nodes.getValue("d")
        assertTrue(observer.tick(10_000).isEmpty())
        cluster.elect("a")
        cluster.propose("a", "observed")
        assertEquals(AgentMode.OBSERVER, observer.mode)
        assertEquals(listOf("observed"), observer.committedCommands)
        assertEquals(2, observer.quorumSize)
        val response = observer.receive("b", ConsensusMessage.RequestVote(5, 10, 4)).single().message
        assertFalse(assertIs<ConsensusMessage.VoteResponse>(response).granted)

        cluster.isolated = setOf("a", "d")
        cluster.propose("a", "no-majority")
        assertEquals(listOf("observed"), cluster.nodes.getValue("a").committedCommands)
        assertFailsWith<IllegalStateException> { observer.propose("forbidden") }
    }

    @Test
    fun duplicateVotesCannotCreateQuorumAndElectionRetries() {
        val cluster = Cluster(listOf("a", "b", "c", "d", "e"))
        val candidate = cluster.nodes.getValue("a")
        candidate.tick(300)
        val vote = ConsensusMessage.VoteResponse(1, true)
        candidate.receive("b", vote)
        candidate.receive("b", vote)
        candidate.receive("unknown", vote)
        assertEquals(AgentMode.CANDIDATE, candidate.mode)
        candidate.tick(300)
        assertEquals(2L, candidate.currentTerm)
        candidate.receive("c", vote) // old-term vote
        assertEquals(AgentMode.CANDIDATE, candidate.mode)
        candidate.receive("b", ConsensusMessage.VoteResponse(2, true))
        candidate.receive("c", ConsensusMessage.VoteResponse(2, true))
        assertEquals(AgentMode.LEADER, candidate.mode)
    }

    @Test
    fun votesAreLimitedToOneCandidatePerTermAndRequireFreshLog() {
        val cluster = Cluster()
        cluster.elect("a")
        cluster.propose("a", "saved")
        val follower = cluster.nodes.getValue("b")
        fun vote(from: String, index: Int, logTerm: Long): Boolean =
            assertIs<ConsensusMessage.VoteResponse>(follower.receive(from,
                ConsensusMessage.RequestVote(2, index, logTerm)).single().message).granted
        assertFalse(vote("c", 0, 0))
        assertTrue(vote("a", 2, 1))
        assertFalse(vote("c", 2, 1))
        assertTrue(vote("a", 2, 1))
    }

    @Test
    fun heartbeatsResetElectionAndOldLeadersAreRejected() {
        val cluster = Cluster()
        cluster.elect("a")
        repeat(10) {
            cluster.nodes.getValue("b").tick(200)
            cluster.heartbeat("a")
        }
        assertEquals(1L, cluster.nodes.getValue("b").currentTerm)
        val follower = cluster.nodes.getValue("b")
        follower.receive("c", ConsensusMessage.RequestVote(2, 0, 0))
        val stale = ConsensusMessage.AppendEntries(1, 42, 0, 0, emptyList(), 0)
        val response = assertIs<ConsensusMessage.AppendResponse>(follower.receive("a", stale).single().message)
        assertFalse(response.success)
        assertEquals(2L, response.term)
        assertEquals(null, follower.leaderId)
    }

    @Test
    fun delayedAppendFailureCannotRewindReplication() {
        val cluster = Cluster()
        cluster.elect("a")
        val leader = cluster.nodes.getValue("a")
        val old = assertIs<ConsensusMessage.AppendEntries>(leader.tick(100).first().message)
        val fresh = leader.propose("new").first()
        val request = assertIs<ConsensusMessage.AppendEntries>(fresh.message)
        assertTrue(leader.receive(fresh.to,
            ConsensusMessage.AppendResponse(1, old.requestId, false, 0)).isEmpty())
        cluster.deliver(fresh.to, cluster.nodes.getValue(fresh.to).receive("a", request))
        cluster.heartbeat("a")
        assertEquals(listOf("new"), leader.committedCommands)
    }

    @Test
    fun newLeaderNoOpCommitsEntriesFromPreviousTerm() {
        val cluster = Cluster()
        cluster.elect("a")
        val leader = cluster.nodes.getValue("a")
        val outbound = leader.propose("survives").single { it.to == "b" }
        // Replicate on b, but drop its acknowledgement before a can commit.
        cluster.nodes.getValue("b").receive("a", outbound.message)
        assertTrue(leader.committedCommands.isEmpty())
        cluster.isolated = setOf("a")
        cluster.elect("b")
        assertEquals(listOf("survives"), cluster.nodes.getValue("b").committedCommands)
    }

    @Test
    fun configurationAndMessagesRoundTripThroughJson() {
        val original = config("a", listOf(QuorumMember("a", "memory://a")))
        val json = Json { encodeDefaults = true }
        assertEquals(original, json.decodeFromString<KlestraConfig>(json.encodeToString(original)))
        val message: ConsensusMessage = ConsensusMessage.AppendEntries(1, 1, 0, 0,
            listOf(LogEntry(1, null), LogEntry(1, "hello")), 0)
        assertEquals(message, json.decodeFromString<ConsensusMessage>(json.encodeToString(message)))
    }

    @Test
    fun invalidMembershipAndTimeoutsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ElectionPolicy.Automatic(100, 50, 10) }
        assertFailsWith<IllegalArgumentException> { ElectionPolicy.Automatic(100, 200, 100) }
        assertFailsWith<IllegalArgumentException> { ElectionPolicy.Automatic(0, 200, 10) }
        assertFailsWith<IllegalArgumentException> { KlestraSpec("a", emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            KlestraSpec("a", listOf(QuorumMember("a", "x"), QuorumMember("a", "y")))
        }
        assertFailsWith<IllegalArgumentException> {
            KlestraSpec("a", listOf(QuorumMember("a", "x", false)))
        }
    }

    companion object {
        private fun config(id: String, members: List<QuorumMember>) = KlestraConfig(
            ObjectMeta("test"), KlestraSpec(id, members, ElectionPolicy.Automatic(300, 300, 100)),
        )
    }
}
