package utils;

import java.util.ArrayList;

public class ValidationResult {
    private final boolean valid;
    private final ArrayList<String> messages;

    public ValidationResult(boolean valid, ArrayList<String> messages) {
        this.valid = valid;
        this.messages = messages;
    }

    public boolean isValid() {
        return valid;
    }

    public ArrayList<String> getMessages() {
        return messages;
    }
}
