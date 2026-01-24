package com.digitalasset.quickstart.auth;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ValidationError;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PartyValidationService {

    private static final Pattern PARTY_PATTERN = Pattern.compile("^[^:]+::[0-9a-fA-F]+$");

    public Result<String, DomainError> validateFormat(final String partyId) {
        if (partyId == null) {
            return Result.err(new ValidationError("partyId is required", ValidationError.Type.REQUEST));
        }
        String normalized = partyId.trim();
        if (normalized.isEmpty()) {
            return Result.err(new ValidationError("partyId is required", ValidationError.Type.REQUEST));
        }
        if (!PARTY_PATTERN.matcher(normalized).matches()) {
            return Result.err(new ValidationError("Invalid partyId format", ValidationError.Type.REQUEST));
        }
        return Result.ok(normalized);
    }

    public Result<String, DomainError> normalize(final String partyId) {
        return validateFormat(partyId);
    }
}
package com.digitalasset.quickstart.auth;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ValidationError;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PartyValidationService {

    private static final Pattern PARTY_PATTERN = Pattern.compile("^[^:]+::[0-9a-fA-F]+$");

    public Result<String, DomainError> validateFormat(final String partyId) {
        if (partyId == null) {
            return Result.err(new ValidationError("partyId is required", ValidationError.Type.REQUEST));
        }
        String normalized = partyId.trim();
        if (normalized.isEmpty()) {
            return Result.err(new ValidationError("partyId is required", ValidationError.Type.REQUEST));
        }
        if (!PARTY_PATTERN.matcher(normalized).matches()) {
            return Result.err(new ValidationError("Invalid partyId format", ValidationError.Type.REQUEST));
        }
        return Result.ok(normalized);
    }

    public Result<String, DomainError> normalize(final String partyId) {
        return validateFormat(partyId);
    }
}
package com.digitalasset.quickstart.auth;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ValidationError;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PartyValidationService {

    private static final Pattern PARTY_PATTERN = Pattern.compile("^[^:]+::[0-9a-fA-F]+$");

    public Result<String, DomainError> validateFormat(final String partyId) {
        if (partyId == null) {
            return Result.err(new ValidationError("partyId is required", ValidationError.Type.REQUEST));
        }
        String trimmed = partyId.trim();
        if (trimmed.isEmpty()) {
            return Result.err(new ValidationError("partyId is required", ValidationError.Type.REQUEST));
        }
        if (!PARTY_PATTERN.matcher(trimmed).matches()) {
            return Result.err(new ValidationError("Invalid partyId format", ValidationError.Type.REQUEST));
        }
        return Result.ok(trimmed);
    }

    public Result<String, DomainError> normalize(final String partyId) {
        return validateFormat(partyId);
    }
}

