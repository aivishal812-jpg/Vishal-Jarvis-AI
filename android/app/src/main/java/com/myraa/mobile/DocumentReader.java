package com.myraa.mobile;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Inflater;

/**
 * Reads a PDF or a plain-text document the user picked, entirely on the phone.
 *
 * No third-party library is bundled: a PDF's page text is stored in Flate-compressed content
 * streams, so this inflates them and pulls the text-showing operators out (Tj, TJ, ' and ").
 * That covers everyday text PDFs (statements, tickets, notes, forms, most e-books). Scanned
 * PDFs have no text layer at all and are reported honestly instead of returning gibberish.
 */
final class DocumentReader {

  static final int MAX_CHARS = 24000;
  private static final int MAX_BYTES = 24 * 1024 * 1024;

  static final class Result {
    final String name;
    final String text;
    final int pages;
    Result(String name, String text, int pages) { this.name = name; this.text = text; this.pages = pages; }
  }

  /** Thrown for a file this reader genuinely cannot handle, with a message for the user. */
  static final class Unsupported extends Exception {
    Unsupported(String message) { super(message); }
  }

  static Result read(Context context, Uri uri) throws Exception {
    String name = displayName(context, uri);
    byte[] bytes = readBytes(context, uri);
    if (bytes.length == 0) throw new Unsupported("That file is empty.");
    String lower = name.toLowerCase(Locale.ROOT);
    boolean pdf = lower.endsWith(".pdf") || (bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F');
    if (pdf) {
      Pdf pdfReader = new Pdf(bytes);
      String text = pdfReader.text();
      if (text.trim().length() < 20) throw new Unsupported("That PDF has no text layer, so it is probably a scan or photos. Open the page and use the screen reader or a photo of it instead.");
      return new Result(name, text, pdfReader.pages());
    }
    if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
      throw new Unsupported("Word files cannot be read on this phone. Save it as a PDF or copy the text into the chat.");
    }
    if (looksBinary(bytes)) {
      throw new Unsupported("That looks like a picture, an archive or another binary file. Use a photo for pictures, or a PDF or text file for documents.");
    }
    return new Result(name, limit(new String(bytes, Charset.forName("UTF-8")).replace("\r\n", "\n").trim()), 0);
  }

  // ---------------------------------------------------------------- helpers

  private static String limit(String text) {
    return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "\n\n… (the rest of the document is not included; ask about a specific part)";
  }

  private static boolean looksBinary(byte[] bytes) {
    int sample = Math.min(bytes.length, 2048), control = 0;
    for (int i = 0; i < sample; i++) {
      int b = bytes[i] & 0xff;
      if (b == 0) return true;
      if (b < 9 || (b > 13 && b < 32)) control++;
    }
    return control > sample / 20;
  }

  private static byte[] readBytes(Context context, Uri uri) throws Exception {
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      if (in == null) throw new Unsupported("Android did not allow reading that file.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[16384];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (out.size() + n > MAX_BYTES) throw new Unsupported("That document is larger than 24 MB. Open the part you need instead.");
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }

  private static String displayName(Context context, Uri uri) {
    try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (index >= 0) { String name = cursor.getString(index); if (name != null && !name.isEmpty()) return name; }
      }
    } catch (ignored RuntimeException) { /* tolerated: a stream that cannot be inflated is not a text stream */ }
    String path = uri.getLastPathSegment();
    return path == null ? "document" : path;
  }

  /** A very small PDF text extractor: object scan, Flate inflate, text operators. */
  public static final class Pdf {
    private final byte[] data;
    public Pdf(byte[] data) { this.data = data; }

    public int pages() {
      int count = 0, index = 0;
      while (true) {
        index = indexOf(data, "/Type".getBytes(Charset.forName("ISO-8859-1")), index);
        if (index < 0) break;
        int end = Math.min(data.length, index + 40);
        String window = new String(data, index, end - index, Charset.forName("ISO-8859-1"));
        if (window.contains("/Page") && !window.contains("/Pages")) count++;
        index += 5;
      }
      return count;
    }

    public String text() {
      StringBuilder out = new StringBuilder();
      Charset latin = Charset.forName("ISO-8859-1");
      int index = 0;
      while (out.length() < MAX_CHARS) {
        int stream = indexOf(data, "stream".getBytes(latin), index);
        if (stream < 0) break;
        int start = stream + 6;
        if (start < data.length && data[start] == '\r') start++;
        if (start < data.length && data[start] == '\n') start++;
        int end = indexOf(data, "endstream".getBytes(latin), start);
        if (end < 0) end = data.length;
        byte[] chunk = new byte[Math.max(0, end - start)];
        System.arraycopy(data, start, chunk, 0, chunk.length);
        String decoded = decode(chunk);
        if (decoded != null) out.append(extractText(decoded)).append('\n');
        index = Math.max(start, end) + 9;
        if (out.length() > MAX_CHARS) break;
      }
      return tidy(out.toString());
    }

    /** Inflates a stream; returns its Latin-1 text, or null when it is not Flate data. */
    private static String decode(byte[] chunk) {
      Inflater inflater = new Inflater();
      try {
        inflater.setInput(chunk);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(chunk.length * 4, 1 << 20));
        byte[] buffer = new byte[16384];
        while (!inflater.finished()) {
          int n = inflater.inflate(buffer);
          if (n == 0) { if (inflater.needsInput() || inflater.needsDictionary()) break; }
          out.write(buffer, 0, n);
          if (out.size() > 4 << 20) break;
        }
        if (out.size() > 0) return new String(out.toByteArray(), Charset.forName("ISO-8859-1"));
      } catch (Exception ignored) {
        // Not Flate: fall through to the uncompressed case below.
      } finally {
        inflater.end();
      }
      String raw = new String(chunk, Charset.forName("ISO-8859-1"));
      return raw.contains("BT") && raw.contains("Tj") ? raw : null;
    }

    /** Pulls the strings out of Tj/TJ/'/" operators, adding spaces and newlines for layout. */
    private static String extractText(String content) {
      StringBuilder out = new StringBuilder();
      int i = 0;
      while (i < content.length()) {
        char c = content.charAt(i);
        if (c == '(') {
          StringBuilder piece = new StringBuilder();
          i++;
          int depth = 1;
          while (i < content.length() && depth > 0) {
            char x = content.charAt(i);
            if (x == '\\') {
              i++;
              if (i < content.length()) piece.append(unescape(content.charAt(i)));
            } else if (x == '(') { depth++; piece.append('('); }
            else if (x == ')') { depth--; if (depth > 0) piece.append(')'); }
            else piece.append(x);
            i++;
          }
          out.append(piece);
          continue;
        }
        // A text-positioning operator means a new visual line.
        if (c == 'T' || c == '\'' || c == '"') {
          if (c != '\'' && c != '"' && i + 1 < content.length() && (content.charAt(i + 1) == 'd' || content.charAt(i + 1) == 'D')) out.append('\n');
          else if (c == '\'' || c == '"') out.append('\n');
          else if (i + 1 < content.length() && content.charAt(i + 1) == '*') out.append('\n');
        }
        i++;
      }
      return out.toString();
    }

    private static char unescape(char escaped) {
      switch (escaped) {
        case 'n': return '\n';
        case 'r': return '\r';
        case 't': return '\t';
        case 'b': return '\b';
        case 'f': return '\f';
        default: return escaped;
      }
    }

    private static String tidy(String raw) {
      List<String> lines = new ArrayList<>();
      for (String line : raw.split("\n")) {
        String clean = line.replaceAll("[ \\t\\u0000]+", " ").trim();
        if (!clean.isEmpty()) lines.add(clean);
      }
      String joined = String.join("\n", lines).replaceAll("\n{3,}", "\n\n").trim();
      return joined.length() > MAX_CHARS ? joined.substring(0, MAX_CHARS) : joined;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
      outer:
      for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
        for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
        return i;
      }
      return -1;
    }
  }

  private DocumentReader() { }
}
