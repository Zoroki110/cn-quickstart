# Setup Canton Validator sur Serveur Dédié

**Serveur:** Hetzner AX41-NVMe
**Specs:** Ryzen 5 3600, 64 GB RAM, 2x 512 GB NVMe SSD

---

## 📋 Prérequis (15 min)

### 1. Connexion SSH

```bash
# Depuis ton local
ssh root@TON_IP_SERVEUR

# Ou avec clé
ssh -i ~/.ssh/id_rsa root@TON_IP_SERVEUR
```

### 2. Mise à Jour Système

```bash
# Update packages
apt update && apt upgrade -y

# Install essentials
apt install -y \
  openjdk-17-jdk \
  postgresql-14 \
  nginx \
  ufw \
  htop \
  net-tools \
  curl \
  wget \
  git \
  unzip
```

### 3. Créer Utilisateur Canton (Sécurité)

```bash
# Créer utilisateur dédié (pas root)
useradd -m -s /bin/bash canton
usermod -aG sudo canton

# Créer répertoires
mkdir -p /opt/canton
mkdir -p /var/log/canton
mkdir -p /data/canton

# Permissions
chown -R canton:canton /opt/canton
chown -R canton:canton /var/log/canton
chown -R canton:canton /data/canton
```

---

## 🐘 Configuration PostgreSQL (10 min)

```bash
# Switcher en user postgres
sudo -u postgres psql

# Dans psql:
CREATE DATABASE canton;
CREATE USER canton_user WITH ENCRYPTED PASSWORD 'CHANGE_ME_STRONG_PASSWORD';
GRANT ALL PRIVILEGES ON DATABASE canton TO canton_user;
\q
```

**Configurer pour Canton:**
```bash
# Éditer pg_hba.conf
nano /etc/postgresql/14/main/pg_hba.conf

# Ajouter cette ligne:
host    canton    canton_user    127.0.0.1/32    md5

# Restart PostgreSQL
systemctl restart postgresql
```

---

## 📦 Installation Canton (10 min)

```bash
# Switcher en user canton
su - canton

# Télécharger Canton (version stable)
cd /opt/canton
wget https://github.com/digital-asset/canton/releases/download/v2.10.2/canton-community-2.10.2.tar.gz

# Extraire
tar -xzf canton-community-2.10.2.tar.gz
mv canton-community-2.10.2/* .
rm canton-community-2.10.2.tar.gz

# Vérifier
./bin/canton --version
# Devrait afficher: Canton 2.10.2
```

---

## ⚙️ Configuration Canton Validator (30 min)

### 1. Créer Configuration

```bash
# Créer fichier config
nano /opt/canton/config/validator.conf
```

**Contenu du fichier:**

```hocon
canton {
  participants {
    participant1 {
      storage {
        type = postgres
        config {
          dataSourceClass = "org.postgresql.ds.PGSimpleDataSource"
          properties = {
            serverName = "localhost"
            portNumber = "5432"
            databaseName = "canton"
            user = "canton_user"
            password = "CHANGE_ME_STRONG_PASSWORD"
          }
        }
      }

      admin-api {
        address = "0.0.0.0"
        port = 5012
      }

      ledger-api {
        address = "0.0.0.0"
        port = 5011
        max-inbound-message-size = 67108864  # 64 MB
      }

      parameters {
        # Performance tuning
        ledger-time-record-time-tolerance = 60s
      }
    }
  }

  # Monitoring
  monitoring {
    metrics {
      reporters = [{
        type = prometheus
        address = "0.0.0.0"
        port = 9000
      }]
    }
  }
}
```

### 2. Créer Service Systemd

```bash
# En tant que root
exit  # sortir de user canton

nano /etc/systemd/system/canton.service
```

**Contenu:**

```ini
[Unit]
Description=Canton Validator
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=canton
Group=canton
WorkingDirectory=/opt/canton
ExecStart=/opt/canton/bin/canton daemon -c /opt/canton/config/validator.conf
Restart=on-failure
RestartSec=10
StandardOutput=append:/var/log/canton/canton.log
StandardError=append:/var/log/canton/canton-error.log

# Limites ressources
LimitNOFILE=65536
MemoryMax=32G

[Install]
WantedBy=multi-user.target
```

**Activer:**
```bash
systemctl daemon-reload
systemctl enable canton
systemctl start canton

# Vérifier status
systemctl status canton

# Voir logs
tail -f /var/log/canton/canton.log
```

---

## 🔒 Configuration Firewall (5 min)

```bash
# Enable UFW
ufw default deny incoming
ufw default allow outgoing

# SSH (IMPORTANT!)
ufw allow 22/tcp

# Canton Ledger API
ufw allow 5011/tcp

# Canton Admin API (optionnel, peut rester fermé)
# ufw allow 5012/tcp

# Prometheus metrics (optionnel)
# ufw allow 9000/tcp

# Enable
ufw enable
ufw status
```

---

## 🌐 Configuration Nginx (Reverse Proxy) (10 min)

**Optionnel mais recommandé pour HTTPS**

```bash
# Installer certbot
apt install -y certbot python3-certbot-nginx

# Config Nginx
nano /etc/nginx/sites-available/canton
```

**Contenu:**

```nginx
upstream canton_ledger_api {
    server 127.0.0.1:5011;
}

server {
    listen 80;
    server_name ton-domaine.com;  # CHANGE ME

    location / {
        proxy_pass http://canton_ledger_api;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;

        # gRPC specific
        grpc_pass grpc://canton_ledger_api;
    }
}
```

**Activer:**
```bash
ln -s /etc/nginx/sites-available/canton /etc/nginx/sites-enabled/
nginx -t
systemctl restart nginx

# Setup HTTPS (optionnel)
certbot --nginx -d ton-domaine.com
```

---

## 🧪 Test Installation (5 min)

### 1. Vérifier Canton tourne

```bash
# Check process
ps aux | grep canton

# Check port
netstat -tuln | grep 5011
# Devrait montrer: 0.0.0.0:5011 LISTEN
```

### 2. Test Connection

**Depuis ton serveur:**
```bash
# Test local
daml ledger info --host localhost --port 5011

# Devrait retourner ledger-id
```

**Depuis ta machine locale:**
```bash
# Test remote
daml ledger info --host TON_IP_SERVEUR --port 5011

# Si firewall OK, devrait retourner ledger-id
```

---

## 📤 Upload DEX sur le Serveur

### Option 1: Depuis ta machine locale

```bash
# Upload le DAR
daml ledger upload-dar \
  --host TON_IP_SERVEUR \
  --port 5011 \
  .daml/dist/clearportx-1.0.0.dar
```

### Option 2: Depuis le serveur

```bash
# Copier le DAR sur le serveur
scp .daml/dist/clearportx-1.0.0.dar root@TON_IP_SERVEUR:/tmp/

# Sur le serveur
ssh root@TON_IP_SERVEUR
daml ledger upload-dar \
  --host localhost \
  --port 5011 \
  /tmp/clearportx-1.0.0.dar
```

---

## 🎯 Initialisation DEX

**Sur le serveur:**

```bash
# Copier les scripts d'init
scp daml/LocalInit.daml root@TON_IP_SERVEUR:/tmp/

# Run init script
daml script \
  --dar /tmp/clearportx-1.0.0.dar \
  --script-name LocalInit:initLocal \
  --ledger-host localhost \
  --ledger-port 5011
```

---

## 🔐 Configuration Token (Lundi)

Quand ton collègue recevra le **token** du partenaire:

### 1. Token JWT

```bash
# Créer fichier token
nano /opt/canton/config/token.txt

# Coller le token JWT
eyJhbGciOiJIUzI1NiIs...

# Protéger
chmod 600 /opt/canton/config/token.txt
chown canton:canton /opt/canton/config/token.txt
```

### 2. Modifier Config Canton

```bash
nano /opt/canton/config/validator.conf
```

**Ajouter dans ledger-api:**

```hocon
ledger-api {
  address = "0.0.0.0"
  port = 5011

  # Ajouter authentification
  auth-services = [{
    type = jwt
    target-audience = "canton-network"
    jwks-url = "https://auth.canton.network/.well-known/jwks.json"
  }]
}
```

**Restart Canton:**
```bash
systemctl restart canton
```

---

## 📊 Monitoring (Optionnel)

### Prometheus + Grafana

```bash
# Install Prometheus
apt install -y prometheus

# Config Prometheus
nano /etc/prometheus/prometheus.yml
```

**Ajouter Canton target:**

```yaml
scrape_configs:
  - job_name: 'canton'
    static_configs:
      - targets: ['localhost:9000']
```

```bash
systemctl restart prometheus

# Install Grafana
apt install -y grafana
systemctl enable grafana-server
systemctl start grafana-server
```

**Accès Grafana:** `http://TON_IP:3000` (user: admin, pass: admin)

---

## ✅ Checklist Installation

- [ ] Serveur accessible en SSH
- [ ] PostgreSQL installé et configuré
- [ ] Canton installé (/opt/canton)
- [ ] Service systemd configuré
- [ ] Canton tourne (systemctl status canton)
- [ ] Port 5011 accessible (firewall)
- [ ] DAR uploadé
- [ ] Script d'init executé
- [ ] Pool créé avec liquidité

---

## 🚀 Prochaines Étapes (Lundi)

1. **Recevoir le token** du partenaire
2. **Configurer l'authentification** dans Canton
3. **Redémarrer** Canton avec le token
4. **Tester** que tout fonctionne avec auth
5. **Déployer** le frontend (si applicable)

---

## 🆘 Troubleshooting

### Canton ne démarre pas

```bash
# Check logs
journalctl -u canton -f

# Vérifier PostgreSQL
systemctl status postgresql
psql -U canton_user -d canton -h localhost

# Permissions
ls -la /opt/canton
ls -la /var/log/canton
```

### Port 5011 non accessible

```bash
# Check firewall
ufw status

# Check Canton écoute
netstat -tuln | grep 5011

# Check depuis l'extérieur
telnet TON_IP 5011
```

### Performance lente

```bash
# Check RAM usage
free -h

# Check CPU
htop

# Check disk I/O
iostat -x 1

# Augmenter limites PostgreSQL
nano /etc/postgresql/14/main/postgresql.conf
# shared_buffers = 16GB
# effective_cache_size = 48GB
systemctl restart postgresql
```

---

## 💰 Coûts Estimés

**Hetzner AX41-NVMe:** ~70€/mois

**Alternative moins chère pour testnet:**
- **Hetzner CPX31:** 16 GB RAM, 4 vCPU, ~15€/mois (suffisant pour testnet)

---

## 📚 Ressources

- [Canton Docs](https://docs.canton.io)
- [DAML Docs](https://docs.daml.com)
- [PostgreSQL Tuning](https://pgtune.leopard.in.ua)

---

**Ready to deploy!** 🚀
