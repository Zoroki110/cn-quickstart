# SSH Tunnel Setup Instructions

## The Problem
- Frontend runs on **server** at `localhost:3001`
- Backend runs on **server** at `localhost:8080`
- Keycloak runs on **server** at `localhost:8082`
- Your browser runs on **your local machine**
- Browser cannot access server's localhost directly

## The Solution: SSH Port Forwarding

You need to run the SSH tunnel command **ON YOUR LOCAL MACHINE** (not on the server).

### Step 1: Close Current SSH Session
If you're currently SSH'd into the server, type:
```bash
exit
```

### Step 2: Open SSH Tunnel from Your Local Machine

**On your local machine** (Windows/Mac/Linux), run this command:

```bash
ssh -L 4001:localhost:3001 \
    -L 4080:localhost:8080 \
    -L 4082:localhost:8082 \
    root@5.9.70.48
```

**What this does:**
- `-L 4001:localhost:3001` → Your localhost:4001 → Server's localhost:3001 (frontend)
- `-L 4080:localhost:8080` → Your localhost:4080 → Server's localhost:8080 (backend)
- `-L 4082:localhost:8082` → Your localhost:4082 → Server's localhost:8082 (Keycloak)

### Step 3: Keep SSH Session Open
**IMPORTANT:** Keep this SSH terminal window open! The tunnel only works while the SSH connection is active.

### Step 4: Verify Tunnels Work

**On your local machine**, open a NEW terminal (keep SSH terminal open) and test:

```bash
# Test frontend
curl http://localhost:4001
# Should return HTML with "ClearportX"

# Test backend
curl http://localhost:4080/api/health/ledger
# Should return JSON with "status":"OK"

# Test Keycloak
curl http://localhost:4082/realms/AppProvider/.well-known/openid-configuration
# Should return JSON with Keycloak config
```

### Step 5: Open Browser

**On your local machine**, open your browser and go to:
```
http://localhost:4001
```

You should now see the ClearportX frontend!

## Troubleshooting

### Error: "Address already in use"
If you see:
```
bind [127.0.0.1]:4001: Address already in use
```

**Solution:**
1. Find what's using the port:
   ```bash
   # On Mac/Linux:
   lsof -i :4001

   # On Windows (PowerShell):
   netstat -ano | findstr :4001
   ```

2. Kill the process or use different ports:
   ```bash
   ssh -L 5001:localhost:3001 \
       -L 5080:localhost:8080 \
       -L 5082:localhost:8082 \
       root@5.9.70.48
   ```

3. Update frontend `.env.development` to use new ports (on server):
   ```bash
   REACT_APP_BACKEND_API_URL=http://localhost:5080
   REACT_APP_KEYCLOAK_URL=http://localhost:5082
   ```

### Multiple SSH Sessions
If you already have other SSH connections to the server:

1. Find them:
   ```bash
   # On Mac/Linux:
   ps aux | grep "ssh.*5.9.70.48"

   # On Windows:
   tasklist | findstr ssh
   ```

2. Kill old SSH connections:
   ```bash
   # On Mac/Linux:
   killall ssh

   # On Windows:
   taskkill /F /IM ssh.exe
   ```

3. Then create fresh tunnel

### Test from Local Machine vs Server

**IMPORTANT:** When testing with curl or browser:
- Tests must run on **your local machine** (laptop/desktop)
- NOT on the server via SSH

Example of WRONG way:
```bash
# ❌ WRONG - This is testing from the server
ssh root@5.9.70.48
curl http://localhost:4001  # This won't work!
```

Example of RIGHT way:
```bash
# ✅ CORRECT - Testing from local machine
ssh -L 4001:localhost:3001 ... root@5.9.70.48  # Terminal 1 (keep open)
# Open new terminal on local machine:
curl http://localhost:4001  # This will work!
```

## Windows-Specific Instructions

### Using PowerShell
```powershell
ssh -L 4001:localhost:3001 -L 4080:localhost:8080 -L 4082:localhost:8082 root@5.9.70.48
```

### Using PuTTY
1. Open PuTTY
2. Session → Host Name: `5.9.70.48`
3. Connection → SSH → Tunnels:
   - Source port: `4001`, Destination: `localhost:3001`, Click "Add"
   - Source port: `4080`, Destination: `localhost:8080`, Click "Add"
   - Source port: `4082`, Destination: `localhost:8082`, Click "Add"
4. Click "Open"
5. Login as root

## Mac/Linux-Specific Instructions

### Keep Tunnel Running in Background
```bash
# Option 1: Use screen/tmux
screen
ssh -L 4001:localhost:3001 -L 4080:localhost:8080 -L 4082:localhost:8082 root@5.9.70.48
# Press Ctrl+A then D to detach

# Option 2: Use nohup
nohup ssh -L 4001:localhost:3001 -L 4080:localhost:8080 -L 4082:localhost:8082 root@5.9.70.48 &
```

### Auto-reconnect if Tunnel Drops
```bash
# Create script: auto-tunnel.sh
while true; do
  ssh -L 4001:localhost:3001 -L 4080:localhost:8080 -L 4082:localhost:8082 root@5.9.70.48
  echo "Connection lost, reconnecting in 5 seconds..."
  sleep 5
done
```

## Final Verification Checklist

Before opening browser, verify all 3 tunnels work **from your local machine**:

- [ ] `curl http://localhost:4001` returns HTML
- [ ] `curl http://localhost:4080/api/health/ledger` returns JSON
- [ ] `curl http://localhost:4082/realms/AppProvider/.well-known/openid-configuration` returns JSON
- [ ] SSH terminal is still open and connected
- [ ] Browser is on **your local machine** (not server)

## What Happens After Tunnels Work

1. **Open browser** → http://localhost:4001
2. **See ClearportX** homepage
3. **Click "Connect Wallet"**
4. **Login:** username=`alice`, password=`alicepass`
5. **See pools data** (no more CORS errors!)
6. **Execute swaps** on Canton Network

---

**Current Server Status:**
- ✅ Frontend running on server port 3001
- ✅ Backend running on server port 8080 (CORS configured)
- ✅ Keycloak running on server port 8082
- ✅ All services healthy

**Your Action Required:**
- Create SSH tunnel from **YOUR LOCAL MACHINE** to server
- Access frontend at http://localhost:4001 from **YOUR LOCAL MACHINE**
