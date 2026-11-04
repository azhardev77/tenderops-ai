package com.tenderops.web;

import com.tenderops.report.PdfReportService;
import com.tenderops.report.ReportDataService;
import com.tenderops.report.XlsxReportService;
import com.tenderops.security.CurrentUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Report downloads (section 9): bid-package PDF and checklist XLSX. Company-scoped. */
@RestController
@RequestMapping("/api/v1")
public class ReportController {

  private static final MediaType XLSX = MediaType.parseMediaType(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final ReportDataService assembler;
  private final PdfReportService pdf;
  private final XlsxReportService xlsx;

  public ReportController(ReportDataService assembler, PdfReportService pdf,
      XlsxReportService xlsx) {
    this.assembler = assembler;
    this.pdf = pdf;
    this.xlsx = xlsx;
  }

  @GetMapping("/tender-versions/{id}/reports/bid-package.pdf")
  public ResponseEntity<byte[]> bidPackage(@PathVariable long id,
      @RequestParam(required = false) Long runId) {
    CurrentUser current = CurrentUser.require();
    byte[] bytes = pdf.bidPackage(assembler.assemble(id, runId, current.companyId()));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDisposition(
        ContentDisposition.attachment().filename("bid-package.pdf").build());
    return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
  }

  @GetMapping("/tender-versions/{id}/reports/checklist.xlsx")
  public ResponseEntity<byte[]> checklist(@PathVariable long id,
      @RequestParam(required = false) Long runId) {
    CurrentUser current = CurrentUser.require();
    byte[] bytes = xlsx.checklistWorkbook(assembler.assemble(id, runId, current.companyId()));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(XLSX);
    headers.setContentDisposition(
        ContentDisposition.attachment().filename("checklist.xlsx").build());
    return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
  }
}
