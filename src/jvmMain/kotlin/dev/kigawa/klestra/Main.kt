package dev.kigawa.klestra

import kotlin.random.Random

/** The queue represents a transport; production applications supply their own RPC delivery. */
fun main() {
    val members = (1..3).map { QuorumMember("node-$it", "memory://node-$it") }
    val nodes = members.associate { member ->
        member.id to ConsensusEngine(
            KlestraConfig(ObjectMeta("embedded-quorum"), KlestraSpec(member.id, members)),
            Random(member.id.hashCode()),
        )
    }
    fun deliver(from: String, messages: List<OutboundMessage>) {
        val queue = ArrayDeque<Pair<String, OutboundMessage>>()
        messages.forEach { queue.addLast(from to it) }
        while (queue.isNotEmpty()) {
            val (sender, outbound) = queue.removeFirst()
            nodes.getValue(outbound.to).receive(sender, outbound.message).forEach {
                queue.addLast(outbound.to to it)
            }
        }
    }

    repeat(100) {
        nodes.forEach { (id, engine) -> deliver(id, engine.tick(10)) }
    }
    val leader = nodes.values.single { it.mode == AgentMode.LEADER }
    println("Leader: ${leader.nodeId}, term: ${leader.currentTerm}")
    deliver(leader.nodeId, leader.propose("put greeting=hello"))
    nodes.forEach { (id, engine) ->
        check(engine.committedCommands == listOf("put greeting=hello"))
        println("$id: ${engine.mode}, commitIndex=${engine.commitIndex}, ${engine.committedCommands}")
    }
}
