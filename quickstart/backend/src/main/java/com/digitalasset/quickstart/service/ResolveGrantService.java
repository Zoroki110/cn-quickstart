package com.digitalasset.quickstart.service;

import clearportx_amm_production_gv.amm.pool.Pool;
import clearportx_amm_production_gv.token.token.Token;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Party;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class ResolveGrantService {

    private final LedgerApi ledgerApi;

    public ResolveGrantService(LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    public record Result(String poolId,
                         String poolCid,
                         boolean visibilityGranted,
                         String module,
                         String entity,
                         String packageId) {}

    public Result resolveAndGrant(String poolId, String party) {
        if (poolId == null || poolId.isBlank()) {
            throw new IllegalArgumentException("poolId is required");
        }
        if (party == null || party.isBlank()) {
            throw new IllegalArgumentException("party is required");
        }
        String operator = Optional.ofNullable(System.getenv("APP_PROVIDER_PARTY")).orElse(party);

        // 1) Resolve freshest active Pool by poolId from operator scope
        List<LedgerApi.ActiveContract<Pool>> poolsOp = ledgerApi.getActiveContractsForParty(Pool.class, operator).join();
        List<LedgerApi.ActiveContract<Pool>> candidates = new ArrayList<>();
        for (var p : poolsOp) {
            if (p.payload.getPoolId.equals(poolId)) candidates.add(p);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No pool candidates found for poolId=" + poolId);
        }
        candidates.sort((a, b) ->
                b.payload.getReserveA.multiply(b.payload.getReserveB)
                        .compareTo(a.payload.getReserveA.multiply(a.payload.getReserveB)));

        // Prefer candidate with alive canonicals for poolParty
        LedgerApi.ActiveContract<Pool> chosen = null;
        for (var cand : candidates) {
            var pay = cand.payload;
            String poolParty = pay.getPoolParty.getParty;
            var toks = ledgerApi.getActiveContractsForParty(Token.class, poolParty).join();
            Set<String> alive = new HashSet<>();
            for (var t : toks) alive.add(t.contractId.getContractId);
            boolean aAlive = pay.getTokenACid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
            boolean bAlive = pay.getTokenBCid.map(cid -> alive.contains(cid.getContractId)).orElse(false);
            if (aAlive && bAlive) {
                chosen = cand;
                break;
            }
        }
        if (chosen == null) chosen = candidates.get(0);

        String currentCid = chosen.contractId.getContractId;

        // 2) If party cannot see it, grant visibility as operator; follow newPoolCid if returned
        boolean granted = false;
        {
            var partyPools = ledgerApi.getActiveContractsForParty(Pool.class, party).join();
            final String targetCid = currentCid;
            boolean visible = partyPools.stream().anyMatch(p -> p.contractId.getContractId.equals(targetCid));
            if (!visible) {
                String cmdId = java.util.UUID.randomUUID().toString();
                Pool.GrantVisibility choice = new Pool.GrantVisibility(new Party(party));
                try {
                    ContractId<Pool> newCid = ledgerApi.exerciseAndGetResultWithParties(
                            chosen.contractId,
                            choice,
                            cmdId,
                            List.of(operator),
                            List.of(operator, party)
                    ).join();
                    if (!newCid.getContractId.equals(currentCid)) {
                        currentCid = newCid.getContractId;
                    }
                    granted = true;
                } catch (Exception first) {
                    // Retry across other operator-visible candidates for same poolId
                    for (var cand : candidates) {
                        try {
                            ContractId<Pool> newCid = ledgerApi.exerciseAndGetResultWithParties(
                                    cand.contractId,
                                    choice,
                                    java.util.UUID.randomUUID().toString(),
                                    List.of(operator),
                                    List.of(operator, party)
                            ).join();
                            currentCid = newCid.getContractId;
                            granted = true;
                            break;
                        } catch (Exception ignore) {
                            // try next
                        }
                    }
                }
            }
        }

        // 3) Template info as seen by party (verifies visibility and DAR)
        String module = null, entity = null, packageId = null;
        try {
            var id = ledgerApi.getTemplateIdForPoolCid(party, currentCid).join();
            module = id.getModuleName();
            entity = id.getEntityName();
            packageId = id.getPackageId();
        } catch (Exception ignored) {
            // Leave template fields null if not resolvable
        }
        return new Result(poolId, currentCid, granted, module, entity, packageId);
    }
}


