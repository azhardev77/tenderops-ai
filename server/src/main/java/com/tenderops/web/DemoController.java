package com.tenderops.web;

import com.tenderops.config.TenderopsProperties;
import com.tenderops.demo.DemoService;
import com.tenderops.security.CurrentUser;
import com.tenderops.security.RateLimiter;
import com.tenderops.tender.TenderService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** One-click demo endpoints. Disabled in prod-like profiles (never prod). */
@RestController
@RequestMapping("/api/v1/demo")
@Profile("!prod & !postgres")
public class DemoController {

  private final DemoService demo;
  private final RateLimiter rateLimiter;
  private final TenderopsProperties properties;

  public DemoController(DemoService demo, RateLimiter rateLimiter,
      TenderopsProperties properties) {
    this.demo = demo;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
  }

  public record RfpResponse(long tenderId, long versionId, Long jobId, boolean duplicate) {
  }

  public record CorrigendumResponse(long versionId, Long jobId, Long amendmentId,
      boolean duplicate) {
  }

  @PostMapping("/load-rfp")
  public ResponseEntity<RfpResponse> loadRfp(HttpServletRequest request) {
    CurrentUser current = CurrentUser.require();
    checkUploadBudget(request);
    TenderService.TenderUpload done = demo.loadRfp(current.companyId());
    RfpResponse body =
        new RfpResponse(done.tenderId(), done.versionId(), done.jobId(), done.duplicate());
    if (done.duplicate()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(body);
  }

  @PostMapping("/load-corrigendum")
  public ResponseEntity<CorrigendumResponse> loadCorrigendum(@RequestParam long tenderId,
      HttpServletRequest request) {
    CurrentUser current = CurrentUser.require();
    checkUploadBudget(request);
    DemoService.CorrigendumLoad done = demo.loadCorrigendum(current.companyId(), tenderId);
    CorrigendumResponse body = new CorrigendumResponse(done.versionId(), done.jobId(),
        done.amendmentId(), done.duplicate());
    if (done.duplicate()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(body);
  }

  @PostMapping("/live-check")
  public DemoService.LiveCheck liveCheck() {
    CurrentUser current = CurrentUser.require();
    if (!current.isAdmin()) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Live check is ADMIN only");
    }
    return demo.liveCheck(current.companyId());
  }

  @GetMapping("/sample-pdfs")
  public Map<String, List<String>> samplePdfs() {
    CurrentUser.require();
    return Map.of("files", demo.samplePdfs());
  }

  private void checkUploadBudget(HttpServletRequest request) {
    if (!rateLimiter.tryConsume("upload:" + request.getRemoteAddr(),
        properties.rateLimit().uploadPerMinute())) {
      throw new RateLimitExceededException();
    }
  }
}
