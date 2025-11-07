package org.example.tasks;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class TaskOrchestrator {
	@FunctionalInterface
	public interface InterruptibleTask {
		void run() throws Exception;
	}

	private enum TaskType { IMMEDIATE, FIXED_DELAY }

	private static final class TaskSpec {
		final String name;
		final InterruptibleTask task;
		final TaskType type;
		final long initialDelay;
		final long delay;
		final TimeUnit unit;

		TaskSpec(String name,
				 InterruptibleTask task,
				 TaskType type,
				 long initialDelay,
				 long delay,
				 TimeUnit unit) {
			this.name = name;
			this.task = task;
			this.type = type;
			this.initialDelay = initialDelay;
			this.delay = delay;
			this.unit = unit;
		}
	}

	private static final class TaskHandle {
		final TaskSpec spec;
		volatile Future<?> future;

		TaskHandle(TaskSpec spec, Future<?> future) {
			this.spec = spec;
			this.future = future;
		}
	}

	private final int poolSize;
	private final ThreadFactory threadFactory;
	private final BooleanSupplier keepRunning;
	private final Logger log;
	private final long monitorIntervalMs;

	private final List<TaskSpec> specs = new ArrayList<>();
	private final Map<String, TaskHandle> handles = new ConcurrentHashMap<>();

	private final AtomicBoolean started = new AtomicBoolean(false);
	private ScheduledThreadPoolExecutor executor;
	private ScheduledFuture<?> monitorFuture;

	public TaskOrchestrator(int poolSize,
						 ThreadFactory threadFactory,
						 BooleanSupplier keepRunning,
						 Logger log,
						 long monitorIntervalMs) {
		this.poolSize = poolSize;
		this.threadFactory = threadFactory;
		this.keepRunning = keepRunning;
		this.log = log;
		this.monitorIntervalMs = monitorIntervalMs;
	}

	public TaskOrchestrator addImmediateTask(String name, InterruptibleTask task) {
		specs.add(new TaskSpec(name, task, TaskType.IMMEDIATE, 0, 0, null));
		return this;
	}

	public TaskOrchestrator addFixedDelayTask(String name,
									  InterruptibleTask task,
									  long initialDelay,
									  long delay,
									  TimeUnit unit) {
		specs.add(new TaskSpec(name, task, TaskType.FIXED_DELAY, initialDelay, delay, unit));
		return this;
	}

	public synchronized void start() {
		if (started.getAndSet(true)) {
			return;
		}
		executor = new ScheduledThreadPoolExecutor(poolSize, threadFactory);
		executor.setRemoveOnCancelPolicy(true);
		executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

		specs.forEach(spec -> {
			Future<?> future = schedule(spec);
			handles.put(spec.name, new TaskHandle(spec, future));
		});

		monitorFuture = executor.scheduleWithFixedDelay(this::monitorTasks,
				monitorIntervalMs,
				monitorIntervalMs,
				TimeUnit.MILLISECONDS);
	}

	public synchronized void stop() {
		if (!started.getAndSet(false)) {
			return;
		}
		if (monitorFuture != null) {
			monitorFuture.cancel(true);
			monitorFuture = null;
		}

		Collection<TaskHandle> snapshot = new ArrayList<>(handles.values());
		snapshot.forEach(handle -> handle.future.cancel(true));
		handles.clear();

		if (executor != null) {
			executor.shutdown();
			try {
				if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
					executor.shutdownNow();
				}
			} catch (InterruptedException e) {
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			} finally {
				executor = null;
			}
		}
	}

	private Future<?> schedule(TaskSpec spec) {
		Runnable wrapped = wrap(spec);
		if (spec.type == TaskType.IMMEDIATE) {
			return executor.submit(wrapped);
		} else {
			return executor.scheduleWithFixedDelay(wrapped,
					spec.initialDelay,
					spec.delay,
					spec.unit);
		}
	}

	private Runnable wrap(TaskSpec spec) {
		return () -> {
			try {
				spec.task.run();
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				if (keepRunning.getAsBoolean()) {
					log.warn("Task {} interrupted", spec.name, ie);
				}
			} catch (CancellationException ignored) {
			} catch (Exception ex) {
				log.error("Task {} failed", spec.name, ex);
				throw new RuntimeException(ex);
			} catch (Throwable t) {
				log.error("Task {} failed with error", spec.name, t);
				throw t;
			}
		};
	}

	private void monitorTasks() {
		if (!keepRunning.getAsBoolean()) {
			return;
		}
		for (TaskHandle handle : new ArrayList<>(handles.values())) {
			Future<?> future = handle.future;
			if (future == null) continue;
			if (future.isCancelled()) {
				continue;
			}
			if (future.isDone()) {
				try {
					future.get();
					log.warn("Task {} finished unexpectedly; restarting", handle.spec.name);
				} catch (CancellationException ignored) {
					continue;
				} catch (ExecutionException ee) {
					log.error("Task {} terminated with execution exception", handle.spec.name, ee.getCause());
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}

				Future<?> newFuture = schedule(handle.spec);
				handle.future = newFuture;
			}
		}
	}
}


