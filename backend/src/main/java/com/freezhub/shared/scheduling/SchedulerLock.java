package com.freezhub.shared.scheduling;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a scheduled job on one instance at a time (`FZ-121`).
 *
 * <p>Five jobs run on every instance and nothing coordinated them. At the default desired
 * count of two that is not redundancy, it is duplication: every pending notification
 * delivered twice, and two audit rows for one restriction activating. A trail that cannot
 * be trusted to say how many times something happened is not evidence of anything.
 *
 * <p><strong>Every timestamp comes from the database.</strong> Two application instances
 * have two clocks, and a lock compared against the holder's own clock is a lock that skew
 * can hand to both of them. Postgres has one clock, and it is already the thing both
 * instances agree on.
 *
 * <p><strong>Acquisition is one statement.</strong> Read-then-write would let two
 * instances both read an expired lock and both take it. {@code ON CONFLICT … DO UPDATE …
 * WHERE locked_until < now()} is decided inside a single row lock, so exactly one of them
 * updates a row and the other is told it changed nothing.
 *
 * <p>Hand-rolled rather than ShedLock, following the precedent of {@code FZ-087}'s rate
 * limiter and {@code FZ-083}'s outbox. What is given up is real: ShedLock handles lease
 * extension for a job that overruns, and this does not. The TTL is the mitigation, and the
 * token below is what stops an overrunning holder releasing somebody else's lock.
 */
@Component
public class SchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLock.class);

    private final JdbcTemplate jdbc;

    public SchedulerLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs {@code work} if this instance can take the named lock, and does nothing if it
     * cannot.
     *
     * <p>The lock is released on the way out, including when the work throws — a job that
     * fails should not hold its name for the rest of the lease. If the process dies
     * instead, the lease expires on its own.
     *
     * @param ttl how long the lock survives an instance that dies mid-job. Comfortably
     *            longer than the job's worst case, because expiring early would let a
     *            second instance start the work this one is still doing.
     * @return whether the work ran
     */
    public boolean runIfAcquired(String name, Duration ttl, Runnable work) {
        String token = UUID.randomUUID().toString();
        if (!acquire(name, ttl, token)) {
            return false;
        }

        try {
            work.run();
            return true;
        } finally {
            release(name, token);
        }
    }

    /**
     * A separate transaction on purpose: the lock must outlive the work's own transaction,
     * or a job that rolls back would release its lock by rolling the row back too — and
     * two instances would then retry the same failing work in lockstep.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean acquire(String name, Duration ttl, String token) {
        int taken = jdbc.update("""
                insert into scheduler_lock (name, locked_until, token)
                values (?, now() + make_interval(secs => ?), ?)
                on conflict (name) do update
                   set locked_until = now() + make_interval(secs => ?),
                       token = excluded.token
                 where scheduler_lock.locked_until < now()
                """, name, (double) ttl.toSeconds(), token, (double) ttl.toSeconds());

        if (taken == 0) {
            log.debug("Another instance holds the {} lock", name);
        }
        return taken > 0;
    }

    /**
     * Releases the lock, but only if this instance still holds it.
     *
     * <p>The token is the whole point: a job that overran its lease has already lost the
     * lock to another instance, and releasing by name alone would cut that instance's work
     * short — turning a slow job into the duplicate delivery this class exists to prevent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void release(String name, String token) {
        jdbc.update("update scheduler_lock set locked_until = now() where name = ? and token = ?",
                name, token);
    }
}
