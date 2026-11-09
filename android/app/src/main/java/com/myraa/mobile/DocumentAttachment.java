package com.myraa.mobile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one read document for the next request, exactly like {@link ImageAttachment} holds a
 * photo. The text is kept out of the prompt until the user actually asks something, and is
 * cleared as soon as one task has used it.
 */
final class DocumentAttachment {
  private static volatile DocumentReader.Result staged;
  private static final Map<String, DocumentReader.Result> BY_TASK = new ConcurrentHashMap<>();

  static void stage(DocumentReader.Result result) { staged = result; }
  static boolean hasStaged() { return staged != null; }
  static String name() { DocumentReader.Result value = staged; return value == null ? "" : value.name; }
  static void clear() { staged = null; }

  /** Moves the staged document onto a chat task. */
  static boolean claim(String taskId) {
    DocumentReader.Result value = staged;
    staged = null;
    if (value == null || taskId == null) return false;
    BY_TASK.put(taskId, value);
    return true;
  }

  static DocumentReader.Result take(String taskId) {
    return taskId == null ? null : BY_TASK.remove(taskId);
  }

  private DocumentAttachment() { }
}
