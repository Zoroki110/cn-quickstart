package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.service.CbtcTransferService;
import com.digitalasset.quickstart.service.CbtcTransferService.AcceptResult;
import com.digitalasset.quickstart.service.CbtcTransferService.TransferView;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/debug/cbtc/transfers")
public class CbtcTransferDebugController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CbtcTransferDebugController.class);

    private final CbtcTransferService transferService;
    private final com.digitalasset.quickstart.service.HoldingsService holdingsService;

    public CbtcTransferDebugController(final CbtcTransferService transferService,
                                       final com.digitalasset.quickstart.service.HoldingsService holdingsService) {
        this.transferService = transferService;
        this.holdingsService = holdingsService;
    }

    @GetMapping("/incoming/{partyId}")
    @WithSpan
    public ResponseEntity<?> listIncoming(@PathVariable("partyId") String partyId) {
        Result<List<TransferView>, DomainError> result = transferService.listIncoming(partyId);
        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return mapError(result.getErrorUnsafe(), String.format("/api/debug/cbtc/transfers/incoming/%s", partyId));
    }

    public record AcceptRequest(String contractId, Boolean acceptAll) { }
    public record AcceptResponse(boolean success, List<AcceptResult> accepted, List<?> holdingsAfter, String error) { }

    @PostMapping("/incoming/{partyId}/accept")
    @WithSpan
    public ResponseEntity<?> accept(@PathVariable("partyId") String partyId,
                                    @RequestBody(required = false) AcceptRequest request) {
        List<String> cids;
        if (request != null && Boolean.TRUE.equals(request.acceptAll())) {
            Result<List<TransferView>, DomainError> list = transferService.listIncoming(partyId);
            if (list.isErr()) {
                return mapError(list.getErrorUnsafe(), String.format("/api/debug/cbtc/transfers/incoming/%s/accept", partyId));
            }
            cids = list.getValueUnsafe().stream().map(TransferView::contractId).toList();
        } else if (request != null && request.contractId() != null && !request.contractId().isBlank()) {
            cids = List.of(request.contractId());
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "contractId is required unless acceptAll=true"
            ));
        }

        Result<List<AcceptResult>, DomainError> acceptResult = transferService.accept(partyId, cids);
        if (acceptResult.isErr()) {
            return mapError(acceptResult.getErrorUnsafe(), String.format("/api/debug/cbtc/transfers/incoming/%s/accept", partyId));
        }

        List<?> holdingsAfter = holdingsService.getHoldingsByParty(partyId)
                .map(List.class::cast)
                .getOrElse(null);

        AcceptResponse resp = new AcceptResponse(true, acceptResult.getValueUnsafe(), holdingsAfter, null);
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<ErrorResponse> mapError(final DomainError error, final String path) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (error.httpStatus() > 0) {
            HttpStatus resolved = HttpStatus.resolve(error.httpStatus());
            if (resolved != null) {
                status = resolved;
            }
        }
        return ResponseEntity.status(status)
                .body(new ErrorResponse(error.code(), error.message(), status.value(), path));
    }
}

