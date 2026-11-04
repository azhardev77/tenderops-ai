package com.tenderops.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * Checklist workbook (TC-RPT-01): a Matrix sheet with every evaluated requirement
 * and a Checklist sheet with every missing document. Money travels as strings (R1);
 * no formulas, no money arithmetic.
 */
@Service
public class XlsxReportService {

  public byte[] checklistWorkbook(ReportDataService.ReportData data) {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet matrix = workbook.createSheet("Matrix");
      header(matrix, "Clause", "Mandatory", "Metric", "Verdict", "Reason", "Threshold",
          "Source page");
      int rowNo = 1;
      for (ReportDataService.MatrixLine line : data.matrix()) {
        Row row = matrix.createRow(rowNo++);
        cell(row, 0, line.requirement().getClauseRef());
        cell(row, 1, line.requirement().isMandatory() ? "yes" : "no");
        cell(row, 2, line.requirement().getMetric());
        cell(row, 3, line.result().getVerdict());
        cell(row, 4, line.result().getReasonCode());
        cell(row, 5, thresholdOf(line));
        row.createCell(6).setCellValue(line.requirement().getSourcePage());
      }
      Sheet checklist = workbook.createSheet("Checklist");
      header(checklist, "Clause", "Requirement", "Missing item", "Corrective action");
      int checkNo = 1;
      for (var item : data.checklist()) {
        Row row = checklist.createRow(checkNo++);
        cell(row, 0, item.clauseRef());
        cell(row, 1, item.requirement());
        cell(row, 2, item.missingItem());
        cell(row, 3, item.correctiveAction());
      }
      Sheet meta = workbook.createSheet("Run");
      header(meta, "Field", "Value");
      metaRow(meta, 1, "Tender reference", data.tender().getReference());
      metaRow(meta, 2, "Company", data.company().getName());
      metaRow(meta, 3, "Recommendation", data.decision().getRecommendation());
      metaRow(meta, 4, "Approval", data.decision().getApprovalStatus());
      metaRow(meta, 5, "Readiness score (rule-based)",
          data.run().getReadinessScore() == null ? null
              : data.run().getReadinessScore().toPlainString());
      metaRow(meta, 6, "Muse mode", data.run().getMuseMode());
      metaRow(meta, 7, "SAM mode", data.run().getSamMode());
      for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
        Sheet sheet = workbook.getSheetAt(s);
        for (int c = 0; c < 8; c++) {
          sheet.autoSizeColumn(c);
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot render checklist workbook", e);
    }
  }

  private static void header(Sheet sheet, String... titles) {
    Row row = sheet.createRow(0);
    for (int i = 0; i < titles.length; i++) {
      cell(row, i, titles[i]);
    }
  }

  private static void metaRow(Sheet sheet, int rowNo, String field, String value) {
    Row row = sheet.createRow(rowNo);
    cell(row, 0, field);
    cell(row, 1, value);
  }

  private static void cell(Row row, int index, String value) {
    Cell cell = row.createCell(index);
    cell.setCellValue(value == null ? "" : value);
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
    return "";
  }
}
