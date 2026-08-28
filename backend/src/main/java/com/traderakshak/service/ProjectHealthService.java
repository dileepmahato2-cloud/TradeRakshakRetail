package com.traderakshak.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

@Slf4j
@Service
public class ProjectHealthService {

    @PersistenceContext
    private EntityManager em;

    @Value("${spring.application.name:TradeRakshak}")
    private String appName;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ════════════════════════════════════════════════════════════════════════════
    //  FULL REPORT (combines all sections)
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> generateFullReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generated_at", LocalDateTime.now().format(DTF));
        report.put("project_name", appName);

        Map<String, Object> gitStatus = analyzeGitRepository();
        Map<String, Object> pendingWork = detectPendingWork();
        Map<String, Object> codeQuality = analyzeCodeQuality();
        Map<String, Object> security = reviewSecurity();
        Map<String, Object> database = validateDatabase();
        Map<String, Object> apis = validateApis();
        Map<String, Object> buildHealth = checkBuildHealth();
        Map<String, Object> features = detectFeatureCompletion();
        Map<String, Object> deployment = assessDeploymentReadiness();

        report.put("git_status", gitStatus);
        report.put("pending_work", pendingWork);
        report.put("code_quality", codeQuality);
        report.put("security_review", security);
        report.put("database_validation", database);
        report.put("api_validation", apis);
        report.put("build_health", buildHealth);
        report.put("feature_status", features);
        report.put("deployment_readiness", deployment);

        int score = calculateHealthScore(gitStatus, pendingWork, codeQuality, security, database, buildHealth);
        report.put("health_score", score);
        report.put("health_grade", score >= 90 ? "A" : score >= 75 ? "B" : score >= 60 ? "C" : score >= 40 ? "D" : "F");
        report.put("overall_status", score >= 75 ? "Ready for Review" : score >= 50 ? "Needs Work" : "Critical Issues");

        List<Map<String, Object>> recommendations = generateRecommendations(gitStatus, pendingWork, codeQuality, security);
        report.put("recommendations", recommendations);

        return report;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  1. GIT REPOSITORY ANALYSIS
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> analyzeGitRepository() {
        Map<String, Object> result = new LinkedHashMap<>();
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) {
            result.put("error", "Not a git repository");
            return result;
        }

        result.put("branch", runGit(projectRoot, "git", "rev-parse", "--abbrev-ref", "HEAD").trim());
        result.put("last_commit", runGit(projectRoot, "git", "log", "-1", "--format=%H|%s|%an|%ai").trim());

        String statusOutput = runGit(projectRoot, "git", "status", "--porcelain");
        List<Map<String, Object>> fileStatuses = new ArrayList<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        statusCounts.put("modified", 0);
        statusCounts.put("added", 0);
        statusCounts.put("deleted", 0);
        statusCounts.put("renamed", 0);
        statusCounts.put("untracked", 0);
        statusCounts.put("staged", 0);

        for (String line : statusOutput.split("\n")) {
            if (line.isBlank()) continue;
            String code = line.substring(0, 2).trim();
            String filePath = line.substring(3).trim();
            String statusType = mapGitStatus(code);
            statusCounts.merge(statusType, 1, Integer::sum);
            if (code.charAt(0) != ' ' && code.charAt(0) != '?') statusCounts.merge("staged", 1, Integer::sum);
        }
        result.put("status_counts", statusCounts);

        String numstatOutput = runGit(projectRoot, "git", "diff", "--numstat", "HEAD");
        List<Map<String, Object>> changedFiles = new ArrayList<>();
        int totalAdded = 0, totalDeleted = 0;

        for (String line : numstatOutput.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            if (parts.length < 3) continue;
            int added = parseIntSafe(parts[0]);
            int deleted = parseIntSafe(parts[1]);
            String file = parts[2];
            int totalChanged = added + deleted;
            totalAdded += added;
            totalDeleted += deleted;

            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("file", file);
            fileInfo.put("lines_added", added);
            fileInfo.put("lines_deleted", deleted);
            fileInfo.put("total_changed", totalChanged);
            fileInfo.put("risk_level", totalChanged > 2000 ? "Critical" : totalChanged > 1000 ? "High" : totalChanged > 300 ? "Medium" : "Low");
            fileInfo.put("status", "Modified");
            fileInfo.put("committed", false);

            String lastAuthor = runGit(projectRoot, "git", "log", "-1", "--format=%an", "--", file).trim();
            String lastDate = runGit(projectRoot, "git", "log", "-1", "--format=%ai", "--", file).trim();
            fileInfo.put("last_modified_by", lastAuthor.isBlank() ? "Unknown" : lastAuthor);
            fileInfo.put("last_modified_date", lastDate.isBlank() ? "New File" : lastDate);

            changedFiles.add(fileInfo);
        }

        changedFiles.sort((a, b) -> Integer.compare((int) b.get("total_changed"), (int) a.get("total_changed")));
        result.put("changed_files", changedFiles);
        result.put("total_files_changed", changedFiles.size());
        result.put("total_lines_added", totalAdded);
        result.put("total_lines_deleted", totalDeleted);
        result.put("total_lines_changed", totalAdded + totalDeleted);

        List<Map<String, Object>> largeChanges = changedFiles.stream()
            .filter(f -> (int) f.get("total_changed") > 300)
            .collect(Collectors.toList());
        result.put("large_changes", largeChanges);
        result.put("large_change_count", largeChanges.size());

        String conflictCheck = runGit(projectRoot, "git", "diff", "--name-only", "--diff-filter=U");
        List<String> conflicts = Arrays.stream(conflictCheck.split("\n")).filter(s -> !s.isBlank()).toList();
        result.put("merge_conflicts", conflicts);
        result.put("has_conflicts", !conflicts.isEmpty());

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  2. PENDING WORK DETECTION
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> detectPendingWork() {
        Map<String, Object> result = new LinkedHashMap<>();
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) return Map.of("error", "Project root not found");

        List<Map<String, Object>> todoItems = new ArrayList<>();
        List<Map<String, Object>> fixmeItems = new ArrayList<>();
        List<Map<String, Object>> hackItems = new ArrayList<>();
        List<Map<String, Object>> emptyExceptions = new ArrayList<>();
        List<Map<String, Object>> returnNulls = new ArrayList<>();
        List<Map<String, Object>> unsupported = new ArrayList<>();
        List<Map<String, Object>> commentedCode = new ArrayList<>();
        List<Map<String, Object>> dummyImpl = new ArrayList<>();

        List<Path> javaFiles = findSourceFiles(projectRoot, "*.java");
        List<Path> tsFiles = findSourceFiles(projectRoot, "*.ts");
        List<Path> allFiles = new ArrayList<>(javaFiles);
        allFiles.addAll(tsFiles);

        for (Path file : allFiles) {
            try {
                List<String> lines = Files.readAllLines(file);
                String relativePath = projectRoot.relativize(file).toString().replace('\\', '/');

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    int lineNum = i + 1;

                    if (containsPattern(line, "TODO")) {
                        todoItems.add(pendingItem(relativePath, lineNum, line, "TODO", "Pending Work"));
                    }
                    if (containsPattern(line, "FIXME")) {
                        fixmeItems.add(pendingItem(relativePath, lineNum, line, "FIXME", "Needs Fix"));
                    }
                    if (containsPattern(line, "HACK")) {
                        hackItems.add(pendingItem(relativePath, lineNum, line, "HACK", "Technical Debt"));
                    }
                    if (line.contains("catch") && i + 1 < lines.size()) {
                        String nextLine = lines.get(Math.min(i + 1, lines.size() - 1)).trim();
                        String twoAfter = i + 2 < lines.size() ? lines.get(i + 2).trim() : "";
                        if (nextLine.equals("}") || (nextLine.isBlank() && twoAfter.equals("}"))) {
                            emptyExceptions.add(pendingItem(relativePath, lineNum, line, "Empty Catch", "Needs Review"));
                        }
                    }
                    if (line.matches(".*return\\s+null\\s*;.*") && !line.startsWith("//")) {
                        returnNulls.add(pendingItem(relativePath, lineNum, line, "Return Null", "Needs Review"));
                    }
                    if (line.contains("UnsupportedOperationException")) {
                        unsupported.add(pendingItem(relativePath, lineNum, line, "Unsupported", "Not Implemented"));
                    }
                    if (line.matches("^//\\s*(public|private|protected|static|class|if|for|while|return|import).*")) {
                        commentedCode.add(pendingItem(relativePath, lineNum, line, "Commented Code", "Cleanup"));
                    }
                    if (line.matches(".*(dummy|placeholder|temp|mock|stub|fake).*") && !line.startsWith("//") && !line.contains("*")) {
                        dummyImpl.add(pendingItem(relativePath, lineNum, line, "Dummy/Placeholder", "Needs Implementation"));
                    }
                }
            } catch (IOException ignored) {}
        }

        result.put("todos", todoItems);
        result.put("todo_count", todoItems.size());
        result.put("fixmes", fixmeItems);
        result.put("fixme_count", fixmeItems.size());
        result.put("hacks", hackItems);
        result.put("hack_count", hackItems.size());
        result.put("empty_catches", emptyExceptions);
        result.put("empty_catch_count", emptyExceptions.size());
        result.put("return_nulls", returnNulls);
        result.put("return_null_count", returnNulls.size());
        result.put("unsupported_operations", unsupported);
        result.put("unsupported_count", unsupported.size());
        result.put("commented_code", commentedCode);
        result.put("commented_code_count", commentedCode.size());
        result.put("dummy_implementations", dummyImpl);
        result.put("dummy_impl_count", dummyImpl.size());

        int totalIssues = todoItems.size() + fixmeItems.size() + hackItems.size() +
            emptyExceptions.size() + returnNulls.size() + unsupported.size();
        result.put("total_pending_issues", totalIssues);

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  3. CODE QUALITY ANALYSIS
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> analyzeCodeQuality() {
        Map<String, Object> result = new LinkedHashMap<>();
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) return Map.of("error", "Project root not found");

        List<Path> javaFiles = findSourceFiles(projectRoot, "*.java");
        List<Path> tsFiles = findSourceFiles(projectRoot, "*.ts");

        List<Map<String, Object>> largeFiles = new ArrayList<>();
        List<Map<String, Object>> largeMethods = new ArrayList<>();
        List<Map<String, Object>> longParamLists = new ArrayList<>();
        List<Map<String, Object>> duplicatePatterns = new ArrayList<>();
        int totalJavaFiles = 0, totalTsFiles = 0, totalLines = 0;

        for (Path file : javaFiles) {
            totalJavaFiles++;
            analyzeFile(file, projectRoot, largeFiles, largeMethods, longParamLists);
        }
        for (Path file : tsFiles) {
            totalTsFiles++;
            analyzeFile(file, projectRoot, largeFiles, largeMethods, longParamLists);
        }

        largeFiles.sort((a, b) -> Integer.compare((int) b.get("lines"), (int) a.get("lines")));
        largeMethods.sort((a, b) -> Integer.compare((int) b.get("lines"), (int) a.get("lines")));

        result.put("total_java_files", totalJavaFiles);
        result.put("total_ts_files", totalTsFiles);
        result.put("large_files", largeFiles.stream().limit(20).toList());
        result.put("large_file_count", largeFiles.size());
        result.put("large_methods", largeMethods.stream().limit(30).toList());
        result.put("large_method_count", largeMethods.size());
        result.put("long_param_lists", longParamLists.stream().limit(20).toList());
        result.put("long_param_count", longParamLists.size());

        // Check for missing tests
        List<Map<String, Object>> missingTests = new ArrayList<>();
        Path testDir = projectRoot.resolve("backend/src/test");
        Set<String> existingTests = new HashSet<>();
        if (Files.exists(testDir)) {
            try (var s = Files.walk(testDir)) {
                s.filter(p -> p.toString().endsWith("Test.java") || p.toString().endsWith("Tests.java"))
                    .forEach(p -> existingTests.add(p.getFileName().toString().replace("Test.java", "").replace("Tests.java", "")));
            } catch (IOException ignored) {}
        }
        for (Path jf : javaFiles) {
            String name = jf.getFileName().toString().replace(".java", "");
            String relPath = projectRoot.relativize(jf).toString().replace('\\', '/');
            if (relPath.contains("/controller/") || relPath.contains("/service/")) {
                if (!existingTests.contains(name)) {
                    missingTests.add(Map.of("file", relPath, "class", name, "type", relPath.contains("/controller/") ? "Controller" : "Service"));
                }
            }
        }
        result.put("missing_tests", missingTests);
        result.put("missing_test_count", missingTests.size());
        result.put("test_coverage_estimate", existingTests.isEmpty() ? "0%" : Math.max(0, 100 - missingTests.size() * 4) + "%");

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  4. SECURITY REVIEW
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> reviewSecurity() {
        Map<String, Object> result = new LinkedHashMap<>();
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) return Map.of("error", "Project root not found");

        List<Map<String, Object>> sqlInjectionRisks = new ArrayList<>();
        List<Map<String, Object>> hardcodedSecrets = new ArrayList<>();
        List<Map<String, Object>> missingAuth = new ArrayList<>();
        List<Map<String, Object>> sensitiveDataLogging = new ArrayList<>();
        List<Map<String, Object>> missingValidation = new ArrayList<>();

        List<Path> allFiles = findSourceFiles(projectRoot, "*.java");
        allFiles.addAll(findSourceFiles(projectRoot, "*.ts"));
        allFiles.addAll(findSourceFiles(projectRoot, "*.yml"));
        allFiles.addAll(findSourceFiles(projectRoot, "*.properties"));

        Pattern sqlConcatPattern = Pattern.compile("\".*\\+.*\\+.*\".*(?:createNativeQuery|createQuery|executeQuery)", Pattern.CASE_INSENSITIVE);
        Pattern secretPattern = Pattern.compile("(?:password|secret|api_key|apikey|token)\\s*=\\s*\"[^\"]{4,}\"", Pattern.CASE_INSENSITIVE);
        Pattern logSensitivePattern = Pattern.compile("log\\.(?:info|debug|warn|error).*(?:password|token|secret|apiKey|api_key)", Pattern.CASE_INSENSITIVE);

        for (Path file : allFiles) {
            try {
                List<String> lines = Files.readAllLines(file);
                String relPath = projectRoot.relativize(file).toString().replace('\\', '/');

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int lineNum = i + 1;

                    if (line.contains("+ \"") && (line.contains("WHERE") || line.contains("SELECT") || line.contains("INSERT") || line.contains("UPDATE") || line.contains("DELETE"))) {
                        if (line.contains("setParameter") || line.contains("PreparedStatement")) continue;
                        if (!line.trim().startsWith("//") && !line.trim().startsWith("*")) {
                            sqlInjectionRisks.add(securityItem(relPath, lineNum, "SQL Injection Risk", "String concatenation in SQL query", "Critical"));
                        }
                    }

                    if (secretPattern.matcher(line).find() && !relPath.contains("test") && !relPath.contains("example")) {
                        hardcodedSecrets.add(securityItem(relPath, lineNum, "Hardcoded Secret", "Potential hardcoded credential", "High"));
                    }

                    if (logSensitivePattern.matcher(line).find()) {
                        sensitiveDataLogging.add(securityItem(relPath, lineNum, "Sensitive Data Logging", "Sensitive field may be logged", "Medium"));
                    }
                }

                if (relPath.endsWith("Controller.java") && !relPath.contains("Auth")) {
                    String content = String.join("\n", lines);
                    if (!content.contains("@AuthenticationPrincipal") && !content.contains("@PreAuthorize") && !content.contains("@Secured")) {
                        if (content.contains("@PostMapping") || content.contains("@PutMapping") || content.contains("@DeleteMapping")) {
                            missingAuth.add(securityItem(relPath, 0, "Missing Auth Check", "Controller has write endpoints without explicit auth annotations", "High"));
                        }
                    }
                }
            } catch (IOException ignored) {}
        }

        result.put("sql_injection_risks", sqlInjectionRisks);
        result.put("hardcoded_secrets", hardcodedSecrets);
        result.put("missing_auth_checks", missingAuth);
        result.put("sensitive_data_logging", sensitiveDataLogging);
        result.put("total_security_issues",
            sqlInjectionRisks.size() + hardcodedSecrets.size() + missingAuth.size() + sensitiveDataLogging.size());

        int criticalCount = (int) sqlInjectionRisks.stream().filter(i -> "Critical".equals(i.get("severity"))).count();
        int highCount = hardcodedSecrets.size() + missingAuth.size();
        result.put("critical_issues", criticalCount);
        result.put("high_issues", highCount);
        result.put("security_grade", criticalCount > 0 ? "F" : highCount > 3 ? "D" : highCount > 0 ? "C" : "A");

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  5. DATABASE VALIDATION
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> validateDatabase() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Get all tables
            List<?> tables = em.createNativeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename").getResultList();
            List<String> tableNames = tables.stream().map(String::valueOf).toList();
            result.put("total_tables", tableNames.size());
            result.put("tables", tableNames);

            // Check for tables without primary keys
            List<?> noPkTables = em.createNativeQuery(
                "SELECT t.tablename FROM pg_tables t " +
                "LEFT JOIN pg_indexes i ON t.tablename = i.tablename AND i.indexname LIKE '%pkey%' " +
                "WHERE t.schemaname = 'public' AND i.indexname IS NULL " +
                "ORDER BY t.tablename").getResultList();
            result.put("tables_without_pk", noPkTables.stream().map(String::valueOf).toList());

            // Check for missing indexes on foreign key columns
            List<?> fkWithoutIdx = em.createNativeQuery(
                "SELECT tc.table_name, kcu.column_name " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name " +
                "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public' " +
                "AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = tc.table_name " +
                "AND indexdef LIKE '%' || kcu.column_name || '%') " +
                "ORDER BY tc.table_name").getResultList();
            List<Map<String, Object>> missingFkIndexes = new ArrayList<>();
            for (Object o : fkWithoutIdx) {
                Object[] r = (Object[]) o;
                missingFkIndexes.add(Map.of("table", r[0], "column", r[1]));
            }
            result.put("missing_fk_indexes", missingFkIndexes);

            // Check for empty tables
            List<Map<String, Object>> emptyTables = new ArrayList<>();
            for (String table : tableNames) {
                try {
                    Object count = em.createNativeQuery("SELECT COUNT(*) FROM " + table).getSingleResult();
                    long rowCount = ((Number) count).longValue();
                    if (rowCount == 0) {
                        emptyTables.add(Map.of("table", table, "rows", 0));
                    }
                } catch (Exception ignored) {}
            }
            result.put("empty_tables", emptyTables);

            // Check for duplicate indexes
            List<?> dupIndexes = em.createNativeQuery(
                "SELECT indexname, tablename, indexdef FROM pg_indexes " +
                "WHERE schemaname = 'public' " +
                "GROUP BY indexname, tablename, indexdef " +
                "HAVING COUNT(*) > 1").getResultList();
            result.put("duplicate_indexes", dupIndexes.stream().map(String::valueOf).toList());

            // Table sizes
            List<?> tableSizes = em.createNativeQuery(
                "SELECT relname AS table_name, pg_size_pretty(pg_total_relation_size(relid)) AS total_size, " +
                "n_live_tup AS row_count " +
                "FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 20").getResultList();
            List<Map<String, Object>> sizeList = new ArrayList<>();
            for (Object o : tableSizes) {
                Object[] r = (Object[]) o;
                sizeList.add(Map.of("table", r[0], "size", r[1], "rows", r[2]));
            }
            result.put("table_sizes", sizeList);

            // Migration files check
            Path projectRoot = findProjectRoot();
            if (projectRoot != null) {
                Path migrationDir = projectRoot.resolve("backend/src/main/resources/db/migration");
                if (Files.exists(migrationDir)) {
                    try (var s = Files.list(migrationDir)) {
                        List<String> migrations = s.map(p -> p.getFileName().toString()).sorted().toList();
                        result.put("migrations", migrations);
                        result.put("migration_count", migrations.size());
                    }
                } else {
                    result.put("migrations", List.of());
                    result.put("migration_count", 0);
                }
            }

            result.put("database_status", "Connected");
        } catch (Exception e) {
            result.put("database_status", "Error");
            result.put("error", e.getMessage());
        }

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  6. API VALIDATION
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> validateApis() {
        Map<String, Object> result = new LinkedHashMap<>();
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) return Map.of("error", "Project root not found");

        List<Map<String, Object>> endpoints = new ArrayList<>();
        List<Map<String, Object>> missingExceptionHandling = new ArrayList<>();
        List<Map<String, Object>> missingValidation = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        List<Map<String, Object>> duplicateApis = new ArrayList<>();

        List<Path> controllerFiles = findSourceFiles(projectRoot.resolve("backend"), "*.java").stream()
            .filter(p -> p.toString().contains("Controller"))
            .toList();

        Pattern mappingPattern = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\((?:value\\s*=\\s*)?\"([^\"]*)\"\\)");
        Pattern requestMapping = Pattern.compile("@RequestMapping\\(\"([^\"]*)\"\\)");

        for (Path file : controllerFiles) {
            try {
                List<String> lines = Files.readAllLines(file);
                String content = String.join("\n", lines);
                String relPath = projectRoot.relativize(file).toString().replace('\\', '/');
                String className = file.getFileName().toString().replace(".java", "");

                String basePath = "";
                Matcher baseMatcher = requestMapping.matcher(content);
                if (baseMatcher.find()) basePath = baseMatcher.group(1);

                boolean hasExceptionHandler = content.contains("@ExceptionHandler") || content.contains("try {") || content.contains("try{");
                boolean hasValidation = content.contains("@Valid") || content.contains("@NotNull") || content.contains("@NotBlank");
                boolean hasAuth = content.contains("@AuthenticationPrincipal") || content.contains("@PreAuthorize");

                Matcher endpointMatcher = mappingPattern.matcher(content);
                int endpointCount = 0;
                while (endpointMatcher.find()) {
                    String method = endpointMatcher.group(1).toUpperCase();
                    String path = basePath + endpointMatcher.group(2);
                    endpointCount++;

                    Map<String, Object> ep = new LinkedHashMap<>();
                    ep.put("method", method);
                    ep.put("path", path);
                    ep.put("controller", className);
                    ep.put("file", relPath);
                    ep.put("has_auth", hasAuth);
                    endpoints.add(ep);

                    String key = method + ":" + path;
                    if (!paths.add(key)) {
                        duplicateApis.add(Map.of("method", method, "path", path, "controller", className));
                    }
                }

                if (endpointCount > 0 && !hasExceptionHandler) {
                    missingExceptionHandling.add(Map.of("controller", className, "file", relPath, "endpoints", endpointCount));
                }
                if (endpointCount > 0 && !hasValidation) {
                    missingValidation.add(Map.of("controller", className, "file", relPath));
                }
            } catch (IOException ignored) {}
        }

        result.put("total_endpoints", endpoints.size());
        result.put("endpoints", endpoints);
        result.put("duplicate_apis", duplicateApis);
        result.put("missing_exception_handling", missingExceptionHandling);
        result.put("missing_validation", missingValidation);
        result.put("controller_count", controllerFiles.size());

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  7. BUILD HEALTH
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> checkBuildHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) return Map.of("error", "Project root not found");

        // Check if build artifacts exist
        Path backendTarget = projectRoot.resolve("backend/target");
        Path frontendDist = projectRoot.resolve("frontend/dist");

        result.put("backend_target_exists", Files.exists(backendTarget));
        result.put("frontend_dist_exists", Files.exists(frontendDist));

        // Check pom.xml dependencies
        Path pomFile = projectRoot.resolve("backend/pom.xml");
        if (Files.exists(pomFile)) {
            try {
                String pomContent = Files.readString(pomFile);
                result.put("has_spring_boot", pomContent.contains("spring-boot-starter"));
                result.put("has_spring_security", pomContent.contains("spring-boot-starter-security"));
                result.put("has_jpa", pomContent.contains("spring-boot-starter-data-jpa"));
                result.put("has_postgresql", pomContent.contains("postgresql"));
                result.put("has_lombok", pomContent.contains("lombok"));
                result.put("has_swagger", pomContent.contains("springdoc") || pomContent.contains("swagger"));
            } catch (IOException ignored) {}
        }

        // Check package.json
        Path packageJson = projectRoot.resolve("frontend/package.json");
        if (Files.exists(packageJson)) {
            try {
                String pkgContent = Files.readString(packageJson);
                result.put("has_angular", pkgContent.contains("@angular/core"));
                result.put("has_tailwind", pkgContent.contains("tailwindcss"));
                result.put("has_typescript", pkgContent.contains("typescript"));
            } catch (IOException ignored) {}
        }

        // Check for compilation markers
        Path classesDir = projectRoot.resolve("backend/target/classes");
        if (Files.exists(classesDir)) {
            try (var s = Files.walk(classesDir)) {
                long classCount = s.filter(p -> p.toString().endsWith(".class")).count();
                result.put("compiled_classes", classCount);
                result.put("backend_build_status", classCount > 0 ? "Compiled" : "Not Compiled");
            } catch (IOException ignored) {
                result.put("backend_build_status", "Unknown");
            }
        } else {
            result.put("backend_build_status", "Not Built");
        }

        // Check for test reports
        Path surefireDir = projectRoot.resolve("backend/target/surefire-reports");
        result.put("test_reports_exist", Files.exists(surefireDir));

        // Application config check
        Path appYml = projectRoot.resolve("backend/src/main/resources/application.yml");
        if (Files.exists(appYml)) {
            try {
                String ymlContent = Files.readString(appYml);
                result.put("has_db_config", ymlContent.contains("datasource"));
                result.put("has_jwt_config", ymlContent.contains("jwt"));
                result.put("has_cors_config", ymlContent.contains("cors"));
            } catch (IOException ignored) {}
        }

        result.put("build_timestamp", Files.exists(backendTarget) ? getModTime(backendTarget) : "Never");

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  8. FEATURE COMPLETION
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> detectFeatureCompletion() {
        Map<String, Object> result = new LinkedHashMap<>();
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) return Map.of("error", "Project root not found");

        Map<String, String[]> features = new LinkedHashMap<>();
        features.put("Stock Upload & Tracker", new String[]{"StockTrackerController.java", "StockTrackerService.java", "stock-tracker.component.ts"});
        features.put("Market Breadth Dashboard", new String[]{"market-breadth.component.ts"});
        features.put("Stock Ranking Engine", new String[]{"StockRankingController.java", "stock-ranking.component.ts"});
        features.put("Trading Dashboard", new String[]{"TradingController.java", "trading.component.ts"});
        features.put("Paper Trading / Portfolio", new String[]{"PaperTradingController.java", "paper-trading.component.ts"});
        features.put("Strategy Builder", new String[]{"StrategyBuilderController.java", "strategy-hub.component.ts"});
        features.put("Backtesting", new String[]{"backtesting.component.ts"});
        features.put("Watchlist", new String[]{"WatchlistController.java", "watchlist.component.ts"});
        features.put("Options Chain", new String[]{"options.component.ts"});
        features.put("Data Warehouse", new String[]{"DataController.java", "data-management.component.ts"});
        features.put("Dhan Broker Integration", new String[]{"BrokerController.java", "DhanBrokerService.java", "broker.component.ts"});
        features.put("Dhan Historical Backfill", new String[]{"DhanHistoricalBackfillService.java"});
        features.put("Sector Intelligence", new String[]{"SectorIntelligenceController.java", "sector-intelligence.component.ts"});
        features.put("Teaching / LMS", new String[]{"TeachingController.java", "teaching-dashboard.component.ts"});
        features.put("Institution Management", new String[]{"InstitutionManagementController.java", "institution-mgmt.component.ts"});
        features.put("User Hierarchy", new String[]{"UserHierarchyController.java", "user-hierarchy.component.ts"});
        features.put("Reports Module", new String[]{"reports.component.ts"});
        features.put("Export Module", new String[]{"StockTrackerController.java"});
        features.put("Events Calendar", new String[]{"EventCalendarController.java", "events.component.ts"});
        features.put("Competitions", new String[]{"competitions.component.ts"});
        features.put("Trade Journal", new String[]{"TradeJournalController.java", "journal.component.ts"});
        features.put("Billing & Pricing", new String[]{"SubscriptionController.java", "pricing.component.ts", "billing.component.ts"});
        features.put("User Management (Admin)", new String[]{"UsersController.java", "users.component.ts"});
        features.put("Admin Feature Config", new String[]{"AdminConfigController.java", "admin-feature-config.component.ts"});
        features.put("Notification Engine", new String[]{"NotificationChannelService.java", "notification-settings.component.ts"});
        features.put("ML Engine", new String[]{"ml-engine.component.ts"});
        features.put("Communication", new String[]{"communication.component.ts"});
        features.put("Feedback & Issues", new String[]{"feedback.component.ts", "issues.component.ts"});

        List<Map<String, Object>> featureList = new ArrayList<>();
        int completed = 0, inProgress = 0, partial = 0, notStarted = 0;

        for (var entry : features.entrySet()) {
            String featureName = entry.getKey();
            String[] requiredFiles = entry.getValue();
            int found = 0;
            int totalLines = 0;
            List<String> missingFiles = new ArrayList<>();

            for (String fileName : requiredFiles) {
                List<Path> matches = findFileByName(projectRoot, fileName);
                if (!matches.isEmpty()) {
                    found++;
                    try {
                        totalLines += (int) Files.lines(matches.get(0)).count();
                    } catch (IOException ignored) {}
                } else {
                    missingFiles.add(fileName);
                }
            }

            String status;
            if (found == requiredFiles.length && totalLines > 100) {
                status = "Completed";
                completed++;
            } else if (found == requiredFiles.length && totalLines <= 100) {
                status = "Partially Implemented";
                partial++;
            } else if (found > 0) {
                status = "In Progress";
                inProgress++;
            } else {
                status = "Not Started";
                notStarted++;
            }

            Map<String, Object> feat = new LinkedHashMap<>();
            feat.put("feature", featureName);
            feat.put("status", status);
            feat.put("files_found", found);
            feat.put("files_required", requiredFiles.length);
            feat.put("total_lines", totalLines);
            feat.put("missing_files", missingFiles);
            featureList.add(feat);
        }

        result.put("features", featureList);
        result.put("completed", completed);
        result.put("in_progress", inProgress);
        result.put("partially_implemented", partial);
        result.put("not_started", notStarted);
        result.put("total_features", features.size());
        result.put("completion_percentage", Math.round(completed * 100.0 / features.size()));

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  9. DEPLOYMENT READINESS
    // ════════════════════════════════════════════════════════════════════════════

    public Map<String, Object> assessDeploymentReadiness() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> git = analyzeGitRepository();
        Map<String, Object> pending = detectPendingWork();
        Map<String, Object> build = checkBuildHealth();
        Map<String, Object> security = reviewSecurity();

        List<Map<String, Object>> blockers = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();

        // Check blockers
        if (Boolean.TRUE.equals(git.get("has_conflicts"))) {
            blockers.add(Map.of("type", "Merge Conflicts", "severity", "Blocker", "message", "Merge conflicts must be resolved before deployment"));
        }

        int securityCritical = (int) security.getOrDefault("critical_issues", 0);
        if (securityCritical > 0) {
            blockers.add(Map.of("type", "Security", "severity", "Blocker", "message", securityCritical + " critical security issues found"));
        }

        if ("Not Built".equals(build.get("backend_build_status"))) {
            blockers.add(Map.of("type", "Build", "severity", "Blocker", "message", "Backend has not been built"));
        }

        // Check warnings
        int totalChanged = (int) git.getOrDefault("total_files_changed", 0);
        if (totalChanged > 30) {
            warnings.add(Map.of("type", "Large Changeset", "severity", "Warning", "message", totalChanged + " files changed — consider splitting into smaller commits"));
        }

        int pendingIssues = (int) pending.getOrDefault("total_pending_issues", 0);
        if (pendingIssues > 10) {
            warnings.add(Map.of("type", "Pending Work", "severity", "Warning", "message", pendingIssues + " TODO/FIXME/HACK items remain"));
        }

        int largeChanges = (int) git.getOrDefault("large_change_count", 0);
        if (largeChanges > 5) {
            warnings.add(Map.of("type", "Large Files", "severity", "Warning", "message", largeChanges + " files with 300+ changed lines"));
        }

        String deployStatus;
        if (!blockers.isEmpty()) {
            deployStatus = "Deployment Blocked";
        } else if (!warnings.isEmpty()) {
            deployStatus = "Needs Review";
        } else {
            deployStatus = "Ready for Production";
        }

        result.put("status", deployStatus);
        result.put("blockers", blockers);
        result.put("blocker_count", blockers.size());
        result.put("warnings", warnings);
        result.put("warning_count", warnings.size());
        result.put("files_uncommitted", totalChanged);
        result.put("pending_issues", pendingIssues);

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════════════

    private int calculateHealthScore(Map<String, Object> git, Map<String, Object> pending,
            Map<String, Object> quality, Map<String, Object> security,
            Map<String, Object> database, Map<String, Object> build) {
        int score = 100;

        int filesChanged = (int) git.getOrDefault("total_files_changed", 0);
        if (filesChanged > 50) score -= 15;
        else if (filesChanged > 30) score -= 10;
        else if (filesChanged > 10) score -= 5;

        if (Boolean.TRUE.equals(git.get("has_conflicts"))) score -= 20;

        int pendingIssues = (int) pending.getOrDefault("total_pending_issues", 0);
        score -= Math.min(20, pendingIssues);

        int securityIssues = (int) security.getOrDefault("total_security_issues", 0);
        score -= Math.min(25, securityIssues * 5);

        int missingTests = (int) quality.getOrDefault("missing_test_count", 0);
        score -= Math.min(15, missingTests);

        if ("Not Built".equals(build.get("backend_build_status"))) score -= 10;

        return Math.max(0, Math.min(100, score));
    }

    private List<Map<String, Object>> generateRecommendations(Map<String, Object> git,
            Map<String, Object> pending, Map<String, Object> quality, Map<String, Object> security) {
        List<Map<String, Object>> recs = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> largeChanges = (List<Map<String, Object>>) git.getOrDefault("large_changes", List.of());
        for (var lc : largeChanges) {
            int changed = (int) lc.get("total_changed");
            if (changed > 1000) {
                recs.add(recommendation("Split Large File", (String) lc.get("file"),
                    changed + " lines changed. Split into multiple focused commits.", "High"));
            }
        }

        int todoCount = (int) pending.getOrDefault("todo_count", 0);
        if (todoCount > 5) {
            recs.add(recommendation("Resolve TODOs", "Project-wide",
                todoCount + " TODO comments found. Review and complete or remove.", "Medium"));
        }

        int missingTests = (int) quality.getOrDefault("missing_test_count", 0);
        if (missingTests > 0) {
            recs.add(recommendation("Add Unit Tests", "Backend",
                missingTests + " controllers/services lack tests. Add test coverage.", "High"));
        }

        int secIssues = (int) security.getOrDefault("total_security_issues", 0);
        if (secIssues > 0) {
            recs.add(recommendation("Fix Security Issues", "Project-wide",
                secIssues + " potential security issues detected. Review before deployment.", "Critical"));
        }

        if (Boolean.TRUE.equals(git.get("has_conflicts"))) {
            recs.add(recommendation("Resolve Merge Conflicts", "Git",
                "Active merge conflicts must be resolved immediately.", "Critical"));
        }

        int emptyErr = (int) pending.getOrDefault("empty_catch_count", 0);
        if (emptyErr > 0) {
            recs.add(recommendation("Fix Empty Catch Blocks", "Project-wide",
                emptyErr + " empty catch blocks found. Add proper error handling.", "Medium"));
        }

        return recs;
    }

    private Map<String, Object> recommendation(String title, String scope, String detail, String priority) {
        return Map.of("title", title, "scope", scope, "detail", detail, "priority", priority);
    }

    private Map<String, Object> pendingItem(String file, int line, String content, String type, String status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("file", file);
        item.put("line", line);
        item.put("content", content.length() > 150 ? content.substring(0, 150) + "..." : content);
        item.put("type", type);
        item.put("status", status);
        return item;
    }

    private Map<String, Object> securityItem(String file, int line, String issue, String detail, String severity) {
        return Map.of("file", file, "line", line, "issue", issue, "detail", detail, "severity", severity);
    }

    private boolean containsPattern(String line, String keyword) {
        return line.toUpperCase().contains(keyword) && !line.trim().startsWith("*") && !line.trim().startsWith("/*");
    }

    private void analyzeFile(Path file, Path root, List<Map<String, Object>> largeFiles,
            List<Map<String, Object>> largeMethods, List<Map<String, Object>> longParamLists) {
        try {
            List<String> lines = Files.readAllLines(file);
            String relPath = root.relativize(file).toString().replace('\\', '/');
            int lineCount = lines.size();

            if (lineCount > 500) {
                largeFiles.add(Map.of("file", relPath, "lines", lineCount,
                    "risk", lineCount > 3000 ? "Critical" : lineCount > 1500 ? "High" : lineCount > 800 ? "Medium" : "Low"));
            }

            Pattern methodPattern = Pattern.compile("^\\s*(public|private|protected)\\s+.*\\((.*)\\)\\s*\\{?\\s*$");
            int methodStart = -1;
            String currentMethod = "";
            int braceCount = 0;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher m = methodPattern.matcher(line);
                if (m.find()) {
                    if (methodStart >= 0 && (i - methodStart) > 80) {
                        largeMethods.add(Map.of("file", relPath, "method", currentMethod,
                            "line", methodStart + 1, "lines", i - methodStart));
                    }
                    methodStart = i;
                    currentMethod = extractMethodName(line);

                    String params = m.group(2);
                    if (params != null) {
                        int paramCount = params.isBlank() ? 0 : params.split(",").length;
                        if (paramCount > 6) {
                            longParamLists.add(Map.of("file", relPath, "method", currentMethod,
                                "line", i + 1, "param_count", paramCount));
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private String extractMethodName(String line) {
        Pattern p = Pattern.compile("\\s+(\\w+)\\s*\\(");
        Matcher m = p.matcher(line);
        return m.find() ? m.group(1) : "unknown";
    }

    private Path findProjectRoot() {
        Path current = Paths.get(System.getProperty("user.dir"));
        for (int i = 0; i < 5; i++) {
            if (Files.exists(current.resolve(".git")) || Files.exists(current.resolve("backend"))) return current;
            current = current.getParent();
            if (current == null) break;
        }
        Path fallback = Paths.get("E:/Stock Market/Stock Market Application/TradeRakshak-Java");
        return Files.exists(fallback) ? fallback : null;
    }

    private List<Path> findSourceFiles(Path dir, String glob) {
        if (!Files.exists(dir)) return List.of();
        try (var s = Files.walk(dir)) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            return s.filter(Files::isRegularFile)
                .filter(p -> matcher.matches(p.getFileName()))
                .filter(p -> !p.toString().contains("node_modules") && !p.toString().contains("target") && !p.toString().contains(".git") && !p.toString().contains("dist"))
                .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<Path> findFileByName(Path dir, String fileName) {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals(fileName))
                .filter(p -> !p.toString().contains("node_modules") && !p.toString().contains("target"))
                .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private String runGit(Path dir, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return output;
        } catch (Exception e) {
            return "";
        }
    }

    private String mapGitStatus(String code) {
        if (code.contains("M")) return "modified";
        if (code.contains("A")) return "added";
        if (code.contains("D")) return "deleted";
        if (code.contains("R")) return "renamed";
        if (code.contains("?")) return "untracked";
        return "modified";
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private String getModTime(Path p) {
        try { return Files.getLastModifiedTime(p).toString(); } catch (IOException e) { return "Unknown"; }
    }

    private double getD(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
}
