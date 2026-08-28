package com.traderakshak.config;

import com.traderakshak.entity.User;
import com.traderakshak.repository.UserRepository;
import com.traderakshak.util.DateUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds BCrypt-hashed passwords for all hierarchy demo users on first boot.
 * BRD §17 L4: passwords NEVER stored plaintext.
 */
@Component @RequiredArgsConstructor @Slf4j
public class DataInitializer implements ApplicationRunner {

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    @PersistenceContext private EntityManager em;

    // {email, name, password, userType, level, plan, segments}
    private static final List<Object[]> SEED_USERS = List.of(
        new Object[]{"superadmin@traderakshak.local","Super Admin","Admin@123",
                     "super_admin","super_admin","Elite","ALL"},
        new Object[]{"admin@algo.local","Platform Admin","Admin@123",
                     "platform_admin","platform_admin","Elite","ALL"},
        new Object[]{"l1admin@demo.local","L1 Admin","Admin@123",
                     "institution_admin","level_1","Pro","EQ-INT,EQ-SWG,EQ-POS"},
        new Object[]{"l2user@demo.local","L2 User","User@123",
                     "level_2","level_2","Pro","EQ-INT,EQ-SWG"},
        new Object[]{"l3viewer@demo.local","L3 Viewer","User@123",
                     "level_3","level_3","Starter","EQ-INT"},
        // New hierarchy users seeded by V4 migration
        new Object[]{"compliance@demo.local","Compliance Officer","User@123",
                     "compliance_officer","compliance_officer","Elite","ALL"},
        new Object[]{"analyst@demo.local","Research Analyst","User@123",
                     "research_analyst","research_analyst","Elite","ALL"},
        new Object[]{"support@demo.local","Support Executive","User@123",
                     "support_executive","support_executive","Starter","EQ-INT,EQ-SWG"},
        new Object[]{"trainer@demo.local","Trainer User","User@123",
                     "trainer","trainer","Pro","EQ-INT,EQ-SWG"},
        new Object[]{"salesmanager@demo.local","Sales Manager","User@123",
                     "sales_manager","sales_manager","Pro","EQ-INT,EQ-SWG"},
        new Object[]{"agentl2@demo.local","Agent L2 Demo","User@123",
                     "agent_l2","agent_l2","Starter","EQ-INT,EQ-SWG"},

        new Object[]{"academy@sta.demo","Academy Admin","Admin@123",
                     "institution_admin","level_1","Institutional","EQ-INT,EQ-SWG,EQ-POS"},
        new Object[]{"trainer@sta.demo","Rahul Sharma (Trainer)","User@123",
                     "trainer","trainer","Pro","EQ-INT,EQ-SWG,EQ-POS,FUT"},
        new Object[]{"student1@sta.demo","Priya Patel","User@123",
                     "paid_subscriber","level_2","Starter","EQ-INT,EQ-SWG"},
        new Object[]{"student2@sta.demo","Amit Kumar","User@123",
                     "paid_subscriber","level_2","Starter","EQ-INT,EQ-SWG"},
        new Object[]{"student3@sta.demo","Sneha Gupta","User@123",
                     "paid_subscriber","level_2","Pro","EQ-INT,EQ-SWG,EQ-POS"},
        new Object[]{"subscriber@demo.local","Paid Subscriber","User@123",
                     "paid_subscriber","level_2","Pro","EQ-INT,EQ-SWG"}
    );

    @Override @Transactional
    public void run(ApplicationArguments args) {
        log.info("Initialising seed users with BCrypt passwords...");
        for (Object[] row : SEED_USERS) {
            String email=(String)row[0], name=(String)row[1], pass=(String)row[2];
            String utype=(String)row[3], level=(String)row[4], plan=(String)row[5], segs=(String)row[6];
            repo.findByEmail(email).ifPresentOrElse(u -> {
                if (u.getPassword().startsWith("$PLACEHOLDER") || !u.getPassword().startsWith("$2")) {
                    u.setPassword(encoder.encode(pass));
                    u.setUserType(utype); u.setRole(utype);
                    u.setUpdatedAt(DateUtil.now());
                    repo.save(u);
                    log.info("Password hashed + userType set for: {}", email);
                }
            }, () -> {
                long seq = repo.countAll() + 1;
                String tid = "TR-" + DateUtil.currentYear() + "-" + String.format("%06d", seq);
                repo.save(User.builder()
                    .trUserId(tid).institutionId(1L).name(name).email(email)
                    .password(encoder.encode(pass)).role(utype).userType(utype)
                    .level(level).plan(plan).segments(segs).status("active")
                    .mobile("9999999999").createdAt(DateUtil.now()).updatedAt(DateUtil.now()).build());
                log.info("Seed user created: {} ({})", tid, email);
            });
        }
        seedDataWarehousePermissions();
        log.info("All seed users initialised.");
    }

    private void seedDataWarehousePermissions() {
        try {
            em.createNativeQuery("""
                INSERT INTO permissions(user_type, module, can_view, can_create, can_edit, can_delete, can_approve, can_export) VALUES
                ('super_admin', 'data_import', 1, 1, 1, 1, 1, 1),
                ('super_admin', 'data_warehouse', 1, 1, 1, 1, 1, 1),
                ('platform_admin', 'data_import', 1, 1, 1, 0, 1, 1),
                ('platform_admin', 'data_warehouse', 1, 1, 1, 0, 1, 1),
                ('admin', 'data_import', 1, 1, 1, 0, 0, 1),
                ('admin', 'data_warehouse', 1, 1, 1, 0, 0, 1),
                ('institution_admin', 'data_import', 1, 1, 0, 0, 0, 1),
                ('institution_admin', 'data_warehouse', 1, 1, 0, 0, 0, 1),
                ('research_analyst', 'data_import', 1, 1, 0, 0, 0, 1),
                ('research_analyst', 'data_warehouse', 1, 1, 0, 0, 0, 1)
                ON CONFLICT (user_type, module) DO UPDATE SET
                    can_view = EXCLUDED.can_view,
                    can_create = EXCLUDED.can_create,
                    can_edit = EXCLUDED.can_edit,
                    can_delete = EXCLUDED.can_delete,
                    can_approve = EXCLUDED.can_approve,
                    can_export = EXCLUDED.can_export
                """).executeUpdate();
            log.info("Data Warehouse permissions verified.");
        } catch (Exception e) {
            log.warn("Data Warehouse permission seed skipped: {}", e.getMessage());
        }
    }
}
// Note: Academy demo users (academy@sta.demo, trainer@sta.demo, student*@sta.demo)
// are seeded by V6 migration. Their passwords are hashed by this initializer
// because SEED_USERS list above includes them if added, otherwise they start
// with $PLACEHOLDER_BCRYPT$ and get fixed on first run via the loop above.
