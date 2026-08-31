package no.nav.budstikka.application.observability

import no.nav.budstikka.domain.decision.Channel
import kotlin.time.Clock
import kotlin.time.Instant

/** Operational view of non-terminal inbox rows, evaluated at [OperationalQueueSnapshot.observedAt]. */
enum class InboxQueueState {
    /** Immediately claimable. Age starts when the row became claimable, not before a scheduled WAIT. */
    DUE,

    /** CLAIMED with an active lease. */
    IN_FLIGHT,

    /** WAIT with a future sending-window retry. This is expected waiting, not backlog. */
    WAITING,
}

/** Operational view of non-terminal delivery rows, evaluated at [OperationalQueueSnapshot.observedAt]. */
enum class DeliveryQueueState {
    /** Immediately claimable. Age starts at creation for READY and at lease expiry for CLAIMED. */
    DUE,

    /** CLAIMED with an active lease. */
    IN_FLIGHT,
}

data class QueueStats(
    val size: Long,
    val oldestAt: Instant?,
) {
    init {
        require(size >= 0) { "size must not be negative" }
        require((size == 0L) == (oldestAt == null)) { "oldestAt must be present exactly when size is positive" }
    }

    companion object {
        val EMPTY = QueueStats(size = 0, oldestAt = null)
    }
}

data class DeliveryQueueKey(
    val channel: Channel,
    val state: DeliveryQueueState,
)

data class OperationalQueueSnapshot(
    val observedAt: Instant,
    val inbox: Map<InboxQueueState, QueueStats>,
    val deliveries: Map<DeliveryQueueKey, QueueStats>,
)

fun interface OperationalQueueSnapshotRepository {
    suspend fun snapshot(observedAt: Instant): OperationalQueueSnapshot
}

fun interface OperationalQueueMetrics {
    fun record(snapshot: OperationalQueueSnapshot)
}

class OperationalQueueSnapshotWorker(
    private val repository: OperationalQueueSnapshotRepository,
    private val metrics: OperationalQueueMetrics,
    private val clock: Clock = Clock.System,
) {
    suspend fun runOnce() {
        metrics.record(repository.snapshot(clock.now()))
    }
}
