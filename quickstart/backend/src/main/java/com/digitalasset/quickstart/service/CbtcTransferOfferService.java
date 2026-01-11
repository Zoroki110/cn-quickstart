package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.ledger.LedgerApi;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service for querying incoming CBTC TransferOffer contracts.
 *
 * CBTC transfers use Utility.Registry.App.V0.Model.Transfer:TransferOffer template.
 * These offers are visible to the receiver party but the underlying TransferInstruction
 * is NOT disclosed - hence backend cannot accept them (only Loop SDK can).
 *
 * This service queries the ledger ACS directly (not scan API) to find TransferOffers
 * where the receiver matches the specified party.
 */
@Service
public class CbtcTransferOfferService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CbtcTransferOfferService.class);

    // CBTC TransferOffer template identifier
    private static final String TRANSFER_OFFER_MODULE = "Utility.Registry.App.V0.Model.Transfer";
    private static final String TRANSFER_OFFER_ENTITY = "TransferOffer";

    // Known CBTC instrument admin on DevNet
    private static final String CBTC_INSTRUMENT_ADMIN = "cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff";
    private static final String CBTC_INSTRUMENT_ID = "CBTC";

    private final LedgerApi ledgerApi;

    public CbtcTransferOfferService(final LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    /**
     * DTO for CBTC TransferOffer
     */
    public record CbtcTransferOfferDto(
            String contractId,
            String sender,
            String receiver,
            BigDecimal amount,
            String reason,
            String executeBefore,
            String instrumentId,
            String instrumentAdmin,
            String rawTemplateId
    ) {}

    /**
     * Get all incoming CBTC TransferOffers for a receiver party.
     *
     * @param receiverParty The party receiving the CBTC offers
     * @return List of TransferOffer DTOs
     */
    @WithSpan
    public CompletableFuture<List<CbtcTransferOfferDto>> getIncomingOffers(final String receiverParty) {
        LOGGER.info("[CbtcTransferOfferService] Querying incoming offers for receiver={}", receiverParty);

        return ledgerApi.getActiveContractsRawForParty(receiverParty)
                .thenApply(contracts -> {
                    List<CbtcTransferOfferDto> offers = new ArrayList<>();

                    LOGGER.info("[CbtcTransferOfferService] Found {} total contracts for party", contracts.size());

                    for (LedgerApi.RawActiveContract contract : contracts) {
                        ValueOuterClass.Identifier templateId = contract.templateId();

                        // Check if this is a TransferOffer
                        if (!TRANSFER_OFFER_MODULE.equals(templateId.getModuleName()) ||
                            !TRANSFER_OFFER_ENTITY.equals(templateId.getEntityName())) {
                            continue;
                        }

                        LOGGER.debug("[CbtcTransferOfferService] Found TransferOffer: cid={}", contract.contractId());

                        // Parse the TransferOffer create arguments
                        CbtcTransferOfferDto dto = parseTransferOffer(contract);
                        if (dto == null) {
                            continue;
                        }

                        // Filter by receiver
                        if (!receiverParty.equals(dto.receiver())) {
                            LOGGER.debug("[CbtcTransferOfferService] Skipping offer - receiver mismatch: expected={}, got={}",
                                    receiverParty, dto.receiver());
                            continue;
                        }

                        // Filter for CBTC only (optional - can remove to show all transfer offers)
                        if (!CBTC_INSTRUMENT_ID.equals(dto.instrumentId())) {
                            LOGGER.debug("[CbtcTransferOfferService] Skipping offer - not CBTC: instrumentId={}",
                                    dto.instrumentId());
                            continue;
                        }

                        LOGGER.info("[CbtcTransferOfferService] Found CBTC offer: cid={}, amount={}, sender={}",
                                truncateCid(dto.contractId()), dto.amount(), truncateParty(dto.sender()));

                        offers.add(dto);
                    }

                    LOGGER.info("[CbtcTransferOfferService] Found {} CBTC TransferOffers for receiver={}",
                            offers.size(), truncateParty(receiverParty));

                    return offers;
                })
                .exceptionally(ex -> {
                    LOGGER.error("[CbtcTransferOfferService] Error querying offers: {}", ex.getMessage(), ex);
                    return List.of();
                });
    }

    /**
     * Parse a RawActiveContract into a CbtcTransferOfferDto.
     *
     * TransferOffer structure (from cbtc-lib):
     * - sender: Party
     * - receiver: Party
     * - amount: Numeric 10
     * - instrumentId: InstrumentId { admin: Party, id: Text }
     * - reason: Text
     * - executeBefore: Optional Time
     */
    private CbtcTransferOfferDto parseTransferOffer(final LedgerApi.RawActiveContract contract) {
        ValueOuterClass.Record args = contract.createArguments();
        if (args == null) {
            return null;
        }

        try {
            String sender = getPartyField(args, "sender", 0);
            String receiver = getPartyField(args, "receiver", 1);
            BigDecimal amount = getNumericField(args, "amount", 2);
            String reason = getTextField(args, "reason", 4);
            String executeBefore = getOptionalTimeField(args, "executeBefore", 5);

            // Parse instrumentId (nested record)
            ValueOuterClass.Value instrumentValue = getFieldValue(args, "instrumentId", 3);
            String instrumentAdmin = null;
            String instrumentId = null;
            if (instrumentValue != null && instrumentValue.hasRecord()) {
                ValueOuterClass.Record instrumentRecord = instrumentValue.getRecord();
                instrumentAdmin = getPartyField(instrumentRecord, "admin", 0);
                instrumentId = getTextField(instrumentRecord, "id", 1);
            }

            String rawTemplateId = contract.templateId().getModuleName() + ":" + contract.templateId().getEntityName();

            return new CbtcTransferOfferDto(
                    contract.contractId(),
                    sender,
                    receiver,
                    amount != null ? amount : BigDecimal.ZERO,
                    reason,
                    executeBefore,
                    instrumentId,
                    instrumentAdmin,
                    rawTemplateId
            );
        } catch (Exception e) {
            LOGGER.warn("[CbtcTransferOfferService] Failed to parse TransferOffer {}: {}",
                    contract.contractId(), e.getMessage());
            return null;
        }
    }

    private ValueOuterClass.Value getFieldValue(ValueOuterClass.Record record, String label, int fallbackIndex) {
        if (record == null) return null;

        // Try by label first
        for (ValueOuterClass.RecordField field : record.getFieldsList()) {
            if (label.equals(field.getLabel())) {
                return field.getValue();
            }
        }

        // Fall back to index
        if (record.getFieldsCount() > fallbackIndex) {
            return record.getFields(fallbackIndex).getValue();
        }

        return null;
    }

    private String getPartyField(ValueOuterClass.Record record, String label, int fallbackIndex) {
        ValueOuterClass.Value value = getFieldValue(record, label, fallbackIndex);
        if (value != null && value.getSumCase() == ValueOuterClass.Value.SumCase.PARTY) {
            return value.getParty();
        }
        return null;
    }

    private String getTextField(ValueOuterClass.Record record, String label, int fallbackIndex) {
        ValueOuterClass.Value value = getFieldValue(record, label, fallbackIndex);
        if (value != null && value.getSumCase() == ValueOuterClass.Value.SumCase.TEXT) {
            return value.getText();
        }
        return null;
    }

    private BigDecimal getNumericField(ValueOuterClass.Record record, String label, int fallbackIndex) {
        ValueOuterClass.Value value = getFieldValue(record, label, fallbackIndex);
        if (value != null && value.getSumCase() == ValueOuterClass.Value.SumCase.NUMERIC) {
            return new BigDecimal(value.getNumeric());
        }
        return null;
    }

    private String getOptionalTimeField(ValueOuterClass.Record record, String label, int fallbackIndex) {
        ValueOuterClass.Value value = getFieldValue(record, label, fallbackIndex);
        if (value == null) return null;

        // Optional is represented as a variant (Some/None)
        if (value.getSumCase() == ValueOuterClass.Value.SumCase.OPTIONAL) {
            ValueOuterClass.Optional opt = value.getOptional();
            if (opt.hasValue() && opt.getValue().getSumCase() == ValueOuterClass.Value.SumCase.TIMESTAMP) {
                return String.valueOf(opt.getValue().getTimestamp());
            }
        }
        return null;
    }

    private String truncateCid(String cid) {
        if (cid == null || cid.length() <= 20) return cid;
        return cid.substring(0, 16) + "...";
    }

    private String truncateParty(String party) {
        if (party == null || party.length() <= 30) return party;
        int sep = party.indexOf("::");
        if (sep > 0 && sep < 20) {
            return party.substring(0, sep + 10) + "...";
        }
        return party.substring(0, 20) + "...";
    }
}
