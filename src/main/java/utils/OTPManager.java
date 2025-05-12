package utils;

import java.util.concurrent.ConcurrentHashMap;

public class OTPManager {

    // מפת אחסון קודי OTP לפי כתובת אימייל
    private final ConcurrentHashMap<String, OTP_Entry> otpEntries = new ConcurrentHashMap<>();

    // שיטה לשלוח OTP ולהוסיף את בקשת ה-OTP לרשימה
    public boolean requestOTP(String email) {

        // אם יש כבר בקשה פעילה של OTP עבור המייל, אין לשלוח עוד אחד עד שהיא תתפוגג או ייבדק מחדש
        OTP_Entry existingEntry = otpEntries.get(email);

        if (existingEntry != null) {
            if (existingEntry.isLocked()) {
                return false;
            }
            if (!existingEntry.isExpired()) {
                return false; // עדיין לא פג תוקף, לא שולחים שוב
            }
            otpEntries.remove(email); // הסרה של רשומה שפג תוקפה
        }
        // מייצר קוד OTP חדש
        String otp = EmailSender.generateOTP();

        // שולח את קוד ה-OTP למייל
        boolean sent = EmailSender.sendOTP(email, otp);

        if (sent) {
            // מעדכן את כמות השליחות עבור האימייל
            otpEntries.put(email, new OTP_Entry(email, otp));
        }
        return sent;
    }

    // מאמת את קוד ה-OTP שנשלח למשתמש
    public boolean isOtpValidate(String email, String otp) {

        OTP_Entry entry = otpEntries.get(email);

        if (entry == null) {
            return false;
        }
        if (entry.isExpired()) {
            otpEntries.remove(email);
            return false;
        }

        boolean valid = entry.isValid(otp);
        if (valid) {
            otpEntries.remove(email);
        }
        return valid;    }

    // שיטה לבדוק אם יש למשתמש בקשה פעילה של OTP
    public boolean isUserPending(String email) {
        OTP_Entry entry = otpEntries.get(email);
        if (entry == null) {
            return false;
        }
        if (entry.isExpired()) {
            otpEntries.remove(email);
            return false;
        }
        return !entry.isLocked();
    }

    // שיטה לנקות את בקשת ה-OTP לאחר שה-OTP אומת בהצלחה
    public void clearOtpRequest(String email) {
        otpEntries.remove(email);
    }

    // שיטה לנקות את כל הבקשות (למשל לאחר סיום כל תהליך)
    public void clearAllOtpRequests() {
        otpEntries.clear();
    }
}
