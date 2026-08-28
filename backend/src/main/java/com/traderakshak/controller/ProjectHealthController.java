package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.service.ProjectHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/project-health")
@RequiredArgsConstructor
@Tag(name = "Project Health", description = "Project health check & pending work analyzer")
public class ProjectHealthController {

    private final ProjectHealthService service;

    @GetMapping("/full-report")
    @Operation(summary = "Complete project health report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFullReport() {
        return ResponseEntity.ok(ApiResponse.ok(service.generateFullReport()));
    }

    @GetMapping("/git-status")
    @Operation(summary = "Git repository analysis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGitStatus() {
        return ResponseEntity.ok(ApiResponse.ok(service.analyzeGitRepository()));
    }

    @GetMapping("/pending-work")
    @Operation(summary = "Pending work detection")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPendingWork() {
        return ResponseEntity.ok(ApiResponse.ok(service.detectPendingWork()));
    }

    @GetMapping("/code-quality")
    @Operation(summary = "Code quality analysis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCodeQuality() {
        return ResponseEntity.ok(ApiResponse.ok(service.analyzeCodeQuality()));
    }

    @GetMapping("/security-review")
    @Operation(summary = "Security review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSecurityReview() {
        return ResponseEntity.ok(ApiResponse.ok(service.reviewSecurity()));
    }

    @GetMapping("/database-validation")
    @Operation(summary = "Database validation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDatabaseValidation() {
        return ResponseEntity.ok(ApiResponse.ok(service.validateDatabase()));
    }

    @GetMapping("/api-validation")
    @Operation(summary = "API validation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getApiValidation() {
        return ResponseEntity.ok(ApiResponse.ok(service.validateApis()));
    }

    @GetMapping("/build-health")
    @Operation(summary = "Build health check")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBuildHealth() {
        return ResponseEntity.ok(ApiResponse.ok(service.checkBuildHealth()));
    }

    @GetMapping("/feature-status")
    @Operation(summary = "Feature completion status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFeatureStatus() {
        return ResponseEntity.ok(ApiResponse.ok(service.detectFeatureCompletion()));
    }

    @GetMapping("/deployment-readiness")
    @Operation(summary = "Deployment readiness assessment")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDeploymentReadiness() {
        return ResponseEntity.ok(ApiResponse.ok(service.assessDeploymentReadiness()));
    }
}
