package com.tenderops.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

/**
 * Bid-package PDF (TC-RPT-01): PDFBox directly (never OpenHTMLToPDF), Helvetica with
 * "Rs." amounts (R14: no embedded rupee font available). Cover page plus content
 * pages; every clause ref, verdict and risk appears as extractable text.
 */
@Service
public class PdfReportService {

  private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
  private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
  private static final float MARGIN = 54f;
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z").withZone(ZoneId.of("Asia/Kolkata"));

  public byte[] bidPackage(ReportDataService.ReportData data) {
    try (PDDocument doc = new PDDocument()) {
      cover(doc, data);
      content(doc, data);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot render bid package PDF", e);
    }
  }

  private void cover(PDDocument doc, ReportDataService.ReportData data) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    float width = page.getMediaBox().getWidth();
    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
      float y = page.getMediaBox().getHeight() - 120f;
      y = centered(cs, "TenderOps AI - Bid Package", width, y, 22, true);
      y = centered(cs, nullToDash(data.tender().getReference()), width, y - 10, 14, false);
      y -= 24f;
      y = line(cs, "Company: " + data.company().getName(), y, 11, false);
      y = line(cs, "Tender: " + data.tender().getTitle(), y, 11, false);
      y = line(cs, "Version: " + data.version().getVersionNumber() + " ("
          + data.version().getKind() + ")", y, 11, false);
      y = line(cs, "Submission deadline: " + nullToDash(dateOf(data)), y, 11, false);
      y = line(cs, "Recommendation: " + data.decision().getRecommendation(), y, 13, true);
      y = line(cs, "Readiness score (rule-based): "
          + plain(data.run().getReadinessScore()), y, 13, true);
      y = line(cs, "Approval: " + data.decision().getApprovalStatus(), y, 11, false);
      y = line(cs, "Extraction: MUSE/" + data.run().getMuseMode() + "  Visual: SAM/"
          + data.run().getSamMode(), y, 11, false);
      y = line(cs, "Generated: " + WHEN.format(java.time.Instant.now()), y, 11, false);
      y -= 12f;
      y = line(cs, "Verdicts are deterministic rule evaluations, not predictions.", y, 9,
          false);
      line(cs, "AI output never authorizes submission; human approval is required.", y, 9,
          false);
    }
  }

  private void content(PDDocument doc, ReportDataService.ReportData data) throws IOException {
    List<Block> blocks = new ArrayList<>();
    blocks.add(new Block("Compliance matrix", 14, true));
    for (ReportDataService.MatrixLine line : data.matrix()) {
      blocks.add(new Block("Clause " + line.requirement().getClauseRef() + " ["
          + line.requirement().getMetric() + "] " + line.result().getVerdict() + " - "
          + nullToDash(line.result().getReasonCode()), 10, true));
      blocks.add(new Block("Threshold: " + thresholdOf(line) + " | Evidence records: "
          + evidenceOf(line), 9, false));
      blocks.add(new Block("Quote (p" + line.requirement().getSourcePage() + "): "
          + oneLine(line.requirement().getOriginalQuote()), 9, false));
    }
    blocks.add(new Block("Risk register (" + data.risks().size() + ")", 14, true));
    if (data.risks().isEmpty()) {
      blocks.add(new Block("No risks recorded.", 10, false));
    }
    for (var risk : data.risks()) {
      blocks.add(new Block(risk.getSeverity() + " " + risk.getRiskCode() + " clause "
          + nullToDash(risk.getSourceClause()), 10, true));
      blocks.add(new Block("Impact: " + nullToDash(risk.getBusinessImpact()), 9, false));
      blocks.add(new Block("Action: " + nullToDash(risk.getCorrectiveAction()), 9, false));
    }
    blocks.add(new Block("Missing-document checklist (" + data.checklist().size() + ")",
        14, true));
    if (data.checklist().isEmpty()) {
      blocks.add(new Block("No missing documents.", 10, false));
    }
    for (var item : data.checklist()) {
      blocks.add(new Block("Clause " + item.clauseRef() + ": " + item.missingItem(), 10,
          false));
    }
    blocks.add(new Block("Executive summary", 14, true));
    blocks.add(new Block(nullToDash(data.run().getSummaryText()), 10, false));

    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    float usableWidth = page.getMediaBox().getWidth() - 2 * MARGIN;
    PDPageContentStream cs = new PDPageContentStream(doc, page);
    float y = page.getMediaBox().getHeight() - MARGIN;
    try {
      for (Block block : blocks) {
        List<String> lines = wrap(block.text(), block.size(), block.bold(), usableWidth);
        if (block.headingGap()) {
          y -= 10f;
        }
        for (String text : lines) {
          if (y < MARGIN + 20f) {
            cs.close();
            page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = page.getMediaBox().getHeight() - MARGIN;
          }
          y = line(cs, text, y, block.size(), block.bold());
        }
        y -= 4f;
      }
    } finally {
      cs.close();
    }
  }

  private List<String> wrap(String text, int size, boolean bold, float maxWidth)
      throws IOException {
    PDFont font = bold ? BOLD : REGULAR;
    List<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String word : text.split("\\s+")) {
      String candidate = current.isEmpty() ? word : current + " " + word;
      float width = font.getStringWidth(candidate) / 1000f * size;
      if (width > maxWidth && !current.isEmpty()) {
        lines.add(current.toString());
        current = new StringBuilder(word);
      } else {
        current = new StringBuilder(candidate);
      }
    }
    if (!current.isEmpty()) {
      lines.add(current.toString());
    }
    if (lines.isEmpty()) {
      lines.add("");
    }
    return lines;
  }

  private float centered(PDPageContentStream cs, String text, float pageWidth, float y,
      int size, boolean bold) throws IOException {
    PDFont font = bold ? BOLD : REGULAR;
    float width = font.getStringWidth(text) / 1000f * size;
    cs.beginText();
    cs.setFont(font, size);
    cs.newLineAtOffset((pageWidth - width) / 2f, y);
    cs.showText(safe(text));
    cs.endText();
    return y - size - 8f;
  }

  private float line(PDPageContentStream cs, String text, float y, int size, boolean bold)
      throws IOException {
    cs.beginText();
    cs.setFont(bold ? BOLD : REGULAR, size);
    cs.newLineAtOffset(MARGIN, y);
    cs.showText(safe(text));
    cs.endText();
    return y - size - 5f;
  }

  /** Type1 Helvetica cannot draw the rupee sign: normalize to "Rs." (R14). */
  private static String safe(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("\u20B9", "Rs.").replaceAll("[\\p{Cntrl}&&[^\t]]", " ");
  }

  private static String oneLine(String text) {
    if (text == null) {
      return "-";
    }
    return text.replaceAll("\\s+", " ").trim();
  }

  private static String nullToDash(String text) {
    return text == null || text.isBlank() ? "-" : text;
  }

  private static String plain(BigDecimal value) {
    return value == null ? "-" : value.toPlainString();
  }

  private static String dateOf(ReportDataService.ReportData data) {
    return data.version().getSubmissionDeadline() == null ? null
        : data.version().getSubmissionDeadline().toString();
  }

  private static String thresholdOf(ReportDataService.MatrixLine line) {
    if (line.requirement().getAmountText() != null
        && !line.requirement().getAmountText().isBlank()) {
      return line.requirement().getAmountText().trim();
    }
    if (line.requirement().getAmountValue() != null) {
      return "Rs. " + line.requirement().getAmountValue().toPlainString();
    }
    if (line.requirement().getConditionTree() != null) {
      return "condition tree";
    }
    return "-";
  }

  private static String evidenceOf(ReportDataService.MatrixLine line) {
    String trace = line.result().getCalculationTrace();
    if (trace == null || !trace.contains("evidenceIds")) {
      return "[]";
    }
    int start = trace.indexOf('[', trace.indexOf("evidenceIds"));
    int end = trace.indexOf(']', start);
    if (start < 0 || end < 0) {
      return "[]";
    }
    String ids = trace.substring(start + 1, end).trim();
    return "[" + ids + "]";
  }

  private record Block(String text, int size, boolean bold) {
    boolean headingGap() {
      return size >= 14;
    }
  }
}
