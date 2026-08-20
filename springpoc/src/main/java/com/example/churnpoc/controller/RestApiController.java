package com.example.churnpoc.controller;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.churnpoc.dto.CustomerReview;
import com.example.churnpoc.dto.DashboardView;
import com.example.churnpoc.dto.UploadReceipt;
import com.example.churnpoc.service.DashboardService;
import com.example.churnpoc.service.ScanService;
import com.example.churnpoc.service.UploadService;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * JSON REST API mirroring the app's operations, for external consumers
 * and for the Swagger UI. The Thymeleaf pages are the human UI; this is
 * the machine-facing equivalent.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "ECRP API", description = "Customer churn scoring and retention workflow")
@OpenAPIDefinition(info = @Info(title = "ECRP API", version = "1.0",
        description = "Upload customer data, score churn risk, and work the at-risk list."))
public class RestApiController {

    private final UploadService uploadService;
    private final ScanService scanService;
    private final DashboardService dashboardService;

    public RestApiController(UploadService theUploadService,
                             ScanService theScanService,
                             DashboardService theDashboardService) {
        uploadService = theUploadService;
        scanService = theScanService;
        dashboardService = theDashboardService;
    }

    @Operation(summary = "Upload a customer CSV, then score every row",
            description = "Replaces the current dataset. Returns the load receipt "
                    + "(received/loaded/skipped). Scoring runs automatically; if the ML "
                    + "service is down the data still loads and can be scored later via /api/scan.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadReceipt upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file provided");
        }
        UploadReceipt receipt = uploadService.load(file);
        try {
            scanService.scanAll();
        }
        catch (RestClientException ignored) {
            // data is loaded; scoring can be retried via /api/scan
        }
        return receipt;
    }

    @Operation(summary = "List scored customers by risk band, paginated",
            description = "band = HIGH | MEDIUM | LOW | ALL (default HIGH). 20 per page, "
                    + "highest churn risk first. Also returns the summary counts.")
    @GetMapping("/customers")
    public DashboardView customers(@RequestParam(defaultValue = "HIGH") String band,
                                   @RequestParam(defaultValue = "0") int page) {
        return dashboardService.getDashboard(band, page);
    }

    @Operation(summary = "Get one customer with prediction and the reasons it was flagged")
    @GetMapping("/customers/{id}")
    public CustomerReview customer(@PathVariable Long id) {
        try {
            return dashboardService.getReview(id);
        }
        catch (IllegalArgumentException exc) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exc.getMessage());
        }
    }

    @Operation(summary = "Re-run churn predictions for all loaded customers",
            description = "Returns the number of customers scored. 503 if the ML service is unreachable.")
    @PostMapping("/scan")
    public Map<String, Integer> scan() {
        try {
            return Map.of("scored", scanService.scanAll());
        }
        catch (RestClientException exc) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ML service is unreachable");
        }
    }

    @Operation(summary = "Mark a customer as handled (unflagged)")
    @PostMapping("/customers/{id}/unflag")
    public ResponseEntity<Void> unflag(@PathVariable Long id) {
        dashboardService.unflag(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete all customer data and predictions")
    @DeleteMapping("/customers")
    public ResponseEntity<Void> clear() {
        dashboardService.clearAll();
        return ResponseEntity.noContent().build();
    }
}
