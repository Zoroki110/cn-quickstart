.PHONY: verify-strict build test sandbox clean help setup check

# Couleurs pour output
RED := \033[0;31m
GREEN := \033[0;32m
YELLOW := \033[0;33m
BLUE := \033[0;34m
CYAN := \033[0;36m
NC := \033[0m

help:
	@echo "$(BLUE)=====================================$(NC)"
	@echo "$(BLUE)     Clearportx AMM - Commands      $(NC)"
	@echo "$(BLUE)=====================================$(NC)"
	@echo "  $(GREEN)make build$(NC)        - Build DAML project (clean output)"
	@echo "  $(GREEN)make build-verbose$(NC) - Build with full output"
	@echo "  $(GREEN)make test$(NC)         - Run all tests"
	@echo "  $(GREEN)make clean$(NC)        - Clean build artifacts"
	@echo "  $(GREEN)make check$(NC)        - Verify system setup"
	@echo ""

setup:
	@echo "$(YELLOW)Setting up project structure...$(NC)"
	@mkdir -p daml/{Token,AMM,Governance}
	@mkdir -p docs
	@echo "$(GREEN)✅ Project structure created$(NC)"

verify-strict:
	@printf "$(CYAN)→$(NC) Running security checks... "
	@FAILED=0; \
	if [ -d "daml/" ]; then \
		if grep -r "queryContractId.*<-.*query" daml/ --include="*.daml" >/dev/null 2>&1; then \
			echo "$(RED)❌$(NC)"; \
			echo "$(RED)  Error: Unsafe query patterns detected$(NC)"; \
			FAILED=1; \
		fi; \
	fi; \
	if [ $$FAILED -eq 0 ]; then \
		echo "$(GREEN)✓$(NC)"; \
	else \
		exit 1; \
	fi

build: verify-strict
	@printf "$(CYAN)→$(NC) Compiling DAML contracts... "
	@daml build >/dev/null 2>&1 && echo "$(GREEN)✓$(NC)" || \
		(echo "$(RED)✗$(NC)" && echo "$(RED)Build failed. Run 'make build-verbose' for details.$(NC)" && exit 1)
	@printf "$(CYAN)→$(NC) Generating DAR... "
	@if [ -f ".daml/dist/clearportx-1.0.0.dar" ]; then \
		SIZE=$$(ls -lh .daml/dist/clearportx-1.0.0.dar | awk '{print $$5}'); \
		echo "$(GREEN)✓$(NC) ($$SIZE)"; \
	else \
		echo "$(RED)✗$(NC)"; \
		exit 1; \
	fi
	@echo ""
	@echo "$(GREEN)✅ Build successful$(NC)"
	@echo "$(BLUE)→$(NC) Output: $(CYAN).daml/dist/clearportx-1.0.0.dar$(NC)"

build-verbose: verify-strict
	@echo "$(YELLOW)Building DAML project (verbose)...$(NC)"
	@daml build
	@echo "$(GREEN)✅ Build successful$(NC)"
	@if [ -d ".daml/dist" ]; then \
		echo "$(BLUE)DAR file:$(NC)"; \
		ls -lh .daml/dist/*.dar; \
	fi

test: build
	@echo "$(YELLOW)Running tests...$(NC)"
	@daml test --color
	@echo "$(GREEN)✅ Tests complete$(NC)"

test-summary:
	@printf "$(CYAN)→$(NC) Running test suite... "
	@RESULT=$$(daml test 2>&1); \
	PASSING=$$(echo "$$RESULT" | grep -o "[0-9]* tests passed" | grep -o "[0-9]*" || echo "0"); \
	FAILING=$$(echo "$$RESULT" | grep -o "[0-9]* tests failed" | grep -o "[0-9]*" || echo "0"); \
	TOTAL=$$((PASSING + FAILING)); \
	if [ $$FAILING -eq 0 ]; then \
		echo "$(GREEN)✓$(NC) ($$PASSING/$$TOTAL passed)"; \
	else \
		echo "$(YELLOW)⚠$(NC) ($$PASSING/$$TOTAL passed, $$FAILING failed)"; \
	fi

sandbox: build
	@echo "$(YELLOW)Starting DAML sandbox...$(NC)"
	@echo "$(BLUE)→ Sandbox will run on http://localhost:6865$(NC)"
	@daml start

clean:
	@printf "$(CYAN)→$(NC) Cleaning build artifacts... "
	@rm -rf .daml/dist target 2>/dev/null || true
	@echo "$(GREEN)✓$(NC)"
	@echo "$(GREEN)✅ Cleaned$(NC)"

check:
	@echo "$(BLUE)╔══════════════════════════════════════╗$(NC)"
	@echo "$(BLUE)║       System Verification            ║$(NC)"
	@echo "$(BLUE)╚══════════════════════════════════════╝$(NC)"
	@printf "$(CYAN)→$(NC) DAML SDK:     "
	@daml version 2>/dev/null | head -1 | awk '{print $$3}' || echo "$(RED)Not installed$(NC)"
	@printf "$(CYAN)→$(NC) Java:         "
	@java -version 2>&1 | head -1 | sed 's/.*version "\(.*\)".*/\1/' || echo "$(RED)Not installed$(NC)"
	@printf "$(CYAN)→$(NC) Project:      "
	@if [ -f "daml.yaml" ]; then echo "$(GREEN)clearportx-1.0.0$(NC)"; else echo "$(RED)Not found$(NC)"; fi
	@printf "$(CYAN)→$(NC) Contracts:    "
	@find daml/AMM daml/Token daml/LPToken -name "*.daml" 2>/dev/null | wc -l | tr -d ' ' | xargs echo
	@printf "$(CYAN)→$(NC) Tests:        "
	@find daml -name "Test*.daml" 2>/dev/null | wc -l | tr -d ' ' | xargs echo
	@echo ""
	@echo "$(GREEN)✅ System ready$(NC)"

# Commande pour déployer (à configurer selon ton setup)
deploy: build
	@echo "$(YELLOW)Deploying to Canton...$(NC)"
	@echo "$(CYAN)→$(NC) Upload command: $(BLUE)daml ledger upload-dar .daml/dist/clearportx-1.0.0.dar$(NC)"
	@echo "$(YELLOW)⚠$(NC)  Configure your Canton connection first"

# Raccourci rapide
all: build test-summary

.DEFAULT_GOAL := build
