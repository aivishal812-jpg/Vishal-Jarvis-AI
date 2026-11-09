package com.myraa.mobile;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.zip.Deflater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * PDF text extraction is the part of document reading that can be tested without Android.
 * A stream written the way a real generator writes it must come back as readable text.
 */
public class DocumentReaderTest {

  @Test public void flateCompressedTextIsExtracted() throws Exception {
    byte[] pdf = pdf("Hello from the Vishal Jarvis test document.");
    DocumentReader.Pdf reader = new DocumentReader.Pdf(pdf);
    String text = reader.text();
    assertTrue("expected the sentence, got: " + text, text.contains("Hello from the Vishal Jarvis test document"));
  }

  @Test public void uncompressedTextIsAlsoExtracted() {
    String body = "BT /F1 12 Tf 72 712 Td (Plain uncompressed line) Tj ET";
    byte[] pdf = build(("4 0 obj\n<< /Length " + body.length() + " >>\nstream\n" + body + "\nendstream\nendobj\n").getBytes(Charset.forName("ISO-8859-1")));
    String text = new DocumentReader.Pdf(pdf).text();
    assertTrue("expected plain text, got: " + text, text.contains("Plain uncompressed line"));
  }

  @Test public void pageCountReadsPageObjects() {
    byte[] pdf = build("%PDF-1.4\n1 0 obj << /Type /Page /Contents 2 0 R >> endobj\n2 0 obj << /Type /Page /Contents 3 0 R >> endobj\n3 0 obj << /Type /Pages /Kids [] >> endobj\n%%EOF");
    assertEquals(2, new DocumentReader.Pdf(pdf).pages());
  }

  @Test public void textIsCappedAndTidied() {
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 5000; i++) many.append("line ").append(i).append(" ) ");
    String text = new DocumentReader.Pdf(pdf(many.toString())).text();
    assertTrue(text.length() <= DocumentReader.MAX_CHARS);
  }

  // ---------------------------------------------------------------- helpers

  private static byte[] pdf(String text) {
    byte[] compressed = deflate(("BT /F1 12 Tf 72 712 Td (" + escape(text) + ") Tj ET").getBytes(Charset.forName("ISO-8859-1")));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String header = "%PDF-1.4\n1 0 obj << /Type /Page /Contents 2 0 R >> endobj\n2 0 obj << /Filter /FlateDecode /Length "
      + compressed.length + " >>\nstream\n";
    out.write(header.getBytes(Charset.forName("ISO-8859-1")), 0, header.length());
    out.write(compressed, 0, compressed.length);
    String footer = "\nendstream\nendobj\n%%EOF";
    out.write(footer.getBytes(Charset.forName("ISO-8859-1")), 0, footer.length());
    return out.toByteArray();
  }

  private static byte[] build(String ascii) { return ascii.getBytes(Charset.forName("ISO-8859-1")); }

  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
  }

  private static byte[] deflate(byte[] input) {
    Deflater deflater = new Deflater();
    deflater.setInput(input);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    while (!deflater.finished()) {
      int n = deflater.deflate(buffer);
      out.write(buffer, 0, n);
    }
    deflater.end();
    return out.toByteArray();
  }
}
