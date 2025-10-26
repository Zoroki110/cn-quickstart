#!/usr/bin/env bash
#
# ClearportX Devnet Deployment Script v2.1.0-hardened
#
# Prerequisites:
# - Docker registry access
# - kubectl configured for devnet cluster
# - Redis cluster deployed and accessible
# - Canton Network devnet credentials
#
# Usage:
#   export DOCKER_REGISTRY=ghcr.io/clearportx
#   export IMAGE_TAG=v2.1.0-hardened
#   ./deploy-devnet.sh
#

set -euo pipefail

# Configuration
NAMESPACE=${NAMESPACE:-clearportx}
IMAGE_REGISTRY=${DOCKER_REGISTRY:-ghcr.io/clearportx}
IMAGE_TAG=${IMAGE_TAG:-v2.1.0-hardened}
BACKEND_IMAGE="$IMAGE_REGISTRY/clearportx-backend:$IMAGE_TAG"
REDIS_HOST=${REDIS_HOST:-redis-cluster.clearportx.svc.cluster.local}

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

step() { echo -e "\n${GREEN}🔹 $*${NC}"; }
warn() { echo -e "${YELLOW}⚠ $*${NC}"; }
error() { echo -e "${RED}✗ $*${NC}" >&2; exit 1; }
success() { echo -e "${GREEN}✓ $*${NC}"; }

step "ClearportX Devnet Deployment - $IMAGE_TAG"

# Validate prerequisites
step "Validating prerequisites"

if ! command -v kubectl &> /dev/null; then
    error "kubectl not found - please install kubectl"
fi

if ! command -v docker &> /dev/null; then
    error "docker not found - please install docker"
fi

success "Prerequisites validated"

# Create namespace if it doesn't exist
step "Creating namespace: $NAMESPACE"
kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -
success "Namespace ready"

# Create secrets
step "Creating secrets"

# Canton Network credentials
if ! kubectl -n $NAMESPACE get secret canton-network-creds &>/dev/null; then
    warn "Canton Network credentials secret not found"
    echo "Please create secret manually:"
    echo "  kubectl -n $NAMESPACE create secret generic canton-network-creds \\"
    echo "    --from-literal=participant-id=YOUR_PARTICIPANT_ID \\"
    echo "    --from-literal=ledger-api-host=participant.devnet.canton.network \\"
    echo "    --from-literal=pqs-jdbc-url=jdbc:postgresql://pqs.devnet.canton.network:5432/pqs \\"
    echo "    --from-literal=pqs-username=pqs_user \\"
    echo "    --from-literal=pqs-password=YOUR_PQS_PASSWORD"
    error "Missing Canton Network credentials"
fi

success "Secrets validated"

# Deploy backend
step "Deploying backend: $BACKEND_IMAGE"

cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: clearportx-backend-config
  namespace: $NAMESPACE
data:
  application-devnet.yml: |
$(cat application-devnet.yml | sed 's/^/    /')
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: clearportx-backend
  namespace: $NAMESPACE
  labels:
    app: clearportx-backend
    version: $IMAGE_TAG
spec:
  replicas: 3  # Multi-pod deployment (distributed rate limiter required)
  selector:
    matchLabels:
      app: clearportx-backend
  template:
    metadata:
      labels:
        app: clearportx-backend
        version: $IMAGE_TAG
    spec:
      containers:
      - name: backend
        image: $BACKEND_IMAGE
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 8081
          name: management
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "devnet"
        - name: SPRING_CONFIG_LOCATION
          value: "classpath:/application.yml,/config/application-devnet.yml"
        - name: REDIS_HOST
          value: "$REDIS_HOST"
        - name: LEDGER_API_HOST
          valueFrom:
            secretKeyRef:
              name: canton-network-creds
              key: ledger-api-host
        - name: PQS_JDBC_URL
          valueFrom:
            secretKeyRef:
              name: canton-network-creds
              key: pqs-jdbc-url
        - name: PQS_USERNAME
          valueFrom:
            secretKeyRef:
              name: canton-network-creds
              key: pqs-username
        - name: PQS_PASSWORD
          valueFrom:
            secretKeyRef:
              name: canton-network-creds
              key: pqs-password
        - name: APP_VERSION
          value: "$IMAGE_TAG"
        volumeMounts:
        - name: config
          mountPath: /config
          readOnly: true
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /api/health/ledger
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
          timeoutSeconds: 5
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /api/health/ledger
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 3
          successThreshold: 1
          failureThreshold: 3
      volumes:
      - name: config
        configMap:
          name: clearportx-backend-config
---
apiVersion: v1
kind: Service
metadata:
  name: clearportx-backend
  namespace: $NAMESPACE
  labels:
    app: clearportx-backend
spec:
  type: ClusterIP
  ports:
  - port: 8080
    targetPort: 8080
    protocol: TCP
    name: http
  - port: 8081
    targetPort: 8081
    protocol: TCP
    name: management
  selector:
    app: clearportx-backend
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: clearportx-backend
  namespace: $NAMESPACE
  annotations:
    kubernetes.io/ingress.class: nginx
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/cors-enable: "true"
    nginx.ingress.kubernetes.io/cors-allow-origin: "https://clearportx-dex.netlify.app,https://clearportx-staging.netlify.app"
    nginx.ingress.kubernetes.io/cors-allow-methods: "GET,POST,PUT,DELETE,OPTIONS"
    nginx.ingress.kubernetes.io/cors-allow-headers: "Authorization,Content-Type,X-Idempotency-Key,X-Request-ID"
    nginx.ingress.kubernetes.io/cors-expose-headers: "Retry-After,X-Request-ID"
    nginx.ingress.kubernetes.io/cors-allow-credentials: "true"
    nginx.ingress.kubernetes.io/rate-limit: "10"  # Nginx rate limit as backup
spec:
  tls:
  - hosts:
    - api.clearportx.devnet.canton.network
    secretName: clearportx-api-tls
  rules:
  - host: api.clearportx.devnet.canton.network
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: clearportx-backend
            port:
              number: 8080
EOF

success "Backend deployment created"

# Wait for rollout
step "Waiting for deployment rollout (max 5 minutes)"
kubectl -n $NAMESPACE rollout status deployment/clearportx-backend --timeout=300s || {
    error "Deployment rollout failed - check logs with: kubectl -n $NAMESPACE logs -l app=clearportx-backend"
}

success "Deployment rollout complete"

# Deploy ServiceMonitor for Prometheus
step "Deploying Prometheus ServiceMonitor"

cat <<EOF | kubectl apply -f -
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: clearportx-backend
  namespace: $NAMESPACE
  labels:
    app: clearportx-backend
    prometheus: kube-prometheus
spec:
  selector:
    matchLabels:
      app: clearportx-backend
  endpoints:
  - port: management
    path: /actuator/prometheus
    interval: 15s
    scrapeTimeout: 10s
EOF

success "ServiceMonitor deployed"

# Verify deployment
step "Verifying deployment health"

sleep 10  # Wait for pods to stabilize

POD_COUNT=$(kubectl -n $NAMESPACE get pods -l app=clearportx-backend --field-selector=status.phase=Running -o json | jq '.items | length')

if [ "$POD_COUNT" -lt 3 ]; then
    warn "Expected 3 pods, found $POD_COUNT running"
else
    success "$POD_COUNT pods running"
fi

# Test health endpoint
BACKEND_URL="https://api.clearportx.devnet.canton.network"

if curl -fsS "$BACKEND_URL/api/health/ledger" >/dev/null 2>&1; then
    success "Health endpoint responding"
else
    warn "Health endpoint not yet accessible (may need DNS propagation)"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${GREEN}✅ DEPLOYMENT COMPLETE${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Deployment Details:"
echo "  Namespace: $NAMESPACE"
echo "  Image: $BACKEND_IMAGE"
echo "  Replicas: 3"
echo "  Redis: $REDIS_HOST"
echo "  Ingress: $BACKEND_URL"
echo ""
echo "Next Steps:"
echo "  1. Verify health: curl $BACKEND_URL/api/health/ledger"
echo "  2. Check logs: kubectl -n $NAMESPACE logs -l app=clearportx-backend -f"
echo "  3. Run smoke test: ./verify-devnet.sh"
echo "  4. Configure Grafana dashboard: import grafana-clearportx-dashboard.json"
echo "  5. Load Prometheus alerts: kubectl -n monitoring apply -f prometheus-clearportx-alerts.yml"
echo ""

exit 0
