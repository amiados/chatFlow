package utils;

import java.time.LocalDateTime;

/**
 * מייצג רשומת OTP עבור אימייל: כולל קוד, תוקף, נעילה ומעקב כשלונות.
 */
public class OTP_Entry {
    private final String email;
    private final String otp;
    private final LocalDateTime expiredAt;
    private int failedAttempts = 0;
    private boolean used = false;
    private LocalDateTime lockedUntil = null;

    private static final int EXPIRE_MINUTES = 5;
    private static final int MAX_FAILED = 3;
    private static final int LOCK_MINUTES = 10;

    /**
     * יוצר OTP_Entry חדש וקובע תוקף.
     */
    public OTP_Entry(String email, String otp) {
        this.email = email;
        this.otp = otp;
        this.expiredAt = LocalDateTime.now().plusMinutes(EXPIRE_MINUTES);
    }

    /**
     * בודק אם הקוד פג תוקף.
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiredAt);
    }

    /**
     * בודק אם הניסיון נעול עקב כשלונות מרובים.
     */
    public boolean isLocked() {
        return lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
    }

    /**
     * מאמת OTP: מסיר entry אם תקין, נועל אם הגיע לסף כשלונות.
     */
    public synchronized boolean isValid(String otpToCheck) {
        if (isExpired() || isLocked() || used) return false;
        if (this.otp.equals(otpToCheck)) {
            used = true;
            return true;
        } else {
            failedAttempts++;
            if (failedAttempts >= MAX_FAILED) {
                lockedUntil = LocalDateTime.now().plusMinutes(LOCK_MINUTES);
            }
            return false;
        }
    }

    public String getEmail() { return email; }
    public int getFailedAttempts() { return failedAttempts; }
    public LocalDateTime getExpiredAt() { return expiredAt; }
    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public int getLockDurationMinutes() { return LOCK_MINUTES; }
}