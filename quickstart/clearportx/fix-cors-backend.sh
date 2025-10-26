#!/bin/bash

echo "🔧 FIXING CORS CONFIGURATION"
echo "============================"
echo ""

# Backup the CorsConfig.java
cp /root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/config/CorsConfig.java \
   /root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/config/CorsConfig.java.backup

# Disable the duplicate CorsConfig
cat > /root/cn-quickstart/quickstart/backend/src/main/java/com/digitalasset/quickstart/config/CorsConfig.java << 'EOF'
package com.digitalasset.quickstart.config;

import org.springframework.context.annotation.Configuration;

// TEMPORARILY DISABLED to avoid CORS conflicts
// Multiple CORS configurations were causing "multiple values" error
// Using WebSecurityConfig CORS configuration instead
// @Configuration
public class CorsConfig {
    /*
    @Bean
    public CorsFilter corsFilter() {
        // Moved to WebSecurityConfig
    }
    */
}
EOF

echo "✅ Disabled duplicate CORS configuration"

# Add ngrok URL to the main CORS config
echo ""
echo "📝 Adding ngrok URL to CORS allowed origins..."

# Update application.yml to include ngrok URL
cat >> /root/cn-quickstart/quickstart/backend/src/main/resources/application.yml << 'EOF'

# Additional CORS origins for ngrok
cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:https://app.clearportx.com,http://localhost:3000,http://localhost:3001,https://*.ngrok-free.app,https://*.ngrok-free.dev}
  allow-credentials: true
  allowed-methods: GET,POST,PUT,DELETE,OPTIONS,HEAD
  allowed-headers: "*"
  exposed-headers: Authorization,X-Total-Count,X-Rate-Limit-Remaining
  max-age: 3600
EOF

echo "✅ Updated CORS configuration"
echo ""
echo "🔄 Rebuilding backend with fixed CORS..."

cd /root/cn-quickstart/quickstart/backend

# Kill any existing backend
pkill -f "gradle" || true
pkill -f "java.*quickstart" || true

# Clean build
./gradlew clean build -x test

echo ""
echo "✅ Backend rebuilt"
echo ""
echo "📋 NEXT STEPS:"
echo "1. Restart the backend with ngrok URL:"
echo "   export CORS_ALLOWED_ORIGINS='https://app.clearportx.com,https://nonexplicable-lacily-leesa.ngrok-free.app,http://localhost:3000'"
echo "   ./start-backend-production.sh"
echo ""
echo "2. Configure Netlify environment variables:"
echo "   REACT_APP_BACKEND_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.app"
echo "   REACT_APP_CANTON_API_URL = https://nonexplicable-lacily-leesa.ngrok-free.app"
echo ""
echo "3. Redeploy on Netlify"
