package auth.contract;

import java.time.LocalDateTime;

/**
 * CONTRACT ONLY — the Session entity is owned by the SESSION/UI role.
 *
 * AuthenticationService only reads {@link #getSessionId()} and
 * {@link #getUserId()}; the remaining fields exist because the class diagram
 * defines them and the SESSION/UI implementation's session handling will
 * need them.
 */
public class Session {

    private final String sessionId;
    private final String userId;
    private final LocalDateTime loginTime;
    private LocalDateTime lastActivity;
    private boolean active;

    public Session(String sessionId, String userId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.loginTime = LocalDateTime.now();
        this.lastActivity = loginTime;
        this.active = true;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public LocalDateTime getLoginTime() {
        return loginTime;
    }

    public LocalDateTime getLastActivity() {
        return lastActivity;
    }

    public boolean isActive() {
        return active;
    }

    public void markInactive() {
        this.active = false;
    }

    public void touch() {
        this.lastActivity = LocalDateTime.now();
    }
}
