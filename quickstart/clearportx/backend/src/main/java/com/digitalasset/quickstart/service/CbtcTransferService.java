package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.ledger.LedgerApi;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CbtcTransferService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CbtcTransferService.class);

    private static final String CHOICE_ACCEPT = "Accept";

    private final LedgerApi ledgerApi;
    private final HoldingsService holdingsService;

    public CbtcTransferService(final LedgerApi ledgerApi, final HoldingsService holdingsService) {
        this.ledgerApi = ledgerApi;
        this.holdingsService = holdingsService;
    }

    public record TransferView(
            String contractId,
            ValueOuterClass.Identifier templateId,
            String instrumentId,
            BigDecimal amount,
            String sender,
            String receiver,
            String registry,
            String rawView
    ) { }

    public record AcceptResult(String contractId, String updateId, String error) { }

    @WithSpan
    public Result<List<TransferView>, DomainError> listIncoming(final String partyId) {
        try {
            List<LedgerApi.RawActiveContract> contracts = ledgerApi.getActiveContractsRawForParty(partyId).join();
            List<TransferView> transfers = contracts.stream()
                    .map(this::parseTransfer)
                    .flatMap(Optional::stream)
                    .filter(tv -> partyId.equals(tv.receiver()))
                    .sorted(Comparator.comparing(TransferView::contractId))
                    .toList();
            LOGGER.info("Found {} potential transfer contracts for {}", transfers.size(), partyId);
            transfers.forEach(tv -> LOGGER.info("Transfer cid={} sender={} receiver={} instrument={} amount={}", tv.contractId(), tv.sender(), tv.receiver(), tv.instrumentId(), tv.amount()));
            return Result.ok(transfers);
        } catch (Exception ex) {
            LOGGER.error("Failed to list incoming transfers for {}: {}", partyId, ex.getMessage(), ex);
            return Result.err(new UnexpectedError(ex.getMessage()));
        }
    }

    @WithSpan
    public Result<List<AcceptResult>, DomainError> accept(final String partyId, final List<String> contractIds) {
        List<AcceptResult> results = new ArrayList<>();
        try {
            // Index raw contracts by cid for quick lookup
            Map<String, LedgerApi.RawActiveContract> byCid = ledgerApi.getActiveContractsRawForParty(partyId).join()
                    .stream()
                    .collect(Collectors.toMap(LedgerApi.RawActiveContract::contractId, c -> c, (a, b) -> a));

            for (String cid : contractIds) {
                LedgerApi.RawActiveContract rac = byCid.get(cid);
                if (rac == null) {
                    results.add(new AcceptResult(cid, null, "not visible"));
                    continue;
                }
                try {
                    ValueOuterClass.Record emptyArgs = ValueOuterClass.Record.newBuilder().build();
                    var resp = ledgerApi.exerciseRaw(
                            rac.templateId(),
                            rac.contractId(),
                            CHOICE_ACCEPT,
                            emptyArgs,
                            List.of(partyId),
                            List.of(partyId),
                            Collections.emptyList()
                    ).join();
                    String updateId = resp.hasTransaction() ? resp.getTransaction().getUpdateId() : null;
                    results.add(new AcceptResult(cid, updateId, null));
                    LOGGER.info("Accepted transfer cid={} updateId={}", cid, updateId);
                } catch (Exception acceptEx) {
                    LOGGER.error("Failed to accept transfer {}: {}", cid, acceptEx.getMessage());
                    results.add(new AcceptResult(cid, null, acceptEx.getMessage()));
                }
            }
            return Result.ok(results);
        } catch (Exception ex) {
            LOGGER.error("accept transfers failed for {}: {}", partyId, ex.getMessage(), ex);
            return Result.err(new UnexpectedError(ex.getMessage()));
        }
    }

    private Optional<TransferView> parseTransfer(final LedgerApi.RawActiveContract rac) {
        ValueOuterClass.Record args = rac.createArguments();
        if (args == null) {
            return Optional.empty();
        }
        // Heuristic field extraction
        ValueOuterClass.Value senderV = getField(args, "sender", 0);
        ValueOuterClass.Value receiverV = getField(args, "receiver", 1);
        ValueOuterClass.Value instrumentV = getField(args, "instrumentId", 4);
        ValueOuterClass.Value amountV = getField(args, "amount", 6);

        String sender = senderV != null && senderV.hasParty() ? senderV.getParty() : null;
        String receiver = receiverV != null && receiverV.hasParty() ? receiverV.getParty() : null;

        String registry = null;
        String instrumentId = null;
        if (instrumentV != null && instrumentV.hasRecord()) {
            ValueOuterClass.Record inst = instrumentV.getRecord();
            ValueOuterClass.Value adminV = getField(inst, "admin", 0);
            ValueOuterClass.Value idV = getField(inst, "id", 1);
            registry = adminV != null && adminV.hasParty() ? adminV.getParty() : null;
            instrumentId = idV != null && idV.hasText() ? idV.getText() : null;
        }

        BigDecimal amount = BigDecimal.ZERO;
        if (amountV != null && amountV.getSumCase() == ValueOuterClass.Value.SumCase.NUMERIC) {
            amount = new BigDecimal(amountV.getNumeric());
        }

        String raw = args.toString();
        return Optional.of(new TransferView(rac.contractId(), rac.templateId(), instrumentId, amount, sender, receiver, registry, raw));
    }

    private ValueOuterClass.Value getField(final ValueOuterClass.Record record, final String label, final int index) {
        if (record == null) {
            return null;
        }
        for (ValueOuterClass.RecordField field : record.getFieldsList()) {
            if (label.equals(field.getLabel())) {
                return field.getValue();
            }
        }
        if (record.getFieldsCount() > index) {
            return record.getFields(index).getValue();
        }
        return null;
    }
}

