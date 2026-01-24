package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.auth.AuthErrors.AuthInvalidSignature;
import com.digitalasset.quickstart.auth.ChallengeStore;
import com.digitalasset.quickstart.auth.PartyValidationService;
import com.digitalasset.quickstart.auth.WalletJwtService;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.dto.auth.ChallengeRequest;
import com.digitalasset.quickstart.dto.auth.ChallengeResponse;
import com.digitalasset.quickstart.dto.auth.VerifyChallengeRequest;
import com.digitalasset.quickstart.dto.auth.VerifyChallengeResponse;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final PartyValidationService partyValidationService;
    private final ChallengeStore challengeStore;
    private final WalletJwtService walletJwtService;

    public AuthController(
            final PartyValidationService partyValidationService,
            final ChallengeStore challengeStore,
            final WalletJwtService walletJwtService
    ) {
        this.partyValidationService = partyValidationService;
        this.challengeStore = challengeStore;
        this.walletJwtService = walletJwtService;
    }

    @PostMapping("/challenge")
    @WithSpan
    public ResponseEntity<?> createChallenge(@RequestBody(required = false) final ChallengeRequest request) {
        Result<ChallengeResponse, DomainError> result = validateChallengeRequest(request)
                .flatMap(challengeStore::createChallenge)
                .map(this::toResponse);

        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return mapDomainErrorToResponse(result.getErrorUnsafe(), "/api/auth/challenge");
    }

    @PostMapping("/verify")
    @WithSpan
    public ResponseEntity<?> verifyChallenge(@RequestBody(required = false) final VerifyChallengeRequest request) {
        Result<VerifyChallengeResponse, DomainError> result = validateVerifyRequest(request)
                .flatMap(this::loadChallenge)
                .flatMap(this::issueTokenAndFinalize);

        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return mapDomainErrorToResponse(result.getErrorUnsafe(), "/api/auth/verify");
    }

    private Result<String, DomainError> validateChallengeRequest(final ChallengeRequest request) {
        if (request == null) {
            return Result.err(new ValidationError("Request body is required", ValidationError.Type.REQUEST));
        }
        return partyValidationService.normalize(request.partyId());
    }

    private Result<ChallengeResponse, DomainError> issueTokenAndFinalize(final VerifyState state) {
        return walletJwtService.issueToken(state.input().partyId(), state.input().walletType())
                .flatMap(token -> challengeStore.delete(state.challenge().challengeId())
                        .map(ignored -> new VerifyChallengeResponse(token, state.input().partyId())));
    }

    private Result<VerifyState, DomainError> loadChallenge(final VerifyInput input) {
        return challengeStore.get(input.challengeId())
                .flatMap(challenge -> {
                    if (!challenge.partyId().equals(input.partyId())) {
                        return Result.err(new ValidationError(
                                "Challenge does not belong to supplied party",
                                ValidationError.Type.REQUEST
                        ));
                    }
                    return Result.ok(new VerifyState(input, challenge));
                });
    }

    private Result<VerifyInput, DomainError> validateVerifyRequest(final VerifyChallengeRequest request) {
        if (request == null) {
            return Result.err(new ValidationError("Request body is required", ValidationError.Type.REQUEST));
        }
        return partyValidationService.normalize(request.partyId())
                .flatMap(party -> {
                    String challengeId = trimToNull(request.challengeId());
                    if (challengeId == null) {
                        return Result.err(new ValidationError("challengeId is required", ValidationError.Type.REQUEST));
                    }
                    String signature = trimToNull(request.signature());
                    if (signature == null) {
                        return Result.err(new AuthInvalidSignature("Signature is required"));
                    }
                    String walletType = normalizeWalletType(request.walletType());
                    return Result.ok(new VerifyInput(challengeId, party, signature, walletType));
                });
    }

    private ChallengeResponse toResponse(final ChallengeStore.Challenge challenge) {
        Instant expiresAt = Instant.ofEpochMilli(challenge.expiresAtMillis());
        return new ChallengeResponse(challenge.challengeId(), challenge.challengeValue(), expiresAt);
    }

    private ResponseEntity<ErrorResponse> mapDomainErrorToResponse(final DomainError error, final String path) {
        HttpStatus status = DomainErrorStatusMapper.map(error);
        ErrorResponse payload = new ErrorResponse(
                error.code(),
                error.message(),
                status.value(),
                path
        );
        return ResponseEntity.status(status).body(payload);
    }

    private String normalizeWalletType(final String walletType) {
        String trimmed = trimToNull(walletType);
        return trimmed == null ? "unknown" : trimmed.toLowerCase();
    }

    private String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Result<ChallengeResponse, DomainError> issueTokenAndFinalize(final VerifyState state) {
        return walletJwtService.issueToken(state.input().partyId(), state.input().walletType())
                .flatMap(token -> challengeStore.delete(state.challenge().challengeId())
                        .map(ignored -> new VerifyChallengeResponse(token, state.input().partyId())));
    }

    private record VerifyInput(String challengeId, String partyId, String signature, String walletType) { }

    private record VerifyState(VerifyInput input, ChallengeStore.Challenge challenge) { }
}

