package model;

public enum ChatRole {
    ADMIN,
    MEMBER;

    public static ChatRole fromString(String role) {
        return ChatRole.valueOf(role.toUpperCase());
    }

    @Override
    public String toString() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
