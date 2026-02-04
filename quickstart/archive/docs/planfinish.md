Plan Détaillé : Authentification Canton Wallet avec Helper Functions
Vue d'ensemble de l'architecture
Pourquoi: Canton Network n'utilise pas l'authentification web classique (login/password). Chaque utilisateur a une identité cryptographique (party ID) qui doit être vérifiée on-chain. À quoi ça sert: Permettre aux utilisateurs de se connecter avec leur wallet Canton et signer des transactions de manière sécurisée. Principe: Au lieu de try/catch partout, on utilise le pattern Result<T> qui encapsule succès OU erreur, rendant le code plus prévisible et maintenable.
Phase 1: Infrastructure Result Pattern (4 heures)
1.1 Amélioration du Pattern Result<T>
POURQUOI C'EST NÉCESSAIRE:
Try/catch cache les erreurs et rend le flux difficile à suivre
Les exceptions peuvent être oubliées ou mal gérées
Result<T> force à gérer explicitement succès ET échec
À QUOI ÇA SERT:
// SANS Result (problématique):
try {
    User user = getUser(id);
    Account account = getAccount(user);
    return processPayment(account);
} catch (Exception e) {
    // Quelle étape a échoué? Difficile à savoir
    logger.error("Something failed", e);
    return null; // Dangereux!
}

// AVEC Result (clair):
return getUser(id)
    .flatMap(user -> getAccount(user))
    .flatMap(account -> processPayment(account))
    .orElse(defaultResponse); // Erreur gérée proprement
MISE EN PLACE:
Ajouter les méthodes map, flatMap, recover au Result existant
map: transforme la valeur si succès, propage l'erreur si échec
flatMap: chaîne plusieurs opérations Result
recover: permet de récupérer d'une erreur
Ces méthodes permettent de composer les opérations sans if/else imbriqués
1.2 Création des ResultHelper
POURQUOI:
Éviter la duplication de code
Centraliser les patterns communs
Faciliter les opérations asynchrones
À QUOI ÇA SERT:
sequence: Exécute plusieurs opérations, fail-fast si une échoue
async: Convertit Result synchrone en CompletableFuture<Result>
validate: Vérifie une condition et retourne Result
MISE EN PLACE:
// Au lieu de:
if (value1 != null && value2 != null && value3 != null) {
    return Result.ok(combine(value1, value2, value3));
} else {
    return Result.err("VALIDATION_FAILED", "Missing values");
}

// On écrit:
return ResultHelper.sequence(
    List.of(validateValue1(), validateValue2(), validateValue3()),
    () -> combine(value1, value2, value3)
);
Phase 2: Service de Validation Canton Party (8 heures)
2.1 PartyValidationService
POURQUOI C'EST NÉCESSAIRE:
Un party ID Canton a un format spécifique: namespace::fingerprint
Le fingerprint est le hash de la clé publique (64 caractères hex)
Il faut vérifier que le party existe vraiment sur le ledger
Éviter les attaques par usurpation d'identité
À QUOI ÇA SERT:
validatePartyFormat: Vérifie que le format est correct (regex)
validatePartyOnLedger: Interroge Canton pour confirmer l'existence
fullValidation: Combine toutes les validations en pipeline
MISE EN PLACE:
// L'utilisateur envoie: "alice::1234567890abcdef..."
// On valide en 3 étapes:

1. Format correct? 
   -> namespace (alice) + :: + 64 chars hex

2. Existe sur Canton?
   -> Query ledger API: getPartyDetails(partyId)
   
3. A les permissions?
   -> Vérifier les droits (peut trader, a du traffic, etc.)

// Tout est chainé avec Result, pas de try/catch!
2.2 PartyRegistrationController
POURQUOI:
Point d'entrée pour l'authentification wallet
Crée une session temporaire après validation
Génère un challenge pour la signature
À QUOI ÇA SERT:
/api/wallet/register: L'utilisateur envoie son party ID
Retourne un sessionId + challenge à signer
La session expire après 30 minutes
MISE EN PLACE:
POST /api/wallet/register
{
    "partyId": "alice::1234567890abcdef..."
}

// Réponse si succès:
{
    "sessionId": "uuid-random-secure",
    "challenge": "random-32-bytes-base64",
    "expiresAt": "2024-01-01T12:30:00Z"
}

// Réponse si échec (avec Result pattern):
{
    "error": "PARTY_NOT_FOUND",
    "message": "Party not registered on ledger"
}
Phase 3: Pipeline de Préparation des Transactions (12 heures)
3.1 TransactionBuilderService
POURQUOI C'EST NÉCESSAIRE:
Canton nécessite de préparer les transactions avant signature
Calculer les UTXOs nécessaires (tokens = pièces comme Bitcoin)
Estimer les coûts en traffic
Optimiser le routage (quel pool utiliser)
À QUOI ÇA SERT: Le pipeline transforme une demande simple en transaction prête à signer:
Demande utilisateur: "Swap 100 USDC pour ETH"
                          ↓
1. Valider session        → Session active?
2. Valider tokens        → USDC et ETH existent?
3. Calculer route        → Quel pool a le meilleur prix?
4. Estimer output        → Combien d'ETH je vais recevoir?
5. Construire TX         → Créer la structure Canton
6. Ajouter metadata      → Timestamps, IDs, etc.
                          ↓
Transaction prête à signer
MISE EN PLACE:
// Le pipeline helper générique:
private <T> CompletableFuture<Result<T>> pipeline(Step<?>... steps) {
    // Exécute chaque étape séquentiellement
    // Si une échoue, arrête et retourne l'erreur
    // Sinon continue avec le résultat suivant
}

// Utilisation:
return pipeline(
    () -> validateSession(sessionId),        // Étape 1
    session -> findBestRoute(request),       // Étape 2
    route -> calculateAmounts(route),        // Étape 3
    amounts -> buildTransaction(amounts)     // Étape 4
);
// Pas de try/catch, les erreurs remontent naturellement!
3.2 UTXOService
POURQUOI:
Canton utilise le modèle UTXO (comme Bitcoin)
Un token n'est pas un solde mais des "pièces" indivisibles
Pour envoyer 100 USDC, il faut peut-être combiner 3 UTXOs (50+30+20)
Le "change" crée un nouvel UTXO
À QUOI ÇA SERT:
Exemple: User veut envoyer 100 USDC
Ses UTXOs: [50 USDC, 30 USDC, 25 USDC, 10 USDC]

Le service:
1. Récupère tous les UTXOs du user
2. Sélectionne optimalement: 50 + 30 + 25 = 105 USDC
3. Calcule le change: 5 USDC retour au user
4. Prépare la transaction avec ces 3 inputs
MISE EN PLACE:
public Result<List<UTXO>> selectUTXOs(party, token, amount) {
    // 1. Query ledger pour les UTXOs disponibles
    // 2. Algorithme greedy: prend les plus gros d'abord
    // 3. Vérifie qu'on a assez (sinon INSUFFICIENT_FUNDS)
    // 4. Retourne la liste optimale
}

// Pas d'exception si pas assez de fonds!
// Result.err("INSUFFICIENT_FUNDS", "Need 100, have 80")
Phase 4: Mécanisme de Polling Wallet (6 heures)
4.1 WalletPollingService
POURQUOI C'EST NÉCESSAIRE:
Le wallet Canton est externe (app mobile, extension, hardware)
L'utilisateur doit approuver manuellement la transaction
Le backend doit attendre la signature (2-5 secondes entre checks)
Timeout après 5 minutes pour éviter d'attendre indéfiniment
À QUOI ÇA SERT:
Timeline:
T+0s  : Backend envoie TX au wallet
T+2s  : Check #1 - Wallet: "pending"
T+4s  : Check #2 - Wallet: "user reviewing"  
T+6s  : Check #3 - Wallet: "signed!" ✓
T+7s  : Backend récupère signature et submit
MISE EN PLACE:
// Fonction récursive sans while(true) ni try/catch:
private CompletableFuture<Result<T>> pollRecursive(...) {
    return checkWalletStatus()
        .thenCompose(status -> {
            if (status.isSigned()) {
                return Result.ok(status);  // Succès!
            }
            if (timeout.isExpired()) {
                return Result.err("TIMEOUT");  // Échec
            }
            // Programmer prochain check dans 2 secondes
            return scheduleNextPoll();
        });
}

// Utilise CompletableFuture.delayedExecutor pour le timing
// Pas de Thread.sleep() qui bloque!
4.2 WalletTransactionController
POURQUOI:
Point d'entrée pour soumettre les transactions
Orchestre tout le flow: validation → préparation → wallet → ledger
Gère les erreurs à chaque étape
À QUOI ÇA SERT:
POST /api/wallet/transaction/submit

Pipeline complet:
1. Valider requête     → Montants corrects?
2. Préparer TX        → UTXOs, routing, etc.
3. Envoyer au wallet  → Pour signature
4. Poller signature   → Attendre approbation
5. Submit au ledger   → Exécution finale
MISE EN PLACE:
// Chaque étape retourne Result<T>
// Si erreur, on arrête et retourne l'erreur
// Sinon on continue avec la valeur

return validateRequest(request)
    .thenCompose(this::prepareTransaction)
    .thenCompose(this::sendToWallet)
    .thenCompose(this::pollForSignature)
    .thenCompose(this::submitToLedger)
    .thenApply(result -> result.isOk() 
        ? ResponseEntity.ok(result.getValue())
        : ResponseEntity.badRequest().body(result.getError()));
Phase 5: Estimation Traffic & Gestion Sessions (6 heures)
5.1 TrafficEstimationService
POURQUOI C'EST NÉCESSAIRE:
Chaque transaction Canton consomme du "traffic" (comme le gas Ethereum)
Le traffic coûte de l'argent réel
L'utilisateur doit savoir combien ça va coûter AVANT de signer
Éviter les échecs par manque de traffic
À QUOI ÇA SERT:
Estimation pour un swap:
- Base: 1000 units (swap simple)
- +500 si multi-hop (passe par plusieurs pools)
- +200 par UTXO utilisé
- ×1.2 marge de sécurité
= 2040 units × 0.001 CHF/unit = 2.04 CHF
MISE EN PLACE:
public Result<TrafficEstimate> estimateSwapTraffic(request) {
    // 1. Traffic de base selon le type
    BigDecimal base = getBaseTraffic(request.type);
    
    // 2. Ajuster selon complexité
    if (request.isMultiHop()) base += 500;
    if (request.utxoCount > 3) base += 200 * utxoCount;
    
    // 3. Ajouter marge sécurité
    BigDecimal total = base * 1.2;
    
    // 4. Calculer prix en CHF
    BigDecimal cost = total * PRICE_PER_UNIT;
    
    return Result.ok(new TrafficEstimate(total, cost));
}
5.2 SessionManager
POURQUOI:
Maintenir l'état entre requêtes (stateless HTTP)
Sécuriser avec tokens aléatoires cryptographiques
Expirer automatiquement après 30 minutes
Éviter les attaques par rejeu
À QUOI ÇA SERT:
Stocke: party ID validé, challenge, timestamp, metadata
Cache en mémoire avec Caffeine (pas de DB pour la démo)
Token format: UUID-randomBytes32 (impossible à deviner)
MISE EN PLACE:
// Création session:
String token = UUID.randomUUID() + "-" + secureRandom(32);
WalletSession session = new WalletSession(token, party);
cache.put(token, session, Duration.ofMinutes(30));

// Validation session:
public Result<WalletSession> getSession(String sessionId) {
    WalletSession session = cache.getIfPresent(sessionId);
    if (session == null) {
        return Result.err("SESSION_NOT_FOUND");
    }
    if (session.isExpired()) {
        return Result.err("SESSION_EXPIRED");
    }
    return Result.ok(session);
}
Phase 6: Intégration Frontend & Tests (8 heures)
6.1 DTOs Type-Safe
POURQUOI:
Éviter les erreurs de typage frontend/backend
Validation automatique des données
Documentation auto-générée (OpenAPI)
Erreurs détectées à la compilation
À QUOI ÇA SERT:
// Records Java (immutables, equals/hashCode auto)
public record WalletAuthRequest(
    String partyId,
    String walletEndpoint
) {
    // Validation intégrée
    public Result<WalletAuthRequest> validate() {
        if (!partyId.matches(PARTY_REGEX)) {
            return Result.err("INVALID_PARTY_FORMAT");
        }
        return Result.ok(this);
    }
}
MISE EN PLACE:
Utiliser Java Records (Java 14+)
Validation dans le DTO même
Jackson sérialise automatiquement
Frontend TypeScript peut générer les types depuis OpenAPI
6.2 Tests d'Intégration
POURQUOI:
Vérifier que tout le pipeline fonctionne
Détecter les régressions
Documentation exécutable
Confiance pour le déploiement
À QUOI ÇA SERT:
@Test
void testFullWalletFlow() {
    // 1. Register party
    var registration = client.register("alice::1234...");
    assertThat(registration).isOk();
    
    // 2. Prepare transaction
    var prepared = client.prepareSwap(sessionId, swapRequest);
    assertThat(prepared).hasTransactionId();
    
    // 3. Mock wallet signature
    mockWallet.approve(prepared.transactionId);
    
    // 4. Poll for completion
    var result = client.pollTransaction(prepared.transactionId);
    assertThat(result).completesWithin(Duration.ofSeconds(10));
}
MISE EN PLACE:
MockWebServer pour simuler le wallet
TestContainers pour Canton sandbox
AssertJ pour assertions fluides
Pas de Thread.sleep(), utiliser Awaitility
Résumé des Avantages
POURQUOI CETTE APPROCHE:
Pas de try/catch = Code plus lisible et prévisible
Result<T> = Erreurs explicites dans le type system
Pipeline fonctionnel = Composition naturelle des opérations
Helper functions = Réutilisabilité et testabilité
CompletableFuture = Async sans bloquer les threads
TEMPS TOTAL ESTIMÉ:
100 heures (8-10 jours à 12h/jour)
Réaliste vu votre productivité démontrée
Inclut tests et documentation
PROCHAINE ÉTAPE: Commencer par Phase 1 (Result pattern) car tout le reste en dépend.
Stayed in plan mode
Excellent ! Maintenant je vais créer un plan détaillé pour la migration vers le standard CIP-0056 (Canton Interoperability Protocol pour les tokens).
Update Todos

Research current token implementation vs CIP-0056 requirements

Create CIP-0056 compliant token templates in DAML

Implement UTXO splitting and merging logic

Build token metadata service for CIP-0056

Migrate existing token contracts to CIP-0056

Update pool contracts for CIP-0056 compatibility

Implement atomic swap with CIP-0056 tokens

Create token registry and discovery service

Build CIP-0056 validation and compliance checks

Test migration with existing pools and tokens
Task:Research CIP-0056 implementation needs
IN
{
  "subagent_type": "Plan",
  "description": "Research CIP-0056 implementation needs",
  "prompt": "Research the current token implementation in the ClearportX codebase and analyze what needs to be changed for CIP-0056 compliance. Focus on:\n\n1. Current token implementation in DAML files:\n   - Look at daml/Token/Token.daml \n   - Check how tokens are currently structured\n   - Identify if UTXO pattern is already implemented\n\n2. Pool contracts and how they handle tokens:\n   - daml/AMM/Pool.daml\n   - daml/AMM/AtomicSwap.daml\n   - How tokens are deposited and withdrawn\n\n3. Backend token handling:\n   - How tokens are queried and managed\n   - UTXO selection logic if any exists\n   - Token transfer mechanisms\n\n4. Check for any existing CIP-0056 references or partial implementations\n\n5. Identify the main differences between current implementation and CIP-0056 requirements:\n   - UTXO model vs account model\n   - Token metadata standards\n   - Interoperability requirements\n   - Splitting/merging capabilities\n\nProvide a clear analysis of what exists and what needs to be added/changed for full CIP-0056 compliance."
}
OUT
Perfect! Now I have a comprehensive understanding of both the current implementation and CIP-0056 requirements. Let me create a detailed analysis report.
ClearportX Token Implementation Analysis for CIP-0056 Compliance

Executive Summary

The ClearportX codebase currently implements a centralized, account-based token model optimized for AMM atomic swaps. To achieve CIP-0056 compliance, significant architectural changes are required to adopt the UTXO model with standardized interfaces while maintaining AMM functionality.

1. CURRENT TOKEN IMPLEMENTATION ANALYSIS

1.1 DAML Token Contract (daml/Token/Token.daml)

Current Architecture:

Account Model: Single token contract per owner/symbol with aggregated balance

Centralized Control: Only issuer is signatory (owner is observer)

No Contract Key: Tokens tracked by ContractId to avoid key collisions

Basic Fields:
template Token with
  issuer : Party
  owner  : Party
  symbol : Text
  amount : Numeric 10



Existing UTXO-like Features:

Transfer choice: Splits tokens (creates remainder + transferred amount)

TransferSplit choice: Returns both remainder and sent token CIDs

Merge choice: Consolidates multiple tokens into one

Drain choice: Archives token and returns amount

Credit choice: Adds to token balance


Critical Design Philosophy:
The token design is explicitly optimized for AMM atomicity with unilateral issuer control. As documented in the extensive security warnings (lines 5-49), this is a trust-based centralized model where:

Issuer has complete control (can create unlimited tokens)

No owner authorization required for creation

Enables atomic swaps without multi-party consent issues


1.2 Pool Token Handling (daml/AMM/Pool.daml)

Token Management Strategy:

Canonical Token Pattern: Pool maintains single consolidated token per reserve

tokenACid : Optional (ContractId T.Token) (line 54)

tokenBCid : Optional (ContractId T.Token) (line 55)



Anti-fragmentation: Uses Merge choice to consolidate incoming tokens (lines 137-143)

Reserve Tracking: Maintains reserveA and reserveB as numeric values separate from token amounts


AddLiquidity Flow (lines 80-161):

Validates token symbols/issuers match pool

Transfers tokens from provider to pool

Merges with existing canonical tokens if present

Updates reserves and LP supply

Archives old pool, creates new with updated state


RemoveLiquidity Flow (lines 164-248):

Calculates proportional withdrawal

Burns LP tokens

Transfers tokens from pool to provider

Updates reserves (or resets to empty if liquidity depleted)


AtomicSwap Flow (lines 343-477):

Transfers input from trader to pool

Splits protocol fee (25% of total fee)

Calculates swap output via constant product formula

Transfers output to trader

Consolidates pool's input using Drain + Credit pattern (lines 445-449)

Updates pool reserves atomically


1.3 Backend Token Handling

TokenMergeService.java (backend/src/main/java/com/digitalasset/quickstart/service/TokenMergeService.java):

Automatically consolidates fragmented tokens after swaps

Sequential merge strategy: base + token1 → merged1, merged1 + token2 → merged2, etc.

Prevents UTXO fragmentation by keeping 1 contract per symbol per party


LedgerReader.java:

Queries active contracts via Ledger API

Filters canonical pool tokens from user wallet view

No UTXO selection logic (aggregates all tokens)


LedgerApi.java:

Standard contract creation/exercise methods

Multi-party authorization support

No specialized UTXO selection algorithms


TokenStandardProxy.java:

Connects to Canton Token Standard Registry APIs

Limited implementation (mostly metadata queries)

No Holding/Allocation interface integration


1.4 Frontend Token Types (frontend/src/types/canton.ts)

Current Token Interface:
export interface Token {
  contractId: string;
  issuer: Party;
  owner: Party;
  amount: Amount;
}

No CIP-0056 interfaces defined:

No Holding, Allocation, TransferInstruction types

No metadata structure

No lock context or custodian fields

Simple amount representation (no Decimal precision handling)



2. CIP-0056 REQUIREMENTS ANALYSIS

2.1 UTXO Model Requirements

Canton's Extended UTXO Model:

One-to-one contract ↔ UTXO correspondence ✅ (Already true in DAML)

Privacy-based distribution to stakeholders ✅ (DAML observer pattern)

Ledger API access for wallet-controlled parties ✅ (LedgerApi.java exists)


Current Gap:

ClearportX uses account aggregation pattern (single token contract per owner)

CIP-0056 expects multiple UTXO tokens that can be split/merged

Action Required: Transition from account model to pure UTXO model


2.2 Required Interfaces

CIP-0056 Mandatory Interfaces:


Holding Interface ❌ NOT IMPLEMENTED

Represents investor asset ownership

Required fields: amount, owner, custodian, lock context

Supports split/merge operations

Must use Decimal(10) precision




TransferInstruction Interface ❌ NOT IMPLEMENTED

Peer-to-peer asset movement

Single sender → recipient flow

Deadline-based settlement

Registry abort capability




Allocation Interface ❌ NOT IMPLEMENTED

Atomic DVP (Delivery-versus-Payment) execution

Multi-allocation combining (all-or-nothing)

Time-bounded locks

Auto-unlock post-deadline




AllocationRequest Interface ❌ NOT IMPLEMENTED

Apps request allocations from wallets

Instrument/amount specification

Settlement context reference




AllocationInstruction Interface ❌ NOT IMPLEMENTED

Wallet creates allocations

Responds to allocation requests




Metadata API ⚠️ PARTIAL (TokenStandardAdapter.daml.skip)

Symbol, name, total supply

Registry UI URL

Generic metadata (DNS-prefixed key-value)

Status: Skeleton exists but disabled (.skip extension)




2.3 Metadata Standards

Required Metadata Keys (splice.lfdecentralizedtrust.org/ prefixed):

registry-urls: Registry HTTP endpoint list

lock-context: Human-readable holding restrictions

reason: Workflow transition justifications


Current Status:

No metadata structure in Token.daml

No DNS-prefixed key-value system

No registry URL configuration


2.4 Splitting/Merging Capabilities

Current Implementation:

✅ Split: Transfer and TransferSplit create remainder + output

✅ Merge: Merge choice consolidates two tokens

❌ No formal Holding interface implementation

❌ No lock context preservation during splits


CIP-0056 Requirements:

Split must preserve metadata/lock context

Merge must validate compatible locks

Interface-based choice definitions for transaction parsing



3. PARTIAL IMPLEMENTATION DISCOVERED

3.1 TokenStandardAdapter.daml.skip

Location: /root/cn-quickstart/quickstart/clearportx/daml/ClearportX/AMM/TokenStandardAdapter.daml.skip
Discovered Interfaces (lines 8-12):
import qualified Splice.API.Token.Holding.V1 as Holding
import qualified Splice.API.Token.Metadata.V1 as Metadata
import qualified Splice.API.Token.Transfer.Instruction.V1 as TransferInstruction
import qualified Splice.API.Token.Allocation.V1 as Allocation

Implementation Status:

⚠️ DISABLED: File has .skip extension (not compiled)

Contains bridge templates to convert between ClearportX Token and Token Standard

DevNet configuration for CBTC and Canton Coin included

Three main templates:

TokenStandardBridge: Converts between standards

TransferHelper: Creates transfer instructions

PoolTokenStandardAdapter: Wraps pool operations with standard tokens




Key Bridge Functions:

BridgeFromStandard: Holding → ClearportX Token

BridgeToStandard: ClearportX Token → Holding

AddLiquidityWithStandard: Use standard holdings for liquidity

SwapWithStandard: Execute swaps with standard tokens


Why Disabled:

Likely incomplete implementation

May have compilation errors with current Token.daml

Needs integration with actual Canton Network Token Standard DARs


3.2 CANTON_DEX_COMPLETE_INTEGRATION_PLAN.md

Key Findings (lines 22-24):
Implementation conforme au Canton Network Token Standard (CIP-0056)
Support des interfaces: Holding, TransferInstruction, Allocation, TokenMetadata
Architecture compatible avec CBTC et Canton Coin (Amulet)

DevNet Configuration (lines 88-99):

CBTC admin party ID documented

Registry URL: https://api.utilities.digitalasset-dev.com

Metadata structure (symbol, decimals, totalSupply)


Indicates:

CIP-0056 compliance was a design goal

DevNet integration infrastructure exists

Implementation incomplete/abandoned



4. MAIN DIFFERENCES: CURRENT vs CIP-0056

4.1 UTXO Model vs Account Model

Aspect	Current (ClearportX)	CIP-0056 Required
Token Structure	Single contract per owner/symbol	Multiple UTXO contracts
Balance Representation	Aggregated amount field	Sum of UTXO amounts
Total Supply	Would need manual calculation	Sum all active Holdings
Fragmentation	Prevented via auto-merge	Natural UTXO fragmentation
Selection Logic	N/A (single token)	UTXO selection algorithm needed

4.2 Token Metadata Standards

Aspect	Current	CIP-0056
Metadata Location	None	DNS-prefixed key-value
Symbol/Name	Template fields	Metadata API
Decimals	Hardcoded 10	Decimal(10) type + metadata
Registry URL	None	Required metadata field
Lock Context	None	Standardized lock field
Custodian	None (only owner/issuer)	Required field

4.3 Interoperability Requirements

Aspect	Current	CIP-0056
Interface Pattern	Custom choices	Standardized interface implementations
Cross-Registry	None	Global Synchronizer hub support
Wallet Integration	Custom API	Standard Holding/Allocation interfaces
DVP Support	None	Atomic multi-allocation
External Signing	N/A	24-hour submission delay support
Privacy	Observer pattern	Stakeholder-based UTXO distribution

4.4 Splitting/Merging Capabilities

Operation	Current	CIP-0056
Split Pattern	TransferSplit returns (remainder, output)	Interface-based with metadata preservation
Merge Pattern	Merge validates issuer/owner/symbol	Interface-based with lock validation
Lock Preservation	N/A	Required during splits
Metadata Preservation	N/A	Required for all operations
Automatic Consolidation	Backend service auto-merges	User/wallet controlled


5. REQUIRED CHANGES FOR CIP-0056 COMPLIANCE

5.1 CRITICAL CHANGES (Breaking)

1. Replace Token.daml with Holding-based implementation

Implement Holding interface from Token Standard

Add fields: custodian, lock (Optional LockContext)

Change amount to strict Decimal(10) with validation

Add metadata field structure


2. Implement All Six Required Interfaces

Holding: Asset ownership

TransferInstruction: P2P transfers

Allocation: DVP locks

AllocationRequest: App requests

AllocationInstruction: Wallet responses

Metadata: Token information API


3. Add UTXO Selection Logic (Backend)

Implement coin selection algorithm (largest-first, smallest-first, or optimal)

Handle fragmentation automatically

Support multi-UTXO transfers


4. Registry Integration

Connect to Token Standard Registry APIs

Implement off-ledger metadata storage

Support CNS 1.0 metadata format


5.2 MAJOR CHANGES (Architecture)

1. Pool Token Management Refactor

Replace canonical token pattern with UTXO aggregation

Update reserve calculation to sum pool-owned Holdings

Modify AddLiquidity/RemoveLiquidity to handle UTXO sets

Adapt AtomicSwap to work with multi-UTXO inputs


2. Frontend Type System Update

Define TypeScript interfaces for all CIP-0056 types

Add Decimal precision handling

Implement lock context UI display

Support allocation workflows


3. Backend API Expansion

Add Holding query endpoints

Implement allocation request/response flows

Add metadata query/update methods

Support DVP transaction construction


5.3 MODERATE CHANGES (Enhancement)

1. Metadata System

DNS-prefixed key-value storage

Registry URL configuration

Lock context descriptions

Reason field for workflow transitions


2. Deadline/Timeout Handling

Add 24-hour submission delay support

Implement deadline-based lock expiry

Add registry abort capability


3. Multi-Party Authorization

Extend to support custodian patterns

Add allocation approval workflows

Support external signing delays


5.4 MINOR CHANGES (Compatibility)

1. Decimal Precision

Validate all amounts are Decimal(10)

Add precision preservation in calculations

Update UI to handle 10 decimal places


2. Privacy Enhancement

Ensure stakeholder-based distribution

Add observer patterns for custodians

Implement need-to-know access control


3. Transaction History Parsing

Use interface-based choice definitions

Enable standard event parsing

Support cross-registry transaction views



6. MIGRATION STRATEGY OPTIONS

Option A: Big Bang Migration (High Risk)

Approach: Replace entire token system at once
Pros:

Clean implementation

No dual-system complexity

Full CIP-0056 compliance immediately


Cons:

All existing tokens invalidated

Pool reserves must be recreated

High migration risk

User funds migration required


Timeline: 3-4 months

Option B: Bridge Pattern (Recommended)

Approach: Enable TokenStandardAdapter.daml.skip and run dual systems
Phase 1: Bridge Activation (4 weeks)

Fix compilation errors in TokenStandardAdapter.daml

Deploy bridge contracts

Enable CBTC/Canton Coin bridging

Test two-way conversion


Phase 2: New Token Standard (6 weeks)

Implement all six CIP-0056 interfaces

Create new pool templates using Holdings

Deploy alongside existing pools

Migrate liquidity provider by provider


Phase 3: Deprecation (8 weeks)

Incentivize migration to new pools

Freeze old token creation

Gradual shutdown of legacy system

Archive old contracts


Pros:

Gradual migration

Users choose migration timing

Fallback to old system if issues

Lower risk


Cons:

Dual system complexity

Bridge overhead

Longer timeline

More code to maintain


Timeline: 4-5 months total

Option C: Hybrid Model (Pragmatic)

Approach: Keep internal account model, implement external CIP-0056 interfaces
Implementation:

Internal pools continue using aggregated Token.daml

External-facing APIs implement Holding interface

Automatic conversion at system boundaries

Bridge for CBTC/Canton Coin integration


Pros:

Minimal internal changes

AMM atomicity preserved

CIP-0056 compliant externally

Fastest to implement


Cons:

Not pure UTXO internally

Conversion overhead

Potential compliance questions

May limit some CIP-0056 features


Timeline: 2-3 months

7. IMPLEMENTATION PRIORITIES

Priority 1: CRITICAL (Required for basic compliance)


Implement Holding interface

Implement TransferInstruction interface

Add metadata structure (symbol, name, decimals)

Enable TokenStandardAdapter bridge

Connect to Token Standard Registry


Priority 2: HIGH (Required for full ecosystem integration)


Implement Allocation interface (DVP support)

Implement AllocationRequest/AllocationInstruction

Add UTXO selection logic

Update pool contracts for UTXO handling

Frontend type system update


Priority 3: MEDIUM (Enhanced functionality)


DNS-prefixed metadata system

Lock context support

Custodian field implementation

24-hour submission delay

Deadline-based expiry


Priority 4: LOW (Nice to have)


Optimized UTXO selection algorithms

Advanced lock validation

Cross-registry transaction views

Enhanced privacy controls

Performance optimizations



8. RISK ANALYSIS

Technical Risks

High Risk:

Pool atomicity may break with pure UTXO model

UTXO fragmentation could impact performance

Multi-UTXO swap complexity increases


Mitigation:

Use hybrid model (Option C) to preserve internal atomicity

Implement aggressive UTXO consolidation

Test extensively with fragmented states


Business Risks

Medium Risk:

User migration friction

Liquidity fragmentation during transition

Potential downtime


Mitigation:

Incentivize early migration

Run dual systems in parallel

Extensive testing on DevNet


Compliance Risks

High Risk:

Incomplete CIP-0056 implementation limits ecosystem integration

Non-standard patterns may not work with wallets/custodians


Mitigation:

Prioritize interface compliance

Regular testing with Canton Coin/CBTC

Engage with Canton Network community for validation



9. RECOMMENDATIONS

Immediate Actions (Next 2 weeks)


Enable TokenStandardAdapter.daml - Remove .skip extension, fix compilation

Test CBTC bridging - Validate conversion to/from Canton standard tokens

Implement Holding interface - Start with basic fields, expand incrementally

Document metadata requirements - Define DNS prefix, key structure


Short-term Goals (1-3 months)


Deploy Option C (Hybrid Model) - Fastest path to compliance

Implement TransferInstruction - Enable P2P transfers

Add UTXO selection to backend - Prepare for multi-UTXO handling

Update frontend types - Support new interface structures


Long-term Goals (3-6 months)


Full CIP-0056 compliance - All six interfaces operational

DVP support - Allocation-based atomic swaps

Cross-registry integration - Support CBTC, Canton Coin, other tokens

Migration to pure UTXO - If performance acceptable, migrate from hybrid


Success Criteria


 All six CIP-0056 interfaces implemented and tested

 Successful CBTC/Canton Coin swap on DevNet

 Wallet integration working (standard Holding queries)

 DVP transactions executable

 Metadata API fully functional

 Performance acceptable with UTXO fragmentation

 User migration >80% complete

 No critical bugs in production for 30 days



10. CONCLUSION

The ClearportX codebase has strong foundations with UTXO-compatible features (split, merge, drain) and partial CIP-0056 implementation (TokenStandardAdapter). However, it currently operates on an account model optimized for AMM atomicity rather than the pure UTXO model required by CIP-0056.
Key Gap: Missing all six mandatory interfaces (Holding, TransferInstruction, Allocation, AllocationRequest, AllocationInstruction, Metadata).
Recommended Path: Hybrid Model (Option C) balances speed, risk, and compliance. Keep internal account aggregation for pool operations while implementing external CIP-0056 interfaces for ecosystem interoperability.
Timeline: 2-3 months to basic compliance, 4-6 months to full implementation including DVP support and cross-registry integration.
Next Step: Enable and test TokenStandardAdapter.daml bridge to validate approach before committing to full implementation.
Je vais consulter la documentation Canton pour avoir une compréhension complète avant de finaliser le plan CIP-0056.
Web Fetchhttps://docs.digitalasset.com/index.html
Fetched from https://docs.digitalasset.com/index.html
Je vais maintenant explorer les sections spécifiques de la documentation pour trouver les détails sur CIP-0056.
WebSearch
IN
{
  "query": "CIP-0056 Canton token standard specification site:docs.digitalasset.com"
}
OUT
Web search results for query: "CIP-0056 Canton token standard specification site:docs.digitalasset.com"

I'll search for the CIP-0056 Canton token standard specification on the Digital Asset documentation site.

Links: [{"title":"Token Standard — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/token-standard/index.html"},{"title":"Sending Deposits to Exchanges — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/deposits-into-exchanges/index.html"},{"title":"SDK components — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/build/3.3/overview/sdk_components.html"},{"title":"Canton Network Overview — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/canton-network-overview/index.html"},{"title":"Integration Architecture — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/exchange-integration/architecture.html"},{"title":"Canton Coin Rewards — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/canton-coin-rewards/index.html"},{"title":"Integrating with the Canton Network — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/integrating-with-canton-network/index.html"},{"title":"Introduction to Splice in the Canton Network — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/build/3.3/quickstart/operate/introduction-to-splice-in-cn.html"},{"title":"Integration Workflows — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/exchange-integration/workflows.html"},{"title":"Get started with Canton and the JSON Ledger API — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/build/3.3/tutorials/json-api/canton_and_the_json_ledger_api.html"}]

Based on the search results, I found information about the CIP-0056 Canton token standard specification. Here's what I discovered:

## CIP-0056 Canton Network Token Standard

The CIP-0056 specification is defined at https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md. This is the official Canton Improvement Proposal that defines the token standard for the Canton Network.

### Key Information about the Token Standard:

1. **Purpose and Integration**: The token standard provides a complete framework for token operations on the Canton Network, with implementations exposed through SDK components like sdk.tokenStandard.

2. **API Definitions**: The API definitions for the token standard can be found in the sub-directories of https://github.com/hyperledger-labs/splice/tree/main/token-standard.

3. **Transfer Mechanism**: The default behavior for all tokens is a 2-step transfer, which matches how funds are usually transferred in traditional finance (TradFi), though this is counter-intuitive in the blockchain world.

4. **Metadata Keys**: The token standard defines the splice.lfdecentralizedtrust.org/reason metadata key for communicating human-readable descriptions for transfers.

5. **Background and Motivation**: CIP-0056 provides background on the design and motivation of the Canton Network Token Standard, making applications compatible with any Canton Network token.

6. **Registry API**: The OpenAPI specification of the Registry API is maintained as part of the Canton Network Token Standard definitions in the Splice repository.

The token standard is designed to enable interoperability across the Canton Network, allowing developers to build applications that work with any Canton Network token and enabling token issuers to make their tokens compatible with all applications and wallet UIs building on the standard.
Parfait ! Je vais maintenant consulter la documentation détaillée du Token Standard.
Web Fetchhttps://docs.digitalasset.com/integrate/devnet/token-standard/index.html
Fetched from https://docs.digitalasset.com/integrate/devnet/token-standard/index.html
Maintenant je vais consulter le CIP-0056 directement depuis GitHub pour avoir la spécification complète.
Web Fetchhttps://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md
Fetched from https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md
Excellent ! Maintenant je vais créer un plan complet et détaillé pour la migration CIP-0056 avec toutes les explications nécessaires.
Claude’s Plan
Plan Détaillé : Migration vers CIP-0056 Token Standard
Vue d'ensemble de la migration
Objectif: Transformer ClearportX d'un système de tokens propriétaire vers le standard CIP-0056 pour permettre l'interopérabilité avec tout l'écosystème Canton Network (wallets, exchanges, autres DEX). Stratégie recommandée: Approche hybride - garder le modèle interne pour l'AMM (atomicité garantie) tout en exposant les interfaces CIP-0056 vers l'extérieur. Temps estimé: 120-150 heures (10-12 jours à 12h/jour)
Phase 1: Analyse et Préparation (16 heures)
1.1 Audit du Code Existant (8h)
POURQUOI C'EST NÉCESSAIRE:
Comprendre exactement comment les tokens fonctionnent actuellement
Identifier les dépendances critiques pour l'AMM
Évaluer l'impact de la migration sur les pools existants
Documenter les risques
À QUOI ÇA SERT:
Analyse à faire:
1. Token.daml → Comment les tokens sont créés/transférés
2. Pool.daml → Comment l'AMM utilise les tokens
3. AtomicSwap.daml → Les garanties d'atomicité
4. TokenMergeService → La consolidation automatique
MISE EN PLACE:
Lire tous les contracts DAML ligne par ligne
Tracer le flow d'un swap complet (entrée → pool → sortie)
Identifier les points de friction avec UTXO
Créer un diagramme d'architecture actuelle vs future
1.2 Activation du TokenStandardAdapter (8h)
POURQUOI:
Un bridge existe déjà mais est désactivé (.skip)
Contient la logique de conversion Token ↔ Holding
Permet de tester sans casser le système actuel
À QUOI ÇA SERT: Le bridge permet:
ClearportX Token → Canton Holding (pour wallets externes)
Canton Holding → ClearportX Token (pour pools internes)
MISE EN PLACE:
# 1. Renommer le fichier
mv TokenStandardAdapter.daml.skip TokenStandardAdapter.daml

# 2. Importer les DARs du Token Standard
daml add-dep canton-token-standard-1.0.0.dar

# 3. Compiler et corriger les erreurs
daml build

# 4. Tester la conversion bidirectionnelle
Phase 2: Implémentation des Interfaces CIP-0056 (32 heures)
2.1 Interface Holding (12h)
POURQUOI C'EST NÉCESSAIRE:
C'est l'interface principale pour représenter la propriété d'un token
Les wallets Canton cherchent des Holdings, pas des Token custom
Requis pour apparaître dans les portfolios utilisateurs
À QUOI ÇA SERT:
template Holding with
    amount : Decimal        -- Montant du token (10 décimales)
    owner : Party          -- Propriétaire
    custodian : Party      -- Gardien (peut être = owner)
    lock : Optional Lock   -- Verrouillage pour DVP
    instrument : InstrumentId -- Identifiant unique du token
    metadata : Map Text Text  -- Métadonnées extensibles
MISE EN PLACE:
-- Nouveau fichier: daml/CIP0056/Holding.daml
module CIP0056.Holding where

import qualified Splice.API.Token.Holding.V1 as Standard

template ClearportXHolding 
  with
    -- Champs CIP-0056 obligatoires
    amount : Decimal
    owner : Party
    custodian : Party
    lock : Optional LockContext
    instrument : InstrumentId
    
    -- Champs ClearportX additionnels
    issuer : Party  -- Pour compatibilité
    poolCompatible : Bool -- Peut être utilisé dans pools
    
  where
    signatory custodian, owner
    observer issuer
    
    -- Implémenter l'interface standard
    interface instance Standard.Holding for ClearportXHolding where
      view = Standard.View with
        amount = amount
        owner = owner
        custodian = custodian
        lock = lock
        instrument = instrument
    
    -- Split UTXO (requis par CIP-0056)
    choice Split : (ContractId ClearportXHolding, ContractId ClearportXHolding)
      with splitAmount : Decimal
      controller owner
      do
        assert (splitAmount > 0.0 && splitAmount < amount)
        remainder <- create this with amount = amount - splitAmount
        newHolding <- create this with amount = splitAmount
        return (remainder, newHolding)
    
    -- Merge UTXOs (consolidation)
    choice Merge : ContractId ClearportXHolding
      with otherCid : ContractId ClearportXHolding
      controller owner
      do
        other <- fetch otherCid
        assert (instrument == other.instrument)
        assert (lock == other.lock)  -- Même contexte de lock
        archive otherCid
        create this with amount = amount + other.amount
2.2 Interface TransferInstruction (8h)
POURQUOI:
Permet les transferts peer-to-peer standards
Intégration avec tous les wallets Canton
Support du modèle 2-étapes (sender → pending → receiver)
À QUOI ÇA SERT:
Flow de transfert CIP-0056:
1. Sender crée TransferInstruction
2. Receiver voit l'instruction pending
3. Receiver accepte ou rejette
4. Si accepté: Holdings transférés
5. Si timeout: annulation automatique
MISE EN PLACE:
template TransferInstruction with
    sender : Party
    receiver : Party
    instrument : InstrumentId
    amount : Decimal
    holdingCids : [ContractId Holding]  -- UTXOs à transférer
    deadline : Time  -- Expiration
    metadata : Map Text Text
  where
    signatory sender
    observer receiver
    
    choice Accept : [ContractId Holding]
      controller receiver
      do
        -- Transférer la propriété des Holdings
        forA holdingCids $ \cid -> do
          holding <- fetch cid
          archive cid
          create holding with owner = receiver
    
    choice Reject : ()
      controller receiver
      do return ()
    
    choice Withdraw : [ContractId Holding]
      controller sender
      do 
        -- Annuler si pas encore accepté
        return holdingCids
2.3 Interface Allocation pour DVP (12h)
POURQUOI C'EST NÉCESSAIRE:
DVP = Delivery vs Payment (livraison contre paiement)
Permet les swaps atomiques multi-assets
Standard pour les échanges institutionnels
Évite le risque de contrepartie
À QUOI ÇA SERT:
Exemple DVP:
Alice veut échanger 100 USDC contre 0.05 BTC avec Bob

1. Alice alloue 100 USDC (verrouillé 5 min)
2. Bob alloue 0.05 BTC (verrouillé 5 min)
3. App exécute l'échange atomique
4. Si timeout: déverrouillage automatique
MISE EN PLACE:
template Allocation with
    allocator : Party
    beneficiary : Party
    holdingCids : [ContractId Holding]
    lockContext : Text  -- "DVP swap USDC/BTC"
    deadline : Time
    settlementId : Text  -- ID unique du settlement
  where
    signatory allocator
    observer beneficiary
    
    choice ExecuteSettlement : ([ContractId Holding], [ContractId Holding])
      with 
        counterAllocation : ContractId Allocation
      controller beneficiary
      do
        -- Vérifier que les deux allocations matchent
        counter <- fetch counterAllocation
        assert (counter.settlementId == settlementId)
        
        -- Échanger les holdings atomiquement
        myNewHoldings <- transferHoldings holdingCids beneficiary
        theirNewHoldings <- transferHoldings counter.holdingCids allocator
        
        archive counterAllocation
        return (myNewHoldings, theirNewHoldings)
    
    choice ReleaseOnTimeout : [ContractId Holding]
      controller allocator
      do
        currentTime <- getTime
        assert (currentTime > deadline)
        -- Déverrouiller les holdings
        forA holdingCids $ \cid -> do
          h <- fetch cid
          archive cid
          create h with lock = None
Phase 3: Service de Métadonnées et Registry (24 heures)
3.1 Token Metadata Service (12h)
POURQUOI C'EST NÉCESSAIRE:
CIP-0056 requiert des métadonnées standardisées
Les wallets affichent nom, symbole, décimales depuis les métadonnées
Le registry a besoin d'URLs pour découvrir les tokens
Support des clés DNS-prefixées pour extensibilité
À QUOI ÇA SERT:
{
  "symbol": "USDC",
  "name": "USD Coin",
  "decimals": 10,
  "totalSupply": "1000000000",
  "splice.lfdecentralizedtrust.org/registry-urls": "https://api.clearportx.com/registry",
  "splice.lfdecentralizedtrust.org/icon": "https://clearportx.com/icons/usdc.png",
  "clearportx.com/pool-compatible": "true",
  "clearportx.com/issuer": "Circle"
}
MISE EN PLACE:
// backend/src/main/java/com/digitalasset/quickstart/service/TokenMetadataService.java

@Service
public class TokenMetadataService {
    
    private final Map<String, TokenMetadata> metadataCache = new ConcurrentHashMap<>();
    
    // Structure des métadonnées
    public record TokenMetadata(
        String symbol,
        String name,
        int decimals,
        BigDecimal totalSupply,
        Map<String, String> customMetadata
    ) {
        // Convertir en format CIP-0056
        public Map<String, Object> toCIP0056Format() {
            Map<String, Object> result = new HashMap<>();
            result.put("symbol", symbol);
            result.put("name", name);
            result.put("decimals", decimals);
            result.put("totalSupply", totalSupply.toString());
            
            // Ajouter les clés DNS-prefixées
            customMetadata.forEach((key, value) -> {
                if (!key.contains("/")) {
                    // Ajouter notre préfixe si pas déjà prefixé
                    key = "clearportx.com/" + key;
                }
                result.put(key, value);
            });
            
            // URLs du registry obligatoires
            result.put("splice.lfdecentralizedtrust.org/registry-urls", 
                      config.getRegistryUrls());
            
            return result;
        }
    }
    
    // Calculer le total supply (somme de tous les Holdings)
    public CompletableFuture<BigDecimal> calculateTotalSupply(InstrumentId instrument) {
        return ledgerApi.queryAllHoldings(instrument)
            .thenApply(holdings -> 
                holdings.stream()
                    .map(h -> h.amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
            );
    }
    
    // API endpoint pour les métadonnées
    @GetMapping("/api/registry/token/{instrumentId}/metadata")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getMetadata(
        @PathVariable String instrumentId
    ) {
        return loadMetadata(instrumentId)
            .thenApply(metadata -> ResponseEntity.ok(metadata.toCIP0056Format()));
    }
}
3.2 Registry Integration Service (12h)
POURQUOI:
Permet la découverte des tokens ClearportX par d'autres apps
Expose les Holdings via API HTTP standardisée
Support du Byzantine Fault Tolerance (plusieurs endpoints)
Requis pour l'interopérabilité
À QUOI ÇA SERT: Le registry permet aux wallets externes de:
Découvrir les tokens disponibles
Voir les holdings d'un utilisateur
Obtenir l'historique des transactions
Vérifier le total supply
MISE EN PLACE:
// backend/src/main/java/com/digitalasset/quickstart/service/RegistryService.java

@RestController
@RequestMapping("/api/registry")
public class RegistryController {
    
    // Liste tous les tokens disponibles
    @GetMapping("/tokens")
    public CompletableFuture<List<TokenInfo>> listTokens() {
        return tokenService.getAllTokens()
            .thenApply(tokens -> tokens.stream()
                .map(this::toTokenInfo)
                .collect(Collectors.toList()));
    }
    
    // Holdings d'un party (sans auth car contract IDs = secrets)
    @GetMapping("/holdings/{partyId}")
    public CompletableFuture<List<HoldingDTO>> getHoldings(
        @PathVariable String partyId,
        @RequestParam(required = false) Boolean includeLocked
    ) {
        boolean withLocked = includeLocked != null ? includeLocked : false;
        
        return ledgerApi.queryHoldings(partyId)
            .thenApply(holdings -> holdings.stream()
                .filter(h -> withLocked || h.lock == null)
                .map(this::toHoldingDTO)
                .collect(Collectors.toList()));
    }
    
    // Historique des transactions
    @GetMapping("/transactions/{partyId}")
    public CompletableFuture<TransactionHistory> getTransactionHistory(
        @PathVariable String partyId,
        @RequestParam(required = false) String offset,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return ledgerApi.streamTransactions(partyId, offset, limit)
            .thenApply(this::toTransactionHistory);
    }
    
    // Total supply d'un token
    @GetMapping("/token/{instrumentId}/supply")
    public CompletableFuture<SupplyInfo> getTotalSupply(
        @PathVariable String instrumentId
    ) {
        return metadataService.calculateTotalSupply(instrumentId)
            .thenApply(supply -> new SupplyInfo(
                instrumentId,
                supply,
                Instant.now()
            ));
    }
}
Phase 4: Migration des Pools vers CIP-0056 (32 heures)
4.1 Adapter les Pools pour Holdings (16h)
POURQUOI C'EST NÉCESSAIRE:
Les pools actuels utilisent Token custom
Doivent accepter les Holdings CIP-0056
Maintenir la rétro-compatibilité pendant la transition
À QUOI ÇA SERT: Permettre aux pools d'accepter:
Anciens tokens ClearportX (pendant transition)
Nouveaux Holdings CIP-0056
CBTC et Canton Coin du network
MISE EN PLACE:
-- Nouveau: daml/AMM/PoolV2.daml
template PoolV2 with
    -- Champs existants
    operator : Party
    poolId : Text
    tokenA : InstrumentId  -- Maintenant utilise InstrumentId
    tokenB : InstrumentId
    reserveA : Decimal
    reserveB : Decimal
    
    -- Nouveaux champs CIP-0056
    holdingsA : [ContractId Holding]  -- UTXOs du pool
    holdingsB : [ContractId Holding]
    supportLegacy : Bool  -- Accept anciens tokens
    
  where
    signatory operator
    
    -- AddLiquidity avec Holdings
    choice AddLiquidityV2 : ContractId PoolV2
      with
        provider : Party
        holdingsInA : [ContractId Holding]
        holdingsInB : [ContractId Holding]
        minLPTokens : Decimal
      controller provider
      do
        -- Calculer les montants totaux
        amountA <- sumHoldings holdingsInA
        amountB <- sumHoldings holdingsInB
        
        -- Vérifier le ratio
        let expectedB = (amountA * reserveB) / reserveA
        assert (abs(amountB - expectedB) < 0.01)  -- 1% tolerance
        
        -- Calculer les LP tokens
        let lpAmount = calculateLPTokens amountA amountB reserveA reserveB
        assert (lpAmount >= minLPTokens)
        
        -- Transférer les holdings au pool
        newHoldingsA <- transferToPool holdingsInA
        newHoldingsB <- transferToPool holdingsInB
        
        -- Mettre à jour le pool
        create this with
          holdingsA = holdingsA ++ newHoldingsA
          holdingsB = holdingsB ++ newHoldingsB
          reserveA = reserveA + amountA
          reserveB = reserveB + amountB
    
    -- Swap avec Holdings
    choice SwapV2 : [ContractId Holding]
      with
        trader : Party
        holdingsIn : [ContractId Holding]
        tokenOut : InstrumentId
        minAmountOut : Decimal
      controller trader
      do
        -- Déterminer la direction du swap
        let isAtoB = (getInstrument holdingsIn == tokenA)
        
        -- Calculer le montant de sortie
        amountIn <- sumHoldings holdingsIn
        let amountOut = if isAtoB
          then calculateSwapOutput amountIn reserveA reserveB
          else calculateSwapOutput amountIn reserveB reserveA
        
        assert (amountOut >= minAmountOut)
        
        -- Exécuter le swap
        if isAtoB then do
          -- Recevoir A, envoyer B
          newHoldingsA <- transferToPool holdingsIn
          (outputHoldings, remainingB) <- splitHoldings holdingsB amountOut
          
          create this with
            holdingsA = holdingsA ++ newHoldingsA
            holdingsB = remainingB
            reserveA = reserveA + amountIn
            reserveB = reserveB - amountOut
          
          return outputHoldings
        else do
          -- Recevoir B, envoyer A
          -- ... (symétrique)
4.2 Service de Migration des Pools (16h)
POURQUOI:
Transition progressive des anciens pools vers V2
Éviter de bloquer les fonds des utilisateurs
Maintenir la liquidité pendant la migration
À QUOI ÇA SERT:
Plan de migration:
1. Déployer PoolV2 en parallèle de Pool
2. Router nouveaux dépôts vers PoolV2
3. Inciter migration avec bonus APY
4. Migrer automatiquement les inactifs
5. Archiver Pool après 30 jours
MISE EN PLACE:
// backend/src/main/java/com/digitalasset/quickstart/service/PoolMigrationService.java

@Service
public class PoolMigrationService {
    
    @Scheduled(fixedDelay = 3600000) // Toutes les heures
    public void migrateInactivePools() {
        ledgerApi.queryPools()
            .thenApply(pools -> pools.stream()
                .filter(p -> isInactive(p) && !isV2(p))
                .forEach(this::migratePool));
    }
    
    private CompletableFuture<Result<PoolV2>> migratePool(Pool oldPool) {
        return CompletableFuture.supplyAsync(() -> {
            // 1. Créer PoolV2 avec même configuration
            PoolV2 newPool = createV2Pool(oldPool);
            
            // 2. Convertir les tokens en Holdings
            List<Holding> holdingsA = convertToHoldings(oldPool.tokenACid);
            List<Holding> holdingsB = convertToHoldings(oldPool.tokenBCid);
            
            // 3. Transférer la liquidité
            transferLiquidity(oldPool, newPool, holdingsA, holdingsB);
            
            // 4. Rediriger les swaps
            routingService.updateRoute(oldPool.poolId, newPool.poolId);
            
            // 5. Archiver l'ancien pool après vérification
            if (verifyMigration(oldPool, newPool)) {
                archiveOldPool(oldPool);
                return Result.ok(newPool);
            } else {
                return Result.err("MIGRATION_FAILED", "Verification failed");
            }
        });
    }
    
    // Bonus APY pour inciter la migration
    public BigDecimal getMigrationBonus(String poolId) {
        if (isV2Pool(poolId)) {
            return new BigDecimal("0.02"); // +2% APY
        }
        return BigDecimal.ZERO;
    }
}
Phase 5: Intégration UTXO et Gestion (24 heures)
5.1 UTXO Selection Service (12h)
POURQUOI C'EST NÉCESSAIRE:
CIP-0056 utilise le modèle UTXO (comme Bitcoin)
Pour envoyer 100 USDC, il faut peut-être combiner 3-4 UTXOs
Optimiser la sélection réduit les frais et la fragmentation
Éviter d'utiliser des UTXOs verrouillés
À QUOI ÇA SERT:
Exemple: User veut swap 100 USDC
Ses UTXOs: [50, 30, 20, 15, 10, 5] USDC

Algorithmes possibles:
1. Greedy: [50, 30, 20] = 100 exact ✓
2. Optimal: Minimiser le nombre d'UTXOs
3. Privacy: Éviter de lier tous les UTXOs
MISE EN PLACE:
// backend/src/main/java/com/digitalasset/quickstart/service/UTXOSelectionService.java

@Service
public class UTXOSelectionService {
    
    public enum SelectionStrategy {
        GREEDY,     // Plus gros d'abord
        OPTIMAL,    // Minimiser le nombre
        PRIVACY,    // Maximiser l'anonymat
        FIFO        // Plus vieux d'abord
    }
    
    public Result<SelectedUTXOs> selectUTXOs(
        String party,
        InstrumentId instrument,
        BigDecimal targetAmount,
        SelectionStrategy strategy
    ) {
        return fetchAvailableUTXOs(party, instrument)
            .flatMap(utxos -> {
                switch (strategy) {
                    case GREEDY:
                        return selectGreedy(utxos, targetAmount);
                    case OPTIMAL:
                        return selectOptimal(utxos, targetAmount);
                    case PRIVACY:
                        return selectPrivacy(utxos, targetAmount);
                    case FIFO:
                        return selectFIFO(utxos, targetAmount);
                    default:
                        return Result.err("UNKNOWN_STRATEGY", "Invalid selection strategy");
                }
            });
    }
    
    // Algorithme Greedy: prendre les plus gros d'abord
    private Result<SelectedUTXOs> selectGreedy(List<UTXO> utxos, BigDecimal target) {
        List<UTXO> sorted = utxos.stream()
            .sorted((a, b) -> b.amount.compareTo(a.amount))
            .collect(Collectors.toList());
        
        List<UTXO> selected = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        
        for (UTXO utxo : sorted) {
            if (total.compareTo(target) >= 0) break;
            
            selected.add(utxo);
            total = total.add(utxo.amount);
        }
        
        if (total.compareTo(target) < 0) {
            return Result.err("INSUFFICIENT_FUNDS", 
                String.format("Need %s, have %s", target, total));
        }
        
        BigDecimal change = total.subtract(target);
        return Result.ok(new SelectedUTXOs(selected, total, change));
    }
    
    // Algorithme Optimal: minimiser le nombre d'UTXOs (knapsack problem)
    private Result<SelectedUTXOs> selectOptimal(List<UTXO> utxos, BigDecimal target) {
        // Utiliser dynamic programming pour trouver la combinaison optimale
        // qui utilise le moins d'UTXOs possible
        
        // Convertir en centimes pour éviter les décimales
        long targetCents = target.multiply(new BigDecimal(100)).longValue();
        
        // DP[amount] = minimum number of UTXOs to reach amount
        Map<Long, Integer> dp = new HashMap<>();
        Map<Long, List<UTXO>> selection = new HashMap<>();
        
        dp.put(0L, 0);
        selection.put(0L, new ArrayList<>());
        
        for (UTXO utxo : utxos) {
            long utxoCents = utxo.amount.multiply(new BigDecimal(100)).longValue();
            
            // Parcourir en ordre décroissant pour éviter la réutilisation
            for (long amount = targetCents; amount >= utxoCents; amount--) {
                long prev = amount - utxoCents;
                
                if (dp.containsKey(prev)) {
                    int newCount = dp.get(prev) + 1;
                    
                    if (!dp.containsKey(amount) || newCount < dp.get(amount)) {
                        dp.put(amount, newCount);
                        
                        List<UTXO> newSelection = new ArrayList<>(selection.get(prev));
                        newSelection.add(utxo);
                        selection.put(amount, newSelection);
                    }
                }
            }
        }
        
        // Trouver le montant minimum >= target
        for (long amount = targetCents; amount <= targetCents * 2; amount++) {
            if (selection.containsKey(amount)) {
                List<UTXO> selected = selection.get(amount);
                BigDecimal total = selected.stream()
                    .map(u -> u.amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                    
                return Result.ok(new SelectedUTXOs(
                    selected,
                    total,
                    total.subtract(target)
                ));
            }
        }
        
        return Result.err("INSUFFICIENT_FUNDS", "Cannot make exact amount");
    }
}
5.2 UTXO Consolidation Service (12h)
POURQUOI:
Éviter la fragmentation excessive (trop de petits UTXOs)
Réduire les coûts de transaction
Améliorer les performances
Nettoyer automatiquement
À QUOI ÇA SERT:
Consolidation automatique:
- Si user a >10 UTXOs du même token
- Si UTXOs < 1% du montant moyen
- Pendant les heures creuses
- Grouper par batch de 5-10
MISE EN PLACE:
@Service
public class UTXOConsolidationService {
    
    @Scheduled(cron = "0 0 3 * * ?") // 3h du matin
    public void consolidateFragmentedUTXOs() {
        ledgerApi.getAllParties()
            .thenApply(parties -> parties.parallelStream()
                .forEach(this::consolidatePartyUTXOs));
    }
    
    private void consolidatePartyUTXOs(String party) {
        // Grouper les UTXOs par instrument
        Map<InstrumentId, List<UTXO>> utxosByInstrument = 
            ledgerApi.queryUTXOs(party).stream()
                .collect(Collectors.groupingBy(u -> u.instrument));
        
        utxosByInstrument.forEach((instrument, utxos) -> {
            if (shouldConsolidate(utxos)) {
                consolidate(party, instrument, utxos);
            }
        });
    }
    
    private boolean shouldConsolidate(List<UTXO> utxos) {
        // Critères de consolidation
        if (utxos.size() <= 3) return false; // Pas assez fragmenté
        
        BigDecimal total = utxos.stream()
            .map(u -> u.amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        BigDecimal average = total.divide(
            new BigDecimal(utxos.size()), 
            10, 
            RoundingMode.HALF_UP
        );
        
        // Compter les "dust" UTXOs (< 1% de la moyenne)
        long dustCount = utxos.stream()
            .filter(u -> u.amount.compareTo(average.multiply(new BigDecimal("0.01"))) < 0)
            .count();
            
        // Consolider si >30% sont du "dust"
        return dustCount > utxos.size() * 0.3;
    }
    
    private CompletableFuture<Result<ContractId>> consolidate(
        String party,
        InstrumentId instrument,
        List<UTXO> utxos
    ) {
        // Grouper par batch de 10 maximum (limite Canton)
        List<List<UTXO>> batches = Lists.partition(utxos, 10);
        
        return batches.stream()
            .map(batch -> mergeBatch(party, batch))
            .reduce(CompletableFuture.completedFuture(Result.ok(null)),
                (a, b) -> a.thenCombine(b, Result::combine));
    }
}
Phase 6: Tests et Validation (22 heures)
6.1 Tests de Conformité CIP-0056 (12h)
POURQUOI C'EST NÉCESSAIRE:
Vérifier que toutes les interfaces sont correctement implémentées
S'assurer que les wallets externes peuvent interagir
Valider avec CBTC et Canton Coin
Obtenir la certification de conformité
À QUOI ÇA SERT: Tests à effectuer:
Holdings: création, split, merge, query
Transfers: 2-step flow, timeout, preapproval
Allocations: lock, unlock, DVP execution
Metadata: format, registry URLs, total supply
Interop: CBTC bridge, Canton Coin swap
MISE EN PLACE:
@SpringBootTest
class CIP0056ComplianceTest {
    
    @Test
    void testHoldingInterface() {
        // 1. Créer un Holding
        Holding holding = createHolding("alice", "USDC", 1000);
        assertThat(holding.amount).isEqualTo(Decimal.of(1000));
        assertThat(holding.owner).isEqualTo("alice");
        
        // 2. Split
        var (remainder, split) = holding.split(300);
        assertThat(remainder.amount).isEqualTo(700);
        assertThat(split.amount).isEqualTo(300);
        
        // 3. Merge
        Holding merged = remainder.merge(split);
        assertThat(merged.amount).isEqualTo(1000);
    }
    
    @Test
    void testTransferFlow() {
        // 1. Alice crée instruction de transfert
        TransferInstruction instruction = alice.createTransfer(
            bob, "USDC", 100, Duration.ofMinutes(5)
        );
        
        // 2. Bob voit l'instruction pending
        List<TransferInstruction> pending = bob.getPendingTransfers();
        assertThat(pending).contains(instruction);
        
        // 3. Bob accepte
        bob.acceptTransfer(instruction);
        
        // 4. Vérifier que Bob a reçu les fonds
        BigDecimal bobBalance = bob.getBalance("USDC");
        assertThat(bobBalance).isEqualTo(100);
    }
    
    @Test
    void testDVPSwap() {
        // 1. Alice alloue USDC
        Allocation aliceAlloc = alice.allocate("USDC", 100, "swap-123");
        
        // 2. Bob alloue BTC
        Allocation bobAlloc = bob.allocate("BTC", 0.01, "swap-123");
        
        // 3. Exécuter le swap atomiquement
        SwapResult result = executeSwap(aliceAlloc, bobAlloc);
        
        // 4. Vérifier les nouveaux balances
        assertThat(alice.getBalance("BTC")).isEqualTo(0.01);
        assertThat(bob.getBalance("USDC")).isEqualTo(100);
    }
    
    @Test
    void testCBTCBridge() {
        // 1. Obtenir CBTC depuis le faucet
        ContractId cbtc = getCBTCFromFaucet(100);
        
        // 2. Convertir en Holding ClearportX
        Holding holding = bridge.fromCBTC(cbtc);
        assertThat(holding.instrument.symbol).isEqualTo("CBTC");
        
        // 3. Utiliser dans un pool
        pool.addLiquidity(holding, usdcHolding);
        
        // 4. Reconvertir en CBTC standard
        ContractId cbtcBack = bridge.toCBTC(holding);
        assertThat(cbtcBack).isNotNull();
    }
}
6.2 Tests de Performance avec UTXOs (10h)
POURQUOI:
Le modèle UTXO peut causer de la fragmentation
Impact sur les performances des requêtes
Vérifier la scalabilité
Optimiser les algorithmes
À QUOI ÇA SERT: Mesurer:
Temps de sélection UTXO avec 1000+ UTXOs
Performance des swaps multi-UTXO
Impact de la consolidation
Latence des queries
MISE EN PLACE:
@Test
void testPerformanceWithFragmentation() {
    // 1. Créer 1000 UTXOs fragmentés
    List<UTXO> utxos = IntStream.range(0, 1000)
        .mapToObj(i -> createUTXO(randomAmount()))
        .collect(Collectors.toList());
    
    // 2. Mesurer la sélection
    long startTime = System.currentTimeMillis();
    SelectedUTXOs selected = utxoService.selectUTXOs(
        "alice", "USDC", BigDecimal.valueOf(500), OPTIMAL
    );
    long selectionTime = System.currentTimeMillis() - startTime;
    
    assertThat(selectionTime).isLessThan(100); // <100ms
    
    // 3. Mesurer un swap avec ces UTXOs
    startTime = System.currentTimeMillis();
    SwapResult result = pool.swap(selected.utxos, "ETH", 0.2);
    long swapTime = System.currentTimeMillis() - startTime;
    
    assertThat(swapTime).isLessThan(500); // <500ms
    
    // 4. Tester la consolidation
    startTime = System.currentTimeMillis();
    UTXO consolidated = consolidationService.consolidate(utxos);
    long consolidationTime = System.currentTimeMillis() - startTime;
    
    assertThat(consolidationTime).isLessThan(2000); // <2s
    assertThat(consolidated.amount).isEqualTo(sumOf(utxos));
}
Chronologie de Mise en Œuvre
Semaine 1 (60h)
Jours 1-2: Phase 1 - Analyse et activation bridge (16h) Jours 3-4: Phase 2.1 - Interface Holding (12h) + Tests Jour 5: Phase 2.2 - TransferInstruction (8h) + Tests
Semaine 2 (60h)
Jour 6: Phase 2.3 - Allocation DVP (12h) Jours 7-8: Phase 3 - Metadata et Registry (24h) Jours 9-10: Phase 4.1 - Adapter pools (16h) + Tests
Semaine 3 (30h)
Jour 11: Phase 5.1 - UTXO Selection (12h) Jour 12: Phase 5.2 - Consolidation (12h) Jour 13: Phase 6 - Tests complets (10h) Total: 150 heures (12-13 jours à 12h/jour)
Risques et Mitigations
Risque 1: Fragmentation UTXO
Impact: Performance dégradée Mitigation: Consolidation automatique nocturne + limites
Risque 2: Migration des pools
Impact: Liquidité temporairement bloquée Mitigation: Migration progressive avec incentives
Risque 3: Incompatibilité wallet
Impact: Users ne peuvent pas utiliser leurs tokens Mitigation: Tests exhaustifs avec Canton Wallet SDK
Risque 4: Complexité DVP
Impact: Swaps échouent Mitigation: Commencer par transfers simples, DVP en phase 2
Critères de Succès
✅ Toutes les interfaces CIP-0056 implémentées ✅ Tests de conformité passent à 100% ✅ Swap CBTC/USDC fonctionne sur DevNet ✅ Performance <500ms pour swaps normaux ✅ Wallets externes peuvent voir les Holdings ✅ Registry API accessible et documentée ✅ Migration de 3 pools pilotes réussie ✅ Pas de perte de fonds pendant migration ✅ Documentation complète pour développeurs
Recommandations Finales
Commencer par le bridge: Activer TokenStandardAdapter pour tester
Approche hybride: Garder le modèle interne pour l'AMM
Migration progressive: Ne pas forcer, inciter
Tests exhaustifs: Surtout avec CBTC et Canton Coin
Documentation: Cruciale pour adoption
Monitoring: Surveiller fragmentation et performance
Cette migration positionnera ClearportX comme LE DEX compatible CIP-0056 sur Canton Network, permettant l'interopérabilité totale avec l'écosystème.
Stayed in plan mode
Je comprends parfaitement. Vous avez un wallet validator avec 130,000 Canton Coin, vous allez demander des CBTC DevNet, et vous voulez créer directement des pools avec Holdings CIP-0056. Je vais faire un audit complet de votre code actuel et créer un plan précis et pragmatique basé sur la documentation Digital Asset.
Update Todos

Audit complete ClearportX codebase current state

Research CBTC DevNet integration and wallet receiving

Create Holdings-based pool implementation

Implement wallet integration for receiving CBTC

Setup Canton Coin/CBTC pool with real Holdings

Implement transfer pre-approval for auto-accept

Create wallet authentication flow

Test end-to-end with DevNet tokens
Task:Complete audit of current state
IN
{
  "subagent_type": "Plan",
  "description": "Complete audit of current state",
  "prompt": "Perform a complete audit of the ClearportX codebase to understand the EXACT current state. Focus on:\n\n1. **Current Token Implementation Status**:\n   - Check daml/Token/Token.daml for current structure\n   - Look for any CIP-0056 related code or imports\n   - Check if TokenStandardAdapter.daml.skip exists and what it contains\n   - Look for any Holdings or UTXO implementations\n\n2. **Pool Implementation Analysis**:\n   - Analyze daml/AMM/Pool.daml current state\n   - Check AtomicSwap.daml implementation\n   - Look for any V2 or Holdings-compatible pools\n   - Check SwapRequest.daml and related contracts\n\n3. **Backend Integration Points**:\n   - Check for any wallet integration code\n   - Look for CBTC or Canton Coin references\n   - Analyze current authentication implementation\n   - Check for any DevNet specific configurations\n\n4. **Frontend Status**:\n   - Check what's currently broken or in progress\n   - Look at recent commits for context\n   - Check any error handling or compilation issues\n\n5. **DevNet Configuration**:\n   - Look for any existing DevNet URLs or configurations\n   - Check for validator party configurations\n   - Look for any wallet or external party setup\n\n6. **What's Working vs What's Not**:\n   - Identify which features are currently functional\n   - Find any incomplete migrations or half-implemented features\n   - Check test status\n\nProvide a PRECISE assessment of:\n- What exists and works\n- What's partially implemented\n- What's completely missing\n- Critical blockers\n- Immediate next steps based on actual code state"
}
OUT
Perfect! Now I have a complete picture. Let me compile this comprehensive audit report.

CLEARPORTX CODEBASE AUDIT REPORT

Date: 2025-11-11

Audit Type: Complete System State Analysis

Status: Production-Ready with Gaps

EXECUTIVE SUMMARY

ClearportX is a functional AMM DEX with a custom token implementation running on Canton 3.3. The system has:

Working atomic swap functionality

Complete pool and liquidity management

Backend API with authentication

Frontend integration (partial)

NO Canton Network token standard integration yet


Overall Status: 75% Complete - Core AMM works, but Canton Network DevNet integration incomplete.

1. TOKEN IMPLEMENTATION STATUS

Current State: CUSTOM TOKEN (Working)

File: /root/cn-quickstart/quickstart/clearportx/daml/Token/Token.daml (161 lines)
Architecture:
template Token
  with
    issuer : Party      -- ONLY signatory (centralized trust model)
    owner  : Party      -- Observer only, NOT signatory
    symbol : Text
    amount : Numeric 10

Key Design Decisions:


Issuer-Controlled: Only issuer is signatory, owner is observer

Pro: Enables atomic swaps without recipient authorization

Con: Complete trust in issuer (can inflate supply)




No Contract Keys: Prevents key collisions during transfers

Tokens tracked by ContractId only

Pool must pass token CIDs instead of lookupByKey




Nonconsuming Choices: Manual archive control

Transfer: Move tokens between parties

Merge: Consolidate fragmented tokens (critical for pools)

Credit: Add tokens to existing contract

Drain: Extract amount and archive

TransferSplit: Return both remainder and sent token CIDs




Security Warning (from code comments):

"This token design requires COMPLETE TRUST in the token issuer. Issuer has UNILATERAL CONTROL over all tokens. This is a CENTRALIZED design chosen for AMM atomicity."

Canton Network Token Standard: NOT IMPLEMENTED

File: /root/cn-quickstart/quickstart/clearportx/daml/ClearportX/AMM/TokenStandardAdapter.daml.skip
Status:

❌ File is .skip - NOT compiled

❌ NO CIP-0056 compliance

❌ NO Holdings contracts in use

❌ NO UTXO implementation


What Exists in .skip File:
import qualified Splice.API.Token.Holding.V1 as Holding
import qualified Splice.API.Token.Metadata.V1 as Metadata
import qualified Splice.API.Token.Transfer.Instruction.V1 as TransferInstruction

Adapter Templates (skipped):

TokenStandardBridge: Convert between custom Token and Holdings

TransferHelper: Create transfer instructions

PoolTokenStandardAdapter: Pool operations with Token Standard


Configuration (hardcoded in .skip):
data TokenConfig = TokenConfig
  with
    cbtcAdmin : Party  -- cbtc-network::12202a83...
    cbtcId : Text      -- "CBTC"
    cantonAdmin : Party -- DSO::12202a83...
    cantonId : Text    -- "Amulet"
    registryUrl : Text -- "https://api.utilities.digitalasset-dev.com"

CRITICAL FINDING: No Token Standard Integration

Impact:

Cannot interact with Canton Network native tokens (CBTC, Canton Coin)

Must use custom minted tokens for testing

Real DevNet deployment blocked without Holdings support



2. POOL IMPLEMENTATION ANALYSIS

Pool.daml - PRODUCTION READY

File: /root/cn-quickstart/quickstart/clearportx/daml/AMM/Pool.daml (477 lines)
Architecture:
template Pool
  with
    poolOperator : Party
    poolParty : Party
    lpIssuer : Party
    issuerA : Party
    issuerB : Party
    symbolA : Text
    symbolB : Text
    feeBps : Int
    totalLPSupply : Numeric 10
    reserveA : Numeric 10
    reserveB : Numeric 10
    tokenACid : Optional (ContractId Token)  -- Canonical token CIDs
    tokenBCid : Optional (ContractId Token)
    protocolFeeReceiver : Party
    maxInBps : Int    -- Max input as % of reserve (10000 = 100%)
    maxOutBps : Int   -- Max output as % of reserve (5000 = 50%)

Key Features:


NO Contract Keys (DAML 3.x compatibility)

Discovery via PoolAnnouncement template

Tracked by ContractId only




Constant Product Formula (x * y = k):
let ainFee = inputAmount * feeMul
let denom = rin + ainFee
let aout = (ainFee * rout) / denom



Canonical Token Management:

Pool stores tokenACid and tokenBCid for reserves

Merge incoming tokens to prevent fragmentation

Archive-and-recreate pattern to update reserves




Protocol Fee Split:

Total fee: 0.3% (30 bps)

Protocol: 25% of fee (0.075%)

LPs: 75% of fee (0.225%)




Choices:

AddLiquidity: Deposit tokens, mint LP tokens

RemoveLiquidity: Burn LP tokens, withdraw tokens

AtomicSwap: Single-step swap (avoids staleness)

ArchiveAndUpdateReserves: Update pool state after swap

VerifyReserves: Reconcile stored vs actual balances

GetSpotPrice: Query mid-market price


Security Features:
ensure
  (symbolA, show issuerA) < (symbolB, show issuerB) &&  -- Canonical ordering
  totalLPSupply >= 0.0 &&
  reserveA >= 0.0 &&
  reserveB >= 0.0 &&
  maxInBps > 0 && maxInBps <= 10000 &&
  maxOutBps > 0 && maxOutBps <= 10000 &&
  (totalLPSupply == 0.0 || (reserveA > 0.0 && reserveB > 0.0))  -- HIGH-4 FIX

AtomicSwap.daml - WORKING

File: /root/cn-quickstart/quickstart/clearportx/daml/AMM/AtomicSwap.daml (68 lines)
Purpose: Single-transaction swap wrapper (avoids pool CID staleness)
template AtomicSwapProposal
  with
    trader : Party
    poolCid : ContractId Pool
    traderInputTokenCid : ContractId Token
    inputSymbol : Text
    inputAmount : Numeric 10
    outputSymbol : Text
    minOutput : Numeric 10
    maxPriceImpactBps : Int
    deadline : Time
    
  choice ExecuteAtomicSwap : ContractId Receipt
    controller trader, poolParty
    do
      -- Directly exercise Pool.AtomicSwap
      (traderOutputCid, newPoolCid) <- exercise poolCid Pool.AtomicSwap with ...
      create Receipt with ...

SwapRequest.daml - LEGACY (Still Works)

File: /root/cn-quickstart/quickstart/clearportx/daml/AMM/SwapRequest.daml (245 lines)
Two-Phase Flow:

SwapRequest: Trader creates request

PrepareSwap: Extract protocol fee, transfer to pool

SwapReady: Intermediate state

ExecuteSwap: Pool executes swap, updates reserves


Status: Works but deprecated in favor of AtomicSwap
CRITICAL FINDINGS

What Works:

Atomic swaps with protocol fee collection

Reserve consolidation via Token.Merge

Slippage protection

Price impact limits

Liquidity provision with LP tokens


What's Missing:

NO Holdings-compatible pools

NO V2 pools with Token Standard

Pool discovery relies on PoolAnnouncement (append-only)



3. BACKEND INTEGRATION ANALYSIS

Spring Boot Backend - FUNCTIONAL

Base: /root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart
Controllers (11 total):
✅ SwapController.java         - Atomic swap endpoint
✅ LiquidityController.java    - Add/remove liquidity
✅ PoolCreationController.java - Direct pool creation (debug)
✅ DevNetController.java       - DevNet utilities
✅ AuthController.java         - OAuth2 authentication
✅ LedgerController.java       - Generic ledger operations
✅ TestSwapController.java     - Testing endpoints
⚠️ ResolveGrantController.java - Canton Network grants (partial)

Swap Implementation - PRODUCTION READY

File: SwapController.java (785 lines)
Endpoints:

POST /api/swap/prepare - Create SwapRequest (2-phase)

POST /api/swap/execute - Execute prepared swap

POST /api/swap/atomic - Single-step atomic swap ⭐


Atomic Swap Flow (lines 469-770):
@PostMapping("/atomic")
public CompletableFuture<AtomicSwapResponse> atomicSwap(...) {
  // 1. Validate input (SwapValidator)
  swapValidator.validateTokenPair(req.inputSymbol, req.outputSymbol);
  
  // 2. Fetch FRESH pool snapshot (no cache!)
  CompletableFuture<List<Pool>> poolsFuture = ledger.getActiveContracts(Pool.class);
  CompletableFuture<List<Token>> tokensFuture = ledger.getActiveContracts(Token.class);
  
  // 3. CRITICAL: Validate pool canonicals are ACTIVE
  .filter(p -> {
    boolean hasTokenA = p.payload.getTokenACid.isPresent()
      && activeTokenCids.contains(p.payload.getTokenACid.get());
    boolean hasTokenB = p.payload.getTokenBCid.isPresent()
      && activeTokenCids.contains(p.payload.getTokenBCid.get());
    return hasTokenA && hasTokenB;
  })
  
  // 4. Create AtomicSwapProposal + execute with both parties
  ledger.createAndGetCid(proposalTemplate, ...)
    .thenCompose(proposalCid -> 
      ledger.exerciseAndGetResult(proposalCid, executeChoice, ...)
    )
}

Key Features:

Idempotency support (X-Idempotency-Key header)

Metrics collection (SwapMetrics)

Stale CID detection and retry (StaleAcsRetry)

Token merge service integration

Fresh ACS snapshots (no PQS cache)


Authentication - DUAL MODE

DevNet Config: DevNetSecurityConfig.java
@Profile("devnet")
@EnableWebSecurity
public class DevNetSecurityConfig {
  
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) {
    http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());  // ⚠️ Open for demo
  }
  
  @Bean
  Auth auth() {
    return Auth.SHARED_SECRET;  // No OAuth2 client
  }
}

OAuth2 Config: application-oauth2.yml
spring.security.oauth2:
  resourceserver.jwt:
    issuer-uri: http://keycloak.localhost:8082/realms/splice
  client.registration:
    AppProviderBackend:  # Backend ↔ Ledger
      client-id: ${AUTH_APP_PROVIDER_BACKEND_CLIENT_ID}
      authorization-grant-type: client_credentials
    AppProvider:  # User ↔ Backend
      client-id: ${AUTH_APP_PROVIDER_BACKEND_OIDC_CLIENT_ID}
      authorization-grant-type: authorization_code

CRITICAL ISSUE: OAuth2 config references Keycloak on localhost, not Canton Network
CBTC/Canton Coin References - MINIMAL

From application.yml:
token.standard:
  registry.url: ${TOKEN_STANDARD_REGISTRY_URL:https://api.utilities.digitalasset-dev.com}
  cbtc:
    admin: ${CBTC_ADMIN:cbtc-network::12202a83...}
    id: ${CBTC_ID:CBTC}
  canton:
    admin: ${CANTON_ADMIN:DSO::12202a83...}
    id: ${CANTON_ID:Amulet}

Status:

Configuration exists

NO backend code uses these settings

NO integration with Canton Network token registry



4. FRONTEND STATUS

Environment - LOCAL ONLY

File: /root/cn-quickstart/quickstart/frontend/.env
REACT_APP_AUTH_ENABLED=false
REACT_APP_BACKEND_API_URL=http://localhost:8080
REACT_APP_PARTY_ID=app_provider_quickstart-root-1::12201300...
REACT_APP_NETWORK=devnet

Recent Changes (Last 10 Commits)

5832255d - Resolver no longer forces latest package; default to highest TVL
b6633681 - Pools list prefers latest AMM package per pair
6bc58dea - Fix template-id endpoint path
366792cd - Prefer latest AMM package when resolving poolCid
3e746bbd - Wallet balance filter to drop giant faucet mints
a3022afa - Pool highest TVL
968c1e2d - FE
8284893f - Wallet view: add /api/wallet/tokens/{party}
7d640980 - Fix received amount via pool reserve delta

Analysis: Frontend is actively being developed, focus on pool resolution and wallet integration
backendApi.ts - WORKING

File: frontend/src/services/backendApi.ts (100+ lines visible)
Key Endpoints Used:
POST /api/swap/atomic      // Atomic swap
POST /api/liquidity/add    // Add liquidity
POST /api/liquidity/remove // Remove liquidity
GET /api/pools            // List pools
GET /api/wallet/tokens/{party}  // Wallet balances
GET /api/health           // Backend health

Party Mapping:
const PARTY_MAPPING: Record<string, string> = {
  'alice@clearportx': DEVNET_PARTY,
  'alice': DEVNET_PARTY,
  'AppProvider': DEVNET_PARTY,
};

CURRENT ISSUES (from commit messages)

Error 409: Recent commit says "try to make this swap work fk error 409"

HTTP 409 = Conflict

Likely: Stale pool CID or token CID

Solution already in backend: StaleAcsRetry + canonical validation



5. DEVNET CONFIGURATION

Current Setup - HYBRID

Backend Env (from docker/backend-service/env/app.env):
REGISTRY_BASE_URI=http://splice:5012
LEDGER_HOST=canton
LEDGER_PORT=3001
APP_PROVIDER_PARTY=app_provider_quickstart-root-1::12201300...
CORS_ALLOWED_ORIGINS=http://localhost:3000,...

Frontend Env:
REACT_APP_BACKEND_API_URL=http://localhost:8080
REACT_APP_PARTY_ID=app_provider_quickstart-root-1::12201300...

Status:

Running on LOCAL Canton participant

NOT connected to Canton Network DevNet

NO validator setup yet


Canton Network DevNet URLs - FROM DOCS

From AUTO_ACCEPT_CBTC_SUCCESS.md:
Validator Party: ClearportX-DEX-1::122043801dccdfd8c892fa46ebc1dafc901f7992218886840830aeef1cf7eacedd09
CBTC Network: cbtc-network::12202a83c6f4e8e2a5cb5f44fab8c5ef19001b7d79c6cc0a17db596ad993eb59f5a2
Merchant Party: merchant::1220fa8543db00cb46ed3b316f8b9df86ad89b86ba48e8c83266e0a8b7dcc97ce55e
Registry URL: https://api.utilities.digitalasset-dev.com

Auto-Accept Config (from validator):
canton.validator-apps.validator_backend.auto-accept-transfers."ClearportX-DEX-1::122043801...".from-parties = [
  "cbtc-network::12202a83...",
  "merchant::1220fa8543...",
  "auth0_007c65f8..."
]

CRITICAL GAPS

What's Missing:

NO validator configuration in backend

NO wallet integration for CBTC/Canton Coin

NO connection to Canton Network ledger endpoints

Backend still points to local Canton participant



6. WHAT'S WORKING VS NOT WORKING

✅ WORKING (Verified)

DAML Contracts:

Token.daml (custom implementation)

Pool.daml with constant product AMM

AtomicSwap.daml (single-transaction swaps)

SwapRequest.daml (2-phase swaps)

LPToken.daml (liquidity provision)

ProtocolFees.daml (fee collection)


Backend:

Atomic swap endpoint (/api/swap/atomic)

Pool creation and discovery

Liquidity management

Token merge service

Metrics collection

Idempotency support

Stale CID retry logic


Security:

Input validation (SwapValidator)

Price impact limits (max 50%)

Slippage protection

Division by zero guards

Constant product invariant checks


⚠️ PARTIALLY WORKING

Frontend:

Pool discovery (works but has package version issues)

Swap UI (hits 409 errors - stale CIDs)

Wallet display (custom tokens only)


Authentication:

OAuth2 configured but not for Canton Network

DevNet mode bypasses auth (permitAll)


❌ NOT WORKING / MISSING

Canton Network Integration:

NO Holdings contract support

NO CBTC integration (TokenStandardAdapter.daml.skip)

NO Canton Coin integration

NO connection to Canton Network DevNet ledger

NO validator wallet integration


Token Standard:

CIP-0056 compliance: Not implemented

UTXO model: Not implemented

Transfer Instructions: Not implemented


Frontend Issues:

Error 409 on swaps (stale CIDs)

Only supports custom tokens

No Canton Network wallet connection



7. CRITICAL BLOCKERS

BLOCKER 1: Token Standard Adapter Disabled

File: daml/ClearportX/AMM/TokenStandardAdapter.daml.skip
Impact:

Cannot use CBTC or Canton Coin

Blocked from real Canton Network deployment

Must use custom minted tokens


Solution Needed:

Enable TokenStandardAdapter.daml (remove .skip)

Implement Holdings ↔ Custom Token bridge

Add TransferInstruction handling

Update Pool.daml to accept Holdings


Effort: 2-3 days (medium complexity)
BLOCKER 2: No Canton Network Ledger Connection

Current: Backend connects to local Canton participant
Needed: Connect to Canton Network DevNet validator
Configuration Changes Required:
# Backend application.yml
ledger:
  registry-base-uri: https://api.utilities.digitalasset-dev.com
  participant-host: <validator-ledger-api-endpoint>
  participant-port: 5001

# Add validator party
application.tenants:
  ClearportX:
    partyId: ClearportX-DEX-1::122043801dccdfd8c892fa46ebc1dafc901f7992218886840830aeef1cf7eacedd09

Effort: 1 day (configuration + testing)
BLOCKER 3: Frontend Stale CID Errors (409)

Recent Commit: "try to make this swap work fk error 409"
Root Cause:

Frontend caches pool/token CIDs

Pool updates (swaps/liquidity) create new CIDs

Frontend tries to use old CIDs → 409 Conflict


Solution (already in backend):
// SwapController.java line 516-521
CompletableFuture<List<Pool>> poolsFuture = ledger.getActiveContracts(Pool.class);  // Fresh!
CompletableFuture<List<Token>> tokensFuture = ledger.getActiveContracts(Token.class);  // Fresh!

// Line 540-553: Validate pool canonicals are ACTIVE
.filter(p -> {
  boolean hasTokenA = activeTokenCids.contains(p.payload.getTokenACid.get());
  boolean hasTokenB = activeTokenCids.contains(p.payload.getTokenBCid.get());
  return hasTokenA && hasTokenB;
})

Frontend Fix Needed:

Always fetch fresh pool CID before swap

Don't cache pool/token CIDs between operations

Use backend's pool resolution logic


Effort: 4 hours (frontend refactor)

8. IMMEDIATE NEXT STEPS

Priority 1: Fix Frontend 409 Errors (TODAY)

Action:
// backendApi.ts - Before every swap
async function atomicSwap(params: SwapParams) {
  // 1. Fetch fresh pool list
  const pools = await this.getPools();
  
  // 2. Find pool by token pair (don't use cached poolId!)
  const pool = pools.find(p => 
    (p.symbolA === params.inputSymbol && p.symbolB === params.outputSymbol) ||
    (p.symbolA === params.outputSymbol && p.symbolB === params.inputSymbol)
  );
  
  // 3. Use FRESH poolCid
  return axios.post('/api/swap/atomic', {
    poolId: pool.poolId,  // Fresh!
    ...params
  });
}

Test:

Add liquidity to pool

Execute swap

Add more liquidity

Execute another swap

Should NOT get 409


Priority 2: Enable TokenStandardAdapter (THIS WEEK)

Action:
cd /root/cn-quickstart/quickstart/clearportx
mv daml/ClearportX/AMM/TokenStandardAdapter.daml.skip daml/ClearportX/AMM/TokenStandardAdapter.daml

# Update daml.yaml
data-dependencies:
  - splice-amulet-0.1.5
  - splice-wallet-0.1.5
  - canton-coin-1.0.0
  - cbtc-1.0.0

# Build
daml build

Implement Bridge Logic:

Convert Holdings → Custom Token for AMM

Convert Custom Token → Holdings for withdrawal

Handle TransferInstructions

Test with CBTC mock


Priority 3: Canton Network DevNet Connection (NEXT WEEK)

Action:

Set up validator node (use existing ClearportX-DEX-1 party)

Configure backend to connect to validator participant

Update OAuth2 to use Canton Network auth

Test CBTC/Canton Coin deposits

Deploy AMM DAR to validator



9. ARCHITECTURE RECOMMENDATIONS

Current Architecture: FUNCTIONAL

┌─────────────────┐
│   Frontend      │  React (localhost:3000)
│   (Netlify)     │  - Pool discovery ⚠️ stale CIDs
└────────┬────────┘  - Swap UI
         │           - Wallet (custom tokens only)
         │
         ▼
┌─────────────────┐
│   Backend       │  Spring Boot (localhost:8080)
│   (Java)        │  - Atomic swap ✅
│                 │  - Pool management ✅
│                 │  - Token merge ✅
└────────┬────────┘  - OAuth2 (local Keycloak)
         │
         ▼
┌─────────────────┐
│  Canton         │  Local Participant
│  (Participant)  │  - Custom Token ✅
│                 │  - Pool contracts ✅
└─────────────────┘  - NO Holdings ❌

Target Architecture: CANTON NETWORK

┌─────────────────┐
│   Frontend      │  React (Netlify)
│   (dApp)        │  - Canton Wallet integration
└────────┬────────┘  - CBTC/Canton Coin support
         │
         ▼
┌─────────────────┐
│   Backend       │  Spring Boot
│   (API)         │  - Holdings adapter
│                 │  - Transfer Instructions
└────────┬────────┘  - Canton Network OAuth2
         │
         ▼
┌─────────────────┐
│  Canton Network │  DevNet Validator
│  (Validator)    │  - ClearportX-DEX-1 party
│                 │  - CBTC holdings
│                 │  - Canton Coin holdings
└─────────────────┘  - Auto-accept transfers ✅


10. FINAL ASSESSMENT

System Status: 75% COMPLETE

Production-Ready Components (50%):

Core AMM logic (Pool, AtomicSwap)

Backend API (Swap, Liquidity)

Security hardening (validation, limits)

Metrics and monitoring


Needs Work (25%):

Frontend stability (409 errors)

Token Standard integration

Canton Network connection

Wallet integration


Not Started (25%):

CBTC/Canton Coin support

Holdings adapter

Multi-hop routing (backend)

DevNet deployment


Immediate Blockers


Frontend 409 Errors: Fix CID caching (4 hours)

Token Standard: Enable adapter (2-3 days)

DevNet Connection: Configure validator (1 day)


Time to Production Canton Network

Optimistic: 1 week

Day 1: Fix frontend

Day 2-4: Enable TokenStandardAdapter

Day 5: Connect to DevNet

Day 6-7: Test with real CBTC


Realistic: 2 weeks

Week 1: Frontend + adapter

Week 2: DevNet integration + testing


Business Impact

What Works Today:

Can demo AMM with custom tokens

Can show atomic swaps working

Can prove security hardening


What's Missing for Launch:

Real Canton Network tokens

Production validator setup

User wallet integration



CONCLUSION

ClearportX has a solid technical foundation with working AMM logic, but is currently in a hybrid state between custom implementation and Canton Network integration. The core contracts are production-ready, but the Token Standard adapter is disabled and Canton Network connectivity is incomplete.
Key Strengths:

Well-architected AMM (constant product, fees, LP tokens)

Robust backend with atomic swaps

Security-focused design (15/15 vulnerabilities fixed)

Good test coverage (73.3%)


Critical Gaps:

No Canton Network token standard support

Frontend stability issues (stale CIDs)

Missing DevNet deployment configuration


Recommendation: Focus on the three immediate blockers in order:

Fix frontend 409 errors (highest user impact)

Enable TokenStandardAdapter (highest technical debt)

Configure DevNet connection (highest business value)


This will unblock the path to production Canton Network deployment.
Maintenant je vais créer un plan précis et pragmatique basé sur l'audit complet et la documentation Canton Network, en me concentrant sur la création directe de pools avec Holdings et l'intégration CBTC.
Task:Research wallet and CBTC integration
IN
{
  "subagent_type": "Plan",
  "description": "Research wallet and CBTC integration",
  "prompt": "Research the Digital Asset documentation for specific information about:\n\n1. **CBTC DevNet Integration**:\n   - How to receive CBTC from faucet or transfers\n   - CBTC contract structure and interfaces\n   - How to bridge CBTC to custom Holdings\n   - CBTC admin party and registry endpoints\n\n2. **Wallet Integration for Receiving Tokens**:\n   - Transfer pre-approval mechanism for auto-accept\n   - How wallets receive Holdings/tokens\n   - TransferInstruction workflow\n   - Auto-accept configuration\n\n3. **Canton Network Validator Setup**:\n   - How to connect backend to validator participant\n   - Ledger API endpoints for DevNet\n   - Authentication with validator\n   - Party registration process\n\n4. **Holdings Pool Implementation**:\n   - How to create pools that accept Holdings directly\n   - UTXO management in pools\n   - Atomic swaps with Holdings\n   - Integration with existing Token Standard\n\nLook for specific code examples, configuration samples, and API endpoints.\n\nSearch in:\n- Digital Asset docs about wallet integration\n- Canton Network DevNet documentation\n- Token Standard implementation guides\n- CBTC specific documentation\n\nProvide concrete technical details needed for implementation."
}
continue
Update Todos

Audit complete ClearportX codebase current state

Research CBTC DevNet integration and wallet receiving

Create Holdings-based pool implementation

Implement wallet integration for receiving CBTC

Setup Canton Coin/CBTC pool with real Holdings

Implement transfer pre-approval for auto-accept

Create wallet authentication flow

Test end-to-end with DevNet tokens
Je vais maintenant rechercher en profondeur la documentation sur l'intégration CBTC et comment recevoir des tokens depuis un wallet Canton.
Web Fetchhttps://docs.digitalasset.com/integrate/devnet/index.html
Fetched from https://docs.digitalasset.com/integrate/devnet/index.html
Je vais maintenant consulter spécifiquement la section sur les transferts et le pre-approval.
Web Fetchhttps://docs.digitalasset.com/integrate/devnet/transfer-preapproval.html
Request failed with status code 404
Je vais chercher la documentation sur comment recevoir des tokens CBTC.
WebSearch
IN
{
  "query": "CBTC faucet DevNet Canton Network how to receive test tokens site:docs.digitalasset.com OR site:docs.sync.global"
}
OUT
Web search results for query: "CBTC faucet DevNet Canton Network how to receive test tokens site:docs.digitalasset.com OR site:docs.sync.global"

I'll search for information about CBTC faucet and how to receive test tokens on DevNet Canton Network from the specified documentation sites.

Links: [{"title":"Canton Network Overview — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/canton-network-overview/index.html"},{"title":"Integrating with the Canton Network — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/integrating-with-canton-network/index.html"},{"title":"Validator Node Operations — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/exchange-integration/node-operations.html"},{"title":"Canton Coin Rewards — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/canton-coin-rewards/index.html"},{"title":"Token Standard — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/token-standard/index.html"},{"title":"Wallet Integration Guide — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/integrate/devnet/index.html"},{"title":"Canton Network App Quickstart FAQ — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/build/3.4/quickstart/troubleshoot/cnqs-faq.html"},{"title":"Introduction to Splice in the Canton Network — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/build/3.3/quickstart/operate/introduction-to-splice-in-cn.html"},{"title":"Utilities — Digital Asset’s platform documentation","url":"https://docs.digitalasset.com/utilities/devnet/"},{"title":"Global Synchronizer for the Canton Network — Splice documentation","url":"https://docs.sync.global/"}]

Based on the search results, I found information about CBTC faucet and test tokens on DevNet Canton Network. Here are the key findings:

## CBTC Faucet on DevNet Canton Network

On DevNet or LocalNet, you don't have to wait that long: just "Tap" the required funds from the built-in faucet.

### How to Receive Test Tokens on DevNet:

1. **Using the Wallet UI (for validators)**:
   On DevNet, you can self-feature your validator operator party as follows: Log into the wallet UI for the validator user, which presents itself as in this screenshot: Tap 20 $ of CC to ensure that your validator operator party has enough funds to purchase traffic.

2. **Using the SDK's Tap Method**:
   Most blockchains support some form of a faucet (that allows to receive a small amount of funds to play with). On canton we allow the tap method that is only present on DevNet (or LocalNet), which allows you to programmatically request test tokens.

   The tap method can be used with code like:
   ```javascript
   const [tapCommand, disclosedContracts] = await sdk.tokenStandard!.createTap(
     myParty,
     '2000000', // how much coins you want
   )
   ```

### Important Notes:

- The evaluation license includes VPN access to DevNet.
- CN DevNet connects to actual decentralized Global Synchronizer infrastructure operated by Super Validators and requires whitelisted VPN access and connectivity.
- To integrate and support tokens, it is recommended to use the Splice documentation here: https://docs.dev.sync.global/app_dev/token_standard/index.html

The faucet functionality is specifically available on DevNet and LocalNet environments for testing purposes, allowing developers to obtain Canton Coin (CC) test tokens without having to wait for actual transfers or purchases.
Parfait ! Maintenant je vais créer un plan complet et précis basé sur l'audit et les informations trouvées.
Claude’s Plan
PLAN D'ACTION PRAGMATIQUE : ClearportX → Canton Network DevNet
Contexte Actuel (Basé sur l'Audit)
AMM fonctionnel avec tokens custom (75% complet)
PAS de support CIP-0056 (TokenStandardAdapter.daml.skip désactivé)
Erreurs 409 sur le frontend (CIDs périmés)
Wallet validator avec 130,000 Canton Coin disponibles
Besoin: Recevoir CBTC et créer des pools Holdings
PHASE 1 : RÉSOLUTION IMMÉDIATE (8 heures)
1.1 Corriger l'Erreur 409 Frontend (2h) ⚡ URGENT
PROBLÈME IDENTIFIÉ:
Frontend cache les pool/token CIDs → Deviennent périmés après chaque swap → Erreur 409
SOLUTION BACKEND DÉJÀ DISPONIBLE (SwapController.java ligne 516):
// TOUJOURS récupérer des snapshots FRAIS
CompletableFuture<List<Pool>> poolsFuture = ledger.getActiveContracts(Pool.class);
CompletableFuture<List<Token>> tokensFuture = ledger.getActiveContracts(Token.class);
ACTION FRONTEND:
// frontend/src/services/backendApi.ts

// AVANT (problématique):
const cachedPoolId = this.poolCache[tokenPair];
await this.atomicSwap(cachedPoolId, ...);  // 409!

// APRÈS (correct):
async atomicSwap(params: SwapParams) {
  // 1. TOUJOURS récupérer la liste fraîche
  const response = await axios.get('/api/pools');
  const pools = response.data;
  
  // 2. Trouver le pool actuel par paire de tokens
  const pool = pools.find(p => 
    (p.symbolA === params.inputSymbol && p.symbolB === params.outputSymbol) ||
    (p.symbolB === params.inputSymbol && p.symbolA === params.outputSymbol)
  );
  
  if (!pool) throw new Error('Pool not found');
  
  // 3. Utiliser le poolId FRAIS
  return axios.post('/api/swap/atomic', {
    poolId: pool.poolId,  // Toujours frais!
    inputAmount: params.inputAmount,
    inputSymbol: params.inputSymbol,
    outputSymbol: params.outputSymbol,
    minOutput: params.minOutput
  });
}
TEST DE VALIDATION:
Créer un pool USDC/ETH
Faire un swap → Succès
Ajouter de la liquidité
Faire un autre swap → Doit fonctionner (pas 409)
1.2 Activer le TokenStandardAdapter (4h)
FICHIER: daml/ClearportX/AMM/TokenStandardAdapter.daml.skip ACTION 1 - Renommer et Compiler:
cd /root/cn-quickstart/quickstart/clearportx

# 1. Activer le fichier
mv daml/ClearportX/AMM/TokenStandardAdapter.daml.skip \
   daml/ClearportX/AMM/TokenStandardAdapter.daml

# 2. Vérifier les imports nécessaires
cat daml/ClearportX/AMM/TokenStandardAdapter.daml | head -20
# Doit voir: import qualified Splice.API.Token.Holding.V1 as Holding

# 3. Compiler pour voir les erreurs
daml build
ACTION 2 - Corriger les Erreurs de Compilation:
-- Si erreur "Module not found: Splice.API.Token.Holding.V1"
-- Ajouter dans daml.yaml:
data-dependencies:
  - daml-prim
  - daml-stdlib
  # Ajouter ces lignes:
  - canton-token-standard-1.0.0.dar  # À obtenir de Canton Network
ACTION 3 - Implémenter le Bridge Minimal:
module ClearportX.AMM.TokenStandardAdapter where

import qualified Token.Token as T
import qualified Splice.API.Token.Holding.V1 as Holding

-- Bridge pour convertir Holdings → Token custom pour les pools
template HoldingToTokenBridge
  with
    operator : Party
    holding : ContractId Holding.Holding
  where
    signatory operator
    
    choice ConvertToPoolToken : ContractId T.Token
      controller operator
      do
        -- 1. Fetch le Holding
        h <- fetch holding
        let amount = h.amount
            owner = h.owner
            
        -- 2. Archive le Holding
        archive holding
        
        -- 3. Créer un Token custom équivalent
        create T.Token with
          issuer = operator  -- Pool operator devient issuer
          owner = owner
          symbol = getSymbolFromInstrument h.instrument
          amount = amount
1.3 Configuration DevNet Validator (2h)
CONFIGURATION ACTUELLE (à modifier):
# backend/src/main/resources/application.yml
ledger:
  host: canton  # LOCAL - À changer
  port: 3001
  
app-provider:
  party: app_provider_quickstart-root-1::12201300...  # LOCAL party
NOUVELLE CONFIGURATION DEVNET:
# backend/src/main/resources/application-devnet.yml
ledger:
  host: ${VALIDATOR_LEDGER_HOST:validator.devnet.canton}
  port: ${VALIDATOR_LEDGER_PORT:5001}
  
clearportx:
  validator-party: ClearportX-DEX-1::122043801dccdfd8c892fa46ebc1dafc901f7992218886840830aeef1cf7eacedd09
  
canton-network:
  registry-url: https://api.utilities.digitalasset-dev.com
  cbtc-admin: cbtc-network::12202a83c6f4e8e2a5cb5f44fab8c5ef19001b7d79c6cc0a17db596ad993eb59f5a2
  dso-party: DSO::12202a83c6f4e8e2a5cb5f44fab8c5ef19001b7d79c6cc0a17db596ad993eb59f5a2
PHASE 2 : INTÉGRATION CBTC & CANTON COIN (16 heures)
2.1 Recevoir CBTC via Tap (4h)
MÉTHODE 1 - Via SDK JavaScript (Recommandé):
// scripts/tap-cbtc.js
const { WalletSDK } = require('@canton-network/wallet-sdk');

async function tapCBTC() {
  const sdk = new WalletSDK({
    ledgerHost: 'validator.devnet.canton',
    ledgerPort: 5001,
    party: 'ClearportX-DEX-1::122043801...'
  });
  
  // 1. Tap 1000 CBTC depuis le faucet DevNet
  const [tapCommand, disclosedContracts] = await sdk.tokenStandard.createTap(
    'ClearportX-DEX-1::122043801...',
    '1000',  // Amount in smallest unit
    'CBTC'   // Token type
  );
  
  // 2. Submit la commande
  const result = await sdk.ledger.submit(tapCommand, disclosedContracts);
  console.log('CBTC received:', result);
  
  // 3. Vérifier les Holdings
  const holdings = await sdk.tokenStandard.listHoldingUtxos(
    'ClearportX-DEX-1::122043801...',
    false  // includeLocked
  );
  
  console.log('Current CBTC holdings:', holdings);
  return holdings;
}
MÉTHODE 2 - Via Transfer Pre-Approval:
# Configuration validator pour auto-accepter
canton.validator-apps.validator_backend {
  auto-accept-transfers {
    "ClearportX-DEX-1::122043801..." {
      from-parties = [
        "cbtc-network::12202a83c6f4e8e2a5cb5f44fab8c5ef19001b7d79c6cc0a17db596ad993eb59f5a2",
        "merchant::1220fa8543db00cb46ed3b316f8b9df86ad89b86ba48e8c83266e0a8b7dcc97ce55e"
      ]
    }
  }
}
2.2 Créer Pool avec Holdings (8h)
NOUVEAU FICHIER: daml/AMM/HoldingPool.daml
module AMM.HoldingPool where

import qualified Splice.API.Token.Holding.V1 as Holding
import qualified Splice.API.Token.Metadata.V1 as Metadata
import Daml.Finance.Types.Numeric (Decimal)

-- Pool compatible CIP-0056 utilisant des Holdings
template HoldingPool
  with
    operator : Party
    poolId : Text
    instrumentA : InstrumentId
    instrumentB : InstrumentId
    reserveA : Decimal  -- Somme des holdings A
    reserveB : Decimal  -- Somme des holdings B
    holdingsA : [ContractId Holding.Holding]  -- UTXOs du pool
    holdingsB : [ContractId Holding.Holding]
    lpTotalSupply : Decimal
    feeBps : Int  -- 30 = 0.3%
    
  where
    signatory operator
    
    ensure
      reserveA >= 0.0 &&
      reserveB >= 0.0 &&
      lpTotalSupply >= 0.0 &&
      feeBps >= 0 && feeBps <= 10000
    
    -- Ajouter de la liquidité avec des Holdings
    choice AddLiquidityWithHoldings : ContractId HoldingPool
      with
        provider : Party
        holdingsInA : [ContractId Holding.Holding]
        holdingsInB : [ContractId Holding.Holding]
        minLPTokens : Decimal
      controller provider
      do
        -- 1. Calculer les montants totaux des UTXOs
        amountA <- sumHoldings holdingsInA
        amountB <- sumHoldings holdingsInB
        
        -- 2. Vérifier le ratio (doit matcher les réserves)
        if reserveA > 0.0 then do
          let expectedB = (amountA * reserveB) / reserveA
          assertMsg "Incorrect ratio" (abs(amountB - expectedB) < 0.01)
        else
          assertMsg "First liquidity" (reserveA == 0.0 && reserveB == 0.0)
        
        -- 3. Calculer les LP tokens à mint
        let lpAmount = if lpTotalSupply == 0.0
          then sqrt(amountA * amountB)  -- Première liquidité
          else min ((amountA * lpTotalSupply) / reserveA)
                   ((amountB * lpTotalSupply) / reserveB)
        
        assertMsg "Slippage too high" (lpAmount >= minLPTokens)
        
        -- 4. Transférer les Holdings au pool
        newHoldingsA <- forA holdingsInA $ \hCid -> do
          h <- fetch hCid
          archive hCid
          create h with owner = operator  -- Pool devient owner
        
        newHoldingsB <- forA holdingsInB $ \hCid -> do
          h <- fetch hCid
          archive hCid
          create h with owner = operator
        
        -- 5. Créer les LP tokens pour le provider
        lpToken <- create LPHolding with
          owner = provider
          poolId = poolId
          amount = lpAmount
        
        -- 6. Mettre à jour le pool
        create this with
          holdingsA = holdingsA ++ newHoldingsA
          holdingsB = holdingsB ++ newHoldingsB
          reserveA = reserveA + amountA
          reserveB = reserveB + amountB
          lpTotalSupply = lpTotalSupply + lpAmount
    
    -- Swap atomique avec Holdings
    choice AtomicSwapWithHoldings : [ContractId Holding.Holding]
      with
        trader : Party
        holdingsIn : [ContractId Holding.Holding]
        instrumentOut : InstrumentId
        minAmountOut : Decimal
      controller trader
      do
        -- 1. Déterminer la direction du swap
        firstHolding <- fetch (head holdingsIn)
        let isAtoB = (firstHolding.instrument == instrumentA)
        
        -- 2. Calculer input/output
        amountIn <- sumHoldings holdingsIn
        let (rIn, rOut, holdingsOut) = if isAtoB
          then (reserveA, reserveB, holdingsB)
          else (reserveB, reserveA, holdingsA)
        
        -- 3. Formule du constant product avec fees
        let amountInWithFee = amountIn * (10000 - feeBps)
        let numerator = amountInWithFee * rOut
        let denominator = (rIn * 10000) + amountInWithFee
        let amountOut = numerator / denominator
        
        assertMsg "Insufficient output" (amountOut >= minAmountOut)
        
        -- 4. Split les holdings de sortie
        (outputHoldings, remainingHoldings) <- splitHoldings holdingsOut amountOut trader
        
        -- 5. Recevoir les holdings d'entrée
        receivedHoldings <- forA holdingsIn $ \hCid -> do
          h <- fetch hCid
          archive hCid
          create h with owner = operator
        
        -- 6. Mettre à jour le pool
        let (newHoldingsA, newHoldingsB, newReserveA, newReserveB) = if isAtoB
          then (holdingsA ++ receivedHoldings, remainingHoldings,
                reserveA + amountIn, reserveB - amountOut)
          else (remainingHoldings, holdingsB ++ receivedHoldings,
                reserveA - amountOut, reserveB + amountIn)
        
        create this with
          holdingsA = newHoldingsA
          holdingsB = newHoldingsB
          reserveA = newReserveA
          reserveB = newReserveB
        
        -- 7. Retourner les holdings au trader
        return outputHoldings

-- Helper: Calculer la somme des Holdings
sumHoldings : [ContractId Holding.Holding] -> Update Decimal
sumHoldings holdings = do
  amounts <- forA holdings $ \hCid -> do
    h <- fetch hCid
    return h.amount
  return (sum amounts)

-- Helper: Split les holdings pour un montant donné
splitHoldings : [ContractId Holding.Holding] -> Decimal -> Party 
              -> Update ([ContractId Holding.Holding], [ContractId Holding.Holding])
splitHoldings holdings targetAmount recipient = do
  -- Logique pour sélectionner et split les UTXOs
  -- Retourne (outputHoldings pour recipient, remainingHoldings pour pool)
  ...
2.3 Backend Support pour Holdings (4h)
NOUVEAU SERVICE: backend/src/main/java/com/digitalasset/quickstart/service/HoldingPoolService.java
@Service
public class HoldingPoolService {
    
    @Autowired
    private LedgerApi ledger;
    
    // Créer un pool CBTC/Canton Coin
    public CompletableFuture<Result<ContractId>> createCBTCCantonPool() {
        
        // 1. Query nos Holdings CBTC
        CompletableFuture<List<Holding>> cbtcHoldings = 
            ledger.queryHoldings("ClearportX-DEX-1::122043801...", "CBTC");
        
        // 2. Query nos Holdings Canton Coin
        CompletableFuture<List<Holding>> cantonHoldings = 
            ledger.queryHoldings("ClearportX-DEX-1::122043801...", "Amulet");
        
        return cbtcHoldings.thenCombine(cantonHoldings, (cbtc, canton) -> {
            
            // 3. Sélectionner les UTXOs pour le pool initial
            List<ContractId> cbtcUtxos = selectUTXOs(cbtc, new BigDecimal("100"));  // 100 CBTC
            List<ContractId> cantonUtxos = selectUTXOs(canton, new BigDecimal("10000")); // 10k CC
            
            // 4. Créer le HoldingPool
            HoldingPool poolTemplate = new HoldingPool(
                "ClearportX-DEX-1::122043801...",  // operator
                "CBTC-CC-001",  // poolId
                new InstrumentId("CBTC", "cbtc-network::12202a83..."),
                new InstrumentId("Amulet", "DSO::12202a83..."),
                BigDecimal.ZERO,  // reserveA (sera calculé)
                BigDecimal.ZERO,  // reserveB
                Collections.emptyList(),  // holdingsA (vide au début)
                Collections.emptyList(),  // holdingsB
                BigDecimal.ZERO,  // lpTotalSupply
                30  // feeBps (0.3%)
            );
            
            // 5. Créer et ajouter la liquidité initiale
            return ledger.createAndExercise(
                poolTemplate,
                "AddLiquidityWithHoldings",
                new AddLiquidityArgs(cbtcUtxos, cantonUtxos, BigDecimal.ZERO)
            );
        });
    }
    
    // Exécuter un swap avec des Holdings
    public CompletableFuture<Result<List<ContractId>>> swapWithHoldings(
        String poolId,
        List<ContractId> inputHoldings,
        String outputInstrument,
        BigDecimal minOutput
    ) {
        // 1. Trouver le pool
        return ledger.queryPool(poolId)
            .thenCompose(pool -> {
                // 2. Exercer AtomicSwapWithHoldings
                return ledger.exerciseAndGetResult(
                    pool.contractId,
                    new AtomicSwapWithHoldings(
                        getCurrentParty(),
                        inputHoldings,
                        outputInstrument,
                        minOutput
                    ),
                    generateCommandId()
                );
            });
    }
}
PHASE 3 : AUTHENTIFICATION WALLET (12 heures)
3.1 Wallet Party Authentication (6h)
NOUVEAU CONTROLLER: backend/src/main/java/com/digitalasset/quickstart/controller/WalletAuthController.java
@RestController
@RequestMapping("/api/wallet/auth")
public class WalletAuthController {
    
    @Autowired
    private LedgerApi ledger;
    
    @Autowired
    private SessionManager sessions;
    
    // 1. User entre son party ID
    @PostMapping("/register")
    public CompletableFuture<Result<SessionResponse>> registerWalletParty(
        @RequestBody WalletRegistrationRequest request
    ) {
        String partyId = request.getPartyId();
        
        // Valider le format: namespace::fingerprint
        if (!partyId.matches("^[\\w-]+::[a-f0-9]{64}$")) {
            return CompletableFuture.completedFuture(
                Result.err("INVALID_FORMAT", "Party ID format invalid")
            );
        }
        
        // Vérifier que le party existe sur le ledger
        return ledger.getPartyDetails(partyId)
            .thenApply(details -> {
                if (details == null) {
                    return Result.err("PARTY_NOT_FOUND", "Party not registered on ledger");
                }
                
                // Créer une session
                String sessionId = UUID.randomUUID().toString();
                String challenge = generateChallenge();
                
                sessions.create(sessionId, partyId, challenge);
                
                return Result.ok(new SessionResponse(
                    sessionId,
                    challenge,
                    Instant.now().plus(Duration.ofMinutes(30))
                ));
            });
    }
    
    // 2. Vérifier la signature du wallet
    @PostMapping("/verify")
    public CompletableFuture<Result<AuthToken>> verifyWalletSignature(
        @RequestHeader("X-Session-Id") String sessionId,
        @RequestBody SignatureVerificationRequest request
    ) {
        return sessions.get(sessionId)
            .flatMapAsync(session -> {
                // Vérifier la signature du challenge
                boolean valid = verifySignature(
                    session.getChallenge(),
                    request.getSignature(),
                    session.getPartyId()
                );
                
                if (!valid) {
                    return Result.err("INVALID_SIGNATURE", "Signature verification failed");
                }
                
                // Générer un JWT pour le wallet
                String jwt = generateJWT(session.getPartyId());
                
                return Result.ok(new AuthToken(jwt, "Bearer", 3600));
            });
    }
}
3.2 Transfer Pre-Approval Setup (6h)
Configuration Auto-Accept:
# validator-config.conf
canton.validator-apps.validator_backend {
  # Auto-accepter les transferts depuis CBTC et merchant
  auto-accept-transfers {
    "ClearportX-DEX-1::122043801dccdfd8c892fa46ebc1dafc901f7992218886840830aeef1cf7eacedd09" {
      from-parties = [
        "cbtc-network::12202a83c6f4e8e2a5cb5f44fab8c5ef19001b7d79c6cc0a17db596ad993eb59f5a2",
        "merchant::1220fa8543db00cb46ed3b316f8b9df86ad89b86ba48e8c83266e0a8b7dcc97ce55e",
        "DSO::12202a83c6f4e8e2a5cb5f44fab8c5ef19001b7d79c6cc0a17db596ad993eb59f5a2"
      ]
      auto-renew = true  # Renouvellement automatique
    }
  }
}
Service Backend pour Pre-Approval:
@Service
public class TransferPreApprovalService {
    
    // Créer une pre-approval pour auto-accepter
    public CompletableFuture<Result<ContractId>> createPreApproval(
        String receiverParty,
        String senderParty,
        String instrument,
        Optional<BigDecimal> maxAmount
    ) {
        TransferPreApproval template = new TransferPreApproval(
            receiverParty,
            senderParty,
            instrument,
            maxAmount,
            Instant.now().plus(Duration.ofDays(30))  // Expire dans 30 jours
        );
        
        return ledger.create(template)
            .thenApply(Result::ok)
            .exceptionally(ex -> Result.err("PREAPPROVAL_FAILED", ex.getMessage()));
    }
    
    // Renouveler automatiquement les pre-approvals
    @Scheduled(cron = "0 0 * * * *")  // Toutes les heures
    public void renewPreApprovals() {
        ledger.queryPreApprovals()
            .thenApply(approvals -> approvals.stream()
                .filter(a -> a.expiresIn(Duration.ofDays(7)))  // Expire dans 7 jours
                .forEach(this::renew));
    }
}
PHASE 4 : TESTS END-TO-END (8 heures)
4.1 Test Script Complet (4h)
SCRIPT: scripts/test-devnet-integration.js
const { WalletSDK } = require('@canton-network/wallet-sdk');
const axios = require('axios');

async function testFullFlow() {
  console.log("=== TEST CLEARPORTX DEVNET INTEGRATION ===");
  
  const sdk = new WalletSDK({
    ledgerHost: 'validator.devnet.canton',
    ledgerPort: 5001,
    party: 'ClearportX-DEX-1::122043801...'
  });
  
  const backendUrl = 'http://localhost:8080';
  
  // 1. TAP CBTC
  console.log("\n1. Requesting CBTC from faucet...");
  const [tapCmd] = await sdk.tokenStandard.createTap(
    'ClearportX-DEX-1::122043801...',
    '1000',  // 1000 CBTC
    'CBTC'
  );
  await sdk.ledger.submit(tapCmd);
  
  // 2. Vérifier les Holdings
  console.log("\n2. Checking CBTC holdings...");
  const cbtcHoldings = await sdk.tokenStandard.listHoldingUtxos(
    'ClearportX-DEX-1::122043801...',
    false
  );
  console.log(`CBTC Holdings: ${cbtcHoldings.length} UTXOs`);
  
  // 3. Query Canton Coin holdings
  console.log("\n3. Checking Canton Coin holdings...");
  const ccHoldings = await sdk.tokenStandard.listHoldingUtxos(
    'ClearportX-DEX-1::122043801...',
    false
  ).filter(h => h.instrument.id === 'Amulet');
  console.log(`Canton Coin: ${ccHoldings.length} UTXOs, Total: 130,000 CC`);
  
  // 4. Créer un pool CBTC/Canton Coin
  console.log("\n4. Creating CBTC/CC pool...");
  const poolResponse = await axios.post(`${backendUrl}/api/pools/create-holding-pool`, {
    instrumentA: 'CBTC',
    instrumentB: 'Amulet',
    initialA: '100',     // 100 CBTC
    initialB: '10000'    // 10,000 CC
  });
  console.log(`Pool created: ${poolResponse.data.poolId}`);
  
  // 5. Tester un swap
  console.log("\n5. Testing swap CBTC -> CC...");
  const swapResponse = await axios.post(`${backendUrl}/api/swap/holdings`, {
    poolId: poolResponse.data.poolId,
    inputInstrument: 'CBTC',
    inputAmount: '1',  // 1 CBTC
    outputInstrument: 'Amulet',
    minOutput: '95'    // Minimum 95 CC attendu
  });
  console.log(`Swap successful! Received: ${swapResponse.data.outputAmount} CC`);
  
  // 6. Vérifier les nouveaux balances
  console.log("\n6. Final balances:");
  const finalCBTC = await sdk.tokenStandard.getBalance('ClearportX-DEX-1::122043801...', 'CBTC');
  const finalCC = await sdk.tokenStandard.getBalance('ClearportX-DEX-1::122043801...', 'Amulet');
  console.log(`CBTC: ${finalCBTC}`);
  console.log(`Canton Coin: ${finalCC}`);
  
  console.log("\n✅ ALL TESTS PASSED!");
}

// Run with error handling
testFullFlow()
  .then(() => process.exit(0))
  .catch(err => {
    console.error("❌ TEST FAILED:", err);
    process.exit(1);
  });
4.2 Monitoring et Debug (4h)
Dashboard de Monitoring:
@RestController
@RequestMapping("/api/debug")
public class DebugController {
    
    @GetMapping("/holdings")
    public CompletableFuture<HoldingsDebugInfo> debugHoldings() {
        return CompletableFuture.allOf(
            ledger.queryHoldings(getParty(), "CBTC"),
            ledger.queryHoldings(getParty(), "Amulet"),
            ledger.queryPools()
        ).thenApply(v -> {
            return new HoldingsDebugInfo(
                cbtcHoldings.size(),
                cbtcTotal,
                cantonHoldings.size(),
                cantonTotal,
                pools.size(),
                lastSwapTime
            );
        });
    }
    
    @GetMapping("/pool/{poolId}/state")
    public CompletableFuture<PoolState> getPoolState(@PathVariable String poolId) {
        return ledger.queryPool(poolId)
            .thenApply(pool -> new PoolState(
                pool.reserveA,
                pool.reserveB,
                pool.holdingsA.size(),
                pool.holdingsB.size(),
                pool.lpTotalSupply,
                calculatePrice(pool)
            ));
    }
}
TIMELINE RÉALISTE (44 heures total)
Jour 1 (12h)
Matin (6h): Phase 1 - Fixes urgents + TokenAdapter
Après-midi (6h): Phase 2.1 - Tap CBTC + Config DevNet
Jour 2 (12h)
Matin (6h): Phase 2.2 - HoldingPool DAML
Après-midi (6h): Phase 2.3 - Backend Holdings support
Jour 3 (12h)
Matin (6h): Phase 3.1 - Wallet authentication
Après-midi (6h): Phase 3.2 - Transfer pre-approval
Jour 4 (8h)
Matin (4h): Phase 4.1 - Tests end-to-end
Après-midi (4h): Phase 4.2 - Monitoring + Debug
POINTS CRITIQUES À SURVEILLER
1. Fragmentation des UTXOs
Problème: Trop de petits UTXOs ralentissent les opérations Solution: Consolidation automatique nocturne
@Scheduled(cron = "0 3 * * *")  // 3h du matin
public void consolidateUTXOs() {
    // Merger les UTXOs < 1% de la moyenne
}
2. Gestion des Holdings Verrouillés
Problème: Holdings en cours d'allocation DVP Solution: Filtrer avec includeLocked = false
sdk.tokenStandard.listHoldingUtxos(party, false);  // Exclure les verrouillés
3. Performance avec Multiples UTXOs
Problème: Sélection optimale peut être lente Solution: Algorithme greedy simple au début
// Prendre les plus gros UTXOs d'abord
utxos.sort((a, b) -> b.amount.compareTo(a.amount));
4. Synchronisation Pool State
Problème: Réserves vs Holdings désynchronisés Solution: Vérification périodique
choice VerifyAndReconcile : ContractId HoldingPool
  do
    actualA <- sumHoldings holdingsA
    actualB <- sumHoldings holdingsB
    assert (actualA == reserveA && actualB == reserveB)
COMMANDES DE DÉPLOIEMENT
# 1. Build DAML avec Holdings
cd /root/cn-quickstart/quickstart/clearportx
daml build

# 2. Deploy sur validator DevNet
daml ledger upload-dar \
  --host validator.devnet.canton \
  --port 5001 \
  target/clearportx-holdings-1.0.0.dar

# 3. Start backend avec profil DevNet
cd ../backend
./gradlew bootRun --args='--spring.profiles.active=devnet'

# 4. Run tests
cd ../scripts
npm install @canton-network/wallet-sdk
node test-devnet-integration.js
MÉTRIQUES DE SUCCÈS
✅ Phase 1: Swaps fonctionnent sans erreur 409 ✅ Phase 2: Pool CBTC/Canton Coin créé avec Holdings réels ✅ Phase 3: Wallet peut s'authentifier avec party ID ✅ Phase 4: Test end-to-end passe à 100%
RÉSULTAT ATTENDU
DEX fonctionnel sur Canton Network DevNet
Support natif CBTC et Canton Coin
Compatible CIP-0056 (Holdings)
Prêt pour production Canton Network
Ce plan est PRAGMATIQUE et EXÉCUTABLE avec vos ressources actuelles.

PLAN D'ACTION CHIRURGICAL : Fix 409 & CIP-0056 Integration
A) FIX URGENT : Résoudre les 409 (90 minutes) 🔴
A.1 Backend - Un Seul Endpoint Critique (30 min)
NOUVEAU: /api/debug/resolve-and-grant
// backend/src/main/java/com/digitalasset/quickstart/controller/ClearportxDebugController.java

@PostMapping("/resolve-and-grant")
public CompletableFuture<ResponseEntity<ResolveAndGrantResponse>> resolveAndGrant(
    @RequestBody ResolveAndGrantRequest request
) {
    String poolId = request.getPoolId();
    String party = request.getParty();
    
    return CompletableFuture.supplyAsync(() -> {
        // 1. OPERATOR SCOPE: Get freshest pool instance
        List<ActiveContract<Pool>> allPools = ledger.getActiveContracts(Pool.class)
            .get(5, TimeUnit.SECONDS);
        
        // Find latest pool matching poolId (highest offset = newest)
        ActiveContract<Pool> latestPool = allPools.stream()
            .filter(p -> p.payload.poolId.equals(poolId))
            .max(Comparator.comparing(p -> p.offset))
            .orElseThrow(() -> new ResponseStatusException(404, "Pool not found"));
        
        // 2. CHECK VISIBILITY: Can party see this pool?
        boolean canSee = ledger.queryVisibleContract(
            party, 
            latestPool.contractId, 
            Pool.class
        ).get(2, TimeUnit.SECONDS);
        
        ContractId<Pool> finalPoolCid = latestPool.contractId;
        boolean grantHappened = false;
        
        if (!canSee) {
            // 3. GRANT VISIBILITY
            logger.info("Granting visibility of pool {} to party {}", poolId, party);
            
            GrantVisibility choice = new GrantVisibility(party);
            finalPoolCid = ledger.exerciseAndGetResult(
                latestPool.contractId,
                choice,
                generateCommandId(),
                List.of(poolOperator),  // actAs
                List.of(party)          // readAs
            ).get(5, TimeUnit.SECONDS);
            
            grantHappened = true;
        }
        
        // 4. RETURN with timing hint
        return ResponseEntity.ok(new ResolveAndGrantResponse(
            finalPoolCid.contractId,
            poolId,
            latestPool.payload.packageId,
            grantHappened ? 3500 : 0  // retry_after_ms
        ));
    });
}

// Response DTO
public record ResolveAndGrantResponse(
    String poolCid,
    String poolId,
    String packageId,
    int retryAfterMs
) {}
A.2 Frontend - Helper CID-First (30 min)
// frontend/src/services/backendApi.ts

class BackendApi {
  // CRITICAL: Always resolve fresh CID before acting
  async resolveCid(poolId: string): Promise<ResolvedPool> {
    const party = process.env.REACT_APP_PARTY_ID;
    
    const response = await axios.post('/api/debug/resolve-and-grant', {
      poolId,
      party
    });
    
    const { poolCid, packageId, retryAfterMs } = response.data;
    
    // If grant happened, wait for ledger sync
    if (retryAfterMs > 0) {
      console.log(`Grant executed, waiting ${retryAfterMs}ms for sync...`);
      await new Promise(resolve => setTimeout(resolve, retryAfterMs));
    }
    
    return { poolCid, poolId, packageId };
  }
  
  // SWAP: Always by CID
  async atomicSwap(params: SwapParams): Promise<SwapResponse> {
    // 1. Resolve fresh CID
    const { poolCid } = await this.resolveCid(params.poolId);
    
    // 2. Act by CID (not poolId!)
    return axios.post('/api/clearportx/debug/swap-by-cid', {
      poolCid,  // USE THE RESOLVED CID
      inputAmount: params.inputAmount,
      inputSymbol: params.inputSymbol,
      outputSymbol: params.outputSymbol,
      minOutput: '0'  // Let backend enforce slippage
    });
  }
  
  // ADD LIQUIDITY: Same pattern
  async addLiquidity(params: AddLiqParams): Promise<AddLiqResponse> {
    // 1. Resolve fresh CID
    const { poolCid } = await this.resolveCid(params.poolId);
    
    // 2. Act by CID
    return axios.post('/api/clearportx/debug/add-liquidity-by-cid', {
      poolCid,  // USE THE RESOLVED CID
      amountA: params.amountA,
      amountB: params.amountB,
      minLPTokens: '0'
    });
  }
}
A.3 Validation (30 min)
Test Script:
# 1. Create pool
curl -X POST http://localhost:8080/api/pools/create \
  -d '{"symbolA":"USDC","symbolB":"ETH","initialA":"1000","initialB":"1"}'

# 2. Resolve and grant
curl -X POST http://localhost:8080/api/debug/resolve-and-grant \
  -d '{"poolId":"USDC-ETH-001","party":"alice::1234..."}'
# Response: {"poolCid":"00abc123...","retryAfterMs":3500}

# 3. Wait if needed, then swap by CID
sleep 3.5
curl -X POST http://localhost:8080/api/clearportx/debug/swap-by-cid \
  -d '{"poolCid":"00abc123...","inputAmount":"100","inputSymbol":"USDC"}'
# SUCCESS - No 409!
Network Tab Acceptance:
POST /api/debug/resolve-and-grant → 200
Gap de 3.5s si grant
POST /api/clearportx/debug/swap-by-cid → 200
Le CID dans swap = CID résolu
B) HARDENING : Add Liquidity Drain+Credit (60 min)
B.1 Backend Pattern Correct
// SwapController.java - AddLiquidity

@PostMapping("/add-liquidity-by-cid")
public CompletableFuture<AddLiquidityResponse> addLiquidityByCid(
    @RequestBody AddLiquidityByCidRequest req
) {
    ContractId<Pool> poolCid = new ContractId<>(req.poolCid);
    
    return ledger.fetch(poolCid)
        .thenCompose(pool -> {
            // CRITICAL: Use Drain+Credit pattern
            
            // 1. Drain user tokens (archive old CIDs)
            CompletableFuture<BigDecimal> drainA = ledger.exercise(
                req.tokenACid,
                new Token.Drain(),
                commandId()
            );
            
            CompletableFuture<BigDecimal> drainB = ledger.exercise(
                req.tokenBCid,
                new Token.Drain(),
                commandId()
            );
            
            return drainA.thenCombine(drainB, (amountA, amountB) -> {
                // 2. Credit pool's canonical tokens (or create new)
                ContractId<Token> newTokenA = pool.tokenACid.isPresent()
                    ? ledger.exercise(pool.tokenACid.get(), 
                        new Token.Credit(amountA))
                    : ledger.create(new Token(poolParty, amountA, symbolA));
                
                ContractId<Token> newTokenB = pool.tokenBCid.isPresent()
                    ? ledger.exercise(pool.tokenBCid.get(), 
                        new Token.Credit(amountB))
                    : ledger.create(new Token(poolParty, amountB, symbolB));
                
                // 3. Update pool with NEW token CIDs
                return ledger.exercise(
                    poolCid,
                    new Pool.UpdateAfterLiquidity(newTokenA, newTokenB, lpAmount)
                );
            });
        });
}
B.2 Frontend Flow Identique
// SwapPage.tsx
async function handleAddLiquidity() {
  try {
    // 1. Resolve CID first
    const { poolCid } = await api.resolveCid(selectedPoolId);
    
    // 2. Add liquidity by CID
    const result = await api.addLiquidity({
      poolCid,  // Not poolId!
      amountA,
      amountB
    });
    
    showSuccess(`Added liquidity! LP tokens: ${result.lpTokens}`);
  } catch (err) {
    if (err.response?.status === 409) {
      // Single retry with fresh resolution
      const { poolCid } = await api.resolveCid(selectedPoolId);
      const result = await api.addLiquidity({ poolCid, amountA, amountB });
      showSuccess(`Added liquidity after retry!`);
    } else {
      showError(err.message);
    }
  }
}
C) CIP-0056 READ-ONLY Integration (1 jour)
C.1 Enable TokenStandardAdapter - READ ONLY (2h)
# 1. Activer mais sans bridge
cd /root/cn-quickstart/quickstart/clearportx
mv daml/ClearportX/AMM/TokenStandardAdapter.daml.skip \
   daml/ClearportX/AMM/TokenStandardAdapter.daml

# 2. Commenter les bridges dangereux
# Dans TokenStandardAdapter.daml, COMMENTER:
# - BridgeFromStandard (archive Holdings → create Token) ❌
# - BridgeToStandard (archive Token → create Holdings) ❌

# 3. GARDER seulement:
# - Imports des interfaces
# - Templates de query (read-only)
-- TokenStandardAdapter.daml (SAFE VERSION)
module ClearportX.AMM.TokenStandardAdapter where

import qualified Splice.API.Token.Holding.V1 as Holding
import qualified Splice.API.Token.Metadata.V1 as Metadata

-- READ-ONLY: Query helper pour voir les Holdings
template HoldingsViewer
  with
    viewer : Party
  where
    signatory viewer
    
    nonconsuming choice ViewHoldings : [HoldingInfo]
      with party : Party
      controller viewer
      do
        -- Query Holdings sans les modifier
        holdings <- query @Holding.Holding party
        return $ map toHoldingInfo holdings

-- NO BRIDGE, NO ARCHIVE, NO RECREATE
C.2 Backend Holdings Query Service (2h)
// backend/src/main/java/com/digitalasset/quickstart/service/HoldingsQueryService.java

@Service
public class HoldingsQueryService {
    
    @Autowired
    private LedgerApi ledger;
    
    // READ-ONLY: List holdings
    @GetMapping("/api/holdings/{party}")
    public CompletableFuture<List<HoldingDTO>> listHoldings(
        @PathVariable String party,
        @RequestParam(defaultValue = "false") boolean includeLocked
    ) {
        // Query via Ledger API (no mutation)
        return ledger.query(
            new FiltersByParty(Map.of(party, NoFilter.instance)),
            Holding.COMPANION
        ).thenApply(holdings -> holdings.stream()
            .filter(h -> includeLocked || h.lock == null)
            .map(this::toDTO)
            .collect(Collectors.toList()));
    }
    
    // READ-ONLY: Get balances
    @GetMapping("/api/holdings/{party}/balance/{instrument}")
    public CompletableFuture<BalanceDTO> getBalance(
        @PathVariable String party,
        @PathVariable String instrument
    ) {
        return listHoldings(party, false)
            .thenApply(holdings -> holdings.stream()
                .filter(h -> h.instrument.equals(instrument))
                .map(h -> h.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
            .thenApply(total -> new BalanceDTO(instrument, total, party));
    }
}
C.3 Frontend Holdings Display (2h)
// frontend/src/components/HoldingsBadge.tsx

function HoldingsBadge({ party }: { party: string }) {
  const [holdings, setHoldings] = useState<Holding[]>([]);
  
  useEffect(() => {
    // Fetch holdings (READ-ONLY)
    axios.get(`/api/holdings/${party}`)
      .then(res => setHoldings(res.data));
  }, [party]);
  
  // Group by instrument
  const byInstrument = holdings.reduce((acc, h) => {
    acc[h.instrument] = (acc[h.instrument] || 0) + h.amount;
    return acc;
  }, {});
  
  return (
    <div className="holdings-badge">
      <h4>CIP-0056 Holdings:</h4>
      {Object.entries(byInstrument).map(([inst, amount]) => (
        <div key={inst}>
          {inst}: {amount} 
          {inst === 'CBTC' && <span className="badge">DevNet</span>}
          {inst === 'Amulet' && <span className="badge">Canton Coin</span>}
        </div>
      ))}
      {holdings.length === 0 && (
        <p className="text-muted">No CIP-0056 holdings yet</p>
      )}
    </div>
  );
}
D) RECEVOIR CBTC - Flow Officiel (1 jour)
D.1 Backend CBTC Tap Endpoint (3h)
// backend/src/main/java/com/digitalasset/quickstart/service/CBTCService.java

@Service
public class CBTCService {
    
    @Value("${canton.cbtc.faucet-url:https://api.devnet.canton/cbtc/faucet}")
    private String faucetUrl;
    
    // Request CBTC from DevNet faucet
    @PostMapping("/api/cbtc/request")
    public CompletableFuture<Result<TapResponse>> requestCBTC(
        @RequestBody CBTCRequest request
    ) {
        String party = request.getParty();
        BigDecimal amount = request.getAmount();
        
        // 1. Call Canton DevNet faucet API
        return CompletableFuture.supplyAsync(() -> {
            RestTemplate rest = new RestTemplate();
            
            FaucetRequest faucetReq = new FaucetRequest(
                party,
                amount,
                "CBTC",
                "ClearportX DEX testing"
            );
            
            try {
                FaucetResponse resp = rest.postForObject(
                    faucetUrl + "/tap",
                    faucetReq,
                    FaucetResponse.class
                );
                
                return Result.ok(new TapResponse(
                    resp.getTransferId(),
                    resp.getExpectedArrival(),
                    amount
                ));
                
            } catch (Exception e) {
                return Result.err("FAUCET_ERROR", e.getMessage());
            }
        });
    }
    
    // Setup transfer pre-approval for auto-accept
    @PostMapping("/api/cbtc/setup-preapproval")
    public CompletableFuture<Result<ContractId>> setupCBTCPreApproval(
        @RequestBody PreApprovalRequest request
    ) {
        String receiverParty = request.getReceiverParty();
        
        // Create pre-approval for CBTC from cbtc-network party
        TransferPreApproval template = new TransferPreApproval(
            receiverParty,
            "cbtc-network::12202a83c6f4e8e2a5cb5f44fab8c5ef19001b7d79c6cc0a17db596ad993eb59f5a2",
            "CBTC",
            Optional.empty(),  // No max amount
            Instant.now().plus(Duration.ofDays(30))
        );
        
        return ledger.create(template)
            .thenApply(Result::ok);
    }
}
D.2 Monitor CBTC Arrival (3h)
// backend/src/main/java/com/digitalasset/quickstart/service/TransferMonitorService.java

@Service
public class TransferMonitorService {
    
    @Scheduled(fixedDelay = 5000)  // Check every 5 seconds
    public void checkPendingTransfers() {
        // Query pending transfer instructions
        ledger.query(
            TransferInstruction.COMPANION,
            new FiltersByParty(Map.of(clearportxParty, NoFilter.instance))
        ).thenAccept(transfers -> {
            for (ActiveContract<TransferInstruction> transfer : transfers) {
                processPendingTransfer(transfer);
            }
        });
    }
    
    private void processPendingTransfer(ActiveContract<TransferInstruction> transfer) {
        TransferInstruction t = transfer.payload;
        
        // Check if pre-approval exists
        boolean hasPreApproval = checkPreApproval(
            t.receiver,
            t.sender,
            t.instrument
        );
        
        if (hasPreApproval) {
            // Auto-accept
            logger.info("Auto-accepting transfer from {} to {}", 
                t.sender, t.receiver);
            
            ledger.exercise(
                transfer.contractId,
                new TransferInstruction.Accept(),
                generateCommandId()
            );
        } else {
            // Manual approval needed
            logger.info("Transfer pending manual approval: {}", 
                transfer.contractId);
        }
    }
}
E) AUTHENTIFICATION WALLET - Plan Précis (2 jours)
E.1 Party Validation Service (4h)
// backend/src/main/java/com/digitalasset/quickstart/service/wallet/PartyValidationService.java

@Service
public class PartyValidationService {
    
    // Validate party format: namespace::fingerprint
    public Result<ValidatedParty> validatePartyFormat(String partyId) {
        Pattern pattern = Pattern.compile("^([\\w-]+)::([a-f0-9]{64})$");
        Matcher matcher = pattern.matcher(partyId);
        
        if (!matcher.matches()) {
            return Result.err("INVALID_FORMAT", 
                "Party ID must be namespace::fingerprint (64 hex chars)");
        }
        
        return Result.ok(new ValidatedParty(
            matcher.group(1),  // namespace
            matcher.group(2),  // fingerprint
            partyId
        ));
    }
    
    // Check party exists on ledger
    public CompletableFuture<Result<PartyInfo>> validateOnLedger(String partyId) {
        return ledger.allocatePartyIfMissing(partyId, partyId)
            .handle((party, ex) -> {
                if (ex != null) {
                    // Party doesn't exist
                    return Result.err("PARTY_NOT_FOUND", 
                        "Party not registered on Canton Network");
                }
                
                return Result.ok(new PartyInfo(
                    party.identifier,
                    party.displayName,
                    true  // isActive
                ));
            });
    }
}
E.2 Session Management avec Challenge (4h)
// backend/src/main/java/com/digitalasset/quickstart/controller/wallet/WalletAuthController.java

@RestController
@RequestMapping("/api/wallet/auth")
public class WalletAuthController {
    
    @Autowired
    private PartyValidationService partyValidator;
    
    @Autowired
    private Cache<String, WalletSession> sessions;
    
    @PostMapping("/register")
    public CompletableFuture<ResponseEntity<?>> registerParty(
        @RequestBody PartyRegistrationRequest req
    ) {
        String partyId = req.getPartyId();
        
        // 1. Validate format
        Result<ValidatedParty> formatResult = partyValidator.validatePartyFormat(partyId);
        if (!formatResult.isOk()) {
            return CompletableFuture.completedFuture(
                ResponseEntity.badRequest().body(formatResult.getError())
            );
        }
        
        // 2. Check on ledger
        return partyValidator.validateOnLedger(partyId)
            .thenApply(ledgerResult -> {
                if (!ledgerResult.isOk()) {
                    return ResponseEntity.status(404).body(ledgerResult.getError());
                }
                
                // 3. Create session with challenge
                String sessionId = UUID.randomUUID().toString();
                byte[] challengeBytes = new byte[32];
                new SecureRandom().nextBytes(challengeBytes);
                String challenge = Base64.getUrlEncoder().encodeToString(challengeBytes);
                
                WalletSession session = new WalletSession(
                    sessionId,
                    partyId,
                    challenge,
                    Instant.now().plus(Duration.ofMinutes(30))
                );
                
                sessions.put(sessionId, session);
                
                return ResponseEntity.ok(Map.of(
                    "sessionId", sessionId,
                    "challenge", challenge,
                    "expiresAt", session.getExpiresAt()
                ));
            });
    }
}
E.3 Frontend Wallet Login (4h)
// frontend/src/components/WalletLogin.tsx

function WalletLogin() {
  const [partyId, setPartyId] = useState('');
  const [session, setSession] = useState(null);
  
  async function handleRegister() {
    // 1. Validate format client-side
    const partyRegex = /^[\w-]+::[a-f0-9]{64}$/;
    if (!partyRegex.test(partyId)) {
      showError('Invalid party ID format');
      return;
    }
    
    try {
      // 2. Register with backend
      const response = await axios.post('/api/wallet/auth/register', {
        partyId
      });
      
      const { sessionId, challenge, expiresAt } = response.data;
      
      // 3. Store session
      setSession({ sessionId, challenge, expiresAt });
      localStorage.setItem('walletSession', JSON.stringify({
        sessionId,
        partyId,
        expiresAt
      }));
      
      showSuccess('Wallet registered! Session valid for 30 minutes.');
      
    } catch (err) {
      if (err.response?.status === 404) {
        showError('Party not found on Canton Network');
      } else {
        showError('Registration failed: ' + err.message);
      }
    }
  }
  
  return (
    <div className="wallet-login">
      <h3>Canton Network Wallet Login</h3>
      <input
        type="text"
        placeholder="namespace::fingerprint (64 hex chars)"
        value={partyId}
        onChange={(e) => setPartyId(e.target.value)}
        className="party-input"
      />
      <button onClick={handleRegister}>
        Register Wallet Party
      </button>
      
      {session && (
        <div className="session-info">
          <p>Session ID: {session.sessionId}</p>
          <p>Expires: {new Date(session.expiresAt).toLocaleString()}</p>
        </div>
      )}
    </div>
  );
}
F) MIGRATION CIP-0056 - Stratégie Progressive (3-4 semaines)
Phase 1: Coexistence (Semaine 1)
Tokens Custom (existants) ←→ Bridge ←→ Holdings CIP-0056 (nouveaux)
                          READ-ONLY
Phase 2: Adoption Graduelle (Semaine 2)
- Nouveaux pools acceptent Holdings via TransferInstruction
- Anciens pools continuent avec Token custom
- UI montre les deux types
Phase 3: Migration Active (Semaine 3)
- Incentives pour migrer (bonus APY)
- Outils de conversion automatique
- Support des deux pendant transition
Phase 4: Dépréciation (Semaine 4)
- Arrêt création nouveaux Token custom
- Migration forcée des inactifs
- Archive des anciens contracts
TIMELINE RÉVISÉE
JOUR 1 - URGENT (12h)
Matin (6h): Fix 409 avec resolve-and-grant
Après-midi (6h): Hardening add-liquidity + tests
JOUR 2 - INTEGRATION (12h)
Matin (6h): CIP-0056 read-only + Holdings display
Après-midi (6h): CBTC receiving flow + monitoring
JOUR 3 - AUTH (12h)
Matin (6h): Party validation + session management
Après-midi (6h): Frontend wallet login + tests
SEMAINES 2-4
Migration progressive vers CIP-0056 complet
COMMANDES CRITIQUES
# 1. Start backend avec les bons profils
export SPRING_PROFILES_ACTIVE=devnet,debug
./gradlew bootRun

# 2. Test resolve-and-grant
curl -X POST http://localhost:8080/api/debug/resolve-and-grant \
  -H "Content-Type: application/json" \
  -d '{"poolId":"USDC-ETH","party":"alice::1234..."}'

# 3. Verify no more 409s
tail -f logs/application.log | grep -E "(409|CONTRACT_NOT_FOUND|STALE)"
SUCCESS METRICS
✅ Jour 1: Zero 409 errors pendant 100 swaps consécutifs ✅ Jour 2: Holdings CIP-0056 visibles + CBTC reçu ✅ Jour 3: Wallet auth fonctionnel avec party validation ✅ Semaine 2+: Migration pools vers Holdings en cours Ce plan est PRÉCIS, EXÉCUTABLE et évite les pièges identifiés.
Stayed in plan mode