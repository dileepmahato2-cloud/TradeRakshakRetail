package com.traderakshak.controller;

// =============================================================================
// UserHierarchyController — 10-level user hierarchy REST API
// All operations enforce hierarchy-level authorization
// =============================================================================

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.repository.UserRepository;
import com.traderakshak.service.UserHierarchyService;
import com.traderakshak.service.UserHierarchyService.CreateUserRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/user-hierarchy")
@RequiredArgsConstructor
@Tag(name = "User Hierarchy", description = "10-level user hierarchy management")
public class UserHierarchyController {

    private final UserHierarchyService hierarchyService;
    private final UserRepository userRepo;

    // ── Query ──────────────────────────────────────────────────────────────────

    @GetMapping("/types")
    @Operation(summary = "Get all user types + which types I can create")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserTypes(
            @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "all_types", UserHierarchyService.ALL_USER_TYPES,
            "creatable_by_me", hierarchyService.creatableTypes(actor),
            "my_type", actor.getUserType(),
            "my_level", actor.getHierarchyLevel()
        )));
    }

    @GetMapping("/tree")
    @Operation(summary = "Get hierarchical user tree for actor's scope")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTree(
            @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(ApiResponse.ok(hierarchyService.getUserTree(actor)));
    }

    @GetMapping("/summary")
    @Operation(summary = "User count per type in my scope")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getSummary(
            @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(ApiResponse.ok(hierarchyService.getHierarchySummary(actor)));
    }

    @GetMapping("/users")
    @Operation(summary = "List all users I can manage")
    public ResponseEntity<ApiResponse<List<User>>> listManageable(
            @AuthenticationPrincipal User actor,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) String status) {
        List<User> all = userRepo.findAll().stream()
            .filter(u -> !u.getId().equals(actor.getId()))
            .filter(u -> actor.canManageUserType(u.getUserType()))
            .filter(u -> userType == null || userType.equals(u.getUserType()))
            .filter(u -> status  == null || status.equals(u.getStatus()))
            .toList();
        return ResponseEntity.ok(ApiResponse.ok(all));
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "Get single user if in my scope")
    public ResponseEntity<ApiResponse<User>> getUser(@PathVariable Long id,
            @AuthenticationPrincipal User actor) {
        User target = userRepo.findById(id)
            .filter(u -> actor.canManageUserType(u.getUserType()) || actor.getId().equals(id))
            .orElseThrow(() -> new com.traderakshak.exception.TradeRakshakException(
                "USR-404", "User not found or outside your scope."));
        return ResponseEntity.ok(ApiResponse.ok(target));
    }

    @GetMapping("/my-permissions")
    @Operation(summary = "Get my permission matrix for all modules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myPermissions(
            @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(ApiResponse.ok(hierarchyService.getMyPermissions(actor)));
    }

    // ── Create ─────────────────────────────────────────────────────────────────

    @PostMapping("/users")
    @Operation(summary = "Create a user — hierarchy-enforced")
    public ResponseEntity<ApiResponse<User>> createUser(
            @RequestBody CreateUserRequest req,
            @AuthenticationPrincipal User actor) {
        User created = hierarchyService.createUser(actor, req);
        return ResponseEntity.ok(ApiResponse.ok(
            "User created. TR ID: " + created.getTrUserId(), created));
    }

    @PostMapping("/users/bulk")
    @Operation(summary = "Bulk create users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkCreate(
            @RequestBody List<CreateUserRequest> requests,
            @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(ApiResponse.ok(
            "Bulk create complete.", hierarchyService.bulkCreate(actor, requests)));
    }

    // ── Status operations ──────────────────────────────────────────────────────

    @PostMapping("/users/{id}/suspend")
    @Operation(summary = "Suspend user")
    public ResponseEntity<ApiResponse<Void>> suspend(@PathVariable Long id,
            @RequestBody ReasonRequest req, @AuthenticationPrincipal User actor) {
        hierarchyService.suspendUser(actor, id, req.getReason());
        return ResponseEntity.ok(ApiResponse.ok("User suspended.", null));
    }

    @PostMapping("/users/{id}/activate")
    @Operation(summary = "Activate / unsuspend user")
    public ResponseEntity<ApiResponse<Void>> activate(@PathVariable Long id,
            @AuthenticationPrincipal User actor) {
        hierarchyService.activateUser(actor, id);
        return ResponseEntity.ok(ApiResponse.ok("User activated.", null));
    }

    @PostMapping("/users/{id}/block")
    @Operation(summary = "Block user permanently")
    public ResponseEntity<ApiResponse<Void>> block(@PathVariable Long id,
            @RequestBody ReasonRequest req, @AuthenticationPrincipal User actor) {
        hierarchyService.blockUser(actor, id, req.getReason());
        return ResponseEntity.ok(ApiResponse.ok("User blocked.", null));
    }

    @PostMapping("/users/{id}/unlock")
    @Operation(summary = "Unlock account (remove login lockout)")
    public ResponseEntity<ApiResponse<Void>> unlock(@PathVariable Long id,
            @AuthenticationPrincipal User actor) {
        hierarchyService.unlockUser(actor, id);
        return ResponseEntity.ok(ApiResponse.ok("Account unlocked.", null));
    }

    @PostMapping("/users/{id}/approve")
    @Operation(summary = "Approve pending user")
    public ResponseEntity<ApiResponse<Void>> approve(@PathVariable Long id,
            @AuthenticationPrincipal User actor) {
        hierarchyService.approveUser(actor, id);
        return ResponseEntity.ok(ApiResponse.ok("User approved.", null));
    }

    @PostMapping("/users/{id}/reset-password")
    @Operation(summary = "Reset user password — returns generated password")
    public ResponseEntity<ApiResponse<Map<String, String>>> resetPassword(
            @PathVariable Long id,
            @RequestBody(required = false) ReasonRequest req,
            @AuthenticationPrincipal User actor) {
        String newPass = req != null ? req.getNewPassword() : null;
        String generated = hierarchyService.resetPassword(actor, id, newPass);
        return ResponseEntity.ok(ApiResponse.ok("Password reset.",
            Map.of("generatedPassword", generated,
                   "note", "Communicate this to the user securely.")));
    }

    @PostMapping("/users/{id}/change-type")
    @Operation(summary = "Change user type (hierarchy-enforced)")
    public ResponseEntity<ApiResponse<Void>> changeType(
            @PathVariable Long id,
            @RequestBody TypeRequest req,
            @AuthenticationPrincipal User actor) {
        hierarchyService.changeUserType(actor, id, req.getUserType());
        return ResponseEntity.ok(ApiResponse.ok("User type changed to " + req.getUserType(), null));
    }

    @PostMapping("/users/{id}/transfer")
    @Operation(summary = "Transfer user to new parent")
    public ResponseEntity<ApiResponse<Void>> transfer(
            @PathVariable Long id,
            @RequestBody TransferRequest req,
            @AuthenticationPrincipal User actor) {
        hierarchyService.transferUser(actor, id, req.getNewParentId());
        return ResponseEntity.ok(ApiResponse.ok("User transferred.", null));
    }

    @PostMapping("/users/{id}/generate-api-key")
    @Operation(summary = "Generate API key for user")
    public ResponseEntity<ApiResponse<Void>> generateApiKey(
            @PathVariable Long id, @AuthenticationPrincipal User actor) {
        hierarchyService.generateApiKey(actor, id);
        return ResponseEntity.ok(ApiResponse.ok("API key generated.", null));
    }

    // ── Bulk operations ────────────────────────────────────────────────────────

    @PostMapping("/users/bulk/plan")
    @Operation(summary = "Bulk update plan for multiple users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkPlan(
            @RequestBody BulkPlanRequest req, @AuthenticationPrincipal User actor) {
        int count = hierarchyService.bulkUpdatePlan(actor, req.getUserIds(), req.getPlan());
        return ResponseEntity.ok(ApiResponse.ok(count + " users updated.", Map.of("updated", count)));
    }

    @PostMapping("/users/bulk/suspend")
    @Operation(summary = "Bulk suspend users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkSuspend(
            @RequestBody BulkActionRequest req, @AuthenticationPrincipal User actor) {
        int count = hierarchyService.bulkSuspend(actor, req.getUserIds(), req.getReason());
        return ResponseEntity.ok(ApiResponse.ok(count + " users suspended.", Map.of("suspended", count)));
    }

    // ── Inner request classes ──────────────────────────────────────────────────

    @Data static class ReasonRequest {
        private String reason;
        private String newPassword;
    }
    @Data static class TypeRequest        { private String userType; }
    @Data static class TransferRequest    { private Long newParentId; }
    @Data static class BulkPlanRequest    { private List<Long> userIds; private String plan; }
    @Data static class BulkActionRequest  { private List<Long> userIds; private String reason; }
}
