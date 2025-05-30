package utils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * מנהל ביקושי OTP: אחסון, שליחה, אימות וניקוי OTP_Entry.
 */
public class OTPManager {
    private final ConcurrentHashMap<String, OTP_Entry> otpEntries = new ConcurrentHashMap<>();

    /**
     * מבקש OTP חדש אם אין אחד בתוקף או נשלח לפני.
     * @param email כתובת המייל
     * @return true אם נשלח בהצלחה
     */
    public boolean requestOTP(String email) {
        OTP_Entry existing = otpEntries.get(email);
        if (existing != null) {
            if (existing.isLocked() || !existing.isExpired()) {
                return false;
            }
            otpEntries.remove(email);
        }
        String otp = EmailSender.generateOTP();
        boolean sent = EmailSender.sendOTP(email, otp);
        if (sent) otpEntries.put(email, new OTP_Entry(email, otp));
        return sent;
    }

    /**
     * מאמת OTP שנשלח.
     */
    public boolean isOtpValidate(String email, String otp) {
        OTP_Entry entry = otpEntries.get(email);
        if (entry == null || entry.isExpired()) {
            otpEntries.remove(email);
            return false;
        }
        boolean valid = entry.isValid(otp);
        if (valid) otpEntries.remove(email);
        return valid;
    }

    /**
     * בודק אם למשתמש יש בקשה פעילה.
     */
    public boolean isUserPending(String email) {
        OTP_Entry entry = otpEntries.get(email);
        if (entry == null || entry.isExpired()) {
            otpEntries.remove(email);
            return false;
        }
        return !entry.isLocked();
    }

    /**
     * מסיר בקשת OTP ספציפית.
     */
    public void clearOtpRequest(String email) {
        otpEntries.remove(email);
    }

    /**
     * מנקה את כל בקשות ה-OTP.
     */
    public void clearAllOtpRequests() {
        otpEntries.clear();
    }
}