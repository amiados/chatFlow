package utils;

import java.time.LocalDateTime;

public class OTP_Entry {

    private final String email;
    private final String otp;
    private final LocalDateTime expiredAt;
    private int failedAttempts = 0;
    private boolean used = false;
    private LocalDateTime lockedUntil = null;

    private static final int minutesToExpire = 5;
    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final int LOCK_DURATION_MINUTES = 10;

    public OTP_Entry(String email, String otp){
        this.email = email;
        this.otp = otp;
        this.expiredAt = LocalDateTime.now().plusMinutes(minutesToExpire);
    }

    public boolean isExpired(){
        return LocalDateTime.now().isAfter(expiredAt);
    }
    public boolean isLocked(){
        return lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
    }
    public synchronized boolean isValid(String otpToCheck){
        if(isExpired() || isLocked() || used){
            return false;
        }
        if(this.otp.equals(otpToCheck)){
            used = true;
            return true;
        } else {
            failedAttempts++;
            if(failedAttempts >= MAX_FAILED_ATTEMPTS){
                lockedUntil = LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES);
            }
            return false;
        }
    }

    // -- getters & setters --
    public String getEmail(){
        return email;
    }
    public int getFailedAttempts() {
        return failedAttempts;
    }
    public LocalDateTime getExpiredAt() {
        return expiredAt;
    }
    public LocalDateTime getLockedUntil() {return lockedUntil;}

    public int getLockDurationMinutes() {
        return LOCK_DURATION_MINUTES;
    }
}
