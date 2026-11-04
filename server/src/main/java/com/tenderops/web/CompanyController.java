package com.tenderops.web;

import com.tenderops.company.Certificate;
import com.tenderops.company.Company;
import com.tenderops.company.CompanyDocument;
import com.tenderops.company.CompanyService;
import com.tenderops.company.FinancialRecord;
import com.tenderops.company.Project;
import com.tenderops.security.CurrentUser;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Company database (section 9): profile, financials, projects, certificates and
 * supporting documents. Reads are company-scoped; writes need ADMIN/BID_MANAGER
 * (TC-SEC-02: VIEWER cannot edit). Money is transported as strings (R1).
 */
@RestController
@RequestMapping("/api/v1/company")
public class CompanyController {

  public record ProfileResponse(long id, String name, String gstNumber,
      boolean gstRegistered, boolean msmeRegistered, boolean blacklisted, boolean demo,
      long dataVersion) {
  }

  public record ProfileRequest(String name, String gstNumber, Boolean gstRegistered,
      Boolean msmeRegistered, Boolean blacklisted) {
  }

  public record FinancialResponse(long id, String fyLabel, LocalDate fyEndDate,
      String turnover, String netWorth, String currency, boolean audited, Long documentId) {
  }

  public record FinancialRequest(String fyLabel, LocalDate fyEndDate, String turnover,
      String netWorth, String currency, Boolean audited, Long documentId,
      Boolean clearDocument) {
  }

  public record ProjectResponse(long id, String name, String contractValue, String currency,
      String status, LocalDate completionDate, String domainCategory, Long workOrderDocId,
      Long completionCertDocId) {
  }

  public record ProjectRequest(String name, String contractValue, String currency,
      String status, LocalDate completionDate, String domainCategory, Long workOrderDocId,
      Long completionCertDocId) {
  }

  public record CertificateResponse(long id, String name, String levelText,
      Integer levelNumber, LocalDate issueDate, LocalDate expiryDate, String amount,
      String currency, Long documentId) {
  }

  public record CertificateRequest(String name, String levelText, Integer levelNumber,
      LocalDate issueDate, LocalDate expiryDate, String amount, String currency,
      Long documentId, Boolean clearDocument) {
  }

  public record DocumentResponse(long id, String fileName, String sha256, String mimeType,
      long sizeBytes) {
  }

  private final CompanyService service;

  public CompanyController(CompanyService service) {
    this.service = service;
  }

  private static CurrentUser current() {
    return CurrentUser.require();
  }

  private static void requireEditor(CurrentUser current) {
    if (!current.canApprove()) {
      throw new AccessDeniedException("Company edits need ADMIN or BID_MANAGER");
    }
  }

  @GetMapping
  public ProfileResponse profile() {
    return toProfile(service.getCompany(current().companyId()));
  }

  @PutMapping
  public ProfileResponse updateProfile(@RequestBody ProfileRequest body) {
    CurrentUser current = current();
    requireEditor(current);
    Company company = service.updateProfile(current.companyId(), current.userId(),
        body.name(), body.gstNumber(), body.gstRegistered(), body.msmeRegistered(),
        body.blacklisted());
    return toProfile(company);
  }

  @GetMapping("/financials")
  public List<FinancialResponse> financials() {
    return service.listFinancials(current().companyId()).stream().map(CompanyController::toFinancial)
        .toList();
  }

  @PostMapping("/financials")
  public ResponseEntity<FinancialResponse> createFinancial(
      @RequestBody FinancialRequest body) {
    CurrentUser current = current();
    requireEditor(current);
    FinancialRecord record = service.createFinancial(current.companyId(), current.userId(),
        body.fyLabel(), body.fyEndDate(), body.turnover(), body.netWorth(), body.currency(),
        body.audited() == null || body.audited(), body.documentId());
    return ResponseEntity.status(HttpStatus.CREATED).body(toFinancial(record));
  }

  @PutMapping("/financials/{id}")
  public FinancialResponse updateFinancial(@PathVariable long id,
      @RequestBody FinancialRequest body) {
    CurrentUser current = current();
    requireEditor(current);
    return toFinancial(service.updateFinancial(current.companyId(), current.userId(), id,
        body.fyLabel(), body.fyEndDate(), body.turnover(), body.netWorth(), body.currency(),
        body.audited(), body.documentId(), Boolean.TRUE.equals(body.clearDocument())));
  }

  @DeleteMapping("/financials/{id}")
  public ResponseEntity<Void> deleteFinancial(@PathVariable long id) {
    CurrentUser current = current();
    requireEditor(current);
    service.deleteFinancial(current.companyId(), current.userId(), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/projects")
  public List<ProjectResponse> projects() {
    return service.listProjects(current().companyId()).stream().map(CompanyController::toProject)
        .toList();
  }

  @PostMapping("/projects")
  public ResponseEntity<ProjectResponse> createProject(@RequestBody ProjectRequest body) {
    CurrentUser current = current();
    requireEditor(current);
    Project project = service.createProject(current.companyId(), current.userId(), body.name(),
        body.contractValue(), body.currency(), body.status(), body.completionDate(),
        body.domainCategory(), body.workOrderDocId(), body.completionCertDocId());
    return ResponseEntity.status(HttpStatus.CREATED).body(toProject(project));
  }

  @PutMapping("/projects/{id}")
  public ProjectResponse updateProject(@PathVariable long id,
      @RequestBody ProjectRequest body) {
    CurrentUser current = current();
    requireEditor(current);
    return toProject(service.updateProject(current.companyId(), current.userId(), id,
        body.name(), body.contractValue(), body.currency(), body.status(),
        body.completionDate(), body.domainCategory(), body.workOrderDocId(),
        body.completionCertDocId()));
  }

  @DeleteMapping("/projects/{id}")
  public ResponseEntity<Void> deleteProject(@PathVariable long id) {
    CurrentUser current = current();
    requireEditor(current);
    service.deleteProject(current.companyId(), current.userId(), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/certificates")
  public List<CertificateResponse> certificates() {
    return service.listCertificates(current().companyId()).stream()
        .map(CompanyController::toCertificate).toList();
  }

  @PostMapping("/certificates")
  public ResponseEntity<CertificateResponse> createCertificate(
      @RequestBody CertificateRequest body) {
    CurrentUser current = current();
    requireEditor(current);
    Certificate certificate = service.createCertificate(current.companyId(), current.userId(),
        body.name(), body.levelText(), body.levelNumber(), body.issueDate(), body.expiryDate(),
        body.amount(), body.currency(), body.documentId());
    return ResponseEntity.status(HttpStatus.CREATED).body(toCertificate(certificate));
  }

  @PutMapping("/certificates/{id}")
  public CertificateResponse updateCertificate(@PathVariable long id,
      @RequestBody CertificateRequest body) {
    CurrentUser current = current();
    requireEditor(current);
    return toCertificate(service.updateCertificate(current.companyId(), current.userId(), id,
        body.name(), body.levelText(), body.levelNumber(), body.issueDate(), body.expiryDate(),
        body.amount(), body.currency(), body.documentId(),
        Boolean.TRUE.equals(body.clearDocument())));
  }

  @DeleteMapping("/certificates/{id}")
  public ResponseEntity<Void> deleteCertificate(@PathVariable long id) {
    CurrentUser current = current();
    requireEditor(current);
    service.deleteCertificate(current.companyId(), current.userId(), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/documents")
  public List<DocumentResponse> documents() {
    return service.listDocuments(current().companyId()).stream()
        .map(CompanyController::toDocument).toList();
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DocumentResponse> uploadDocument(
      @RequestParam("file") MultipartFile file) {
    CurrentUser current = current();
    requireEditor(current);
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new BadRequestException("Cannot read upload");
    }
    CompanyDocument document = service.uploadDocument(current.companyId(), current.userId(),
        file.getOriginalFilename(), file.getContentType(), bytes);
    return ResponseEntity.status(HttpStatus.CREATED).body(toDocument(document));
  }

  private static ProfileResponse toProfile(Company company) {
    return new ProfileResponse(company.getId(), company.getName(), company.getGstNumber(),
        company.isGstRegistered(), company.isMsmeRegistered(), company.isBlacklisted(),
        company.isDemo(), company.getDataVersion());
  }

  private static FinancialResponse toFinancial(FinancialRecord record) {
    return new FinancialResponse(record.getId(), record.getFyLabel(), record.getFyEndDate(),
        CompanyService.moneyString(record.getTurnover()),
        CompanyService.moneyString(record.getNetWorth()), record.getCurrency(),
        record.isAudited(), record.getDocumentId());
  }

  private static ProjectResponse toProject(Project project) {
    return new ProjectResponse(project.getId(), project.getName(),
        CompanyService.moneyString(project.getContractValue()), project.getCurrency(),
        project.getStatus(), project.getCompletionDate(), project.getDomainCategory(),
        project.getWorkOrderDocId(), project.getCompletionCertDocId());
  }

  private static CertificateResponse toCertificate(Certificate certificate) {
    return new CertificateResponse(certificate.getId(), certificate.getName(),
        certificate.getLevelText(), certificate.getLevelNumber(), certificate.getIssueDate(),
        certificate.getExpiryDate(), CompanyService.moneyString(certificate.getAmount()),
        certificate.getCurrency(), certificate.getDocumentId());
  }

  private static DocumentResponse toDocument(CompanyDocument document) {
    return new DocumentResponse(document.getId(), document.getFileName(), document.getSha256(),
        document.getMimeType(), document.getSizeBytes());
  }
}
