package com.digitalasset.quickstart.dto.auth;

public record VerifyChallengeRequest(
        String challengeId,
        String partyId,
        String signature,
        String walletType
) { }

