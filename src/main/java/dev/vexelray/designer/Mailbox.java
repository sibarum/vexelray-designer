package dev.vexelray.designer;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * A mailbox and the one thread that drains it: <b>capacity one, latest wins</b>.
 *
 * <p>The first component on this stack, written by hand. The model it is a witness for —
 * {@code vexelray-framework/docs/architecture.md}, <i>the concurrency model</i> — is <i>one component, one
 * thread, one mailbox</i>, with placement decided in the wiring and never at runtime. This is that, for one
 * component, with nothing generalised yet: what two of these have in common is what the framework should own,
 * and one of them is not a census.
 *
 * <h2>Why the mailbox is here and not on the bus</h2>
 *
 * <p>Atchung has the right policy already — {@code Backpressure.COALESCE_LATEST}, whose capacity <em>is</em>
 * one, for exactly the payload class a scene edit belongs to: the next edit supersedes this one, so dropping
 * it costs nothing that could still have been drawn. What it does not have is a way to <em>wait</em>.
 * {@code Pump.drain()} is non-blocking and there is no signal when something arrives, because a {@code Pump}
 * is drained by an owner that already has a wake of its own — a frame loop. A component whose only reason to
 * wake is its own mailbox has nothing to park on.
 *
 * <p>So the inbound mailbox is a field and a park, and the bus carries the <em>result</em> outward, where it
 * has many readers and earns its place. Whether {@code Pump} should grow an await instead is a question for
 * atchung, and the second component is the one that should answer it.
 *
 * <h2>Stop, not drain-then-stop</h2>
 *
 * <p>The model says an actor's shutdown is <i>drain then stop</i>. That rule is about <b>edges</b> — a
 * keystroke, a command, a mutation — where nothing downstream can reconstruct what is dropped. A coalescing
 * mailbox holds a sample by construction, and the sample it is holding at shutdown is a picture nobody will
 * see. Draining it would be work done to produce an image for a window that is closing.
 */
final class Mailbox<T> implements AutoCloseable {

    /** How long {@link #close()} waits for the thread to notice, before leaving it to the daemon rule. */
    private static final long STOP_MILLIS = 250;

    /**
     * The one slot. A write overwrites whatever was waiting, which is the whole of the coalescing policy —
     * there is no queue to bound and no eviction to choose.
     */
    private final AtomicReference<T> pending = new AtomicReference<>();

    private final Thread thread;
    private volatile boolean running = true;

    /**
     * Start the component.
     *
     * <p><b>A platform thread, not a virtual one</b>, and the argument is Kronometer's rather than a
     * preference: a desktop application that follows its advice runs the timeline kernel on a single carrier,
     * and work placed on that carrier deadlocks against the serialisation that makes the baton fast.
     *
     * <p>Daemon, so a component that will not stop cannot hold the process open — the same rule
     * {@code Gui}'s own workers follow.
     */
    Mailbox(String name, Consumer<T> work) {
        this.thread = Thread.ofPlatform().name(name).daemon(true).unstarted(() -> run(work));
        this.thread.start();
    }

    /** Post a message, superseding whatever has not been taken yet. Safe from any thread. */
    void post(T message) {
        pending.set(message);
        LockSupport.unpark(thread);
    }

    /**
     * Whether a newer message arrived while the current one was being handled.
     *
     * <p>For the handler to ask before it publishes: a result already known to be superseded is a picture of
     * a design the user has moved past, and showing it is a flash of the wrong thing on the way to the right
     * one. The work cannot be taken back — nothing here cancels — but it need not be applied.
     */
    boolean superseded() {
        return pending.get() != null;
    }

    private void run(Consumer<T> work) {
        while (running) {
            T next = pending.getAndSet(null);
            if (next == null) {
                // Parked until a post unparks us. A permit left by a post that raced this check makes the
                // next park return immediately, which is why the loop re-reads rather than trusting the wake.
                LockSupport.park(this);
                continue;
            }
            work.accept(next);
        }
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        try {
            thread.join(STOP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
