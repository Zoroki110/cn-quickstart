package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryEndpoint;
import com.digitalasset.quickstart.config.RegistryRoutingConfig.RegistryKind;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Resolve TransferInstruction choice-context disclosures for devnet-only workflows.
 */
@Service
@Profile("devnet")
public class TransferInstructionChoiceContextService {

    private static final Logger logger = LoggerFactory.getLogger(TransferInstructionChoiceContextService.class);

    private final LedgerConfig ledgerConfig;

    @Autowired(required = false)
    private RegistryRoutingConfig registryRouting;

    public TransferInstructionChoiceContextService(final LedgerConfig ledgerConfig) {
        this.ledgerConfig = ledgerConfig;
    }

    public record ChoiceContextResult(
            List<CommandsOuterClass.DisclosedContract> disclosedContracts,
            String registryUsed,
            List<String> registriesAttempted
    ) { }

    public Result<ChoiceContextResult, ApiError> resolveDisclosedContracts(String contractId, String adminHint) {
        if (contractId == null || contractId.isBlank()) {
            return Result.err(ApiError.of(ErrorCode.VALIDATION, "contractId is required"));
        }

        List<RegistryEndpoint> registriesToTry = getRegistriesToTry(adminHint);
        if (registriesToTry.isEmpty()) {
            return Result.err(ApiError.of(
                    ErrorCode.PRECONDITION_FAILED,
                    "no registryBaseUri configured",
                    java.util.Map.of("adminHint", adminHint)
            ));
        }

        List<String> attempted = new ArrayList<>();
        ChoiceContextDto ctx = null;
        String successfulBase = null;

        for (RegistryEndpoint ep : registriesToTry) {
            attempted.add(ep.baseUri());
            try {
                ctx = fetchChoiceContext(ep, contractId);
                if (ctx != null && ctx.disclosedContracts != null && !ctx.disclosedContracts.isEmpty()) {
                    successfulBase = ep.baseUri();
                    break;
                }
            } catch (Exception e) {
                logger.warn("Choice-context fetch failed base={} error={}", abbreviate(ep.baseUri()), e.getMessage());
            }
        }

        if (ctx == null || ctx.disclosedContracts == null || ctx.disclosedContracts.isEmpty()) {
            return Result.err(ApiError.of(
                    ErrorCode.PRECONDITION_FAILED,
                    "choice-context missing disclosures",
                    java.util.Map.of("attempted", attempted)
            ));
        }

        List<CommandsOuterClass.DisclosedContract> disclosed = ctx.disclosedContracts.stream()
                .map(TransferInstructionChoiceContextService::toDisclosedContract)
                .filter(Objects::nonNull)
                .toList();

        if (disclosed.isEmpty()) {
            return Result.err(ApiError.of(
                    ErrorCode.PRECONDITION_FAILED,
                    "choice-context disclosures are invalid",
                    java.util.Map.of("attempted", attempted)
            ));
        }

        return Result.ok(new ChoiceContextResult(disclosed, successfulBase, attempted));
    }

    private ChoiceContextDto fetchChoiceContext(RegistryEndpoint ep, String contractId) {
        RestTemplate rest = new RestTemplate();
        try {
            String url1 = buildUrl(ep, true, contractId);
            String url2 = buildUrl(ep, false, contractId);
            ChoiceContextDto ctx = url1 != null ? rest.postForObject(url1, java.util.Map.of(), ChoiceContextDto.class) : null;
            if (ctx == null || ctx.disclosedContracts == null || ctx.disclosedContracts.isEmpty()) {
                ctx = url2 != null ? rest.postForObject(url2, java.util.Map.of(), ChoiceContextDto.class) : null;
            }
            return ctx;
        } catch (Exception e) {
            logger.warn("Failed to fetch choice-context from base={} error={}", abbreviate(ep.baseUri()), e.getMessage());
            return null;
        }
    }

    private List<RegistryEndpoint> getRegistriesToTry(String adminHint) {
        if (registryRouting != null) {
            return registryRouting.getRegistriesToTry(adminHint);
        }
        String base = ledgerConfig.getRegistryBaseUri();
        if (base != null && !base.isBlank()) {
            return List.of(new RegistryEndpoint(base, RegistryKind.SCAN, adminHint));
        }
        return List.of();
    }

    private static String buildUrl(RegistryEndpoint ep, boolean withRegistryPrefix, String contractId) {
        if (ep == null || ep.baseUri() == null || ep.baseUri().isBlank()) return null;
        String url = ep.baseUri();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (ep.kind() == RegistryKind.UTILITIES_TOKEN_STANDARD) {
            if (ep.admin() == null || ep.admin().isBlank()) return null;
            return url + "/api/token-standard/v0/registrars/" + ep.admin() + "/registry/transfer-instruction/v1/" + contractId + "/choice-contexts/accept";
        } else if (ep.kind() == RegistryKind.LOOP_TOKEN_STANDARD_V1) {
            return url + "/api/v1/token-standard/transfer-instructions/" + contractId + "/choice-contexts/accept";
        }
        if (withRegistryPrefix) {
            return url + "/registry/transfer-instruction/v1/" + contractId + "/choice-contexts/accept";
        }
        return url + "/transfer-instruction/v1/" + contractId + "/choice-contexts/accept";
    }

    private static CommandsOuterClass.DisclosedContract toDisclosedContract(DisclosedContractDto dc) {
        try {
            ValueOuterClass.Identifier tid = parseTemplateIdentifier(dc.templateId);
            byte[] blob = Base64.getDecoder().decode(dc.createdEventBlob);
            return CommandsOuterClass.DisclosedContract.newBuilder()
                    .setTemplateId(tid)
                    .setContractId(dc.contractId)
                    .setCreatedEventBlob(com.google.protobuf.ByteString.copyFrom(blob))
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private static ValueOuterClass.Identifier parseTemplateIdentifier(String templateIdStr) {
        String[] parts = templateIdStr.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid templateId format: " + templateIdStr);
        }
        return ValueOuterClass.Identifier.newBuilder()
                .setPackageId(parts[0])
                .setModuleName(parts[1])
                .setEntityName(parts[2])
                .build();
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        if (s.length() <= 30) return s;
        return s.substring(0, 15) + "..." + s.substring(s.length() - 10);
    }

    private static final class ChoiceContextDto {
        public ChoiceContextDataDto choiceContextData;
        public List<DisclosedContractDto> disclosedContracts;
    }
    private static final class ChoiceContextDataDto {
        public java.util.Map<String, ContextValueDto> values;
    }
    private static final class ContextValueDto {
        public String tag;
        public Object value;
    }
    private static final class DisclosedContractDto {
        public String templateId;
        public String contractId;
        public String createdEventBlob;
    }
}

