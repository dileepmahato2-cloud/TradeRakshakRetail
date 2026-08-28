package com.traderakshak.service;

// =============================================================================
// NotificationChannelService
// Manages each user's notification channel preferences:
//   Email, SMS (MSG91), Telegram, WhatsApp, In-App
//
// Users configure channels on-screen; this service:
//  1. Saves / updates channel preferences
//  2. Sends test messages per channel
//  3. Dispatches notifications through all enabled channels
//  4. Logs every delivery attempt
// =============================================================================

import com.traderakshak.config.AppProperties;
import com.traderakshak.entity.User;
import com.traderakshak.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationChannelService {

    @PersistenceContext private EntityManager em;
    private final JavaMailSender              mailSender;
    private final BlockingHttpService         http;
    private final AppProperties               props;

    // ── Get user's channel settings ───────────────────────────────────────────

    public Map<String, Object> getUserChannels(Long userId, User user) {
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT * FROM user_notification_channels WHERE user_id=:uid")
                .setParameter("uid", userId).getSingleResult();
            return rowToMap(row, user);
        } catch (Exception e) {
            // Return defaults using user's account email + mobile
            return buildDefaults(user);
        }
    }

    // ── Save / update channel preferences ────────────────────────────────────

    @Transactional
    public Map<String, Object> saveChannels(User user, Map<String, Object> req) {
        // Ensure email defaults to account email
        String email = getString(req, "emailAddress", user.getEmail());
        String mobile = getString(req, "smsMobile", user.getMobile() != null ? user.getMobile() : "");

        // Validate Telegram: just store — verification done by test message
        String telegramChatId = getString(req, "telegramChatId", "");
        String whatsappNumber = getString(req, "whatsappNumber", "");

        // Upsert
        em.createNativeQuery(
            "INSERT INTO user_notification_channels(" +
            "user_id, institution_id, " +
            "email_enabled, email_address, " +
            "sms_enabled, sms_mobile, " +
            "telegram_enabled, telegram_chat_id, " +
            "whatsapp_enabled, whatsapp_number, whatsapp_opt_in, " +
            "in_app_enabled, " +
            "notify_alerts_email, notify_alerts_sms, notify_alerts_telegram, notify_alerts_whatsapp, " +
            "notify_trades_email, notify_trades_sms, notify_trades_telegram, notify_trades_whatsapp, " +
            "notify_account_email, notify_account_sms, notify_account_telegram, notify_account_whatsapp, " +
            "notify_competition_email, notify_competition_sms, " +
            "notify_lms_email, notify_lms_sms, " +
            "quiet_hours_enabled, quiet_start_time, quiet_end_time, " +
            "updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT (user_id) DO UPDATE SET " +
            "institution_id=EXCLUDED.institution_id, email_enabled=EXCLUDED.email_enabled, email_address=EXCLUDED.email_address, " +
            "sms_enabled=EXCLUDED.sms_enabled, sms_mobile=EXCLUDED.sms_mobile, telegram_enabled=EXCLUDED.telegram_enabled, " +
            "telegram_chat_id=EXCLUDED.telegram_chat_id, whatsapp_enabled=EXCLUDED.whatsapp_enabled, whatsapp_number=EXCLUDED.whatsapp_number, " +
            "whatsapp_opt_in=EXCLUDED.whatsapp_opt_in, in_app_enabled=EXCLUDED.in_app_enabled, notify_alerts_email=EXCLUDED.notify_alerts_email, " +
            "notify_alerts_sms=EXCLUDED.notify_alerts_sms, notify_alerts_telegram=EXCLUDED.notify_alerts_telegram, " +
            "notify_alerts_whatsapp=EXCLUDED.notify_alerts_whatsapp, notify_trades_email=EXCLUDED.notify_trades_email, " +
            "notify_trades_sms=EXCLUDED.notify_trades_sms, notify_trades_telegram=EXCLUDED.notify_trades_telegram, " +
            "notify_trades_whatsapp=EXCLUDED.notify_trades_whatsapp, notify_account_email=EXCLUDED.notify_account_email, " +
            "notify_account_sms=EXCLUDED.notify_account_sms, notify_account_telegram=EXCLUDED.notify_account_telegram, " +
            "notify_account_whatsapp=EXCLUDED.notify_account_whatsapp, notify_competition_email=EXCLUDED.notify_competition_email, " +
            "notify_competition_sms=EXCLUDED.notify_competition_sms, notify_lms_email=EXCLUDED.notify_lms_email, " +
            "notify_lms_sms=EXCLUDED.notify_lms_sms, quiet_hours_enabled=EXCLUDED.quiet_hours_enabled, " +
            "quiet_start_time=EXCLUDED.quiet_start_time, quiet_end_time=EXCLUDED.quiet_end_time, updated_at=EXCLUDED.updated_at")
            .setParameter(1,  user.getId())
            .setParameter(2,  user.getInstitutionId())
            .setParameter(3,  getInt(req, "emailEnabled", 1))
            .setParameter(4,  email)
            .setParameter(5,  getInt(req, "smsEnabled", 0))
            .setParameter(6,  mobile)
            .setParameter(7,  getInt(req, "telegramEnabled", 0))
            .setParameter(8,  telegramChatId)
            .setParameter(9,  getInt(req, "whatsappEnabled", 0))
            .setParameter(10, whatsappNumber)
            .setParameter(11, getInt(req, "whatsappOptIn", 0))
            .setParameter(12, 1)  // in_app always on
            .setParameter(13, getInt(req, "notifyAlertsEmail",     1))
            .setParameter(14, getInt(req, "notifyAlertsSms",       0))
            .setParameter(15, getInt(req, "notifyAlertsTelegram",  0))
            .setParameter(16, getInt(req, "notifyAlertsWhatsapp",  0))
            .setParameter(17, getInt(req, "notifyTradesEmail",     1))
            .setParameter(18, getInt(req, "notifyTradesSms",       0))
            .setParameter(19, getInt(req, "notifyTradesTelegram",  0))
            .setParameter(20, getInt(req, "notifyTradesWhatsapp",  0))
            .setParameter(21, getInt(req, "notifyAccountEmail",    1))
            .setParameter(22, getInt(req, "notifyAccountSms",      1))
            .setParameter(23, getInt(req, "notifyAccountTelegram", 0))
            .setParameter(24, getInt(req, "notifyAccountWhatsapp", 0))
            .setParameter(25, getInt(req, "notifyCompetitionEmail",1))
            .setParameter(26, getInt(req, "notifyCompetitionSms",  0))
            .setParameter(27, getInt(req, "notifyLmsEmail",        1))
            .setParameter(28, getInt(req, "notifyLmsSms",          0))
            .setParameter(29, getInt(req, "quietHoursEnabled",     0))
            .setParameter(30, getString(req, "quietStartTime", "22:00"))
            .setParameter(31, getString(req, "quietEndTime",   "07:00"))
            .setParameter(32, DateUtil.now())
            .executeUpdate();

        log.info("Notification channels saved for user {}", user.getTrUserId());
        return getUserChannels(user.getId(), user);
    }

    // ── Send test message to a specific channel ───────────────────────────────

    public Map<String, Object> testChannel(User user, String channel) {
        String msg = "🔔 Test notification from TradeRakshak™\n" +
                     "User: " + user.getName() + " (" + user.getTrUserId() + ")\n" +
                     "Time: " + DateUtil.now() + "\n" +
                     "⚠ TradeRakshak™ is NOT a SEBI-registered Investment Advisor.";

        boolean sent = false;
        String detail = "";

        Map<String, Object> channels = getUserChannels(user.getId(), user);

        switch (channel.toLowerCase()) {
            case "email" -> {
                String addr = getString(channels, "emailAddress", user.getEmail());
                sent = sendEmail(addr, "TradeRakshak™ — Test Notification", msg);
                detail = "Sent to: " + addr;
                if (sent) updateTestTimestamp(user.getId(), "email");
            }
            case "sms" -> {
                String mobile = getString(channels, "smsMobile", "");
                if (mobile.isEmpty()) { detail = "No mobile number configured."; break; }
                sent = sendSms(mobile, msg);
                detail = "Sent to: +" + mobile;
                if (sent) updateTestTimestamp(user.getId(), "sms");
            }
            case "telegram" -> {
                String chatId = getString(channels, "telegramChatId", "");
                if (chatId.isEmpty()) {
                    detail = "Telegram Chat ID not configured. " +
                             "Message our bot @TradeRakshakBot with /start to get your Chat ID.";
                    break;
                }
                sent = sendTelegram(chatId, msg);
                detail = sent ? "Sent to chat ID: " + chatId
                    : "Failed. Ensure you have started @TradeRakshakBot and entered the correct Chat ID.";
                if (sent) {
                    updateTestTimestamp(user.getId(), "telegram");
                    // Mark telegram verified
                    em.createNativeQuery("UPDATE user_notification_channels SET telegram_verified=1 WHERE user_id=:uid")
                        .setParameter("uid", user.getId()).executeUpdate();
                }
            }
            case "whatsapp" -> {
                String phone = getString(channels, "whatsappNumber", "");
                if (phone.isEmpty()) { detail = "WhatsApp number not configured."; break; }
                int optIn = getIntFromMap(channels, "whatsappOptIn", 0);
                if (optIn == 0) { detail = "WhatsApp opt-in required. Enable the WhatsApp checkbox first."; break; }
                sent = sendWhatsApp(phone, msg);
                detail = sent ? "Sent to: " + phone : "Failed. Check WhatsApp number format (+91XXXXXXXXXX).";
                if (sent) updateTestTimestamp(user.getId(), "whatsapp");
            }
            default -> detail = "Unknown channel: " + channel;
        }

        logDelivery(user.getId(), channel, "test", "Test Notification", msg,
                    channel.equals("email") ? user.getEmail() : "", sent ? "sent" : "failed", detail);

        return Map.of("channel", channel, "sent", sent, "detail", detail, "testedAt", DateUtil.now());
    }

    // ── Dispatch notification through all configured + subscribed channels ────

    @Async
    public void dispatch(Long userId, String notificationType, String subject, String body) {
        try {
            Object[] row = (Object[]) em.createNativeQuery(
                "SELECT unc.*, u.email, u.mobile, u.name FROM user_notification_channels unc " +
                "JOIN users u ON u.id=unc.user_id WHERE unc.user_id=:uid")
                .setParameter("uid", userId).getSingleResult();

            // Check quiet hours
            if (isQuietHours(row)) {
                log.debug("Notification suppressed (quiet hours) for user {}", userId);
                return;
            }

            // Determine column indices for notification type flags
            // Columns: 0=id,1=user_id,2=inst_id,3=email_en,4=email_addr,5=email_verified...
            // 13–28 = notify_* flags (in order as inserted)
            // user email/mobile at end

            String email   = row[row.length - 3] != null ? row[row.length - 3].toString() : "";
            String mobile  = row[row.length - 2] != null ? row[row.length - 2].toString() : "";
            String emailAddr = row[4] != null && !row[4].toString().isEmpty() ? row[4].toString() : email;

            // Email
            if (isEnabled(row, 3) && isTypeEnabled(row, notificationType, "email")) {
                boolean ok = sendEmail(emailAddr, subject, body);
                logDelivery(userId, "email", notificationType, subject, body, emailAddr,
                            ok ? "sent" : "failed", "");
            }

            // SMS
            if (isEnabled(row, 5) && !mobile.isEmpty() && isTypeEnabled(row, notificationType, "sms")) {
                String smsNum = row[6] != null && !row[6].toString().isEmpty() ? row[6].toString() : mobile;
                boolean ok = sendSms(smsNum, subject + ": " + body.substring(0, Math.min(body.length(), 160)));
                logDelivery(userId, "sms", notificationType, subject, body, smsNum,
                            ok ? "sent" : "failed", "");
            }

            // Telegram
            if (isEnabled(row, 7) && row[8] != null && !row[8].toString().isEmpty()
                    && isTypeEnabled(row, notificationType, "telegram")) {
                String chatId = row[8].toString();
                boolean ok = sendTelegram(chatId, "<b>" + subject + "</b>\n\n" + body);
                logDelivery(userId, "telegram", notificationType, subject, body, chatId,
                            ok ? "sent" : "failed", "");
            }

            // WhatsApp
            if (isEnabled(row, 9) && isEnabled(row, 11) && row[10] != null
                    && !row[10].toString().isEmpty()
                    && isTypeEnabled(row, notificationType, "whatsapp")) {
                String phone = row[10].toString();
                boolean ok = sendWhatsApp(phone, subject + "\n" + body);
                logDelivery(userId, "whatsapp", notificationType, subject, body, phone,
                            ok ? "sent" : "failed", "");
            }
        } catch (Exception e) {
            log.debug("Notification dispatch error for user {}: {}", userId, e.getMessage());
        }
    }

    // ── Actual channel senders ────────────────────────────────────────────────

    public boolean sendEmail(String to, String subject, String body) {
        if (to == null || to.isBlank()) return false;
        try {
            String smtpHost = sysSetting("smtp_host");
            if (smtpHost.isEmpty()) {
                log.debug("SMTP not configured — email not sent to {}", to);
                return false;
            }
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(to); msg.setSubject(subject); msg.setText(body + "\n\n" +
                "---\n⚠ TradeRakshak™ is NOT a SEBI-registered Investment Advisor. For educational use only.");
            String fromEmail = sysSetting("smtp_from_email");
            String smtpUser  = sysSetting("smtp_user");
            msg.setFrom(fromEmail.isEmpty() ? smtpUser : fromEmail);
            mailSender.send(msg);
            return true;
        } catch (Exception e) {
            log.warn("Email failed to {}: {}", to, e.getMessage());
            return false;
        }
    }

    public boolean sendSms(String mobile, String message) {
        String apiKey = sysSetting("msg91_api_key");
        if (apiKey.isEmpty()) { log.debug("MSG91 not configured"); return false; }
        try {
            String senderId = sysSetting("msg91_sender_id");
            if (senderId.isEmpty()) senderId = "TRDRK";
            http.postJson("https://api.msg91.com/api/v5/flow",
                Map.of("authkey", apiKey),
                Map.of(
                    "template_id", sysSetting("msg91_otp_template_id"),
                    "short_url", "0",
                    "mobiles", "91" + mobile.replaceAll("[^0-9]", ""),
                    "var1", message.substring(0, Math.min(message.length(), 100))
                ),
                10000);
            return true;
        } catch (Exception e) {
            log.warn("SMS failed to {}: {}", mobile, e.getMessage());
            return false;
        }
    }

    public boolean sendTelegram(String chatId, String message) {
        // Use platform bot token from system_settings, not per-user token
        String token = sysSetting("telegram_bot_token");
        if (token.isEmpty()) { log.debug("Telegram bot not configured"); return false; }
        try {
            http.postJson("https://api.telegram.org/bot" + token + "/sendMessage",
                Map.of(), Map.of("chat_id", chatId, "text", message, "parse_mode", "HTML"), 10000);
            return true;
        } catch (Exception e) {
            log.warn("Telegram failed to chatId {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    public boolean sendWhatsApp(String phone, String message) {
        // Twilio WhatsApp (Phase 2); for Phase 1 — log and return true as no-op
        String accountSid = sysSetting("twilio_account_sid");
        if (accountSid.isEmpty()) {
            log.debug("WhatsApp (Twilio) not configured — skipping");
            return false;
        }
        try {
            String authToken = sysSetting("twilio_auth_token");
            String from = sysSetting("twilio_whatsapp_from"); // e.g. whatsapp:+14155238886
            String to   = "whatsapp:" + phone;
            http.postFormBasic("https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json",
                accountSid, authToken,
                Map.of("From", from, "To", to, "Body", message),
                10000);
            return true;
        } catch (Exception e) {
            log.warn("WhatsApp failed to {}: {}", phone, e.getMessage());
            return false;
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private boolean isEnabled(Object[] row, int col) {
        return row[col] != null && ((Number) row[col]).intValue() == 1;
    }

    private boolean isTypeEnabled(Object[] row, String notifType, String channel) {
        // Map: notifType → (email_col, sms_col, telegram_col, whatsapp_col)
        // Based on column order in V11 schema
        java.util.HashMap<String, int[]> colMap = new java.util.HashMap<>();
        colMap.put("alert",       new int[]{13, 14, 15, 16});
        colMap.put("trade",       new int[]{17, 18, 19, 20});
        colMap.put("account",     new int[]{21, 22, 23, 24});
        colMap.put("competition", new int[]{25, 26, 25, 26});
        colMap.put("lms",         new int[]{27, 28, 27, 28});
        int[] cols = colMap.getOrDefault(notifType, new int[]{13, 14, 15, 16});
        int idx = switch (channel) {
            case "email"    -> cols[0];
            case "sms"      -> cols[1];
            case "telegram" -> cols[2];
            case "whatsapp" -> cols[3];
            default         -> cols[0];
        };
        return idx < row.length && row[idx] != null && ((Number) row[idx]).intValue() == 1;
    }

    private boolean isQuietHours(Object[] row) {
        try {
            if (row[29] == null || ((Number) row[29]).intValue() == 0) return false;
            String startStr = row[30] != null ? row[30].toString() : "22:00";
            String endStr   = row[31] != null ? row[31].toString() : "07:00";
            java.time.LocalTime now   = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
            java.time.LocalTime start = java.time.LocalTime.parse(startStr);
            java.time.LocalTime end   = java.time.LocalTime.parse(endStr);
            return start.isAfter(end)
                ? !now.isBefore(start) || now.isBefore(end)
                : !now.isBefore(start) && now.isBefore(end);
        } catch (Exception e) { return false; }
    }

    @Transactional
    private void updateTestTimestamp(Long userId, String channel) {
        String col = channel + "_last_test";
        try {
            em.createNativeQuery("UPDATE user_notification_channels SET " + col + "=:t WHERE user_id=:uid")
                .setParameter("t", DateUtil.now()).setParameter("uid", userId).executeUpdate();
        } catch (Exception ignored) {}
    }

    @Transactional
    private void logDelivery(Long userId, String channel, String type, String subject,
                              String body, String recipient, String status, String error) {
        try {
            em.createNativeQuery(
                "INSERT INTO notification_delivery_log(user_id,channel,notification_type," +
                "subject,message,recipient,status,error_message,sent_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")
                .setParameter(1,userId).setParameter(2,channel).setParameter(3,type)
                .setParameter(4,subject)
                .setParameter(5,body != null && body.length()>500?body.substring(0,500):body)
                .setParameter(6,recipient).setParameter(7,status)
                .setParameter(8,error.length()>255?error.substring(0,255):error)
                .setParameter(9,DateUtil.now()).setParameter(10,DateUtil.now())
                .executeUpdate();
        } catch (Exception e) { log.debug("Log delivery error: {}", e.getMessage()); }
    }

    private String sysSetting(String key) {
        try {
            Object r = em.createNativeQuery("SELECT value FROM system_settings WHERE key=:k")
                .setParameter("k", key).getSingleResult();
            return r != null ? r.toString() : "";
        } catch (Exception e) { return ""; }
    }

    private Map<String, Object> rowToMap(Object[] row, User user) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("emailEnabled",       row[3]  != null ? row[3]  : 1);
        m.put("emailAddress",       row[4]  != null ? row[4]  : user.getEmail());
        m.put("emailVerified",      row[5]  != null ? row[5]  : 0);
        m.put("smsEnabled",         row[6]  != null ? row[6]  : 0);
        m.put("smsMobile",          row[7]  != null ? row[7]  : (user.getMobile() != null ? user.getMobile() : ""));
        m.put("smsVerified",        row[8]  != null ? row[8]  : 0);
        m.put("telegramEnabled",    row[9]  != null ? row[9]  : 0);
        m.put("telegramChatId",     row[10] != null ? row[10] : "");
        m.put("telegramBotName",    row[11] != null ? row[11] : "@TradeRakshakBot");
        m.put("telegramVerified",   row[12] != null ? row[12] : 0);
        m.put("whatsappEnabled",    row[13] != null ? row[13] : 0);
        m.put("whatsappNumber",     row[14] != null ? row[14] : "");
        m.put("whatsappVerified",   row[15] != null ? row[15] : 0);
        m.put("whatsappOptIn",      row[16] != null ? row[16] : 0);
        m.put("inAppEnabled",       1);
        m.put("notifyAlertsEmail",     row[17] != null ? row[17] : 1);
        m.put("notifyAlertsSms",       row[18] != null ? row[18] : 0);
        m.put("notifyAlertsTelegram",  row[19] != null ? row[19] : 0);
        m.put("notifyAlertsWhatsapp",  row[20] != null ? row[20] : 0);
        m.put("notifyTradesEmail",     row[21] != null ? row[21] : 1);
        m.put("notifyTradesSms",       row[22] != null ? row[22] : 0);
        m.put("notifyAccountEmail",    row[25] != null ? row[25] : 1);
        m.put("notifyAccountSms",      row[26] != null ? row[26] : 1);
        m.put("quietHoursEnabled",  row[33] != null ? row[33] : 0);
        m.put("quietStartTime",     row[34] != null ? row[34] : "22:00");
        m.put("quietEndTime",       row[35] != null ? row[35] : "07:00");
        m.put("emailLastTest",      row.length > 36 && row[36] != null ? row[36] : "");
        m.put("smsLastTest",        row.length > 37 && row[37] != null ? row[37] : "");
        m.put("telegramLastTest",   row.length > 38 && row[38] != null ? row[38] : "");
        m.put("whatsappLastTest",   row.length > 39 && row[39] != null ? row[39] : "");
        return m;
    }

        private Map<String, Object> buildDefaults(User user) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("emailEnabled",       1);
        m.put("emailAddress",       user.getEmail() != null ? user.getEmail() : "");
        m.put("smsEnabled",         0);
        m.put("smsMobile",          user.getMobile() != null ? user.getMobile() : "");
        m.put("telegramEnabled",    0);
        m.put("telegramChatId",     "");
        m.put("telegramBotName",    "@TradeRakshakBot");
        m.put("telegramVerified",   0);
        m.put("whatsappEnabled",    0);
        m.put("whatsappNumber",     "");
        m.put("inAppEnabled",       1);
        m.put("notifyAlertsEmail",  1);
        m.put("notifyAlertsSms",    0);
        m.put("notifyTradesEmail",  1);
        m.put("notifyAccountEmail", 1);
        m.put("quietHoursEnabled",  0);
        m.put("quietStartTime",     "22:00");
        m.put("quietEndTime",       "07:00");
        return m;
    }

    private String  getString(Map<?,?> m, String k, String d) { Object v=m.get(k); return v==null?d:v.toString(); }
    private int     getInt(Map<?,?> m, String k, int d)       { Object v=m.get(k); return v==null?d:((Number)v).intValue(); }
    private int     getIntFromMap(Map<?,?> m, String k, int d) { return getInt(m, k, d); }
}
