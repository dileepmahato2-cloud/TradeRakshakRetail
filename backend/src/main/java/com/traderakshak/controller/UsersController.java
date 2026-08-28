package com.traderakshak.controller;
import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.entity.User;
import com.traderakshak.repository.UserRepository;
import com.traderakshak.util.DateUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/users") @RequiredArgsConstructor
@Tag(name="User Management", description="Admin user CRUD")
public class UsersController {
    private final UserRepository repo;
    private final PasswordEncoder encoder;

    @GetMapping @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN','SUPER_ADMIN','INSTITUTION_ADMIN')")
    public ResponseEntity<ApiResponse<List<User>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(repo.findAll()));
    }

    @PostMapping @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN','SUPER_ADMIN','INSTITUTION_ADMIN')")
    public ResponseEntity<ApiResponse<User>> save(@RequestBody SaveRequest req) {
        Optional<User> existing = repo.findByEmail(req.getEmail().toLowerCase());
        if (existing.isPresent()) {
            User u = existing.get();
            u.setName(req.getName()); u.setRole(req.getRole()); u.setLevel(req.getLevel());
            u.setPlan(req.getPlan()); u.setStatus(req.getStatus()); u.setMobile(req.getMobile());
            u.setSegments(req.getSegments()); u.setUpdatedAt(DateUtil.now());
            if (req.getPassword() != null && !req.getPassword().isBlank())
                u.setPassword(encoder.encode(req.getPassword()));
            return ResponseEntity.ok(ApiResponse.ok("User updated.", repo.save(u)));
        }
        long seq = repo.countAll() + 1;
        String trUserId = "TR-" + DateUtil.currentYear() + "-" + String.format("%06d", seq);
        User newUser = User.builder()
            .trUserId(trUserId).institutionId(1L).name(req.getName())
            .email(req.getEmail().toLowerCase())
            .password(encoder.encode(req.getPassword() != null && !req.getPassword().isBlank() ? req.getPassword() : "User@123"))
            .role(req.getRole()).level(req.getLevel()).plan(req.getPlan())
            .segments(req.getSegments()).status(req.getStatus()).mobile(req.getMobile())
            .createdAt(DateUtil.now()).updatedAt(DateUtil.now()).build();
        return ResponseEntity.ok(ApiResponse.ok("User created. TR ID: "+trUserId, repo.save(newUser)));
    }

    @PutMapping("/{id}") @PreAuthorize("hasAnyRole('ADMIN','PLATFORM_ADMIN','SUPER_ADMIN','INSTITUTION_ADMIN')")
    public ResponseEntity<ApiResponse<User>> update(@PathVariable Long id, @RequestBody SaveRequest req) {
        User u = repo.findById(id).orElseThrow();
        u.setName(req.getName()); u.setRole(req.getRole()); u.setLevel(req.getLevel());
        u.setPlan(req.getPlan()); u.setStatus(req.getStatus()); u.setMobile(req.getMobile());
        u.setSegments(req.getSegments()); u.setPlanExpiry(req.getPlanExpiry()); u.setUpdatedAt(DateUtil.now());
        if (req.getPassword() != null && !req.getPassword().isBlank())
            u.setPassword(encoder.encode(req.getPassword()));
        return ResponseEntity.ok(ApiResponse.ok("User updated.", repo.save(u)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<User>> me(@AuthenticationPrincipal User u) {
        return ResponseEntity.ok(ApiResponse.ok(u));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<User>> updateMe(@RequestBody SaveRequest req, @AuthenticationPrincipal User u) {
        u.setName(req.getName()); u.setMobile(req.getMobile()); u.setUpdatedAt(DateUtil.now());
        if (req.getPassword() != null && !req.getPassword().isBlank())
            u.setPassword(encoder.encode(req.getPassword()));
        return ResponseEntity.ok(ApiResponse.ok("Profile updated.", repo.save(u)));
    }

    @Data static class SaveRequest {
        private String name, email, password, role, level, plan, segments="EQ-INT,EQ-SWG", status="active", mobile, planExpiry;
    }
}
