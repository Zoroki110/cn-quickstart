# ✅ CHECKLIST TESTS - CLEARPORTX DEX

**Date:** 23 Octobre 2025  
**Objectif:** Valider que TOUT fonctionne avant le déploiement devnet

---

## 🔐 PHASE 1: AUTHENTIFICATION (5 min)

### Test 1.1: Accès Frontend
- [ ] Ouvrir http://localhost:4001
- [ ] Page se charge correctement
- [ ] Pas d'erreurs dans la console navigateur (F12)

**Résultat attendu:** Page d'accueil ClearportX s'affiche

---

### Test 1.2: Login OAuth2
- [ ] Cliquer sur le bouton "Login"
- [ ] Redirection vers Keycloak (http://localhost:4082)
- [ ] Entrer credentials: **alice** / **alice123**
- [ ] Cliquer "Sign In"
- [ ] Redirection automatique vers frontend

**Résultat attendu:** Connecté avec succès, JWT token stocké

---

### Test 1.3: Affichage des Données
- [ ] Balances des tokens s'affichent
- [ ] Liste des pools apparaît
- [ ] Aucune erreur "Unauthorized"

**Résultat attendu:**
- ETH: ~165,020
- USDC: ~165,010,000
- BTC: ~8,250
- USDT: ~165,005,000
- 3 pools visibles

---

## 💱 PHASE 2: SWAPS SIMPLES (15 min)

### Test 2.1: Swap ETH → USDC
**Instructions:**
1. [ ] Sélectionner pool "ETH/USDC"
2. [ ] Entrer montant: **1.0 ETH**
3. [ ] Vérifier le montant USDC estimé (devrait être ~1,960 USDC)
4. [ ] Vérifier le slippage (devrait être <1%)
5. [ ] Cliquer "Swap"
6. [ ] Attendre confirmation (1-3 secondes)

**Résultat attendu:**
- ✅ Swap réussit
- ✅ Nouveau balance ETH: 165,019 (-1)
- ✅ Nouveau balance USDC: 165,011,960 (+1,960)
- ✅ Message de succès s'affiche

**Captures d'écran:**
- [ ] Balance AVANT swap
- [ ] Confirmation de swap
- [ ] Balance APRÈS swap

---

### Test 2.2: Swap inverse USDC → ETH
**Instructions:**
1. [ ] Sélectionner pool "ETH/USDC"
2. [ ] Entrer montant: **2000 USDC**
3. [ ] Vérifier montant ETH estimé (devrait être ~1.01 ETH)
4. [ ] Cliquer "Swap"
5. [ ] Attendre confirmation

**Résultat attendu:**
- ✅ Swap réussit
- ✅ Nouveau balance USDC: ~165,009,960 (-2,000)
- ✅ Nouveau balance ETH: ~165,020.01 (+1.01)

---

### Test 2.3: Swap avec autre pool (ETH → USDT)
**Instructions:**
1. [ ] Sélectionner pool "ETH/USDT"
2. [ ] Entrer montant: **0.5 ETH**
3. [ ] Vérifier montant USDT estimé
4. [ ] Cliquer "Swap"

**Résultat attendu:**
- ✅ Swap réussit
- ✅ Balances mis à jour correctement

---

## 💧 PHASE 3: LIQUIDITÉ (15 min)

### Test 3.1: Ajouter Liquidité
**Instructions:**
1. [ ] Naviguer vers section "Liquidity"
2. [ ] Sélectionner pool "BTC/USDC"
3. [ ] Entrer montant: **0.1 BTC**
4. [ ] Le montant USDC est calculé automatiquement (~2,000 USDC)
5. [ ] Cliquer "Add Liquidity"
6. [ ] Attendre confirmation

**Résultat attendu:**
- ✅ Liquidité ajoutée
- ✅ Balances BTC et USDC diminuent
- ✅ Vous recevez des LP tokens
- ✅ Réserves du pool augmentent

---

### Test 3.2: Retirer Liquidité
**Instructions:**
1. [ ] Voir vos positions de liquidité
2. [ ] Cliquer "Remove Liquidity" sur la position créée
3. [ ] Entrer pourcentage: **50%** (la moitié)
4. [ ] Cliquer "Remove"
5. [ ] Attendre confirmation

**Résultat attendu:**
- ✅ Liquidité retirée
- ✅ Vous récupérez BTC et USDC
- ✅ LP tokens brûlés
- ✅ Position partiellement fermée

---

## 🔥 PHASE 4: EDGE CASES (10 min)

### Test 4.1: Montant Trop Grand
**Instructions:**
1. [ ] Essayer de swap **1,000,000 ETH** → USDC
2. [ ] (Vous n'avez que ~165,020 ETH)

**Résultat attendu:**
- ❌ Erreur claire: "Insufficient balance"
- ❌ Swap ne s'exécute pas
- ❌ Balances inchangés

---

### Test 4.2: Slippage Élevé
**Instructions:**
1. [ ] Essayer swap **10,000 ETH** → USDC
2. [ ] Vérifier le slippage affiché

**Résultat attendu:**
- ⚠️ Warning: "High slippage detected (>10%)"
- ⚠️ Message explicatif
- ✅ Possibilité de continuer quand même (avec confirmation)

---

### Test 4.3: Montant Zero/Négatif
**Instructions:**
1. [ ] Essayer d'entrer **0 ETH**
2. [ ] Essayer d'entrer **-5 ETH**

**Résultat attendu:**
- ❌ Bouton "Swap" désactivé
- ❌ Message: "Amount must be positive"

---

## 📊 PHASE 5: MÉTRIQUES & UI (5 min)

### Test 5.1: Affichage des Pools
- [ ] Tous les pools s'affichent correctement
- [ ] Réserves (reserves) sont visibles
- [ ] Prix (price) est calculé
- [ ] APR/APY s'affiche (si implémenté)

**Résultat attendu:**
- ✅ 3 pools minimum visibles
- ✅ Données cohérentes

---

### Test 5.2: Historique des Transactions
- [ ] Voir l'historique de vos swaps
- [ ] Timestamps corrects
- [ ] Montants corrects

**Résultat attendu:**
- ✅ Liste des transactions récentes
- ✅ Details de chaque swap

---

### Test 5.3: Refresh des Données
- [ ] Effectuer un swap
- [ ] Actualiser la page (F5)
- [ ] Vérifier que les nouveaux balances persistent

**Résultat attendu:**
- ✅ Balances corrects après refresh
- ✅ Pas de perte de données

---

## 🔄 PHASE 6: LOGOUT & RE-LOGIN (5 min)

### Test 6.1: Déconnexion
- [ ] Cliquer "Logout"
- [ ] Vérifier que JWT token est supprimé
- [ ] Vérifier que vous ne voyez plus les données

**Résultat attendu:**
- ✅ Déconnecté avec succès
- ✅ Redirect vers page de login

---

### Test 6.2: Re-connexion
- [ ] Cliquer "Login"
- [ ] Re-entrer: alice / alice123
- [ ] Vérifier que vos balances réapparaissent

**Résultat attendu:**
- ✅ Login réussit
- ✅ Même balances qu'avant déconnexion
- ✅ Historique toujours visible

---

## ✅ RÉSULTAT FINAL

### Score de Réussite

**Nombre de tests réussis:** _____ / 20

**Critères de validation:**
- **18-20:** ✅ Excellent - Prêt pour devnet!
- **15-17:** 🟡 Bon - Quelques ajustements mineurs
- **12-14:** 🟠 Moyen - Corrections nécessaires
- **<12:** ❌ Insuffisant - Débugger avant devnet

---

## 📝 NOTES & BUGS DÉTECTÉS

**Bugs trouvés:**
1. _____________________________________
2. _____________________________________
3. _____________________________________

**Améliorations suggérées:**
1. _____________________________________
2. _____________________________________
3. _____________________________________

---

## 🎯 PROCHAINES ÉTAPES

Si tous les tests passent:
- [ ] Compléter [CANTON_NETWORK_QUESTIONS.md](CANTON_NETWORK_QUESTIONS.md)
- [ ] Préparer la réunion avec Canton Network
- [ ] Planifier le déploiement devnet

Si des bugs critiques:
- [ ] Documenter les bugs
- [ ] Créer des issues GitHub
- [ ] Corriger avant devnet

