# 🔐 ClearportX Admin System - Guide Complet

## 📋 Vue d'ensemble

Le système Admin de ClearportX te permet de **contrôler totalement** qui peut créer des tokens et des pools sur ton DEX. C'est parfait pour les partenariats professionnels comme BitSafe.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────┐
│         TOI (ClearportX Admin)              │
│                                              │
│  ✅ Approuves les issuers                   │
│  ✅ Approuves les pools                     │
│  ✅ Révoques les permissions                │
│  ✅ Contrôle 100% du système                │
└─────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────┐
│      Partenaires Approuvés (BitSafe)        │
│                                              │
│  1. Soumettent PartnerRequest               │
│  2. Reçoivent IssuerPermission              │
│  3. Créent TokenIssueRequest                │
│  4. Attendent ton approbation               │
└─────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────┐
│            Traders (Utilisateurs)           │
│                                              │
│  ✅ Tradent sur pools approuvés             │
│  ❌ Ne peuvent PAS créer tokens             │
│  ❌ Ne peuvent PAS créer pools              │
└─────────────────────────────────────────────┘
```

---

## 📁 Fichiers du Système Admin

### 1. **AdminRegistry.daml** - Contrôleur Central
- `AdminRegistry`: Template principal avec liste des issuers et pools approuvés
- `IssuerPermission`: Permission donnée aux partenaires
- `TokenIssueRequest`: Demande d'émission de tokens
- `PoolCreationRequest`: Demande de création de pool
- `PartnerRequest`: Demande de partenariat

### 2. **InitAdmin.daml** - Scripts d'Initialisation
- `initAdminProd`: Initialise système vide (pour lundi)
- `initAdminWithPartners`: Initialise avec partenaires pré-approuvés

### 3. **AdminFlowTest.daml** - Tests Complets
- `adminFlowTest`: Scénario complet BitSafe
- `revokePartnerTest`: Test de révocation

---

## 🚀 WORKFLOW COMPLET

### **Étape 1: Initialisation (Lundi)**

```bash
# Upload le DAR sur Canton Network
docker exec splice-onboarding bash -c '
  source /app/utils.sh
  TOKEN=$(get_admin_token ...)
  curl -H "Authorization: Bearer $TOKEN" \
    --data-binary @/canton/dars/clearportx-1.0.0.dar \
    http://canton:3975/v2/packages
'

# Initialise le système admin
daml script \
  --dar clearportx-1.0.0.dar \
  --script-name Admin.InitAdmin:initAdminProd \
  --ledger-host <canton-host> --ledger-port 3901
```

Résultat:
```
✅ AdminRegistry créé
   - 0 issuers approuvés
   - 0 pools approuvés
   - TOI contrôles tout
```

---

### **Étape 2: BitSafe Demande un Partenariat**

BitSafe exécute (ou via interface web):

```daml
submit bitsafe do
  createCmd PartnerRequest with
    admin = clearportx_admin
    partner = bitsafe
    companyName = "BitSafe"
    requestedTokenSymbol = "BITSAFE_USD"
    businessDescription = "Stablecoin sécurisé avec audit"
```

**Tu reçois une notification** de la demande.

---

### **Étape 3: TOI Approuves BitSafe**

```daml
submit clearportx_admin do
  exerciseCmd <partnerRequestCid> ApprovePartner with
    maxSupply = Some 10000000.0  -- 10M max
```

Résultat:
```
✅ BitSafe approuvé
   - Peut créer BITSAFE_USD
   - Max supply: 10,000,000
```

---

### **Étape 4: BitSafe Demande à Émettre des Tokens**

```daml
submit bitsafe do
  exerciseCmd <issuerPermissionCid> RequestTokenIssuance with
    receiver = alice
    amount = 10000.0
```

**Tu reçois une notification** de la demande.

---

### **Étape 5: TOI Approuves l'Émission**

```daml
submit clearportx_admin do
  exerciseCmd <tokenIssueRequestCid> ApproveTokenIssuance
```

Résultat:
```
✅ Token créé
   - Alice a reçu 10,000 BITSAFE_USD
   - Issuer: BitSafe
```

---

### **Étape 6: Demande de Création de Pool**

Un opérateur demande un pool BITSAFE_USD/USDC:

```daml
submit poolOperator do
  createCmd PoolCreationRequest with
    admin = clearportx_admin
    operator = poolOperator
    ...
    symbolA = "BITSAFE_USD"
    symbolB = "USDC"
    poolId = "BITSAFE_USD-USDC-PROD"
```

---

### **Étape 7: TOI Approuves le Pool**

```daml
submit clearportx_admin do
  exerciseCmd <poolCreationRequestCid> ApprovePoolCreation
```

Résultat:
```
✅ Pool créé
   - BITSAFE_USD/USDC
   - Frais: 0.3%
   - Prêt pour trading
```

---

## 🔒 SÉCURITÉ

### **Permissions**

| Action | Qui peut le faire ? | Requis |
|--------|-------------------|--------|
| **Créer AdminRegistry** | TOI uniquement | Signature admin |
| **Approuver Issuer** | TOI uniquement | Choice sur AdminRegistry |
| **Créer Token** | Issuer approuvé + TOI | IssuerPermission + Approbation |
| **Créer Pool** | N'importe qui + TOI | PoolCreationRequest + Approbation |
| **Révoquer Permission** | TOI uniquement | Choice RevokeIssuer |

### **Protections**

1. **Double approbation**:
   - Issuer approuvé → crée demande
   - TOI approuve → token émis

2. **Supply limits**:
   ```daml
   IssuerPermission with
     maxSupply = Some 10000000.0  -- Limite stricte
   ```

3. **Révocation instantanée**:
   ```daml
   exerciseCmd registryCid RevokeIssuer with
     issuer = badActor
   ```

---

## 📊 MONITORING

### **Voir les Issuers Approuvés**

```daml
submit admin do
  exerciseCmd registryCid GetApprovedIssuers
-- Retourne: [bitsafe, circle, ...]
```

### **Voir les Pools Approuvés**

```daml
submit admin do
  exerciseCmd registryCid GetApprovedPools
-- Retourne: ["BITSAFE_USD-USDC-PROD", ...]
```

### **Audit Trail**

Toutes les actions sont enregistrées sur la blockchain:
- Qui a demandé quoi
- Quand
- Montants
- Approbations/rejets

---

## 🎯 SCÉNARIOS PRATIQUES

### **Scénario 1: Nouveau Partenaire (BitSafe)**

1. BitSafe contacte ClearportX
2. TOI vérifie KYC/compliance
3. BitSafe soumet `PartnerRequest`
4. TOI approuve avec `ApprovePartner`
5. BitSafe peut maintenant créer BITSAFE_USD

### **Scénario 2: Émission de Tokens**

1. BitSafe veut créer 100K BITSAFE_USD
2. BitSafe soumet `TokenIssueRequest`
3. TOI vérifie la demande
4. TOI approuve avec `ApproveTokenIssuance`
5. Tokens créés et distribués

### **Scénario 3: Révocation d'un Partenaire**

1. BadActor viole les termes
2. TOI exécute `RevokeIssuer`
3. BadActor ne peut plus créer de tokens
4. Tokens existants restent valides
5. Nouveaux tokens bloqués

---

## 🚀 DÉPLOIEMENT LUNDI

### **Checklist**

- [ ] Recevoir token X Ventures
- [ ] Upload clearportx-1.0.0.dar (1.1 MB)
- [ ] Exécuter `InitAdmin:initAdminProd`
- [ ] Vérifier AdminRegistry créé
- [ ] Documenter contractId du registry
- [ ] Créer dashboard de monitoring

### **Commandes Lundi**

```bash
# 1. Upload DAR
./upload-clearportx.sh

# 2. Init Admin
daml script \
  --dar clearportx-1.0.0.dar \
  --script-name Admin.InitAdmin:initAdminProd \
  --ledger-host canton --ledger-port 3901 \
  --access-token-file /path/to/token

# 3. Sauvegarder le ContractId du registry
echo "AdminRegistry CID: <contract-id>" > admin-registry.txt
```

### **Premiers Partenaires à Approuver**

1. **Circle** (USDC)
   - Symbol: "USDC"
   - MaxSupply: None (illimité)

2. **BitSafe** (si prêt)
   - Symbol: "BITSAFE_USD"
   - MaxSupply: Some 10000000.0

3. **Autres** selon négociations

---

## ❓ FAQ

### **Q: Que se passe-t-il si je révoque un issuer ?**
R: Il ne peut plus créer de NOUVEAUX tokens, mais les tokens existants restent valides et tradables.

### **Q: Puis-je changer le maxSupply d'un issuer ?**
R: Oui, révoque l'ancienne permission et crée une nouvelle avec le nouveau maxSupply.

### **Q: Comment gérer les disputes ?**
R: Toutes les actions sont sur la blockchain = audit trail complet. Tu peux prouver qui a fait quoi et quand.

### **Q: Les traders peuvent-ils créer des pools ?**
R: Avec le système admin: NON. Seuls les pools approuvés par TOI peuvent exister.

### **Q: Comment tester en local ?**
R:
```bash
daml script \
  --dar clearportx-1.0.0.dar \
  --script-name Test.AdminFlowTest:adminFlowTest
```

---

## 📈 AVANTAGES DU SYSTÈME ADMIN

✅ **Contrôle Total**: TOI décides qui participe
✅ **Compliance**: Vérifie KYC avant approbation
✅ **Sécurité**: Double approbation pour tokens
✅ **Traçabilité**: Audit trail complet
✅ **Flexibilité**: Révocation instantanée
✅ **Professionnel**: Parfait pour partenariats B2B

---

## 🎉 CONCLUSION

Avec ce système:

1. **TOI contrôles 100% des tokens et pools**
2. **BitSafe et partenaires** doivent demander permission
3. **Traders** peuvent seulement trader (pas créer)
4. **Audit trail complet** sur blockchain
5. **Révocation instantanée** si problème

**Prêt pour production lundi** 🚀
