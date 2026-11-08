package com.myraa.mobile;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import java.util.List;

/** Coordinates the visible streaming request with persisted jobs without duplicate execution. */
final class AiTaskCoordinator {
  private static String foregroundId, jobId;
  static boolean beginForeground(AiTaskStore store, String id) {
    synchronized (AiTaskStore.LOCK) {
      AiTaskStore.Task first = store.firstPending();
      if (foregroundId != null || jobId != null || first == null || !id.equals(first.id)) return false;
      if (!store.running(id)) return false;
      foregroundId = id; return true;
    }
  }
  static void endForeground(String id) {
    synchronized (AiTaskStore.LOCK) { if (id != null && id.equals(foregroundId)) foregroundId = null; }
  }
  static boolean beginJob(AiTaskStore store, String id) {
    synchronized (AiTaskStore.LOCK) {
      AiTaskStore.Task first = store.firstPending();
      if (foregroundId != null || jobId != null || first == null || !id.equals(first.id)) return false;
      if (!store.running(id)) return false;
      jobId = id; return true;
    }
  }
  static void endJob(String id) { synchronized (AiTaskStore.LOCK) { if (id != null && id.equals(jobId)) jobId = null; } }
  static void schedule(Context context, AiTaskStore.Task task) {
    if (task == null || !("queued".equals(task.status) || "running".equals(task.status))) return;
    JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (scheduler == null) throw new IllegalStateException("Android job scheduler unavailable");
    // Avoid resetting the platform's exponential retry backoff on every Activity resume.
    if (scheduler.getPendingJob(task.jobId()) != null) return;
    PersistableBundle extras = new PersistableBundle(); extras.putString("taskId", task.id);
    JobInfo info = new JobInfo.Builder(task.jobId(), new ComponentName(context, AiTaskJobService.class))
      .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
      .setPersisted(true).setMinimumLatency(10000)
      .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
    if (scheduler.schedule(info) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Android could not schedule the task");
  }
  static void recover(Context context) {
    for (AiTaskStore.Task task : AiTaskStore.get(context).pending()) {
      try { schedule(context, task); } catch (RuntimeException ignored) { /* retried next time the app is opened */ }
    }
  }
  static void cancelJobOnly(Context context, AiTaskStore.Task task) {
    if (task == null) return;
    JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (scheduler != null) scheduler.cancel(task.jobId());
  }
  static void cancel(Context context, AiTaskStore.Task task) {
    if (task == null) return;
    AiTaskStore.get(context).cancel(task.id);
    JobScheduler scheduler = (JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (scheduler != null) scheduler.cancel(task.jobId());
    AiTaskJobService.stopTask(task.id);
  }
  static void cancelAll(Context context) {
    List<AiTaskStore.Task> pending = AiTaskStore.get(context).pending();
    for (AiTaskStore.Task task : pending) cancel(context, task);
  }
  private AiTaskCoordinator() {}
}
