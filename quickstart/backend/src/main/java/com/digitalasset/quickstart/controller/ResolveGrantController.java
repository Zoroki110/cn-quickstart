package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.service.ResolveGrantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/debug")
public class ResolveGrantController {

    private final ResolveGrantService svc;
    private final com.digitalasset.quickstart.ledger.LedgerApi ledgerApi;

    public ResolveGrantController(ResolveGrantService svc, com.digitalasset.quickstart.ledger.LedgerApi ledgerApi) {
        this.svc = svc;
        this.ledgerApi = ledgerApi;
    }

    public record ResolveReq(String poolId, String party) {}
    public record ResolveResp(boolean success,
                              String poolId,
                              String poolCid,
                              boolean visibilityGranted,
                              Boolean partyVisible,
                              String module,
                              String entity,
                              String packageId,
                              String error) {}

    @PostMapping("/resolve-and-grant")
    public ResponseEntity<ResolveResp> resolveAndGrant(@RequestBody ResolveReq req) {
        try {
            var out = svc.resolveAndGrant(req.poolId(), req.party());
            // Check final party visibility
            boolean partyVisible = ledgerApi.getActiveContractsForParty(clearportx_amm_production_gv.amm.pool.Pool.class, req.party())
                    .join()
                    .stream()
                    .anyMatch(p -> p.contractId.getContractId.equals(out.poolCid()));

            return ResponseEntity.ok(new ResolveResp(
                    true,
                    out.poolId(),
                    out.poolCid(),
                    out.visibilityGranted(),
                    partyVisible,
                    out.module(),
                    out.entity(),
                    out.packageId(),
                    null
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(
                    new ResolveResp(false, req.poolId(), null, false, null, null, null, null, e.toString()));
        }
    }
}


