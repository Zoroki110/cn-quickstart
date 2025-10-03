# Makefile Commands Guide

## 🚀 Quick Commands

### Build (Clean Output)
```bash
make build
# Output:
# → Running security checks... ✓
# → Compiling DAML contracts... ✓
# → Generating DAR... ✓ (856K)
# ✅ Build successful
# → Output: .daml/dist/clearportx-1.0.0.dar
```

### Build (Verbose - Full Details)
```bash
make build-verbose
# Shows all warnings and compilation details
```

### System Check
```bash
make check
# Verifies:
# - DAML SDK version
# - Java version
# - Project structure
# - Number of contracts and tests
```

### Run Tests
```bash
make test
# Runs full test suite with colored output

make test-summary
# Quick summary: X/Y passed
```

### Clean
```bash
make clean
# Removes .daml/dist and build artifacts
```

### Deploy
```bash
make deploy
# Shows deployment command for Canton
```

### Help
```bash
make help
# Shows all available commands
```

## 📋 All Commands

| Command | Description |
|---------|-------------|
| `make` or `make build` | Clean build (default) |
| `make build-verbose` | Build with full output |
| `make test` | Run all tests |
| `make test-summary` | Quick test summary |
| `make check` | System verification |
| `make clean` | Clean artifacts |
| `make deploy` | Show deploy command |
| `make help` | Show help |
| `make all` | Build + test summary |

## 🎨 Output Colors

- 🔵 **Blue** - Info/headers
- 🟢 **Green** - Success
- 🟡 **Yellow** - Warnings
- 🔴 **Red** - Errors
- 🔷 **Cyan** - Actions/progress

## 🔒 Security Checks

The build automatically runs security verification:
- ✅ Checks for unsafe patterns
- ✅ Validates code structure
- ✅ Ensures no forbidden constructs

If security check fails, build stops immediately.

## 💡 Tips

**Quick rebuild after changes:**
```bash
make
```

**Debug build issues:**
```bash
make build-verbose
```

**Before testnet deploy:**
```bash
make clean && make build && make check
```

**See what will be deployed:**
```bash
ls -lh .daml/dist/
```
