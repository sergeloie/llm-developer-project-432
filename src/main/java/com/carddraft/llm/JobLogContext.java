package com.carddraft.llm;

import org.slf4j.MDC;

/**
 * Which job the current thread is working on.
 *
 * <p>A thread local plus the logging context, and the two are set together because they answer the
 * same question for different audiences: the thread local is what the cost recorder reads, the
 * logging context is what a log search reads.
 *
 * <p>Not passed as an argument through every layer that might make a call. That would thread a job
 * identifier through parsers, repositories and prompts that have no use for it, and every new call
 * site would then have a decision to make about it. The cost of that is not compile errors — it is
 * a new code path quietly recording nothing.
 *
 * <p>Deliberately inheritable, because a model call can hand work to another thread and the record
 * should still name the job. What it is not allowed to do is leak: {@link #clear()} runs in a
 * finally block, so a pooled thread does not carry one job's identifier into the next.
 */
public final class JobLogContext {

    private static final String MDC_KEY = "job.id";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private JobLogContext() {
    }

    /**
     * Binds a job to this thread for the duration of the try block.
     *
     * <p>Scoped rather than a setter with a matching clear, because an unbalanced clear leaves a
     * thread with no job and a forgotten clear leaves it with the wrong one. Both fail quietly, and
     * the second one attributes a cost to the wrong card.
     */
    public static <T> T withJob(String jobId, java.util.function.Supplier<T> work) {
        String previous = CURRENT.get();
        CURRENT.set(jobId);
        MDC.put(MDC_KEY, jobId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
                MDC.remove(MDC_KEY);
            } else {
                CURRENT.set(previous);
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    public static void withJob(String jobId, Runnable work) {
        withJob(jobId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * The job being worked on, or null when there is none.
     *
     * <p>Null is a legitimate answer and must not be an error: the synchronous endpoint calls the
     * model with no job behind it, and refusing to record those calls would leave the table
     * answering "what did the asynchronous path cost" while appearing to answer the real question.
     */
    public static String currentJob() {
        return CURRENT.get();
    }
}