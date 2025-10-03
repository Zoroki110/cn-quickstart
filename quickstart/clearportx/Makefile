.PHONY: verify-strict build test sandbox clean help setup

# Couleurs pour output
RED := \033[0;31m
GREEN := \033[0;32m
YELLOW := \033[0;33m
BLUE := \033[0;34m
NC := \033[0m

help:
	@echo "$(BLUE)=====================================$(NC)"
	@echo "$(BLUE)     Clearportx AMM - Commands      $(NC)"
	@echo "$(BLUE)=====================================$(NC)"
	@echo "  $(GREEN)make setup$(NC)        - Setup initial structure"
	@echo "  $(GREEN)make build$(NC)        - Build DAML project"
	@echo "  $(GREEN)make verify-strict$(NC) - Check forbidden patterns"
	@echo "  $(GREEN)make test$(NC)         - Run all tests"
	@echo "  $(GREEN)make sandbox$(NC)      - Start local sandbox"
	@echo "  $(GREEN)make clean$(NC)        - Clean build artifacts"

setup:
	@echo "$(YELLOW)Setting up project structure...$(NC)"
	@mkdir -p daml/{Token,AMM,Governance}
	@mkdir -p test/{Unit,Integration,Properties}
	@mkdir -p scripts docs canton
	@echo "$(GREEN)✅ Project structure created$(NC)"

verify-strict:
	@echo "$(YELLOW)=== STRICT VERIFICATION ===$(NC)"
	@if [ -d "daml/" ]; then \
		echo "Checking for forbidden patterns..."; \
		! grep -r "query\|queryFilter" daml/ --exclude-dir=test 2>/dev/null || \
			(echo "$(RED)❌ query/queryFilter forbidden$(NC)" && exit 1); \
		! grep -r "GetReservesForPool.*Numeric.*Numeric.*Numeric" daml/ 2>/dev/null || \
			(echo "$(RED)❌ 3-value GetReservesForPool found$(NC)" && exit 1); \
		! grep -r "0\.0001" daml/ 2>/dev/null || \
			(echo "$(RED)❌ Seed tokens found$(NC)" && exit 1); \
	fi
	@echo "$(GREEN)✅ All verification checks passed$(NC)"

build: verify-strict
	@echo "$(YELLOW)Building DAML project...$(NC)"
	@daml build
	@echo "$(GREEN)✅ Build successful$(NC)"
	@if [ -d ".daml/dist" ]; then \
		echo "$(BLUE)DAR file:$(NC)"; \
		ls -lh .daml/dist/*.dar; \
	fi

test: build
	@echo "$(YELLOW)Running tests...$(NC)"
	@daml test --color
	@echo "$(GREEN)✅ All tests passed$(NC)"

sandbox: build
	@echo "$(YELLOW)Starting DAML sandbox...$(NC)"
	@echo "$(BLUE)Sandbox will run on http://localhost:6865$(NC)"
	@daml start

clean:
	@echo "$(YELLOW)Cleaning build artifacts...$(NC)"
	@rm -rf .daml target
	@echo "$(GREEN)✅ Cleaned$(NC)"

# Commande bonus pour vérifier l'installation
check:
	@echo "$(BLUE)System Check:$(NC)"
	@echo "DAML version: $$(daml version | head -1)"
	@echo "Java version: $$(java -version 2>&1 | head -1)"
	@echo "$(GREEN)✅ System ready$(NC)"