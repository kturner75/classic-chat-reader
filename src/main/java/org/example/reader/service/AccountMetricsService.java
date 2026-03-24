package org.example.reader.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Service
public class AccountMetricsService {

    private final LongAdder statusReads = new LongAdder();

    private final LongAdder registerRequests = new LongAdder();
    private final LongAdder registerSucceeded = new LongAdder();
    private final LongAdder registerFailed = new LongAdder();
    private final LongAdder registerRolloutBlocked = new LongAdder();
    private final LongAdder registerRateLimited = new LongAdder();

    private final LongAdder loginRequests = new LongAdder();
    private final LongAdder loginSucceeded = new LongAdder();
    private final LongAdder loginFailed = new LongAdder();
    private final LongAdder loginRolloutBlocked = new LongAdder();
    private final LongAdder loginRateLimited = new LongAdder();

    private final LongAdder googleLoginStarts = new LongAdder();
    private final LongAdder googleLoginSucceeded = new LongAdder();
    private final LongAdder googleLoginFailed = new LongAdder();
    private final LongAdder googleLoginRolloutBlocked = new LongAdder();

    private final LongAdder logoutRequests = new LongAdder();
    private final LongAdder logoutSucceeded = new LongAdder();

    private final LongAdder claimSyncRequests = new LongAdder();
    private final LongAdder claimSyncUnauthorized = new LongAdder();
    private final LongAdder claimSyncSucceeded = new LongAdder();
    private final LongAdder claimSyncApplied = new LongAdder();
    private final LongAdder claimSyncNoop = new LongAdder();
    private final LongAdder claimSyncFailed = new LongAdder();

    public void recordStatusRead() {
        statusReads.increment();
    }

    public void recordRegisterResult(AccountAuthService.ResultStatus status) {
        registerRequests.increment();
        if (status == AccountAuthService.ResultStatus.SUCCESS) {
            registerSucceeded.increment();
            return;
        }
        if (status == AccountAuthService.ResultStatus.ROLLOUT_RESTRICTED) {
            registerRolloutBlocked.increment();
            return;
        }
        registerFailed.increment();
    }

    public void recordRegisterRateLimited() {
        registerRequests.increment();
        registerRateLimited.increment();
    }

    public void recordLoginResult(AccountAuthService.ResultStatus status) {
        loginRequests.increment();
        if (status == AccountAuthService.ResultStatus.SUCCESS) {
            loginSucceeded.increment();
            return;
        }
        if (status == AccountAuthService.ResultStatus.ROLLOUT_RESTRICTED) {
            loginRolloutBlocked.increment();
            return;
        }
        loginFailed.increment();
    }

    public void recordLoginRateLimited() {
        loginRequests.increment();
        loginRateLimited.increment();
    }

    public void recordGoogleLoginStart() {
        googleLoginStarts.increment();
    }

    public void recordGoogleLoginResult(AccountAuthService.ResultStatus status) {
        if (status == AccountAuthService.ResultStatus.SUCCESS) {
            googleLoginSucceeded.increment();
            return;
        }
        if (status == AccountAuthService.ResultStatus.ROLLOUT_RESTRICTED) {
            googleLoginRolloutBlocked.increment();
            return;
        }
        googleLoginFailed.increment();
    }

    public void recordGoogleLoginFailure() {
        googleLoginFailed.increment();
    }

    public void recordLogoutResult(AccountAuthService.ResultStatus status) {
        logoutRequests.increment();
        if (status == AccountAuthService.ResultStatus.SUCCESS) {
            logoutSucceeded.increment();
        }
    }

    public void recordClaimSyncUnauthorized() {
        claimSyncRequests.increment();
        claimSyncUnauthorized.increment();
    }

    public void recordClaimSyncSuccess(boolean claimApplied) {
        claimSyncRequests.increment();
        claimSyncSucceeded.increment();
        if (claimApplied) {
            claimSyncApplied.increment();
        } else {
            claimSyncNoop.increment();
        }
    }

    public void recordClaimSyncFailure() {
        claimSyncRequests.increment();
        claimSyncFailed.increment();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("statusReads", statusReads.sum());
        metrics.put("registerRequests", registerRequests.sum());
        metrics.put("registerSucceeded", registerSucceeded.sum());
        metrics.put("registerFailed", registerFailed.sum());
        metrics.put("registerRolloutBlocked", registerRolloutBlocked.sum());
        metrics.put("registerRateLimited", registerRateLimited.sum());
        metrics.put("loginRequests", loginRequests.sum());
        metrics.put("loginSucceeded", loginSucceeded.sum());
        metrics.put("loginFailed", loginFailed.sum());
        metrics.put("loginRolloutBlocked", loginRolloutBlocked.sum());
        metrics.put("loginRateLimited", loginRateLimited.sum());
        metrics.put("googleLoginStarts", googleLoginStarts.sum());
        metrics.put("googleLoginSucceeded", googleLoginSucceeded.sum());
        metrics.put("googleLoginFailed", googleLoginFailed.sum());
        metrics.put("googleLoginRolloutBlocked", googleLoginRolloutBlocked.sum());
        metrics.put("logoutRequests", logoutRequests.sum());
        metrics.put("logoutSucceeded", logoutSucceeded.sum());
        metrics.put("claimSyncRequests", claimSyncRequests.sum());
        metrics.put("claimSyncUnauthorized", claimSyncUnauthorized.sum());
        metrics.put("claimSyncSucceeded", claimSyncSucceeded.sum());
        metrics.put("claimSyncApplied", claimSyncApplied.sum());
        metrics.put("claimSyncNoop", claimSyncNoop.sum());
        metrics.put("claimSyncFailed", claimSyncFailed.sum());
        return metrics;
    }
}
