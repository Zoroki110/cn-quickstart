package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.dto.HoldingPoolCreateRequest;
import com.digitalasset.quickstart.dto.HoldingPoolResponse;
import com.digitalasset.quickstart.dto.HoldingPoolBootstrapRequest;
import com.digitalasset.quickstart.service.HoldingPoolService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holding-pools")
public class HoldingPoolController {

    private final HoldingPoolService holdingPoolService;

    public HoldingPoolController(final HoldingPoolService holdingPoolService) {
        this.holdingPoolService = holdingPoolService;
    }

    @PostMapping
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> create(@Valid @RequestBody HoldingPoolCreateRequest request) {
        return holdingPoolService.create(request)
                .thenApply(this::toCreateResponse);
    }

    @GetMapping
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> list() {
        return holdingPoolService.list()
                .thenApply(this::toListResponse);
    }

    @GetMapping("/{contractId}")
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> getByCid(@PathVariable("contractId") String contractId) {
        return holdingPoolService.getByContractId(contractId)
                .thenApply(this::toGetResponse);
    }

    @PostMapping("/{contractId}/archive")
    @WithSpan
    public ResponseEntity<?> archive(@PathVariable("contractId") String contractId) {
        Result<java.util.Map<String, Object>, DomainError> res = holdingPoolService.archive(contractId);
        if (res.isOk()) {
            return ResponseEntity.ok(res.getValueUnsafe());
        }
        return errorResponse(res.getErrorUnsafe(), "/api/holding-pools/{contractId}/archive");
    }

    @PostMapping("/{poolId}/bootstrap")
    @WithSpan
    public ResponseEntity<?> bootstrap(@PathVariable("poolId") String poolId,
                                       @Valid @RequestBody HoldingPoolBootstrapRequest request) {
        Result<java.util.Map<String, Object>, DomainError> res = holdingPoolService.bootstrap(poolId, request);
        if (res.isOk()) {
            return ResponseEntity.ok().body(res.getValueUnsafe());
        }
        DomainError err = res.getErrorUnsafe();
        ErrorResponse payload = new ErrorResponse(err.code(), err.message(), DomainErrorStatusMapper.map(err).value(), String.format("/api/holding-pools/%s/bootstrap", poolId));
        return ResponseEntity.status(DomainErrorStatusMapper.map(err)).body(payload);
    }

    private ResponseEntity<?> toCreateResponse(Result<HoldingPoolResponse, DomainError> result) {
        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return errorResponse(result.getErrorUnsafe(), "/api/holding-pools");
    }

    private ResponseEntity<?> toListResponse(Result<List<HoldingPoolResponse>, DomainError> result) {
        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return errorResponse(result.getErrorUnsafe(), "/api/holding-pools");
    }

    private ResponseEntity<?> toGetResponse(Result<HoldingPoolResponse, DomainError> result) {
        if (result.isOk()) {
            return ResponseEntity.ok(result.getValueUnsafe());
        }
        return errorResponse(result.getErrorUnsafe(), "/api/holding-pools/{contractId}");
    }

    private ResponseEntity<ErrorResponse> errorResponse(final DomainError error, final String path) {
        HttpStatus status = DomainErrorStatusMapper.map(error);
        ErrorResponse payload = new ErrorResponse(error.code(), error.message(), status.value(), path);
        return ResponseEntity.status(status).body(payload);
    }
}
