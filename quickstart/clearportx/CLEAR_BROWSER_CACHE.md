# 🔧 FIX CONTRACT_NOT_FOUND - Tokens Fragmentés

**Erreur vue:** `CONTRACT_NOT_FOUND(11,1ea92b08)`

**Cause:** Les tokens sont fragmentés en plusieurs contrats après les swaps précédents.

---

## 🎯 SOLUTION RAPIDE

### Option 1: Reset Canton Ledger (Recommandé pour Tests)
```bash
# Sur le serveur (via SSH)
cd /root/cn-quickstart/quickstart/clearportx
make clean
make start

# Attendre 2-3 minutes
# Tous les tokens seront recréés proprement
```

**Durée:** 3 minutes  
**Avantage:** État propre, pas de fragmentation  
**Inconvénient:** Perd l'historique des swaps

---

### Option 2: Implémenter Token Merge Automatique
Le backend a déjà un `TokenMergeService.java` qui peut fusionner les tokens avant chaque swap.

**Status:** Implémenté mais pas encore activé dans le flow de swap

---

## ✅ RECOMMANDATION IMMÉDIATE

**Pour vos tests maintenant:**
1. Reset le ledger avec `make clean && make start`
2. Attendre 3 minutes
3. Refresh le navigateur (Ctrl+F5 pour vider le cache)
4. Tester les swaps

**Les tokens seront propres et les swaps fonctionneront!**

