package com.tenderops.company;

import com.tenderops.audit.AuditService;
import com.tenderops.common.FileSafetyValidator;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.document.StorageService;
import com.tenderops.web.BadRequestException;
import com.tenderops.web.ConflictException;
import com.tenderops.web.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company database writes (section 9): every mutation bumps the per-company data
 * version (section 6.11) and is audit-logged with before/after (R11). Money arrives
 * as strings and is stored at scale 4 HALF_UP (R1).
 */
@Service
public class CompanyService {

  private final CompanyRepository companies;
  private final FinancialRecordRepository financials;
  private final ProjectRepository projects;
  private final CertificateRepository certificates;
  private final CompanyDocumentRepository documents;
  private final StorageService storage;
  private final AuditService audit;
  private final TenderopsProperties properties;

  public CompanyService(CompanyRepository companies,
      FinancialRecordRepository financials, ProjectRepository projects,
      CertificateRepository certificates, CompanyDocumentRepository documents,
      StorageService storage, AuditService audit, TenderopsProperties properties) {
    this.companies = companies;
    this.financials = financials;
    this.projects = projects;
    this.certificates = certificates;
    this.documents = documents;
    this.storage = storage;
    this.audit = audit;
    this.properties = properties;
  }

  /** Parses API money strings; null/blank stays null, negatives are rejected. */
  public static BigDecimal money(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      throw new BadRequestException("Invalid money value: " + value);
    }
    if (parsed.compareTo(BigDecimal.ZERO) < 0) {
      throw new BadRequestException("Money values cannot be negative");
    }
    return parsed.setScale(4, RoundingMode.HALF_UP);
  }

  public static String moneyString(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  @Transactional(readOnly = true)
  public Company getCompany(long companyId) {
    return companies.findById(companyId)
        .orElseThrow(() -> new NotFoundException("Company not found"));
  }

  @Transactional
  public Company updateProfile(long companyId, long userId, String name, String gstNumber,
      Boolean gstRegistered, Boolean msmeRegistered, Boolean blacklisted) {
    Company company = getCompany(companyId);
    Map<String, Object> before = profileMap(company);
    if (name != null) {
      if (name.isBlank()) {
        throw new BadRequestException("Company name cannot be blank");
      }
      company.setName(name.trim());
    }
    if (gstNumber != null) {
      company.setGstNumber(gstNumber.isBlank() ? null : gstNumber.trim());
    }
    if (gstRegistered != null) {
      company.setGstRegistered(gstRegistered);
    }
    if (msmeRegistered != null) {
      company.setMsmeRegistered(msmeRegistered);
    }
    if (blacklisted != null) {
      company.setBlacklisted(blacklisted);
    }
    company.bumpDataVersion();
    companies.save(company);
    audit.record(companyId, userId, "UPDATE", "company", companyId, before,
        profileMap(company));
    return company;
  }

  @Transactional(readOnly = true)
  public List<FinancialRecord> listFinancials(long companyId) {
    return financials.findByCompanyIdOrderByFyEndDateDesc(companyId);
  }

  @Transactional
  public FinancialRecord createFinancial(long companyId, long userId, String fyLabel,
      LocalDate fyEndDate, String turnover, String netWorth, String currency,
      boolean audited, Long documentId) {
    requireLabel(fyLabel, "fyLabel");
    if (fyEndDate == null) {
      throw new BadRequestException("fyEndDate is required");
    }
    if (financials.findByCompanyIdAndFyLabel(companyId, fyLabel.trim()).isPresent()) {
      throw new ConflictException("Financial record for " + fyLabel.trim()
          + " already exists");
    }
    checkDocument(companyId, documentId);
    FinancialRecord record = new FinancialRecord(companyId, fyLabel.trim(), fyEndDate);
    record.setTurnover(money(turnover));
    record.setNetWorth(money(netWorth));
    record.setCurrency(currencyCode(currency));
    record.setAudited(audited);
    record.setDocumentId(documentId);
    record = financials.save(record);
    touch(companyId, userId, "CREATE", "financial_record", record.getId(), null,
        financialMap(record));
    return record;
  }

  @Transactional
  public FinancialRecord updateFinancial(long companyId, long userId, long id, String fyLabel,
      LocalDate fyEndDate, String turnover, String netWorth, String currency,
      Boolean audited, Long documentId, boolean clearDocument) {
    FinancialRecord record = financialOf(companyId, id);
    Map<String, Object> before = financialMap(record);
    final Long recordId = record.getId();
    if (fyLabel != null) {
      requireLabel(fyLabel, "fyLabel");
      financials.findByCompanyIdAndFyLabel(companyId, fyLabel.trim())
          .ifPresent(other -> {
            if (!other.getId().equals(recordId)) {
              throw new ConflictException("Financial record for " + fyLabel.trim()
                  + " already exists");
            }
          });
      record.setFyLabel(fyLabel.trim());
    }
    if (fyEndDate != null) {
      record.setFyEndDate(fyEndDate);
    }
    if (turnover != null) {
      record.setTurnover(money(turnover));
    }
    if (netWorth != null) {
      record.setNetWorth(money(netWorth));
    }
    if (currency != null) {
      record.setCurrency(currencyCode(currency));
    }
    if (audited != null) {
      record.setAudited(audited);
    }
    if (clearDocument) {
      record.setDocumentId(null);
    } else if (documentId != null) {
      checkDocument(companyId, documentId);
      record.setDocumentId(documentId);
    }
    record = financials.save(record);
    touch(companyId, userId, "UPDATE", "financial_record", record.getId(), before,
        financialMap(record));
    return record;
  }

  @Transactional
  public void deleteFinancial(long companyId, long userId, long id) {
    FinancialRecord record = financialOf(companyId, id);
    Map<String, Object> before = financialMap(record);
    financials.delete(record);
    touch(companyId, userId, "DELETE", "financial_record", id, before, null);
  }

  @Transactional(readOnly = true)
  public List<Project> listProjects(long companyId) {
    return projects.findByCompanyIdOrderById(companyId);
  }

  @Transactional
  public Project createProject(long companyId, long userId, String name, String contractValue,
      String currency, String status, LocalDate completionDate, String domainCategory,
      Long workOrderDocId, Long completionCertDocId) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("Project name is required");
    }
    if (domainCategory == null || domainCategory.isBlank()) {
      throw new BadRequestException("domainCategory is required");
    }
    checkDocument(companyId, workOrderDocId);
    checkDocument(companyId, completionCertDocId);
    Project project = new Project(companyId, name.trim());
    project.setContractValue(money(contractValue));
    project.setCurrency(currencyCode(currency));
    project.setStatus(statusCode(status));
    project.setCompletionDate(completionDate);
    project.setDomainCategory(domainCategory.trim().toUpperCase());
    project.setWorkOrderDocId(workOrderDocId);
    project.setCompletionCertDocId(completionCertDocId);
    project = projects.save(project);
    touch(companyId, userId, "CREATE", "project", project.getId(), null,
        projectMap(project));
    return project;
  }

  @Transactional
  public Project updateProject(long companyId, long userId, long id, String name,
      String contractValue, String currency, String status, LocalDate completionDate,
      String domainCategory, Long workOrderDocId, Long completionCertDocId) {
    Project project = projectOf(companyId, id);
    Map<String, Object> before = projectMap(project);
    if (name != null) {
      if (name.isBlank()) {
        throw new BadRequestException("Project name cannot be blank");
      }
      project.setName(name.trim());
    }
    if (contractValue != null) {
      project.setContractValue(money(contractValue));
    }
    if (currency != null) {
      project.setCurrency(currencyCode(currency));
    }
    if (status != null) {
      project.setStatus(statusCode(status));
    }
    if (completionDate != null) {
      project.setCompletionDate(completionDate);
    }
    if (domainCategory != null) {
      if (domainCategory.isBlank()) {
        throw new BadRequestException("domainCategory cannot be blank");
      }
      project.setDomainCategory(domainCategory.trim().toUpperCase());
    }
    if (workOrderDocId != null) {
      checkDocument(companyId, workOrderDocId);
      project.setWorkOrderDocId(workOrderDocId);
    }
    if (completionCertDocId != null) {
      checkDocument(companyId, completionCertDocId);
      project.setCompletionCertDocId(completionCertDocId);
    }
    project = projects.save(project);
    touch(companyId, userId, "UPDATE", "project", project.getId(), before,
        projectMap(project));
    return project;
  }

  @Transactional
  public void deleteProject(long companyId, long userId, long id) {
    Project project = projectOf(companyId, id);
    Map<String, Object> before = projectMap(project);
    projects.delete(project);
    touch(companyId, userId, "DELETE", "project", id, before, null);
  }

  @Transactional(readOnly = true)
  public List<Certificate> listCertificates(long companyId) {
    return certificates.findByCompanyIdOrderById(companyId);
  }

  @Transactional
  public Certificate createCertificate(long companyId, long userId, String name,
      String levelText, Integer levelNumber, LocalDate issueDate, LocalDate expiryDate,
      String amount, String currency, Long documentId) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("Certificate name is required");
    }
    checkDocument(companyId, documentId);
    Certificate certificate = new Certificate(companyId, name.trim());
    certificate.setLevelText(levelText == null || levelText.isBlank() ? null
        : levelText.trim());
    certificate.setLevelNumber(levelNumber);
    certificate.setIssueDate(issueDate);
    certificate.setExpiryDate(expiryDate);
    certificate.setAmount(money(amount));
    certificate.setCurrency(currencyCode(currency));
    certificate.setDocumentId(documentId);
    certificate = certificates.save(certificate);
    touch(companyId, userId, "CREATE", "certificate", certificate.getId(), null,
        certificateMap(certificate));
    return certificate;
  }

  @Transactional
  public Certificate updateCertificate(long companyId, long userId, long id, String name,
      String levelText, Integer levelNumber, LocalDate issueDate, LocalDate expiryDate,
      String amount, String currency, Long documentId, boolean clearDocument) {
    Certificate certificate = certificateOf(companyId, id);
    Map<String, Object> before = certificateMap(certificate);
    if (name != null) {
      if (name.isBlank()) {
        throw new BadRequestException("Certificate name cannot be blank");
      }
      certificate.setName(name.trim());
    }
    if (levelText != null) {
      certificate.setLevelText(levelText.isBlank() ? null : levelText.trim());
    }
    if (levelNumber != null) {
      certificate.setLevelNumber(levelNumber);
    }
    if (issueDate != null) {
      certificate.setIssueDate(issueDate);
    }
    if (expiryDate != null) {
      certificate.setExpiryDate(expiryDate);
    }
    if (amount != null) {
      certificate.setAmount(money(amount));
    }
    if (currency != null) {
      certificate.setCurrency(currencyCode(currency));
    }
    if (clearDocument) {
      certificate.setDocumentId(null);
    } else if (documentId != null) {
      checkDocument(companyId, documentId);
      certificate.setDocumentId(documentId);
    }
    certificate = certificates.save(certificate);
    touch(companyId, userId, "UPDATE", "certificate", certificate.getId(), before,
        certificateMap(certificate));
    return certificate;
  }

  @Transactional
  public void deleteCertificate(long companyId, long userId, long id) {
    Certificate certificate = certificateOf(companyId, id);
    Map<String, Object> before = certificateMap(certificate);
    certificates.delete(certificate);
    touch(companyId, userId, "DELETE", "certificate", id, before, null);
  }

  @Transactional(readOnly = true)
  public List<CompanyDocument> listDocuments(long companyId) {
    return documents.findByCompanyIdOrderById(companyId);
  }

  @Transactional
  public CompanyDocument uploadDocument(long companyId, long userId, String fileName,
      String mimeType, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new BadRequestException("Uploaded file is empty");
    }
    FileSafetyValidator.requireSizeWithin(bytes, properties.limits().maxPdfBytes());
    String extension = extensionFor(bytes, fileName);
    String name = fileName == null || fileName.isBlank() ? "document" + extension
        : fileName.trim();
    String storagePath = storage.store(bytes, extension);
    CompanyDocument document = new CompanyDocument(companyId, name, storagePath,
        FileSafetyValidator.sha256Hex(bytes),
        mimeType == null || mimeType.isBlank() ? "application/octet-stream"
            : mimeType.trim(),
        bytes.length, false);
    document = documents.save(document);
    touch(companyId, userId, "CREATE", "company_document", document.getId(), null,
        Map.of("fileName", name, "sha256", document.getSha256()));
    return document;
  }

  private void touch(long companyId, long userId, String action, String entityType,
      Long entityId, Object before, Object after) {
    Company company = getCompany(companyId);
    company.bumpDataVersion();
    companies.save(company);
    audit.record(companyId, userId, action, entityType, entityId, before, after);
  }

  private FinancialRecord financialOf(long companyId, long id) {
    FinancialRecord record = financials.findById(id)
        .orElseThrow(() -> new NotFoundException("Financial record not found"));
    if (record.getCompanyId() != companyId) {
      throw new NotFoundException("Financial record not found");
    }
    return record;
  }

  private Project projectOf(long companyId, long id) {
    Project project = projects.findById(id)
        .orElseThrow(() -> new NotFoundException("Project not found"));
    if (project.getCompanyId() != companyId) {
      throw new NotFoundException("Project not found");
    }
    return project;
  }

  private Certificate certificateOf(long companyId, long id) {
    Certificate certificate = certificates.findById(id)
        .orElseThrow(() -> new NotFoundException("Certificate not found"));
    if (certificate.getCompanyId() != companyId) {
      throw new NotFoundException("Certificate not found");
    }
    return certificate;
  }

  private void checkDocument(long companyId, Long documentId) {
    if (documentId == null) {
      return;
    }
    CompanyDocument document = documents.findById(documentId)
        .orElseThrow(() -> new BadRequestException("Supporting document not found"));
    if (document.getCompanyId() != companyId) {
      throw new BadRequestException("Supporting document belongs to another company");
    }
  }

  private static void requireLabel(String label, String field) {
    if (label == null || label.isBlank()) {
      throw new BadRequestException(field + " is required");
    }
  }

  private static String currencyCode(String currency) {
    if (currency == null || currency.isBlank()) {
      return "INR";
    }
    return currency.trim().toUpperCase();
  }

  private static String statusCode(String status) {
    if (status == null || status.isBlank()) {
      return "COMPLETED";
    }
    return status.trim().toUpperCase();
  }

  private static String extensionFor(byte[] bytes, String fileName) {
    if (isPdf(bytes) || isPng(bytes) || isJpeg(bytes)) {
      if (fileName != null) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
          String ext = fileName.substring(dot).toLowerCase();
          if (ext.matches("\\.[a-z0-9]{1,8}")) {
            return ext;
          }
        }
      }
      return isPdf(bytes) ? ".pdf" : isPng(bytes) ? ".png" : ".jpg";
    }
    throw new BadRequestException("Supporting document must be a PDF, PNG or JPEG file");
  }

  private static boolean isPdf(byte[] bytes) {
    return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
        && bytes[3] == 'F' && bytes[4] == '-';
  }

  private static boolean isPng(byte[] bytes) {
    return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
        && bytes[2] == 'N' && bytes[3] == 'G';
  }

  private static boolean isJpeg(byte[] bytes) {
    return bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8
        && bytes[2] == (byte) 0xFF;
  }

  private static Map<String, Object> profileMap(Company company) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", company.getName());
    map.put("gstNumber", company.getGstNumber());
    map.put("gstRegistered", company.isGstRegistered());
    map.put("msmeRegistered", company.isMsmeRegistered());
    map.put("blacklisted", company.isBlacklisted());
    map.put("dataVersion", company.getDataVersion());
    return map;
  }

  private static Map<String, Object> financialMap(FinancialRecord record) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("fyLabel", record.getFyLabel());
    map.put("fyEndDate", String.valueOf(record.getFyEndDate()));
    map.put("turnover", moneyString(record.getTurnover()));
    map.put("netWorth", moneyString(record.getNetWorth()));
    map.put("currency", record.getCurrency());
    map.put("audited", record.isAudited());
    map.put("documentId", record.getDocumentId());
    return map;
  }

  private static Map<String, Object> projectMap(Project project) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", project.getName());
    map.put("contractValue", moneyString(project.getContractValue()));
    map.put("currency", project.getCurrency());
    map.put("status", project.getStatus());
    map.put("completionDate", String.valueOf(project.getCompletionDate()));
    map.put("domainCategory", project.getDomainCategory());
    return map;
  }

  private static Map<String, Object> certificateMap(Certificate certificate) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", certificate.getName());
    map.put("levelText", certificate.getLevelText());
    map.put("levelNumber", certificate.getLevelNumber());
    map.put("issueDate", String.valueOf(certificate.getIssueDate()));
    map.put("expiryDate", String.valueOf(certificate.getExpiryDate()));
    map.put("amount", moneyString(certificate.getAmount()));
    map.put("currency", certificate.getCurrency());
    map.put("documentId", certificate.getDocumentId());
    return map;
  }
}
