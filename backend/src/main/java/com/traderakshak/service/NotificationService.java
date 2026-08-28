package com.traderakshak.service;

import com.traderakshak.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import com.traderakshak.util.DateUtil;

/** Port of Python send_telegram(), send_email(), send_admin_email() */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final AppProperties props;
    private final JavaMailSender mailSender;
    private final BlockingHttpService http;
    @PersistenceContext private EntityManager em;

    public boolean sendTelegram(String message) {
        String token  = sett("telegram_bot_token");
        String chatId = sett("telegram_chat_id");
        if (token.isEmpty() || chatId.isEmpty()) return false;
        try {
            http.postJson("https://api.telegram.org/bot" + token + "/sendMessage",
                Map.of(), Map.of("chat_id", chatId, "text", message, "parse_mode", "HTML"), 10000);
            return true;
        } catch (Exception e) { log.warn("Telegram failed: {}", e.getMessage()); return false; }
    }

    public boolean sendEmail(String to, String subject, String body) {
        String host = sett("smtp_host"), user = sett("smtp_user"), pass = sett("smtp_password");
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty() || to.isEmpty()) return false;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(to); msg.setSubject(subject); msg.setText(body);
            msg.setFrom(sett("smtp_from_email").isEmpty() ? user : sett("smtp_from_email"));
            mailSender.send(msg);
            logComm("email", to, subject, body);
            return true;
        } catch (Exception e) {
            log.warn("Email failed to {}: {}", to, e.getMessage());
            logComm("email", to, subject, "FAILED: " + e.getMessage());
            return false;
        }
    }

    public boolean sendAdminEmail(String subject, String body) {
        String adminEmail = sett("admin_alert_email");
        return !adminEmail.isEmpty() && sendEmail(adminEmail, subject, body);
    }

    private String sett(String key) {
        try {
            Object r = em.createNativeQuery("SELECT value FROM system_settings WHERE key=:k")
                .setParameter("k", key).getSingleResult();
            return r != null ? r.toString() : "";
        } catch (Exception e) { return ""; }
    }

    @Transactional
    private void logComm(String channel, String to, String subject, String body) {
        try {
            em.createNativeQuery("INSERT INTO communications(channel,to_address,subject,body,sent_at,status,created_at) VALUES(?,?,?,?,?,?,?)")
                .setParameter(1,channel).setParameter(2,to).setParameter(3,subject)
                .setParameter(4,body.length()>2000?body.substring(0,2000):body)
                .setParameter(5,DateUtil.now()).setParameter(6,"sent").setParameter(7,DateUtil.now())
                .executeUpdate();
        } catch (Exception e) { log.debug("logComm error: {}", e.getMessage()); }
    }
}
