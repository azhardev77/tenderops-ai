package com.tenderops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Test-only PDF byte isolation. Ingest dedups by (company, SHA-256) and the H2
 * database is shared across test classes, so tests stamp a unique trailer id into
 * their fixtures: identical text, distinct SHA, zero cross-test version collisions.
 */
public final class IsolatedPdfs {

  private IsolatedPdfs() {
  }

  public static byte[] tag(byte[] pdf, String tag) {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      COSArray ids = new COSArray();
      ids.add(new COSString("tenderops-" + tag + "-a"));
      ids.add(new COSString("tenderops-" + tag + "-b"));
      doc.getDocument().getTrailer().setItem(COSName.ID, ids);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot tag test PDF", e);
    }
  }
}
